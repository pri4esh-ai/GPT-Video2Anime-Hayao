package com.gptvideo2anime.pipeline

import android.content.Context
import android.net.Uri
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import java.io.File

data class ProcessResult(
    val outputFile: File
)

class VideoProcessor(
    private val context: Context
) {

    private val modelManager = ModelManager(context)
    private val codecEngine = MediaCodecVideoEngine(context)

    fun processVideo(
        uri: Uri,
        strength: Int,
        onProgress: (Int, Int, String) -> Unit
    ): ProcessResult {

        val videoInfo = codecEngine.inspect(uri)

        val modelPath = modelManager.animeModelPath()
            ?: throw IllegalStateException("AnimeGAN model missing.")

        val outputDir = File(context.filesDir, "output").apply { mkdirs() }

        val outputFile = File(
            outputDir,
            "anime_${System.currentTimeMillis()}.mp4"
        )

        OnnxAnimeEngine(modelPath).use { engine ->

            codecEngine.processVideo(
                inputUri = uri,
                outputFile = outputFile,
                animeEngine = engine,
                strength = strength / 100f
            ) { current, total, stage ->

                onProgress(current, total, stage)
            }
        }

        if (!outputFile.exists()) {
            throw IllegalStateException("Output video missing.")
        }

        return ProcessResult(outputFile)
    }
}
