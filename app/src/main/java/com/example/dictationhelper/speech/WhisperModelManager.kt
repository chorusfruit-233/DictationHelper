/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Metadata for a whisper.cpp model supported by the app. */
data class WhisperModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeLabel: String,
    val url: String
)

object WhisperModelManager {
    private const val MODEL_DIR = "whisper_model"
    private const val PREFS = "app_settings"
    private const val SELECTED_MODEL_KEY = "whisper_model_id"

    /** The bundled model used by the with-whisper-model APK and the default choice. */
    const val DEFAULT_MODEL_ID = "base-q5_1"

    val MODEL_OPTIONS: List<WhisperModelSpec> = listOf(
        WhisperModelSpec(
            "base-q5_1", "Base Q5_1（推荐）", "ggml-base-q5_1.bin", "约 60MB",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin?download=true"
        ),
        WhisperModelSpec(
            "tiny-q5_1", "Tiny Q5_1", "ggml-tiny-q5_1.bin", "约 32MB",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin?download=true"
        ),
        WhisperModelSpec(
            "tiny", "Tiny（原始）", "ggml-tiny.bin", "约 78MB",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"
        ),
        WhisperModelSpec(
            "base", "Base（原始）", "ggml-base.bin", "约 148MB",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true"
        ),
        WhisperModelSpec(
            "small-q5_1", "Small Q5_1", "ggml-small-q5_1.bin", "约 190MB",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true"
        ),
        WhisperModelSpec(
            "small", "Small（原始）", "ggml-small.bin", "约 488MB",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true"
        )
    )

    // Kept for source compatibility with callers that used the old constant.
    val OFFICIAL_MODEL_URL: String get() = modelSpec(DEFAULT_MODEL_ID).url

    val isDownloading = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val error = mutableStateOf<String?>(null)
    /** Incremented after an install, allowing Compose screens to refresh model status. */
    val modelRevision = mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var lastProgressPost = 0L

    fun modelOptions(): List<WhisperModelSpec> = MODEL_OPTIONS

    fun modelSpec(modelId: String): WhisperModelSpec =
        MODEL_OPTIONS.firstOrNull { it.id == modelId } ?: MODEL_OPTIONS.first { it.id == DEFAULT_MODEL_ID }

    fun selectedModelId(context: Context): String {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SELECTED_MODEL_KEY, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        return if (MODEL_OPTIONS.any { it.id == id }) id else DEFAULT_MODEL_ID
    }

    fun selectedModel(context: Context): WhisperModelSpec = modelSpec(selectedModelId(context))

    fun selectModel(context: Context, modelId: String): Boolean {
        if (MODEL_OPTIONS.none { it.id == modelId }) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SELECTED_MODEL_KEY, modelId).apply()
        modelRevision.value++
        return true
    }

    fun modelFile(context: Context): File = modelFile(context, selectedModel(context))

    fun modelFile(context: Context, modelId: String): File = modelFile(context, modelSpec(modelId))

    private fun modelFile(context: Context, spec: WhisperModelSpec): File =
        File(File(context.filesDir, MODEL_DIR), spec.fileName)

    fun isReady(context: Context): Boolean = isReady(context, selectedModelId(context))

    fun isReady(context: Context, modelId: String): Boolean {
        val file = modelFile(context, modelId)
        return file.isFile && file.length() > 1_000_000L && hasWhisperMagic(file)
    }

    fun installedModelIds(context: Context): Set<String> =
        MODEL_OPTIONS.asSequence().filter { isReady(context, it.id) }.map { it.id }.toSet()

    /** Copy the selected model from assets on first use (used by bundled APKs). */
    suspend fun ensureReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        val spec = selectedModel(context)
        if (isReady(context, spec.id)) return@withContext true
        if (context.assets.list(MODEL_DIR)?.contains(spec.fileName) != true) return@withContext false

        val target = modelFile(context, spec)
        val staging = File(context.cacheDir, "${spec.fileName}-assets.part")
        try {
            context.assets.open("$MODEL_DIR/${spec.fileName}").use { input ->
                FileOutputStream(staging).use { output -> input.copyTo(output) }
            }
            if (!hasWhisperMagic(staging)) return@withContext false
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            withContext(Dispatchers.Main) { modelRevision.value++ }
            isReady(context, spec.id)
        } catch (_: Exception) {
            false
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun cancel() { job?.cancel() }

    /** Download a selected model. A successful download makes it the active model. */
    fun downloadOfficialModel(
        context: Context,
        modelId: String = selectedModelId(context),
        onComplete: (Boolean) -> Unit
    ) {
        if (isDownloading.value) return
        val spec = modelSpec(modelId)
        startTransfer(context, spec, onComplete) {
            // Hugging Face occasionally resets the CDN connection on mobile networks.
            // Retry through the mirror before reporting an installation error.
            val urls = listOf(
                spec.url,
                spec.url.replace("https://huggingface.co", "https://hf-mirror.com")
            )
            var lastError: Exception? = null
            var downloaded = false
            for (url in urls) {
                try {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 120_000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${connection.responseCode}")
                    }
                    val total = connection.contentLengthLong
                    try {
                        connection.inputStream.use { input -> copyToModel(context, input, total, spec) }
                    } finally { connection.disconnect() }
                    downloaded = true
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (!downloaded) throw IllegalStateException("下载失败：${lastError?.message ?: "网络错误"}", lastError)
        }
    }

    /** Import a model file into the currently selected model slot. */
    fun importFromUri(context: Context, uri: Uri, onComplete: (Boolean) -> Unit) {
        if (isDownloading.value) return
        val spec = selectedModel(context)
        startTransfer(context, spec, onComplete) {
            val resolver = context.contentResolver
            val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            resolver.openInputStream(uri)?.use { input -> copyToModel(context, input, total, spec) }
                ?: throw IllegalArgumentException("无法打开模型文件")
        }
    }

    private fun startTransfer(context: Context, spec: WhisperModelSpec, onComplete: (Boolean) -> Unit, transfer: suspend () -> Unit) {
        isDownloading.value = true
        progress.floatValue = 0f
        error.value = null
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                transfer()
                withContext(Dispatchers.Main) {
                    selectModel(context, spec.id)
                    progress.floatValue = 1f
                    isDownloading.value = false
                    onComplete(true)
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) { isDownloading.value = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloading.value = false
                    error.value = e.message ?: "模型导入失败"
                    onComplete(false)
                }
            }
        }
    }

    private suspend fun copyToModel(context: Context, input: java.io.InputStream, total: Long, spec: WhisperModelSpec) {
        val directory = File(context.filesDir, MODEL_DIR)
        directory.mkdirs()
        val temp = File(context.cacheDir, "${spec.fileName}.part")
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    output.write(buffer, 0, count)
                    copied += count
                    if (total > 0) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProgressPost >= 250L || copied >= total) {
                            lastProgressPost = now
                            val value = (copied.toFloat() / total).coerceIn(0f, 1f)
                            mainHandler.post { progress.floatValue = value * 0.95f }
                        }
                    }
                }
            }
            if (!hasWhisperMagic(temp)) throw IllegalArgumentException("不是有效的 whisper.cpp 模型文件")
            val target = File(directory, spec.fileName)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun hasWhisperMagic(file: File): Boolean =
        file.length() > 1_000_000L && WhisperModelFiles.hasWhisperMagic(file)
}
