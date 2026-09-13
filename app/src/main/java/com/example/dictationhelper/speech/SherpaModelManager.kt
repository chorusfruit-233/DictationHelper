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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object SherpaModelManager {
    private const val MODEL_DIR = "sherpa_model_streaming"
    const val OFFICIAL_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2"
    val isImporting = mutableStateOf(false)
    val isPreparing = mutableStateOf(false)
    val copiedBytes = mutableLongStateOf(0L)
    val progress = mutableFloatStateOf(0f)
    val error = mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importJob: Job? = null
    private val assetsCopyMutex = Mutex()

    fun cancelImport() {
        importJob?.cancel()
    }

    fun modelDirectory(context: Context): File = File(context.filesDir, MODEL_DIR)

    fun isReady(context: Context): Boolean {
        return isReady(modelDirectory(context))
    }

    /** "fp32" or "int8" for the installed model, or null when nothing is installed. */
    fun installedPrecision(context: Context): String? =
        SherpaModelFiles.find(modelDirectory(context), "encoder")?.let { SherpaModelFiles.precisionOf(it) }

    /**
     * Checks the installed model and, for bundled APKs, copies the asset model on IO.
     * Keeping this separate from [isReady] makes UI recomposition a cheap operation.
     */
    suspend fun ensureReady(context: Context): Boolean {
        if (isReady(context)) return true
        if (!hasBundledAssets(context)) return false
        isPreparing.value = true
        copiedBytes.longValue = 0
        preparedBytes = 0
        lastPreparePost = 0
        return try {
            // NonCancellable: leaving the screen that triggered the copy must not abort it
            // halfway, otherwise the model never finishes installing.
            withContext(Dispatchers.IO + NonCancellable) { prepareFromAssets(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            isPreparing.value = false
            copiedBytes.longValue = 0
            preparedBytes = 0
        }
    }

    private fun hasBundledAssets(context: Context): Boolean = try {
        !context.assets.list(MODEL_DIR).isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    private suspend fun prepareFromAssets(context: Context): Boolean = assetsCopyMutex.withLock {
        val target = modelDirectory(context)
        if (isReady(target)) return@withLock true

        val staging = File(context.cacheDir, "$MODEL_DIR-assets-staging")
        if (staging.exists()) staging.deleteRecursively()
        copyAssets(context, MODEL_DIR, staging)
        if (!isReady(staging)) throw IllegalStateException("内置 sherpa 模型不完整")
        if (target.exists()) target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        isReady(target)
    }

    private fun isReady(dir: File): Boolean {
        return findModelFile(dir, "tokens") != null &&
            findModelFile(dir, "encoder") != null &&
            findModelFile(dir, "decoder") != null &&
            findModelFile(dir, "joiner") != null
    }

    private fun copyAssets(context: Context, assetPath: String, target: File) {
        target.mkdirs()
        context.assets.list(assetPath)?.forEach { name ->
            val childAsset = "$assetPath/$name"
            val child = File(target, name)
            val nested = context.assets.list(childAsset)
            if (!nested.isNullOrEmpty()) copyAssets(context, childAsset, child)
            else context.assets.open(childAsset).use { input ->
                child.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        reportCopiedBytes(count)
                    }
                }
            }
        }
    }

    /**
     * Publishes the copied byte count at most every 250 ms; the copy runs on IO and a
     * per-chunk state write would recompose the caller thousands of times.
     */
    private fun reportCopiedBytes(count: Int) {
        preparedBytes += count
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPreparePost >= 250L) {
            lastPreparePost = now
            copiedBytes.longValue = preparedBytes
        }
    }

    fun importFromUri(context: Context, uri: Uri, onComplete: (Boolean) -> Unit) {
        if (isImporting.value) return
        isImporting.value = true
        progress.floatValue = 0f
        error.value = null
        importJob?.cancel()
        importJob = scope.launch(Dispatchers.IO) {
        val temp = File(context.cacheDir, "sherpa_model_import.archive")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("无法打开模型文件")

                installArchive(context, temp)
                temp.delete()
                mainHandler.post {
                    progress.floatValue = 1f
                    isImporting.value = false
                    onComplete(true)
                }
            } catch (_: CancellationException) {
                mainHandler.post { isImporting.value = false }
            } catch (e: Exception) {
                temp.delete()
                mainHandler.post {
                    isImporting.value = false
                    error.value = e.message ?: "模型导入失败"
                    onComplete(false)
                }
            }
        }
    }

    fun downloadOfficialModel(context: Context, onComplete: (Boolean) -> Unit) {
        if (isImporting.value) return
        isImporting.value = true
        progress.floatValue = 0f
        error.value = null
        importJob?.cancel()
        importJob = scope.launch(Dispatchers.IO) {
            val temp = File(context.cacheDir, "sherpa_model_download.tar.bz2")
            try {
                val connection = (URL(OFFICIAL_MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                connection.connect()
                if (connection.responseCode !in 200..299) throw IllegalStateException("下载失败：HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(1024 * 64)
                        var read = 0L
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0) {
                                val now = android.os.SystemClock.uptimeMillis()
                                val value = (read.toFloat() / total).coerceIn(0f, 1f) * 0.8f
                                if (now - lastProgressPost >= 250L || value >= 0.8f) {
                                    lastProgressPost = now
                                    mainHandler.post { progress.floatValue = value }
                                }
                            }
                        }
                    }
                }
                connection.disconnect()
                installArchive(context, temp)
                temp.delete()
                mainHandler.post {
                    progress.floatValue = 1f
                    isImporting.value = false
                    onComplete(true)
                }
            } catch (_: CancellationException) {
                temp.delete()
                mainHandler.post { isImporting.value = false }
            } catch (e: Exception) {
                temp.delete()
                mainHandler.post {
                    isImporting.value = false
                    error.value = e.message ?: "模型下载失败"
                    onComplete(false)
                }
            }
        }
    }

    private var lastProgressPost = 0L
    private var preparedBytes = 0L
    private var lastPreparePost = 0L

    private suspend fun installArchive(context: Context, archive: File) {
        val target = modelDirectory(context)
        val staging = File(context.cacheDir, "sherpa_model_staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        val bzip2 = archive.inputStream().use { it.readNBytes(3).contentEquals(byteArrayOf('B'.code.toByte(), 'Z'.code.toByte(), 'h'.code.toByte())) }
        if (archive.name.endsWith(".tar.bz2") || bzip2) {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    extractEntry(staging, entry.name, entry.isDirectory, tar)
                    entry = tar.nextTarEntry
                }
            }
        } else {
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    extractEntry(staging, entry.name, entry.isDirectory, zip)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        SherpaModelFiles.pruneUnusedWeights(staging)
        if (!isReady(staging)) throw IllegalArgumentException("未找到 tokens、encoder、decoder、joiner 模型文件")
        if (target.exists()) target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
    }

    private fun extractEntry(root: File, rawName: String, directory: Boolean, input: java.io.InputStream) {
        val name = rawName.replace('\\', '/')
        val out = File(root, name)
        val canonicalRoot = root.canonicalFile
        if (out.canonicalFile != canonicalRoot && !out.canonicalPath.startsWith(canonicalRoot.path + File.separator)) {
            throw IllegalArgumentException("模型压缩包包含非法路径")
        }
        if (directory) out.mkdirs() else {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { input.copyTo(it) }
        }
    }

    /**
     * Picks the file for [kind] deterministically; see [SherpaModelFiles.find].
     */
    internal fun findModelFile(root: File, kind: String): File? = SherpaModelFiles.find(root, kind)
}
