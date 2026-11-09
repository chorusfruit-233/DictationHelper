/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class BluetoothMicrophoneTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val audioManager get() = context.getSystemService(AudioManager::class.java)

    @Test
    fun outputOnlyBluetoothDoesNotChangeAudioMode() = runTest {
        audioManager.mode = AudioManager.MODE_RINGTONE
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        )
        val microphone = BluetoothMicrophone(context)

        assertNull(microphone.connect())
        microphone.release()

        assertEquals(AudioManager.MODE_RINGTONE, audioManager.mode)
    }

    @Test
    fun classicHeadsetUsesItsInputAndRestoresPreviousRoute() = runTest {
        val previous = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 1)
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 2)
        val input = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 3)
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.setCommunicationDevice(previous)
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(headset))
        shadowOf(audioManager).addInputDevice(input, false)
        val microphone = BluetoothMicrophone(context)

        assertSame(input, microphone.connect())
        assertSame(headset, audioManager.communicationDevice)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        assertTrue(microphone.isRouteConnected())

        microphone.release()
        microphone.release()

        assertNull(microphone.inputDevice)
        assertSame(previous, audioManager.communicationDevice)
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    @Test
    fun leAudioHeadsetIsSupportedAndRouteLossIsDetected() = runTest {
        val headset = device(AudioDeviceInfo.TYPE_BLE_HEADSET, 2)
        val input = device(AudioDeviceInfo.TYPE_BLE_HEADSET, 3)
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(headset))
        shadowOf(audioManager).addInputDevice(input, false)
        val microphone = BluetoothMicrophone(context)
        assertSame(input, microphone.connect())

        audioManager.clearCommunicationDevice()
        assertFalse(microphone.isRouteConnected())
        microphone.release()
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    @Test
    fun alreadyActiveBluetoothHeadsetIsPreferred() = runTest {
        val other = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 1)
        val active = device(AudioDeviceInfo.TYPE_BLE_HEADSET, 2)
        val input = device(AudioDeviceInfo.TYPE_BLE_HEADSET, 3)
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(other, active))
        shadowOf(audioManager).addInputDevice(input, false)
        audioManager.setCommunicationDevice(active)
        val microphone = BluetoothMicrophone(context)

        assertSame(input, microphone.connect())
        assertSame(active, audioManager.communicationDevice)
        microphone.release()
    }

    @Test
    fun rejectedRouteRestoresModeAndReportsError() = runTest {
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        )
        shadowOf(audioManager).lockCommunicationDevice(true)
        val microphone = BluetoothMicrophone(context)

        try {
            microphone.connect()
            fail("Expected rejected Bluetooth route to fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("无法选择"))
        }

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertNull(microphone.inputDevice)
    }

    @Test
    fun timeoutDoesNotLeaveCommunicationModeOrRouteActive() = runTest {
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        )
        // A route request can succeed while its input never becomes available.
        val microphone = BluetoothMicrophone(context)
        try {
            microphone.connect()
            fail("Expected unavailable Bluetooth input to time out")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("超时"))
        }
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertNull(audioManager.communicationDevice)
        assertNull(microphone.inputDevice)
    }

    @Test
    fun cancellingConnectionRestoresAudioState() = runTest {
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        )
        val microphone = BluetoothMicrophone(context)
        val connection = launch(start = CoroutineStart.UNDISPATCHED) { microphone.connect() }
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

        connection.cancelAndJoin()

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertNull(audioManager.communicationDevice)
        assertNull(microphone.inputDevice)
    }

    @Suppress("DEPRECATION")
    @Test
    @Config(sdk = [29], manifest = Config.NONE)
    fun legacyScoWaitsForConnectedBroadcastAndRestoresState() = runTest {
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 2)
        val input = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 3)
        shadowOf(audioManager).addOutputDevice(headset, false)
        shadowOf(audioManager).addInputDevice(input, false)
        val microphone = BluetoothMicrophone(context)
        val connection = launch(start = CoroutineStart.UNDISPATCHED) { microphone.connect() }
        assertTrue(connection.isActive)
        assertNull(microphone.inputDevice)

        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED).putExtra(
            AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTED
        ))
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertTrue(connection.isCompleted)
        assertSame(input, microphone.inputDevice)
        microphone.release()
        assertFalse(audioManager.isBluetoothScoOn)
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    private fun device(type: Int, id: Int = 1): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        val handle = ReflectionHelpers.getField<Any>(port, "mHandle")
        ReflectionHelpers.setField(handle, "mId", id)
        return device
    }
}
