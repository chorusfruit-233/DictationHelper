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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WhisperModelManager {
    private const val MODEL_DIR = "whisper_model"
    private const val MODEL_FILE = "ggml-tiny.bin"
    private const val ASSET_MODEL_PATH = "$MODEL_DIR/$MODEL_FILE"
    const val OFFICIAL_MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"

    val isDownloading = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val error = mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var lastProgressPost = 0L

    fun modelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), MODEL_FILE)

    fun isReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() > 1_000_000L && hasWhisperMagic(file)
    }

    suspend fun ensureReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isReady(context)) return@withContext true
        if (context.assets.list(MODEL_DIR).isNullOrEmpty()) return@withContext false

        val target = modelFile(context)
        val staging = File(context.cacheDir, "$MODEL_FILE-assets.part")
        try {
            context.assets.open(ASSET_MODEL_PATH).use { input ->
                FileOutputStream(staging).use { output -> input.copyTo(output) }
            }
            if (!hasWhisperMagic(staging)) return@withContext false
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            isReady(context)
        } catch (_: Exception) {
            false
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun downloadOfficialModel(context: Context, onComplete: (Boolean) -> Unit) {
        if (isDownloading.value) return
        startTransfer(onComplete) {
            val connection = (URL(OFFICIAL_MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("下载失败：HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            try {
                connection.inputStream.use { input ->
                    copyToModel(context, input, total)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    fun importFromUri(context: Context, uri: Uri, onComplete: (Boolean) -> Unit) {
        if (isDownloading.value) return
        startTransfer(onComplete) {
            val resolver = context.contentResolver
            val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            resolver.openInputStream(uri)?.use { input ->
                copyToModel(context, input, total)
            } ?: throw IllegalArgumentException("无法打开模型文件")
        }
    }

    private fun startTransfer(onComplete: (Boolean) -> Unit, transfer: suspend () -> Unit) {
        isDownloading.value = true
        progress.floatValue = 0f
        error.value = null
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                transfer()
                withContext(Dispatchers.Main) {
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

    private suspend fun copyToModel(context: Context, input: java.io.InputStream, total: Long) {
        val directory = File(context.filesDir, MODEL_DIR)
        directory.mkdirs()
        val temp = File(context.cacheDir, "$MODEL_FILE.part")
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
            val target = File(directory, MODEL_FILE)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun hasWhisperMagic(file: File): Boolean {
        if (!file.isFile || file.length() <= 1_000_000L) return false
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    (magic.contentEquals(byteArrayOf('g'.code.toByte(), 'g'.code.toByte(), 'm'.code.toByte(), 'l'.code.toByte())) ||
                        magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())))
            }
        } catch (_: Exception) {
            false
        }
    }
}
