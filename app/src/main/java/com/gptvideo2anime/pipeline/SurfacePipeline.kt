package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.view.Surface
import com.gptvideo2anime.inference.OnnxAnimeEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class SurfacePipeline(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val animeEngine: OnnxAnimeEngine
) : AutoCloseable {

    companion object {
        private const val MODEL_SIZE = 512
    }

    private val imageQueue =
        ArrayBlockingQueue<Image>(2)

    private val surfaceTexture =
        SurfaceTexture(0).apply {
            setDefaultBufferSize(
                outputWidth,
                outputHeight
            )
        }

    val decoderSurface =
        Surface(surfaceTexture)

    private val imageReader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.Builder(
                MODEL_SIZE,
                MODEL_SIZE
            )
                .setMaxImages(2)
                .setImageFormat(
                    android.graphics.ImageFormat.YUV_420_888
                )
                .build()
        } else {
            ImageReader.newInstance(
                MODEL_SIZE,
                MODEL_SIZE,
                android.graphics.ImageFormat.YUV_420_888,
                2
            )
        }

    init {
        imageReader.setOnImageAvailableListener(
            { reader ->
                val image =
                    reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                if (!imageQueue.offer(image)) {
                    image.close()
                }
            },
            null
        )
    }
    fun processNextFrame(): Bitmap? {

        val image =
            imageQueue.poll(
                100,
                TimeUnit.MILLISECONDS
            ) ?: return null

        return try {

            val bitmap =
                YuvConverter.imageToBitmap(image)

            val resized =
                if (
                    bitmap.width == MODEL_SIZE &&
                    bitmap.height == MODEL_SIZE
                ) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        MODEL_SIZE,
                        MODEL_SIZE,
                        true
                    )
                }

            try {

                animeEngine.processFrame(
                    resized
                )

            } finally {

                if (
                    resized !== bitmap &&
                    !resized.isRecycled
                ) {
                    resized.recycle()
                }

                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

        } finally {
            image.close()
        }
    }

    fun encoderInputSurface(): Surface {
        return imageReader.surface
    }
    fun clearQueue() {

        while (true) {

            val image =
                imageQueue.poll()
                    ?: break

            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {

        clearQueue()

        try {
            imageReader.close()
        } catch (_: Exception) {
        }

        try {
            decoderSurface.release()
        } catch (_: Exception) {
        }

        try {
            surfaceTexture.release()
        } catch (_: Exception) {
        }
    }
}
