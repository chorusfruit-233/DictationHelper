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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "Vosk"

class VoskRecognizer {
    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var onResult: ((String) -> Unit)? = null

    private var recognizer: org.vosk.Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognitionJob: Job? = null
    @Volatile private var shouldStop = false
    @Volatile private var deliverFinalResult = false
    private var currentLang = ""

    fun init(context: Context, lang: String): Boolean {
        Log.d(TAG, "init lang=$lang")
        if (!VoskModelManager.isModelReady(context, lang)) {
            Log.e(TAG, "model not ready for $lang")
            error = "未安装${if (lang == "en-US") "英文" else "中文"}语音模型"
            return false
        }
        if (currentLang == lang && recognizer != null) return true

        destroy()
        currentLang = lang

        return try {
            val path = VoskModelManager.getModelPath(context, lang)
            Log.d(TAG, "loading model from $path")
            val model = org.vosk.Model(path)
            recognizer = org.vosk.Recognizer(model, 16000.0f).apply {
                setMaxAlternatives(3)
                setWords(true)
            }
            Log.d(TAG, "model loaded successfully")
            error = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e)
            error = "模型加载失败: ${e.message}"
            false
        }
    }

    fun startListening() {
        Log.d(TAG, "startListening")
        if (recognizer == null) {
            Log.e(TAG, "recognizer is null")
            error = "识别器未初始化"
            return
        }

        val previousJob = recognitionJob
        if (previousJob?.isActive == true) {
            requestStop(cancelJob = true)
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

        shouldStop = false
        deliverFinalResult = false
        partialText = ""
        error = null
        isListening = true

        val bufferSize = AudioRecord.getMinBufferSize(16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        Log.d(TAG, "bufferSize=$bufferSize")

        if (bufferSize <= 0) {
            isListening = false
            error = "音频设备不可用"
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed, state=${audioRecord?.state}")
            isListening = false
            error = "麦克风初始化失败"
            audioRecord?.release()
            audioRecord = null
            return
        }

        recognitionJob?.cancel()
        val activeRecognizer = recognizer ?: return
        val activeAudioRecord = audioRecord ?: return
        recognitionJob = scope.launch {
            var resultText = ""
            var latestPartialText = ""
            try {
                withContext(Dispatchers.IO) {
                activeAudioRecord.startRecording()
                Log.d(TAG, "recording started")
                val buffer = ShortArray(bufferSize)
                while (!shouldStop) {
                    val count = activeAudioRecord.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    val accepted = activeRecognizer.acceptWaveForm(buffer, count)
                    if (accepted) {
                        val r = activeRecognizer.result
                        val text = JSONObject(r).optString("text", "")
                        Log.d(TAG, "ACCEPT: text='$text'")
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.Main) { partialText = "" }
                            Log.d(TAG, "posting immediately: '$text'")
                            withContext(Dispatchers.Main) {
                                Log.d(TAG, "MAIN callback: '$text'")
                                onResult?.invoke(text)
                            }
                        }
                    } else {
                        val p = activeRecognizer.partialResult
                        val text = JSONObject(p).optString("partial", "")
                        if (text.isNotBlank()) {
                            latestPartialText = text
                            withContext(Dispatchers.Main) { partialText = text }
                        }
                    }
                }
                Log.d(TAG, "loop ended, result='$resultText' partial='$latestPartialText'")
                if (resultText.isEmpty() && latestPartialText.isNotEmpty()) {
                    val fr = activeRecognizer.finalResult
                    resultText = JSONObject(fr).optString("text", "").ifBlank { latestPartialText }
                    Log.d(TAG, "final flush: resultText='$resultText'")
                }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "recognition cancelled")
            } catch (e: Throwable) {
                Log.e(TAG, "recognition error", e)
                error = "识别错误: ${e.message}"
            } finally {
                try { activeAudioRecord.stop() } catch (_: Exception) {}
                try { activeAudioRecord.release() } catch (_: Exception) {}
                if (audioRecord === activeAudioRecord) audioRecord = null
                isListening = false
            }
            Log.d(TAG, "thread done, resultText='$resultText', posting to main")
            if (resultText.isNotBlank() && (deliverFinalResult || !shouldStop)) {
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "MAIN: invoking onResult with '$resultText'")
                    onResult?.invoke(resultText)
                }
            } else if (!shouldStop) {
                Log.w(TAG, "resultText is blank, skipping")
                withContext(Dispatchers.Main) {
                    error = "未识别到文字，请确保语音模型与说话语言匹配"
                }
            }
        }
    }

    fun stopListening() {
        Log.d(TAG, "stopListening")
        deliverFinalResult = true
        requestStop(cancelJob = false)
    }

    private fun requestStop(cancelJob: Boolean) {
        shouldStop = true
        try { audioRecord?.stop() } catch (_: Exception) { }
        if (cancelJob) recognitionJob?.cancel()
    }

    fun destroy() {
        val job = recognitionJob
        val recognizerToClose = recognizer
        deliverFinalResult = false
        requestStop(cancelJob = true)

        val completed = if (job == null) {
            true
        } else {
            runBlocking {
                withTimeoutOrNull(500) {
                    job.join()
                    true
                } ?: false
            }
        }

        if (completed) {
            closeRecognizer(recognizerToClose)
        } else {
            Log.w(TAG, "recognition did not stop within timeout; deferring recognizer close")
            job?.invokeOnCompletion { closeRecognizer(recognizerToClose) }
        }
        recognitionJob = null
        recognizer = null
        audioRecord?.release()
        audioRecord = null
        currentLang = ""
    }

    private fun closeRecognizer(value: org.vosk.Recognizer?) {
        try {
            value?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "recognizer close failed", e)
        }
    }
}
