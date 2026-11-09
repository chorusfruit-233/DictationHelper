/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One recording session owns the communication route, including while it connects.
 * All methods are called on the main thread. The caller must hold BLUETOOTH_CONNECT
 * on Android 12+ before calling connect(). No scanning or pairing is performed.
 */
@Suppress("DEPRECATION")
class BluetoothMicrophone(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var previousMode: Int? = null
    private var previousDevice: AudioDeviceInfo? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private var previousSco = false
    private var startedSco = false
    private var scoConnected = false
    private var scoReceiver: BroadcastReceiver? = null
    var inputDevice: AudioDeviceInfo? = null
        private set

    /** Returns null when no headset with a microphone is connected. A2DP is output-only. */
    suspend fun connect(): AudioDeviceInfo? {
        release()
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices.filter { isBluetooth(it) }
            devices.firstOrNull { it.id == audioManager.communicationDevice?.id } ?: devices.firstOrNull()
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } ?: return null

        previousMode = audioManager.mode
        selectedDevice = device
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousDevice = audioManager.communicationDevice
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                check(audioManager.setCommunicationDevice(device)) { "无法选择蓝牙麦克风" }
            } else {
                previousSco = audioManager.isBluetoothScoOn
                scoConnected = previousSco
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        scoConnected = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ==
                            AudioManager.SCO_AUDIO_STATE_CONNECTED
                    }
                }
                appContext.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                scoReceiver = receiver
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!previousSco) {
                    startedSco = true
                    audioManager.startBluetoothSco()
                }
                audioManager.isBluetoothScoOn = true
            }
            // Switching to HFP/SCO or LE Audio is asynchronous. Opening AudioRecord
            // before this completes can leave it recording the phone microphone.
            val ready = withTimeoutOrNull(5_000) {
                while (true) {
                    val input = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == device.type && it.address == device.address }
                    if (input != null && isRouteConnected()) {
                        inputDevice = input
                        break
                    }
                    delay(100)
                }
                true
            } ?: false
            check(ready) { "蓝牙麦克风连接超时，请检查耳机的通话音频是否开启，或关闭“优先使用蓝牙麦克风”后重试" }
            return inputDevice
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    fun isRouteConnected(): Boolean {
        val selected = selectedDevice ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.id == selected.id
        } else {
            scoConnected && audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.address == selected.address }
        }
    }

    /** Idempotent; also used on permission errors, timeouts, cancellation and screen exit. */
    fun release() {
        val mode = previousMode ?: return
        previousMode = null
        inputDevice = null
        scoReceiver?.let { appContext.unregisterReceiver(it) }
        scoReceiver = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Restore the request even if the asynchronous switch has not yet
                // completed, so cancelling cannot leave a pending Bluetooth route.
                val previous = previousDevice
                if (previous == null || !audioManager.setCommunicationDevice(previous)) {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                if (startedSco) audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = previousSco
            }
        } catch (e: Exception) {
            Log.w("BluetoothMicrophone", "Unable to restore audio route", e)
        } finally {
            try {
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = mode
            } catch (e: Exception) {
                Log.w("BluetoothMicrophone", "Unable to restore audio mode", e)
            }
            selectedDevice = null
            previousDevice = null
            startedSco = false
            scoConnected = false
        }
    }

    private fun isBluetooth(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
}

/** setPreferredDevice() is only a request. Verify the actual input before decoding. */
internal fun verifySpeechInput(recorder: AudioRecord, inputDevice: AudioDeviceInfo?) {
    if (inputDevice != null) {
        check(recorder.routedDevice?.id == inputDevice.id) {
            "蓝牙麦克风未生效，请重新连接耳机，或关闭“优先使用蓝牙麦克风”后重试"
        }
    }
}

/** Keep all engines at 16 kHz mono; Android resamples the headset's native rate. */
internal fun createSpeechRecorder(bufferSize: Int, inputDevice: AudioDeviceInfo?): AudioRecord {
    val recorder = try {
        AudioRecord(
            if (inputDevice == null) MediaRecorder.AudioSource.VOICE_RECOGNITION
            else MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
    } catch (e: SecurityException) {
        // The permission can be revoked after the screen's permission check.
        throw IllegalStateException("未授予麦克风权限，请在系统设置中允许录音", e)
    }
    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        if (inputDevice != null) {
            check(recorder.setPreferredDevice(inputDevice)) { "无法使用所选蓝牙麦克风" }
        }
        return recorder
    } catch (e: Exception) {
        recorder.release()
        throw e
    }
}
