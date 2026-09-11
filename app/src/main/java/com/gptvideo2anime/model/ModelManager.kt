package com.gptvideo2anime.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val ANIME_MODEL_NAME = "animegan_hayao.onnx"
        private const val ANIME_MODEL_URL = "https://huggingface.co/TachibanaYoshino/AnimeGANv2/resolve/main/hayao.onnx"
    }

    sealed class DownloadState {
        data class Progress(val percent: Int) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    val animeModelFile: File
        get() = File(modelsDir, ANIME_MODEL_NAME)

    // FIXED: Added ensureModels() to satisfy MainViewModel.kt
    suspend fun ensureModels(): String {
        return animeModelPath() ?: throw IllegalStateException("Failed to ensure models are present.")
    }

    suspend fun animeModelPath(): String? = withContext(Dispatchers.IO) {
        if (isModelValid(animeModelFile)) {
            return@withContext animeModelFile.absolutePath
        }

        if (copyFromAssets(ANIME_MODEL_NAME, animeModelFile)) {
            Log.i(TAG, "Successfully extracted model from assets.")
            return@withContext animeModelFile.absolutePath
        }

        Log.i(TAG, "Downloading model from remote CDN: $ANIME_MODEL_URL")
        val success = downloadModelInternal(ANIME_MODEL_URL, animeModelFile)
        if (success) animeModelFile.absolutePath else null
    }

    fun downloadModelWithProgress(): Flow<DownloadState> = flow {
        if (isModelValid(animeModelFile)) {
            emit(DownloadState.Completed)
            return@flow
        }

        val tempFile = File(modelsDir, "$ANIME_MODEL_NAME.tmp")
        try {
            val url = URL(ANIME_MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                emit(DownloadState.Error("HTTP Error ${connection.responseCode}: ${connection.responseMessage}"))
                return@flow
            }

            val fileLength = connection.contentLength
            val input: InputStream = connection.inputStream
            val output = FileOutputStream(tempFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            var lastProgress = -1

            while (input.read(data).also { count = it } != -1) {
                total += count.toLong()
                output.write(data, 0, count)

                if (fileLength > 0) {
                    val progress = ((total * 100) / fileLength).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        emit(DownloadState.Progress(progress))
                    }
                }
            }

            output.flush()
            output.close()
            input.close()

            if (tempFile.exists() && tempFile.renameTo(animeModelFile)) {
                emit(DownloadState.Completed)
            } else {
                emit(DownloadState.Error("Failed to save downloaded model file."))
            }

        } catch (e: Exception) {
            tempFile.delete()
            emit(DownloadState.Error(e.localizedMessage ?: "Network error downloading model."))
        }
    }.flowOn(Dispatchers.IO)

    private fun copyFromAssets(assetName: String, outputFile: File): Boolean {
        return try {
            val assetPath = "models/$assetName"
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            isModelValid(outputFile)
        } catch (e: Exception) {
            outputFile.delete()
            false
        }
    }

    private fun downloadModelInternal(urlString: String, outputFile: File): Boolean {
        val tempFile = File(modelsDir, "$ANIME_MODEL_NAME.tmp")
        return try {
            URL(urlString).openStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(outputFile)
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            tempFile.delete()
            false
        }
    }

    private fun isModelValid(file: File): Boolean {
        return file.exists() && file.length() > 1024 * 1024
    }

    fun clearDownloadedModels() {
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }
}
