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

/**
 * Manages the EGL/Surface hardware rendering pipeline, capturing YUV frames from MediaCodec,
 * converting them to Bitmaps, and running ONNX style-transfer inference.
 */
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
                // Queue is full, drop oldest or current frame to prevent backlog
                image.close()
            }
        }, handler)
    }

    /**
     * Polls the next decoded frame, converts it, runs Hayao style transfer,
     * and returns the resulting anime Bitmap scaled to output dimensions.
     */
    fun processNextFrame(): Bitmap? {
        val image = imageQueue.poll(100, TimeUnit.MILLISECONDS) ?: return null

        return try {
            val bitmap = YuvConverter.imageToBitmap(image)
            
            // Run ONNX inference
            val processedBitmap = animeEngine.processFrame(bitmap)

            // Ensure output matches requested video stream dimensions
            if (processedBitmap.width == outputWidth && processedBitmap.height == outputHeight) {
                processedBitmap
            } else {
                val scaled = Bitmap.createScaledBitmap(processedBitmap, outputWidth, outputHeight, true)
                if (scaled !== processedBitmap && !processedBitmap.isRecycled) {
                    processedBitmap.recycle()
                }
                scaled
            }
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
