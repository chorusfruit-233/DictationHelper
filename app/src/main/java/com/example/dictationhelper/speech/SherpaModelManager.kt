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

object SherpaModelManager {
    private const val MODEL_DIR = "sherpa_model_streaming"
    val isImporting = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val error = mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importJob: Job? = null

    fun modelDirectory(context: Context): File = File(context.filesDir, MODEL_DIR)

    fun isReady(context: Context): Boolean {
        return isReady(modelDirectory(context))
    }

    private fun isReady(dir: File): Boolean {
        return findModelFile(dir, "tokens") != null &&
            findModelFile(dir, "encoder") != null &&
            findModelFile(dir, "decoder") != null &&
            findModelFile(dir, "joiner") != null
    }

    fun importFromUri(context: Context, uri: Uri, onComplete: (Boolean) -> Unit) {
        if (isImporting.value) return
        isImporting.value = true
        progress.floatValue = 0f
        error.value = null
        importJob?.cancel()
        importJob = scope.launch(Dispatchers.IO) {
            val temp = File(context.cacheDir, "sherpa_model_import.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("无法打开模型文件")

                val target = modelDirectory(context)
                val staging = File(context.cacheDir, "sherpa_model_staging")
                if (staging.exists()) staging.deleteRecursively()
                staging.mkdirs()
                ZipInputStream(BufferedInputStream(temp.inputStream())).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        val out = File(staging, name)
                        val root = staging.canonicalFile
                        if (out.canonicalFile != root && !out.canonicalPath.startsWith(root.path + File.separator)) {
                            throw IllegalArgumentException("模型压缩包包含非法路径")
                        }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                if (!isReady(staging)) throw IllegalArgumentException("未找到 tokens、encoder、decoder、joiner 模型文件")
                if (target.exists()) target.deleteRecursively()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
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
