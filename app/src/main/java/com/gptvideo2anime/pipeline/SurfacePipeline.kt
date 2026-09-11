package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
        private const val QUEUE_CAPACITY = 2
    }

    private val handlerThread = HandlerThread("SurfacePipelineThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val imageQueue = ArrayBlockingQueue<Image>(QUEUE_CAPACITY)

    private val imageReader: ImageReader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ImageReader.Builder(outputWidth, outputHeight)
            .setMaxImages(QUEUE_CAPACITY)
            .setImageFormat(android.graphics.ImageFormat.YUV_420_888)
            .build()
    } else {
        ImageReader.newInstance(
            outputWidth,
            outputHeight,
            android.graphics.ImageFormat.YUV_420_888,
            QUEUE_CAPACITY
        )
    }

    val decoderSurface: Surface
        get() = imageReader.surface

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!imageQueue.offer(image)) {
                image.close()
            }
        }, handler)
    }

    fun processNextFrame(): Bitmap? {
        val image = imageQueue.poll(100, TimeUnit.MILLISECONDS) ?: return null

        return try {
            val bitmap = YuvConverter.imageToBitmap(image)

            val resized = if (bitmap.width == MODEL_SIZE && bitmap.height == MODEL_SIZE) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, MODEL_SIZE, MODEL_SIZE, true)
            }

            val processedFrame: Bitmap? = try {
                animeEngine.processFrame(resized)
            } finally {
                if (resized !== bitmap && !resized.isRecycled) {
                    resized.recycle()
                }
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

            processedFrame
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun clearQueue() {
        while (true) {
            val image = imageQueue.poll() ?: break
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
            handlerThread.quitSafely()
        } catch (_: Exception) {
        }
    }
}
