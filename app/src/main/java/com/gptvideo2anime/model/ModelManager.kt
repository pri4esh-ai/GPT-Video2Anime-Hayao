package com.gptvideo2anime.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class ModelManager(private val context: Context) {

    companion object {
        private const val ANIME_MODEL_NAME = "animegan_hayao.onnx"
        // Replace with your direct download URL for the Hayao ONNX model
        private const val ANIME_MODEL_URL = "https://example.com/models/animegan_hayao.onnx"
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    val animeModelFile: File
        get() = File(modelsDir, ANIME_MODEL_NAME)

    suspend fun animeModelPath(): String? = withContext(Dispatchers.IO) {
        if (!animeModelFile.exists() || animeModelFile.length() == 0L) {
            val success = downloadModel(ANIME_MODEL_URL, animeModelFile)
            if (!success) return@withContext null
        }
        animeModelFile.absolutePath
    }

    private fun downloadModel(urlString: String, outputFile: File): Boolean {
        return try {
            URL(urlString).openStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            outputFile.delete()
            false
        }
    }

    fun clearDownloadedModels() {
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }
}
