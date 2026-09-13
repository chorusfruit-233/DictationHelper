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
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
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
    val progress = mutableFloatStateOf(0f)
    val error = mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importJob: Job? = null

    fun cancelImport() {
        importJob?.cancel()
    }

    fun modelDirectory(context: Context): File = File(context.filesDir, MODEL_DIR)

    fun isReady(context: Context): Boolean {
        val target = modelDirectory(context)
        if (isReady(target)) return true
        return try {
            if (context.assets.list(MODEL_DIR)?.isNotEmpty() == true) {
                copyAssets(context, MODEL_DIR, target)
                isReady(target)
            } else false
        } catch (_: Exception) {
            false
        }
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
            else context.assets.open(childAsset).use { input -> child.outputStream().use { output -> input.copyTo(output) } }
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
                            if (total > 0) mainHandler.post { progress.floatValue = (read.toFloat() / total).coerceIn(0f, 1f) * 0.8f }
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

    internal fun findModelFile(root: File, kind: String): File? {
        if (!root.exists()) return null
        return root.walkTopDown().firstOrNull { file ->
            file.isFile && when (kind) {
                "tokens" -> file.name == "tokens.txt"
                "encoder" -> file.name.startsWith("encoder") && file.extension == "onnx"
                "decoder" -> file.name.startsWith("decoder") && file.extension == "onnx"
                "joiner" -> file.name.startsWith("joiner") && file.extension == "onnx"
                else -> false
            }
        }
    }
}
