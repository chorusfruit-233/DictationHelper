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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object VoskModelManager {
    private const val MODEL_DIR_CN = "vosk_model_cn"
    private const val MODEL_DIR_EN = "vosk_model_en"

    val downloadProgress = mutableFloatStateOf(0f)
    val isDownloading = mutableStateOf(false)
    val downloadError = mutableStateOf<String?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var importJob: Job? = null

    fun modelDirForLang(lang: String) = if (lang == "en-US") MODEL_DIR_EN else MODEL_DIR_CN

    fun getModelPath(context: Context, lang: String): String {
        val filesDir = File(context.filesDir, modelDirForLang(lang))
        if (filesDir.exists() && filesDir.isDirectory && File(filesDir, "am/final.mdl").exists()) {
            return filesDir.absolutePath
        }
        // Check assets for bundled model
        val assetsDir = modelDirForLang(lang)
        return try {
            val assetFiles = context.assets.list(assetsDir)
            if (assetFiles != null && assetFiles.isNotEmpty()) {
                // Copy from assets to files for Vosk (needs file path)
                val targetDir = File(context.filesDir, assetsDir)
                if (!targetDir.exists()) {
                    copyAssets(context, assetsDir, targetDir)
                }
                targetDir.absolutePath
            } else {
                filesDir.absolutePath
            }
        } catch (_: Exception) {
            filesDir.absolutePath
        }
    }

    private fun copyAssets(context: Context, assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        context.assets.list(assetPath)?.forEach { name ->
            val childPath = "$assetPath/$name"
            val childFile = File(targetDir, name)
            try {
                val subFiles = context.assets.list(childPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    copyAssets(context, childPath, childFile)
                } else {
                    context.assets.open(childPath).use { input ->
                        childFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun isModelReady(context: Context, lang: String): Boolean {
        val dir = File(context.filesDir, modelDirForLang(lang))
        val amFinal = File(dir, "am/final.mdl")
        val confFile = File(dir, "conf/model.conf")
        if (amFinal.exists() && confFile.exists()) return true

        // Check assets for pre-installed model (from CI build)
        val assetsDir = modelDirForLang(lang)
        return try {
            val assetFiles = context.assets.list(assetsDir)
            if (assetFiles != null && assetFiles.isNotEmpty()) {
                // Copy assets to files dir so Vosk can use it
                copyAssets(context, assetsDir, dir)
                amFinal.exists() && confFile.exists()
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun importFromUri(context: Context, lang: String, uri: Uri, onComplete: (Boolean) -> Unit) {
        if (isDownloading.value) return
        importJob?.cancel()
        isDownloading.value = true
        downloadProgress.floatValue = 0f
        downloadError.value = null

        val targetDir = modelDirForLang(lang)

        importJob = scope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "vosk_import_${lang}.zip")
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                } ?: throw Exception("无法打开文件")

                val modelDir = File(context.filesDir, targetDir)
                if (modelDir.exists()) modelDir.deleteRecursively()
                modelDir.mkdirs()

                mainHandler.post { downloadProgress.floatValue = 0.6f }
                
                // Collect entries to detect common root
                val entryPairs = mutableListOf<Pair<String, Boolean>>()
                ZipInputStream(BufferedInputStream(tempFile.inputStream())).use { firstPass ->
                    var e = firstPass.nextEntry
                    while (e != null) {
                        entryPairs.add(e.name to e.isDirectory)
                        e = firstPass.nextEntry
                    }
                }

                // Detect common root directory
                val nonDirNames = entryPairs
                    .filter { !it.second }
                    .map { it.first }
                val commonRoot = if (nonDirNames.isNotEmpty() && nonDirNames.all { it.contains("/") }) {
                    val firstSlash = nonDirNames.first().indexOf("/")
                    val candidate = nonDirNames.first().substring(0, firstSlash + 1)
                    if (nonDirNames.all { it.startsWith(candidate) }) candidate else ""
                } else ""

                // Actually extract
                ZipInputStream(BufferedInputStream(tempFile.inputStream())).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = if (commonRoot.isNotEmpty()) 
                            entry.name.removePrefix(commonRoot)
                        else 
                            entry.name
                        if (entryName.isNotEmpty()) {
                            val outFile = File(modelDir, entryName)
                            val root = modelDir.canonicalFile
                            if (outFile.canonicalFile != root && !outFile.canonicalPath.startsWith(root.path + File.separator)) {
                                throw Exception("压缩包包含非法路径")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                tempFile.delete()
                mainHandler.post {
                    downloadProgress.floatValue = 1f
                    isDownloading.value = false
                }

                if (!isModelReady(context, lang)) {
                    throw Exception("导入的 zip 不是有效的 Vosk 语音模型")
                }
                mainHandler.post { onComplete(true) }
            } catch (e: CancellationException) {
                mainHandler.post { isDownloading.value = false }
            } catch (e: Exception) {
                mainHandler.post {
                    isDownloading.value = false
                    downloadError.value = e.message ?: "导入失败"
                    onComplete(false)
                }
            }
        }
    }
}
