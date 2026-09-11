package com.gptvideo2anime.inference

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class OnnxAnimeEngine(
    private val modelPath: String,
    private val modelWidth: Int = 512,
    private val modelHeight: Int = 512
) : Closeable {

    enum class Layout {
        NCHW,
        NHWC
    }

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = environment.createSession(modelPath, OrtSession.SessionOptions())

    private val inputName: String = session.inputNames.first()
    private val inputInfo: TensorInfo = session.inputInfo[inputName]?.info as TensorInfo
    private val inputLayout: Layout = detectLayout(inputInfo.shape, "input")

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
            resized.getPixels(
                pixelBuffer,
                0,
                modelWidth,
                0,
                0,
                modelWidth,
                modelHeight
            )

            when (inputLayout) {
                Layout.NCHW -> {
                    val plane = pixelCount
                    for (i in 0 until pixelCount) {
                        val p = pixelBuffer[i]
                        floatBuffer[i] = toModelValue((p shr 16) and 255)
                        floatBuffer[plane + i] = toModelValue((p shr 8) and 255)
                        floatBuffer[plane * 2 + i] = toModelValue(p and 255)
                    }
                }

                Layout.NHWC -> {
                    var k = 0
                    for (i in 0 until pixelCount) {
                        val p = pixelBuffer[i]
                        floatBuffer[k++] = toModelValue((p shr 16) and 255)
                        floatBuffer[k++] = toModelValue((p shr 8) and 255)
                        floatBuffer[k++] = toModelValue(p and 255)
                    }
                }
            }

            val shape = when (inputLayout) {
                Layout.NCHW -> longArrayOf(1L, 3L, modelHeight.toLong(), modelWidth.toLong())
                Layout.NHWC -> longArrayOf(1L, modelHeight.toLong(), modelWidth.toLong(), 3L)
            }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(floatBuffer),
                shape
            ).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    require(result.size() > 0) { "AnimeGAN returned no output." }

                    val outputInfo = session.outputInfo[session.outputNames.first()]?.info as? TensorInfo

                    return outputToBitmap(
                        output = result[0].value,
                        outputShape = outputInfo?.shape,
                        outputWidth = frame.width,
                        outputHeight = frame.height,
                        fallbackWidth = modelWidth,
                        fallbackHeight = modelHeight
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
        output: Any,
        outputShape: LongArray?,
        outputWidth: Int,
        outputHeight: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Bitmap {
        val data = extractFloatArray(output)
        require(data.isNotEmpty()) { "AnimeGAN returned empty output." }

        val shape = outputShape?.takeIf { it.size == 4 }

        val layout = try {
            shape?.let { detectLayout(it, "output") } ?: inputLayout
        } catch (_: Exception) {
            inputLayout
        }

        val outWidth: Int
        val outHeight: Int

        if (shape != null) {
            when (layout) {
                Layout.NCHW -> {
                    outHeight = resolveDimension(shape[2], fallbackHeight)
                    outWidth = resolveDimension(shape[3], fallbackWidth)
                }

                Layout.NHWC -> {
                    outHeight = resolveDimension(shape[1], fallbackHeight)
                    outWidth = resolveDimension(shape[2], fallbackWidth)
                }
            }
        } else {
            outWidth = fallbackWidth
            outHeight = fallbackHeight
        }

        val count = outWidth * outHeight
        require(data.size >= count * 3) { "Invalid AnimeGAN output." }

        val pixels = IntArray(count)

        when (layout) {
            Layout.NCHW -> {
                val plane = count
                for (i in 0 until count) {
                    val red = modelValueToByte(data[i])
                    val green = modelValueToByte(data[plane + i])
                    val blue = modelValueToByte(data[plane * 2 + i])
                    pixels[i] = argb(red, green, blue)
                }
            }

            Layout.NHWC -> {
                for (i in 0 until count) {
                    val base = i * 3
                    val red = modelValueToByte(data[base])
                    val green = modelValueToByte(data[base + 1])
                    val blue = modelValueToByte(data[base + 2])
                    pixels[i] = argb(red, green, blue)
                }
            }
        }

        val modelBitmap = Bitmap.createBitmap(
            outWidth,
            outHeight,
            Bitmap.Config.ARGB_8888
        )

        modelBitmap.setPixels(
            pixels,
            0,
            outWidth,
            0,
            0,
            outWidth,
            outHeight
        )

        if (outWidth == outputWidth && outHeight == outputHeight) {
            return modelBitmap
        }

        val finalBitmap = Bitmap.createScaledBitmap(
            modelBitmap,
            outputWidth,
            outputHeight,
            true
        )

        if (!modelBitmap.isRecycled) {
            modelBitmap.recycle()
        }

        return finalBitmap
    }

    private fun toModelValue(value: Int): Float {
        return value / 127.5f - 1f
    }

    private fun modelValueToByte(value: Float): Int {
        return (((value + 1f) * 127.5f).roundToInt()).coerceIn(0, 255)
    }

    private fun extractFloatArray(value: Any): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> flattenNestedArray(value)
            else -> throw IllegalStateException("Unsupported output tensor type: ${value::class.java.name}")
        }
    }

    private fun flattenNestedArray(array: Array<*>): FloatArray {
        val flatList = FloatArray(pixelCount * 3)
        var offset = 0

        fun traverse(element: Any?) {
            when (element) {
                is FloatArray -> {
                    System.arraycopy(element, 0, flatList, offset, element.size)
                    offset += element.size
                }
                is Array<*> -> {
                    for (item in element) {
                        traverse(item)
                    }
                }
                is Number -> {
                    flatList[offset++] = element.toFloat()
                }
            }
        }

        traverse(array)
        return flatList
    }

    private fun detectLayout(
        shape: LongArray,
        tensorName: String
    ): Layout {
        require(shape.size == 4) {
            "Unsupported $tensorName shape: ${shape.contentToString()}"
        }

        val channelFirst = shape[1] == 3L
        val channelLast = shape[3] == 3L

        return when {
            channelFirst && !channelLast -> Layout.NCHW
            channelLast && !channelFirst -> Layout.NHWC
            else -> Layout.NCHW // Default fallback for dynamic ONNX shapes
        }
    }

    private fun resolveDimension(
        dimension: Long,
        fallback: Int
    ): Int {
        return if (dimension > 0L) {
            dimension.toInt()
        } else {
            fallback
        }
    }

    private fun argb(
        red: Int,
        green: Int,
        blue: Int
    ): Int {
        return (255 shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun inputNames(): Set<String> = session.inputNames

    fun outputNames(): Set<String> = session.outputNames

    override fun close() {
        runCatching { session.close() }
        runCatching { environment.close() }
    }
}
