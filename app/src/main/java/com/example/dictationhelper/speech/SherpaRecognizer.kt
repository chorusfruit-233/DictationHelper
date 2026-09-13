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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Sherpa"

class SherpaRecognizer {
    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var onResult: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private var recorder: AudioRecord? = null
    private var stopSignal: AtomicBoolean? = null

    suspend fun init(context: Context): Boolean {
        destroy()
        if (!SherpaModelManager.ensureReady(context)) {
            error = "未安装 sherpa-onnx 模型"
            return false
        }
        return try {
            val dir = SherpaModelManager.modelDirectory(context)
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(16000, 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = requireFile(dir, "encoder"),
                        decoder = requireFile(dir, "decoder"),
                        joiner = requireFile(dir, "joiner")
                    ),
                    tokens = requireFile(dir, "tokens"),
                    numThreads = 2,
                    provider = "cpu"
                ),
                enableEndpoint = true
            )
            recognizer = OnlineRecognizer(config = config)
            stream = recognizer?.createStream()
            error = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e)
            error = "sherpa 模型加载失败: ${e.message}"
            destroy()
            false
        }
    }

    fun startListening() {
        val activeRecognizer = recognizer
        val activeStream = stream
        if (activeRecognizer == null || activeStream == null) {
            error = "sherpa 识别器未初始化"
            return
        }
        val previousJob = job
        if (previousJob?.isActive == true) {
            stopListening()
            val stopped = runBlocking {
                withTimeoutOrNull(500) {
                    previousJob.join()
                    true
                } ?: false
            }
            if (!stopped) {
                error = "上一次识别尚未停止，请稍后再试"
                return
            }
        }
        val activeStopSignal = AtomicBoolean(false)
        stopSignal = activeStopSignal
        partialText = ""
        error = null
        isListening = true
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) {
            isListening = false
            error = "音频设备不可用"
            return
        }
        val activeRecorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
        if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
            activeRecorder.release()
            isListening = false
            error = "麦克风初始化失败"
            return
        }
        recorder = activeRecorder
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeRecorder.startRecording()
                    val buffer = ShortArray(bufferSize)
                    while (!activeStopSignal.get()) {
                        val count = activeRecorder.read(buffer, 0, buffer.size)
                        if (count <= 0) break
                        val samples = FloatArray(count) { buffer[it] / 32768.0f }
                        activeStream.acceptWaveform(samples, 16000)
                        while (activeRecognizer.isReady(activeStream)) {
                            activeRecognizer.decode(activeStream)
                        }
                        val text = activeRecognizer.getResult(activeStream).text
                        if (text.isNotBlank()) withContext(Dispatchers.Main) { partialText = text }
                        if (activeRecognizer.isEndpoint(activeStream)) {
                            if (text.isNotBlank()) withContext(Dispatchers.Main) { onResult?.invoke(text) }
                            activeRecognizer.reset(activeStream)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (!activeStopSignal.get()) withContext(Dispatchers.Main) { error = "sherpa 识别错误: ${e.message}" }
            } finally {
                try { activeRecorder.stop() } catch (_: Exception) { }
                activeRecorder.release()
                if (recorder === activeRecorder) recorder = null
                withContext(Dispatchers.Main) { isListening = false }
            }
        }
    }

    fun stopListening() {
        stopSignal?.set(true)
        try { recorder?.stop() } catch (_: Exception) { }
        job?.cancel()
    }

    fun destroy() {
        val jobToStop = job
        val streamToRelease = stream
        val recognizerToRelease = recognizer
        stopListening()
        val completed = if (jobToStop == null) true else runBlocking {
            withTimeoutOrNull(500) {
                jobToStop.join()
                true
            } ?: false
        }
        if (completed) {
            releaseResources(streamToRelease, recognizerToRelease)
        } else {
            Log.w(TAG, "recognition did not stop within timeout; deferring sherpa release")
            jobToStop?.invokeOnCompletion { releaseResources(streamToRelease, recognizerToRelease) }
        }
        job = null
        stopSignal = null
        stream = null
        recognizer = null
        if (jobToStop == null) {
            try { recorder?.release() } catch (_: Exception) { }
        }
        recorder = null
    }

    private fun releaseResources(
        stream: com.k2fsa.sherpa.onnx.OnlineStream?,
        recognizer: OnlineRecognizer?
    ) {
        try { stream?.release() } catch (e: Throwable) { Log.w(TAG, "stream release failed", e) }
        try { recognizer?.release() } catch (e: Throwable) { Log.w(TAG, "recognizer release failed", e) }
    }

    private fun requireFile(root: File, kind: String): String =
        SherpaModelManager.findModelFile(root, kind)?.absolutePath
            ?: error("缺少 sherpa $kind 文件")
}
