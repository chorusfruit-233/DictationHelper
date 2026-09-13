/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Whisper"

/** whisper.cpp is a batch recognizer: audio is collected until the user stops recording. */
class WhisperRecognizer {
    var isListening by mutableStateOf(false)
        private set
    var isProcessing by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var onResult: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var contextPtr = 0L
    private var recorder: AudioRecord? = null
    private var stopSignal: AtomicBoolean? = null

    suspend fun init(context: Context): Boolean {
        destroy()
        if (!WhisperModelManager.ensureReady(context)) {
            error = "未安装 whisper.cpp 模型"
            return false
        }
        return try {
            val path = WhisperModelManager.modelFile(context).absolutePath
            val loaded = withContext(Dispatchers.IO) { WhisperNative.initContext(path) }
            if (loaded == 0L) throw IllegalStateException("模型加载失败")
            contextPtr = loaded
            error = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e)
            error = "whisper 模型加载失败: ${e.message}"
            false
        }
    }

    fun startListening() {
        val activeContext = contextPtr
        if (activeContext == 0L) {
            error = "whisper 识别器未初始化"
            return
        }
        if (job?.isActive == true) {
            error = "上一次识别尚未停止，请稍后再试"
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) {
            error = "音频设备不可用"
            return
        }
        val activeRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
            activeRecorder.release()
            error = "麦克风初始化失败"
            return
        }

        val activeStopSignal = AtomicBoolean(false)
        stopSignal = activeStopSignal
        recorder = activeRecorder
        partialText = ""
        error = null
        isProcessing = false
        isListening = true

        job = scope.launch {
            val samples = FloatArrayBuilder()
            try {
                withContext(Dispatchers.IO) {
                    activeRecorder.startRecording()
                    val buffer = ShortArray(bufferSize)
                    while (!activeStopSignal.get()) {
                        val count = activeRecorder.read(buffer, 0, buffer.size)
                        if (count <= 0) break
                        for (index in 0 until count) samples.add(buffer[index] / 32768.0f)
                        ensureActive()
                    }
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (!activeStopSignal.get()) error = "录音错误: ${e.message}"
            } finally {
                try { activeRecorder.stop() } catch (_: Exception) { }
                activeRecorder.release()
                if (recorder === activeRecorder) recorder = null
                isListening = false
            }

            if (activeStopSignal.get() && samples.size >= 1_600) {
                isProcessing = true
                try {
                    val text = withContext(Dispatchers.Default) {
                        WhisperNative.transcribe(
                            activeContext,
                            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6),
                            samples.toArray(),
                            "auto"
                        ).trim()
                    }
                    if (text.isNotBlank()) {
                        partialText = text
                        onResult?.invoke(text)
                    } else {
                        error = "未识别到文字，请靠近麦克风后重试"
                    }
                } catch (e: Throwable) {
                    if (e !is CancellationException) error = "whisper 识别错误: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    fun stopListening() {
        stopSignal?.set(true)
        try { recorder?.stop() } catch (_: Exception) { }
    }

    fun destroy() {
        val oldJob = job
        val oldContext = contextPtr
        stopSignal?.set(true)
        try { recorder?.stop() } catch (_: Exception) { }
        oldJob?.cancel()
        job = null
        recorder = null
        isListening = false
        isProcessing = false
        contextPtr = 0L
        if (oldContext != 0L) {
            if (oldJob == null) {
                scope.launch(Dispatchers.IO) { WhisperNative.freeContext(oldContext) }
            } else {
                oldJob.invokeOnCompletion {
                    scope.launch(Dispatchers.IO) { WhisperNative.freeContext(oldContext) }
                }
            }
        }
    }

    private class FloatArrayBuilder(initialCapacity: Int = 16_000) {
        private var values = FloatArray(initialCapacity)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
