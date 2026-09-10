package com.gptvideo2anime.pipeline

import android.content.Context
import android.net.Uri
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import java.io.File

data class ProcessingInfo(
    val inputUri: Uri,
    val displayName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val rotation: Int,
    val outputPath: String
)

data class ProcessVideoResult(
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
        onProgress: (current: Int, total: Int, stage: String) -> Unit
    ): ProcessVideoResult {

        val info = process(
            uri = uri,
            strength = strength / 100f,
            onProgress = onProgress
        )

        return ProcessVideoResult(
            outputFile = File(info.outputPath)
        )
    }

    fun process(
        uri: Uri,
        strength: Float = 0.40f,
        onProgress: (
            current: Int,
            total: Int,
            stage: String
        ) -> Unit
    ): ProcessingInfo {

        require(strength in 0f..1f) {
            "Strength must be between 0 and 1."
        }

        onProgress(0, 100, "Inspecting video")

        val videoInfo = codecEngine.inspect(uri)

        if (videoInfo.durationUs <= 0L) {
            throw IllegalStateException(
                "Unable to determine video duration."
            )
        }

        onProgress(1, 100, "Preparing AnimeGAN")

        val modelPath =
            modelManager.animeModelPath()
                ?: throw IllegalStateException(
                    "AnimeGANv3 model is not available."
                )

        val outputDirectory =
            File(context.filesDir, "output").apply {
                mkdirs()
            }

        val outputFile =
            File(
                outputDirectory,
                "anime_${System.currentTimeMillis()}.mp4"
            )

        OnnxAnimeEngine(modelPath).use { animeEngine ->

            codecEngine.processVideo(
                inputUri = uri,
                outputFile = outputFile,
                animeEngine = animeEngine,
                strength = strength
            ) { currentFrame, totalFrames, stage ->

                val progress =
                    if (totalFrames > 0) {
                        (
                            currentFrame.toLong() * 98L / totalFrames + 1L
                        ).coerceIn(1L, 99L).toInt()
                    } else {
                        1
                    }

                onProgress(progress, 100, stage)
            }
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException(
                "Video processing completed without creating an output file."
            )
        }

        onProgress(100, 100, "Complete")

        return ProcessingInfo(
            inputUri = uri,
            displayName = outputFile.name,
            durationUs = videoInfo.durationUs,
            width = videoInfo.width,
            height = videoInfo.height,
            frameRate = videoInfo.frameRate,
            rotation = videoInfo.rotationDegrees,
            outputPath = outputFile.absolutePath
        )
    }
}
