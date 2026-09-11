package com.gptvideo2anime.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import java.io.File

data class ProcessResult(
    val outputFile: File
)

class VideoProcessor(
    private val context: Context
) {

    companion object {
        private const val TAG = "VideoProcessor"
        private const val MAX_TEMP_FILES = 5
    }

    private val modelManager = ModelManager(context)
    private val codecEngine = MediaCodecVideoEngine(context)

    fun processVideo(
        uri: Uri,
        strength: Int,
        onProgress: (Int, Int, String) -> Unit
    ): ProcessResult {

        // Validate URI and retrieve metadata
        val videoInfo = codecEngine.inspect(uri)
        Log.i(TAG, "Processing video: ${videoInfo.width}x${videoInfo.height}, FPS: ${videoInfo.frameRate}")

        // Ensure model file is accessible
        val modelPath = modelManager.animeModelPath()
            ?: throw IllegalStateException("AnimeGAN model missing from local storage.")

        val modelFile = File(modelPath)
        require(modelFile.exists() && modelFile.length() > 0) {
            "AnimeGAN model file is invalid or empty at $modelPath"
        }

        // Output directory setup
        val outputDir = File(context.filesDir, "output").apply { mkdirs() }
        
        // Cleanup old generated videos to prevent internal storage exhaustion
        cleanOldOutputs(outputDir)

        val outputFile = File(
            outputDir,
            "anime_${System.currentTimeMillis()}.mp4"
        )

        // Clamp strength between 0% and 100% and scale to float 0.0 - 1.0
        val normalizedStrength = (strength.coerceIn(0, 100)) / 100f

        OnnxAnimeEngine(modelPath).use { engine ->
            codecEngine.processVideo(
                inputUri = uri,
                outputFile = outputFile,
                animeEngine = engine,
                strength = normalizedStrength
            ) { current, total, stage ->
                onProgress(current, total, stage)
            }
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("Failed to generate processed output video.")
        }

        return ProcessResult(outputFile)
    }

    private fun cleanOldOutputs(outputDir: File) {
        try {
            val files = outputDir.listFiles() ?: return
            if (files.size >= MAX_TEMP_FILES) {
                files.sortBy { it.lastModified() }
                val filesToDelete = files.size - MAX_TEMP_FILES + 1
                for (i in 0 until filesToDelete) {
                    files[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old output files: ${e.message}")
        }
    }
}
