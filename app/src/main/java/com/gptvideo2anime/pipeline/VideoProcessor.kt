package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import java.io.File

class VideoProcessor(
    private val context: Context
) {

    private val codecEngine =
        MediaCodecVideoEngine(context)

    private val modelManager =
        ModelManager(context)

    data class ProcessingInfo(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val durationUs: Long,
        val mime: String,
        val decoderAvailable: Boolean,
        val encoderAvailable: Boolean,
        val encoderMime: String,
        val testFramePath: String?
    )

    fun process(
        uri: Uri,
        onProgress: (
            current: Int,
            total: Int,
            stage: String
        ) -> Unit
    ): ProcessingInfo {

        val total = 7

        onProgress(0, total, "Opening video")

        val info =
            codecEngine.inspect(uri)

        onProgress(1, total, "Checking decoder")

        codecEngine.findDecoder(info.mime)
            ?: throw IllegalStateException(
                "No decoder for ${info.mime}"
            )

        val encoderMime =
            codecEngine.bestEncoderMime(info.mime)

        onProgress(2, total, "Checking encoder")

        codecEngine.findEncoder(encoderMime)
            ?: throw IllegalStateException(
                "No encoder for $encoderMime"
            )

        val modelPath =
            modelManager.animeModelPath()
                ?: throw IllegalStateException(
                    "AnimeGANv3 model missing."
                )

        onProgress(3, total, "Extracting first frame")

        val frame =
            extractFirstFrame(uri)

        onProgress(4, total, "Running AnimeGANv3")

        val anime =
            OnnxAnimeEngine(modelPath).use {
                it.processFrame(frame)
            }

        onProgress(5, total, "Saving preview")

        val dir =
            File(context.filesDir, "stage1")

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val file =
            File(dir, "anime_test_frame.png")

        try {

            file.outputStream().use {

                anime.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    it
                )

                it.flush()
            }

        } finally {

            anime.recycle()
            frame.recycle()
        }

        Log.i(
            "VideoProcessor",
            "Preview saved: ${file.absolutePath}"
        )

        onProgress(6, total, "Finalizing")

        val result =
            ProcessingInfo(
                width = info.width,
                height = info.height,
                frameRate = info.frameRate,
                durationUs = info.durationUs,
                mime = info.mime,
                decoderAvailable = true,
                encoderAvailable = true,
                encoderMime = encoderMime,
                testFramePath = file.absolutePath
            )

        onProgress(7, total, "Complete")

        return result
    }

    private fun extractFirstFrame(
        uri: Uri
    ): Bitmap {

        val retriever =
            MediaMetadataRetriever()

        try {

            retriever.setDataSource(
                context,
                uri
            )

            return retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: throw IllegalStateException(
                "Unable to extract first frame."
            )

        } finally {

            retriever.release()
        }
    }
}
