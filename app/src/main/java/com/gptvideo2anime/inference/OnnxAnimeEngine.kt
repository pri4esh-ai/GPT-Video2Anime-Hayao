package com.gptvideo2anime.inference

import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * High-performance ONNX Runtime inference engine for Hayao/Ghibli Anime Style Transfer.
 */
class OnnxAnimeEngine(
    private val modelPath: String
) : Closeable {

    enum class Layout {
        NCHW,
        NHWC
    }

    private val environment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        
        // Attempt NNAPI (Hardware/GPU Acceleration on Android) safely
        runCatching {
            addNnapi()
        }
    }

    private val session: OrtSession = environment.createSession(modelPath, sessionOptions)

    private val inputName: String = session.inputNames.first()
    private val outputName: String = session.outputNames.first()

    private val inputInfo: TensorInfo = session.inputInfo[inputName]!!.info as TensorInfo

    private val inputLayout: Layout = detectLayout(inputInfo.shape, Layout.NCHW)

    private val modelWidth: Int = resolveDimension(
        if (inputLayout == Layout.NCHW) inputInfo.shape[3] else inputInfo.shape[2],
        512
    )

    private val modelHeight: Int = resolveDimension(
        if (inputLayout == Layout.NCHW) inputInfo.shape[2] else inputInfo.shape[1],
        512
    )

    private val pixelCount: Int = modelWidth * modelHeight
    private val pixelBuffer: IntArray = IntArray(pixelCount)
    private val floatBuffer: FloatArray = FloatArray(pixelCount * 3)

    @Synchronized
    fun processFrame(frame: Bitmap): Bitmap {
        require(!frame.isRecycled) { "Input bitmap is recycled." }

        val resized = if (frame.width == modelWidth && frame.height == modelHeight) {
            frame
        } else {
            Bitmap.createScaledBitmap(frame, modelWidth, modelHeight, true)
        }

        val ownsResized = resized !== frame

        try {
            resized.getPixels(pixelBuffer, 0, modelWidth, 0, 0, modelWidth, modelHeight)

            // Normalize pixels to [-1, 1] range for generator network
            when (inputLayout) {
                Layout.NCHW -> {
                    val plane = pixelCount
                    for (i in 0 until pixelCount) {
                        val p = pixelBuffer[i]
                        floatBuffer[i] = ((p shr 16 and 255) / 127.5f) - 1.0f
                        floatBuffer[plane + i] = ((p shr 8 and 255) / 127.5f) - 1.0f
                        floatBuffer[plane * 2 + i] = ((p and 255) / 127.5f) - 1.0f
                    }
                }
                Layout.NHWC -> {
                    var k = 0
                    for (p in pixelBuffer) {
                        floatBuffer[k++] = ((p shr 16 and 255) / 127.5f) - 1.0f
                        floatBuffer[k++] = ((p shr 8 and 255) / 127.5f) - 1.0f
                        floatBuffer[k++] = ((p and 255) / 127.5f) - 1.0f
                    }
                }
            }

            val shape = inputInfo.shape.copyOf().apply {
                if (this[0] <= 0L) this[0] = 1L
                if (inputLayout == Layout.NCHW) {
                    this[1] = 3L
                    this[2] = modelHeight.toLong()
                    this[3] = modelWidth.toLong()
                } else {
                    this[1] = modelHeight.toLong()
                    this[2] = modelWidth.toLong()
                    this[3] = 3L
                }
            }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(floatBuffer),
                shape
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    require(result.size() > 0) { "ONNX model returned no output." }

                    val outputTensor = result[0] as? OnnxTensor
                        ?: error("ONNX output is not an OnnxTensor")

                    val outputInfo = session.outputInfo[outputName]?.info as? TensorInfo

                    return outputToBitmap(
                        outputTensor = outputTensor,
                        outputShape = outputInfo?.shape,
                        outputWidth = frame.width,
                        outputHeight = frame.height
                    )
                }
            }
        } finally {
            if (ownsResized && !resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    fun infer(frame: Bitmap): Bitmap = processFrame(frame)

    private fun outputToBitmap(
        outputTensor: OnnxTensor,
        outputShape: LongArray?,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        val floatBuffer = outputTensor.floatBuffer

        val layout = if (outputShape != null) {
            detectLayout(outputShape, inputLayout)
        } else {
            inputLayout
        }

        val outWidth = if (outputShape != null) {
            resolveDimension(
                if (layout == Layout.NCHW) outputShape[3] else outputShape[2],
                modelWidth
            )
        } else {
            modelWidth
        }

        val outHeight = if (outputShape != null) {
            resolveDimension(
                if (layout == Layout.NCHW) outputShape[2] else outputShape[1],
                modelHeight
            )
        } else {
            modelHeight
        }

        val count = outWidth * outHeight
        val pixels = IntArray(count)

        when (layout) {
            Layout.NCHW -> {
                val plane = count
                for (i in 0 until count) {
                    val r = floatBuffer.get(i)
                    val g = floatBuffer.get(plane + i)
                    val b = floatBuffer.get(plane * 2 + i)
                    pixels[i] = argb(
                        modelValueToByte(r),
                        modelValueToByte(g),
                        modelValueToByte(b)
                    )
                }
            }
            Layout.NHWC -> {
                for (i in 0 until count) {
                    val base = i * 3
                    val r = floatBuffer.get(base)
                    val g = floatBuffer.get(base + 1)
                    val b = floatBuffer.get(base + 2)
                    pixels[i] = argb(
                        modelValueToByte(r),
                        modelValueToByte(g),
                        modelValueToByte(b)
                    )
                }
            }
        }

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)

        return if (outWidth == outputWidth && outHeight == outputHeight) {
            bitmap
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, outputWidth, outputHeight, true)
            if (scaled !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            scaled
        }
    }

    private fun detectLayout(shape: LongArray, defaultLayout: Layout): Layout {
        if (shape.size != 4) return defaultLayout
        if (shape[1] == 3L) return Layout.NCHW
        if (shape[3] == 3L) return Layout.NHWC
        return defaultLayout
    }

    private fun resolveDimension(value: Long, fallback: Int): Int =
        if (value > 0L) value.toInt() else fallback

    private fun modelValueToByte(value: Float): Int =
        (((value + 1f) * 127.5f).roundToInt()).coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (255 shl 24) or (r shl 16) or (g shl 8) or b

    fun inputNames(): Set<String> = session.inputNames
    fun outputNames(): Set<String> = session.outputNames

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
        runCatching { environment.close() }
    }
}
