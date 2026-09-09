package com.gptvideo2anime.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

class OnnxAnimeEngine(
    modelPath: String
) : AutoCloseable {

    private val environment =
        OrtEnvironment.getEnvironment()

    private val session =
        environment.createSession(modelPath)

    fun inputNames(): Set<String> =
        session.inputNames

    fun outputNames(): Set<String> =
        session.outputNames

    fun processFrame(
        frame: Bitmap
    ): Bitmap {
        require(!frame.isRecycled) {
            "Input frame is recycled."
        }

        val inputName =
            session.inputNames.firstOrNull()
                ?: throw IllegalStateException(
                    "ONNX model has no input."
                )

        val tensorInfo =
            (session.inputInfo[inputName]?.info as? TensorInfo)
                ?: throw IllegalStateException(
                    "Unable to inspect ONNX input."
                )

        val inputShape =
            tensorInfo.shape

        require(inputShape.size == 4) {
            "Unsupported ONNX input rank: ${inputShape.size}"
        }

        val isNchw =
            inputShape[1] == 3L

        val modelHeight =
            resolveDimension(
                if (isNchw) inputShape[2] else inputShape[1],
                512
            )

        val modelWidth =
            resolveDimension(
                if (isNchw) inputShape[3] else inputShape[2],
                512
            )

        val resized =
            Bitmap.createScaledBitmap(
                frame,
                modelWidth,
                modelHeight,
                true
            )

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

            Log.i(
                "OnnxAnimeEngine",
                "Input tensor=${shape.contentToString()} layout=${if (isNchw) "NCHW" else "NHWC"}"
            )

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(tensorData),
                shape
            ).use { inputTensor ->

                session.run(
                    mapOf(
                        inputName to inputTensor
                    )
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
            if (!resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    fun infer(
        frame: Bitmap
    ): Bitmap {
        return processFrame(frame)
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

        val data =
            FloatArray(pixelCount * 3)

        for (i in 0 until pixelCount) {
            val pixel = pixels[i]

            val r =
                ((pixel shr 16) and 0xFF) / 255f

            val g =
                ((pixel shr 8) and 0xFF) / 255f

            val b =
                (pixel and 0xFF) / 255f

            data[i] =
                r * 2f - 1f

            data[pixelCount + i] =
                g * 2f - 1f

            data[pixelCount * 2 + i] =
                b * 2f - 1f
        }

        return data
    }

    private fun bitmapToNhwcFloatArray(
        bitmap: Bitmap
    ): FloatArray {
        val width = bitmap.width
        val height = bitmap.height

        val pixels =
            IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val data =
            FloatArray(width * height * 3)

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
        val data =
            extractFloatArray(output)

        val pixelCount =
            modelWidth * modelHeight

        require(data.size >= pixelCount * 3) {
            "ONNX output contains insufficient pixel data: ${data.size}"
        }

        val pixels =
            IntArray(pixelCount)

        val isNchwOutput =
            data.size == pixelCount * 3 &&
                looksLikeNchwOutput(data, pixelCount)

        if (isNchwOutput) {
            for (i in 0 until pixelCount) {
                val r =
                    outputValueToByte(data[i])

                val g =
                    outputValueToByte(
                        data[pixelCount + i]
                    )

                val b =
                    outputValueToByte(
                        data[pixelCount * 2 + i]
                    )

                pixels[i] =
                    (255 shl 24) or
                        (r shl 16) or
                        (g shl 8) or
                        b
            }
        } else {
            for (i in 0 until pixelCount) {
                val base =
                    i * 3

                val r =
                    outputValueToByte(data[base])

                val g =
                    outputValueToByte(data[base + 1])

                val b =
                    outputValueToByte(data[base + 2])

                pixels[i] =
                    (255 shl 24) or
                        (r shl 16) or
                        (g shl 8) or
                        b
            }
        }

        val modelBitmap =
            Bitmap.createBitmap(
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

        val scaled =
            Bitmap.createScaledBitmap(
                modelBitmap,
                outputWidth,
                outputHeight,
                true
            )

        modelBitmap.recycle()

        return scaled
    }

    private fun looksLikeNchwOutput(
        data: FloatArray,
        pixelCount: Int
    ): Boolean {
        if (data.size != pixelCount * 3) {
            return false
        }

        val sampleCount =
            minOf(pixelCount, 32)

        var channelRangeScore = 0

        for (i in 0 until sampleCount) {
            val r =
                data[i]

            val g =
                data[pixelCount + i]

            val b =
                data[pixelCount * 2 + i]

            if (
                r in -1.01f..1.01f &&
                g in -1.01f..1.01f &&
                b in -1.01f..1.01f
            ) {
                channelRangeScore++
            }
        }

        return channelRangeScore >= sampleCount / 2
    }

    private fun extractFloatArray(
        value: Any
    ): FloatArray {
        return when (value) {
            is FloatArray ->
                value

            is Array<*> -> {
                val list =
                    ArrayList<Float>()

                fun visit(value: Any?) {
                    when (value) {
                        is FloatArray ->
                            value.forEach(list::add)

                        is Array<*> ->
                            value.forEach(::visit)

                        is Number ->
                            list.add(value.toFloat())

                        null -> Unit

                        else ->
                            throw IllegalStateException(
                                "Unsupported output element: ${value::class.java.name}"
                            )
                    }
                }

                visit(value)

                list.toFloatArray()
            }

            else ->
                throw IllegalStateException(
                    "Unsupported output type: ${value::class.java.name}"
                )
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
        val normalized =
            if (value in -1f..1f) {
                (value + 1f) * 0.5f
            } else {
                value / 255f
            }

        return (
            normalized.coerceIn(0f, 1f) * 255f
        ).toInt()
    }

    override fun close() {
        session.close()
    }
}
