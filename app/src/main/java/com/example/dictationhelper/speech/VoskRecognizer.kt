package com.example.dictationhelper.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private var recognitionThread: Thread? = null
    @Volatile private var shouldStop = false
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

        shouldStop = false
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

        recognitionThread = Thread {
            var resultText = ""
            try {
                audioRecord?.startRecording()
                Log.d(TAG, "recording started")
                val buffer = ShortArray(bufferSize)
                while (!shouldStop) {
                    val count = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (count <= 0) break
                    val accepted = recognizer?.acceptWaveForm(buffer, count) ?: false
                    if (accepted) {
                        val r = recognizer?.result ?: "{}"
                        val text = JSONObject(r).optString("text", "")
                        Log.d(TAG, "ACCEPT: text='$text'")
                        if (text.isNotBlank()) {
                            partialText = ""
                            Log.d(TAG, "posting immediately: '$text'")
                            Handler(Looper.getMainLooper()).post {
                                Log.d(TAG, "MAIN callback: '$text'")
                                onResult?.invoke(text)
                            }
                        }
                    } else {
                        val p = recognizer?.partialResult ?: "{}"
                        val text = JSONObject(p).optString("partial", "")
                        if (text.isNotBlank()) partialText = text
                    }
                }
                Log.d(TAG, "loop ended, result='$resultText' partial='$partialText'")
                if (resultText.isEmpty() && partialText.isNotEmpty()) {
                    val fr = recognizer?.finalResult ?: "{}"
                    resultText = JSONObject(fr).optString("text", "").ifBlank { partialText }
                    Log.d(TAG, "final flush: resultText='$resultText'")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "recognition error", e)
                error = "识别错误: ${e.message}"
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                isListening = false
            }
            Log.d(TAG, "thread done, resultText='$resultText', posting to main")
            if (resultText.isNotBlank()) {
                Handler(Looper.getMainLooper()).post {
                    Log.d(TAG, "MAIN: invoking onResult with '$resultText'")
                    onResult?.invoke(resultText)
                }
            } else {
                Log.w(TAG, "resultText is blank, skipping")
                Handler(Looper.getMainLooper()).post {
                    error = "未识别到文字，请确保语音模型与说话语言匹配"
                }
            }
        }.apply { start() }
    }

    fun stopListening() {
        Log.d(TAG, "stopListening")
        shouldStop = true
        recognitionThread?.interrupt()
    }

    fun destroy() {
        stopListening()
        recognitionThread?.join(500)
        recognizer?.close()
        recognizer = null
        audioRecord?.release()
        audioRecord = null
        currentLang = ""
    }
}
