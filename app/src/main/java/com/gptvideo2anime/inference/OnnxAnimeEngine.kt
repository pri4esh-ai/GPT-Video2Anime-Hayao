package com.gptvideo2anime.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import java.nio.FloatBuffer

class OnnxAnimeEngine(
    modelPath: String
) : AutoCloseable {

    private val environment = OrtEnvironment.getEnvironment()

    private val session: OrtSession =
        environment.createSession(modelPath)

    private val inputName: String =
        session.inputNames.firstOrNull()
            ?: throw IllegalStateException("ONNX model has no input.")

    private val inputInfo: TensorInfo =
        session.inputInfo[inputName]?.info as? TensorInfo
            ?: throw IllegalStateException("Unable to inspect ONNX input.")

    private val isNchw: Boolean =
        inputInfo.shape.size == 4 && inputInfo.shape[1] == 3L

    private val modelHeight: Int =
        resolveDimension(
            if (isNchw) inputInfo.shape[2] else inputInfo.shape[1],
            512
        )

    private val modelWidth: Int =
        resolveDimension(
            if (isNchw) inputInfo.shape[3] else inputInfo.shape[2],
            512
        )

    fun processFrame(frame: Bitmap): Bitmap {
        require(!frame.isRecycled) {
            "Input frame is recycled."
        }

        val resized = if (
            frame.width == modelWidth &&
            frame.height == modelHeight
        ) {
            frame
        } else {
            Bitmap.createScaledBitmap(
                frame,
                modelWidth,
                modelHeight,
                true
            )
        }

        try {
            val tensorData =
                if (isNchw) {
                    bitmapToNchwFloatArray(resized)
                } else {
                    bitmapToNhwcFloatArray(resized)
                }

            val shape =
                if (isNchw) {
                    longArrayOf(
                        1L,
                        3L,
                        modelHeight.toLong(),
                        modelWidth.toLong()
                    )
                } else {
                    longArrayOf(
                        1L,
                        modelHeight.toLong(),
                        modelWidth.toLong(),
                        3L
                    )
                }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(tensorData),
                shape
            ).use { inputTensor ->

                session.run(
                    mapOf(inputName to inputTensor)
                ).use { result ->

                    require(result.size() > 0) {
                        "ONNX model returned no output."
                    }

                    return outputToBitmap(
                        result[0].value,
                        frame.width,
                        frame.height,
                        modelWidth,
                        modelHeight
                    )
                }
            }
        } finally {
            if (resized !== frame && !resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    private fun bitmapToNchwFloatArray(
        bitmap: Bitmap
    ): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height

        val pixels = IntArray(pixelCount)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val data = FloatArray(pixelCount * 3)

        for (i in 0 until pixelCount) {
            val pixel = pixels[i]

            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            data[i] = r * 2f - 1f
            data[pixelCount + i] = g * 2f - 1f
            data[pixelCount * 2 + i] = b * 2f - 1f
        }

        return data
    }

    private fun bitmapToNhwcFloatArray(
        bitmap: Bitmap
    ): FloatArray {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val data = FloatArray(width * height * 3)

        var index = 0

        for (pixel in pixels) {
            data[index++] =
                (((pixel shr 16) and 0xFF) / 255f) * 2f - 1f

            data[index++] =
                (((pixel shr 8) and 0xFF) / 255f) * 2f - 1f

            data[index++] =
                ((pixel and 0xFF) / 255f) * 2f - 1f
        }

        return data
    }

    private fun outputToBitmap(
        output: Any,
        outputWidth: Int,
        outputHeight: Int,
        modelWidth: Int,
        modelHeight: Int
    ): Bitmap {
        val data = extractFloatArray(output)

        val pixelCount = modelWidth * modelHeight

        require(data.size >= pixelCount * 3) {
            "Invalid ONNX output size: ${data.size}"
        }

        val pixels = IntArray(pixelCount)

        for (i in 0 until pixelCount) {
            val base = i * 3

            val r = outputValueToByte(data[base])
            val g = outputValueToByte(data[base + 1])
            val b = outputValueToByte(data[base + 2])

            pixels[i] =
                (255 shl 24) or
                    (r shl 16) or
                    (g shl 8) or
                    b
        }

        val modelBitmap = Bitmap.createBitmap(
            modelWidth,
            modelHeight,
            Bitmap.Config.ARGB_8888
        )

        modelBitmap.setPixels(
            pixels,
            0,
            modelWidth,
            0,
            0,
            modelWidth,
            modelHeight
        )

        if (
            modelWidth == outputWidth &&
            modelHeight == outputHeight
        ) {
            return modelBitmap
        }

        val scaled = Bitmap.createScaledBitmap(
            modelBitmap,
            outputWidth,
            outputHeight,
            true
        )

        modelBitmap.recycle()

        return scaled
    }

    private fun extractFloatArray(
        value: Any
    ): FloatArray {
        return when (value) {
            is FloatArray -> value

            is Array<*> -> {
                val result = ArrayList<Float>()

                fun visit(item: Any?) {
                    when (item) {
                        is FloatArray -> {
                            for (value in item) {
                                result.add(value)
                            }
                        }

                        is Array<*> -> {
                            for (child in item) {
                                visit(child)
                            }
                        }

                        is Number -> {
                            result.add(item.toFloat())
                        }

                        null -> Unit

                        else -> {
                            throw IllegalStateException(
                                "Unsupported ONNX output element: " +
                                    item::class.java.name
                            )
                        }
                    }
                }

                visit(value)

                result.toFloatArray()
            }

            else -> {
                throw IllegalStateException(
                    "Unsupported ONNX output type: " +
                        value::class.java.name
                )
            }
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

    private fun outputValueToByte(
        value: Float
    ): Int {
        return (
            ((value + 1f) * 0.5f)
                .coerceIn(0f, 1f) * 255f
            ).toInt()
    }

    override fun close() {
        session.close()
    }
}
