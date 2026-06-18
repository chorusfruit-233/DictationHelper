package com.example.dictationhelper.speech

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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

    fun modelDirForLang(lang: String) = if (lang == "en-US") MODEL_DIR_EN else MODEL_DIR_CN

    fun getModelPath(context: Context, lang: String): String {
        return File(context.filesDir, modelDirForLang(lang)).absolutePath
    }

    fun isModelReady(context: Context, lang: String): Boolean {
        val dir = File(context.filesDir, modelDirForLang(lang))
        val amFinal = File(dir, "am/final.mdl")
        val confFile = File(dir, "conf/model.conf")
        return amFinal.exists() && confFile.exists()
    }

    fun importFromUri(context: Context, lang: String, uri: Uri, onComplete: (Boolean) -> Unit) {
        if (isDownloading.value) return
        isDownloading.value = true
        downloadProgress.floatValue = 0f
        downloadError.value = null

        val targetDir = modelDirForLang(lang)

        Thread {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("无法打开文件")
                val totalSize = inputStream.available().toLong()
                var read = 0L

                val tempFile = File(context.cacheDir, "vosk_import_${lang}.zip")
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        read += bytesRead
                        if (totalSize > 0) {
                            downloadProgress.floatValue = (read.toFloat() / totalSize).coerceAtMost(0.5f)
                        }
                    }
                }
                inputStream.close()

                val modelDir = File(context.filesDir, targetDir)
                if (modelDir.exists()) modelDir.deleteRecursively()
                modelDir.mkdirs()

                downloadProgress.floatValue = 0.6f
                
                // Collect entries to detect common root
                val entryPairs = mutableListOf<Pair<String, java.util.zip.ZipEntry>>()
                ZipInputStream(BufferedInputStream(tempFile.inputStream())).use { firstPass ->
                    var e = firstPass.nextEntry
                    while (e != null) {
                        entryPairs.add(e.name to e)
                        e = firstPass.nextEntry
                    }
                }

                // Detect common root directory
                val nonDirNames = entryPairs
                    .filter { !it.second.isDirectory }
                    .map { it.first }
                val commonRoot = if (nonDirNames.all { it.contains("/") }) {
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
                downloadProgress.floatValue = 1f
                isDownloading.value = false

                if (!isModelReady(context, lang)) {
                    throw Exception("导入的 zip 不是有效的 Vosk 语音模型")
                }
                onComplete(true)
            } catch (e: Exception) {
                isDownloading.value = false
                downloadError.value = e.message ?: "导入失败"
                onComplete(false)
            }
        }.start()
    }
}
