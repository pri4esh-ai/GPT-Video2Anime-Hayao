package com.gptvideo2anime.inference

import ai.onnxruntime.*
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class OnnxAnimeEngine(
    modelPath: String
) : AutoCloseable {

    companion object {
        private const val MODEL_SIZE = 512
        private const val CHANNELS = 3
        private const val THREADS = 4
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputName: String
    private val outputName: String

    private val inputLayout: Layout

    private val modelWidth: Int
    private val modelHeight: Int

    // ---------- Reusable buffers ----------

    private val pixelCount: Int

    private val inputPixels: IntArray

    private val inputFloatBuffer: FloatBuffer

    private val inputTensor: OnnxTensor

    private val outputPixels: IntArray

    private val outputBitmap: Bitmap

    init {

        val options =
            OrtSession.SessionOptions().apply {

                setIntraOpNumThreads(THREADS)
                setInterOpNumThreads(1)
                setOptimizationLevel(
                    OrtSession.SessionOptions.OptLevel.ALL_OPT
                )
            }

        session =
            environment.createSession(
                modelPath,
                options
            )

        inputName =
            session.inputNames.first()

        outputName =
            session.outputNames.first()

        val info =
            session.inputInfo[inputName]!!.info as TensorInfo

        val shape =
            info.shape

        inputLayout =
            if (shape[1] == 3L)
                Layout.NCHW
            else
                Layout.NHWC

        modelWidth =
            if (inputLayout == Layout.NCHW)
                (shape[3].takeIf { it > 0 } ?: MODEL_SIZE.toLong()).toInt()
            else
                (shape[2].takeIf { it > 0 } ?: MODEL_SIZE.toLong()).toInt()

        modelHeight =
            if (inputLayout == Layout.NCHW)
                (shape[2].takeIf { it > 0 } ?: MODEL_SIZE.toLong()).toInt()
            else
                (shape[1].takeIf { it > 0 } ?: MODEL_SIZE.toLong()).toInt()

        pixelCount =
            modelWidth * modelHeight

        inputPixels =
            IntArray(pixelCount)

        inputFloatBuffer =
            FloatBuffer.allocate(
                pixelCount * CHANNELS
            )

        inputTensor =
            OnnxTensor.createTensor(
                environment,
                inputFloatBuffer,
                if (inputLayout == Layout.NCHW)
                    longArrayOf(
                        1,
                        3,
                        modelHeight.toLong(),
                        modelWidth.toLong()
                    )
                else
                    longArrayOf(
                        1,
                        modelHeight.toLong(),
                        modelWidth.toLong(),
                        3
                    )
            )

        outputPixels =
            IntArray(pixelCount)

        outputBitmap =
            Bitmap.createBitmap(
                modelWidth,
                modelHeight,
                Bitmap.Config.ARGB_8888
            )
    }
fun processFrame(
    frame: Bitmap
): Bitmap {

    require(!frame.isRecycled) {
        "Input frame is recycled."
    }

    val resized =
        if (
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

    val ownsResized =
        resized !== frame

    try {

        updateInputTensor(resized)

        session.run(
            mapOf(inputName to inputTensor)
        ).use { result ->

            require(result.size() > 0) {
                "ONNX model returned no output."
            }

            return outputToBitmap(
                output = result[0].value,
                outputWidth = frame.width,
                outputHeight = frame.height
            )
        }

    } finally {

        if (
            ownsResized &&
            !resized.isRecycled
        ) {
            resized.recycle()
        }
    }
}

fun infer(
    frame: Bitmap
): Bitmap {
    return processFrame(frame)
}

private fun updateInputTensor(
    bitmap: Bitmap
) {

    bitmap.getPixels(
        inputPixels,
        0,
        modelWidth,
        0,
        0,
        modelWidth,
        modelHeight
    )

    inputFloatBuffer.position(0)

    if (inputLayout == Layout.NCHW) {

        for (i in 0 until pixelCount) {
            inputFloatBuffer.put(
                i,
                ((inputPixels[i] shr 16 and 255) / 127.5f) - 1f
            )
        }

        val greenOffset =
            pixelCount

        for (i in 0 until pixelCount) {
            inputFloatBuffer.put(
                greenOffset + i,
                ((inputPixels[i] shr 8 and 255) / 127.5f) - 1f
            )
        }

        val blueOffset =
            pixelCount * 2

        for (i in 0 until pixelCount) {
            inputFloatBuffer.put(
                blueOffset + i,
                ((inputPixels[i] and 255) / 127.5f) - 1f
            )
        }

    } else {

        var index = 0

        for (pixel in inputPixels) {

            inputFloatBuffer.put(
                index++,
                ((pixel shr 16 and 255) / 127.5f) - 1f
            )

            inputFloatBuffer.put(
                index++,
                ((pixel shr 8 and 255) / 127.5f) - 1f
            )

            inputFloatBuffer.put(
                index++,
                ((pixel and 255) / 127.5f) - 1f
            )
        }
    }

    inputFloatBuffer.position(0)
}
private fun outputToBitmap(
    output: Any,
    outputWidth: Int,
    outputHeight: Int
): Bitmap {

    val data =
        extractFloatArray(output)

    require(data.size >= pixelCount * 3) {
        "ONNX output too small."
    }

    if (inputLayout == Layout.NCHW) {

        val gOffset = pixelCount
        val bOffset = pixelCount * 2

        for (i in 0 until pixelCount) {

            val r = outputValueToByte(data[i])
            val g = outputValueToByte(data[gOffset + i])
            val b = outputValueToByte(data[bOffset + i])

            outputPixels[i] =
                (255 shl 24) or
                (r shl 16) or
                (g shl 8) or
                b
        }

    } else {

        var index = 0

        for (i in 0 until pixelCount) {

            val r = outputValueToByte(data[index++])
            val g = outputValueToByte(data[index++])
            val b = outputValueToByte(data[index++])

            outputPixels[i] =
                (255 shl 24) or
                (r shl 16) or
                (g shl 8) or
                b
        }
    }

    outputBitmap.setPixels(
        outputPixels,
        0,
        modelWidth,
        0,
        0,
        modelWidth,
        modelHeight
    )

    if (
        outputWidth == modelWidth &&
        outputHeight == modelHeight
    ) {
        return outputBitmap.copy(
            Bitmap.Config.ARGB_8888,
            false
        )
    }

    return Bitmap.createScaledBitmap(
        outputBitmap,
        outputWidth,
        outputHeight,
        true
    )
}
private fun extractFloatArray(
    value: Any
): FloatArray {

    return when (value) {

        is FloatArray ->
            value

        is Array<*> -> {

            /*
             * Fast path for ONNX outputs shaped as Array<FloatArray>.
             */
            if (
                value.isNotEmpty() &&
                value[0] is FloatArray
            ) {

                var total = 0

                for (row in value) {
                    total += (row as FloatArray).size
                }

                val out = FloatArray(total)

                var offset = 0

                for (row in value) {

                    val array = row as FloatArray

                    System.arraycopy(
                        array,
                        0,
                        out,
                        offset,
                        array.size
                    )

                    offset += array.size
                }

                return out
            }

            /*
             * Generic fallback for nested ONNX arrays.
             */
            val list = ArrayList<Float>()

            fun visit(any: Any?) {

                when (any) {

                    is FloatArray ->
                        list.addAll(any.toList())

                    is Array<*> ->
                        any.forEach { visit(it) }

                    is Number ->
                        list.add(any.toFloat())

                    null -> Unit

                    else ->
                        throw IllegalStateException(
                            "Unsupported ONNX output element: ${any::class.java.name}"
                        )
                }
            }

            visit(value)

            list.toFloatArray()
        }

        else ->
            throw IllegalStateException(
                "Unsupported ONNX output type: ${value::class.java.name}"
            )
    }
}

private fun outputValueToByte(
    value: Float
): Int {

    val normalized =
        ((value + 1f) * 127.5f)
            .roundToInt()

    return normalized.coerceIn(
        0,
        255
    )
}

private enum class Layout {
    NCHW,
    NHWC
}

override fun close() {

    try {
        inputTensor.close()
    } catch (_: Exception) {
    }

    try {
        session.close()
    } catch (_: Exception) {
    }
}
}
