package com.gptvideo2anime.inference

import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class OnnxAnimeEngine(
    private val modelPath: String
) : Closeable {

    enum class Layout { NCHW, NHWC }

    private val environment = OrtEnvironment.getEnvironment()

    private val session = environment.createSession(
        modelPath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(
                OrtSession.SessionOptions.OptLevel.ALL_OPT
            )
        }
    )

    private val inputName = session.inputNames.first()

    private val inputInfo =
        session.inputInfo[inputName]!!.info as TensorInfo

    private val inputLayout =
        detectLayout(inputInfo.shape, Layout.NCHW)

    private val modelWidth =
        resolveDimension(
            if (inputLayout == Layout.NCHW)
                inputInfo.shape[3]
            else
                inputInfo.shape[2],
            512
        )

    private val modelHeight =
        resolveDimension(
            if (inputLayout == Layout.NCHW)
                inputInfo.shape[2]
            else
                inputInfo.shape[1],
            512
        )

    private val pixelCount =
        modelWidth * modelHeight

    private val pixelBuffer =
        IntArray(pixelCount)

    private val floatBuffer =
        FloatArray(pixelCount * 3)

    @Synchronized
    fun processFrame(frame: Bitmap): Bitmap {

        require(!frame.isRecycled)

        val resized =
            if (
                frame.width == modelWidth &&
                frame.height == modelHeight
            ) frame
            else Bitmap.createScaledBitmap(
                frame,
                modelWidth,
                modelHeight,
                true
            )

        val ownsResized =
            resized !== frame

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

                        floatBuffer[i] =
                            ((p shr 16 and 255) / 127.5f) - 1f

                        floatBuffer[plane + i] =
                            ((p shr 8 and 255) / 127.5f) - 1f

                        floatBuffer[plane * 2 + i] =
                            ((p and 255) / 127.5f) - 1f
                    }
                }

                Layout.NHWC -> {

                    var k = 0

                    for (p in pixelBuffer) {

                        floatBuffer[k++] =
                            ((p shr 16 and 255) / 127.5f) - 1f

                        floatBuffer[k++] =
                            ((p shr 8 and 255) / 127.5f) - 1f

                        floatBuffer[k++] =
                            ((p and 255) / 127.5f) - 1f
                    }
                }
            }

            val shape =
                when (inputLayout) {

                    Layout.NCHW ->
                        longArrayOf(
                            1,
                            3,
                            modelHeight.toLong(),
                            modelWidth.toLong()
                        )

                    Layout.NHWC ->
                        longArrayOf(
                            1,
                            modelHeight.toLong(),
                            modelWidth.toLong(),
                            3
                        )
                }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(floatBuffer),
                shape
            ).use { tensor ->

                session.run(
                    mapOf(inputName to tensor)
                ).use { result ->

                    require(result.size() > 0)

                    val outputInfo =
                        session.outputInfo[
                            session.outputNames.first()
                        ]?.info as? TensorInfo

                    return outputToBitmap(
                        output = result[0].value!!,
                        outputShape = outputInfo?.shape,
                        outputWidth = frame.width,
                        outputHeight = frame.height
                    )
                }
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

    fun infer(frame: Bitmap) =
        processFrame(frame)

    private fun outputToBitmap(
        output: Any,
        outputShape: LongArray?,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {

        val data =
            extractFloatArray(output)

        val layout =
            if (outputShape != null)
                detectLayout(outputShape, inputLayout)
            else
                inputLayout

        val outWidth =
            if (outputShape != null)
                resolveDimension(
                    if (layout == Layout.NCHW)
                        outputShape[3]
                    else
                        outputShape[2],
                    modelWidth
                )
            else
                modelWidth

        val outHeight =
            if (outputShape != null)
                resolveDimension(
                    if (layout == Layout.NCHW)
                        outputShape[2]
                    else
                        outputShape[1],
                    modelHeight
                )
            else
                modelHeight

        val count =
            outWidth * outHeight

        require(
            data.size >= count * 3
        ) {
            "ONNX output too small: ${data.size}"
        }

        val pixels =
            IntArray(count)

        when (layout) {

            Layout.NCHW -> {

                val plane = count

                for (i in 0 until count) {

                    pixels[i] =
                        argb(
                            modelValueToByte(data[i]),
                            modelValueToByte(data[plane + i]),
                            modelValueToByte(data[plane * 2 + i])
                        )
                }
            }

            Layout.NHWC -> {

                for (i in 0 until count) {

                    val b = i * 3

                    pixels[i] =
                        argb(
                            modelValueToByte(data[b]),
                            modelValueToByte(data[b + 1]),
                            modelValueToByte(data[b + 2])
                        )
                }
            }
        }

        val bitmap =
            Bitmap.createBitmap(
                outWidth,
                outHeight,
                Bitmap.Config.ARGB_8888
            )

        bitmap.setPixels(
            pixels,
            0,
            outWidth,
            0,
            0,
            outWidth,
            outHeight
        )

        return if (
            outWidth == outputWidth &&
            outHeight == outputHeight
        ) {

            bitmap

        } else {

            val scaled =
                Bitmap.createScaledBitmap(
                    bitmap,
                    outputWidth,
                    outputHeight,
                    true
                )

            if (
                scaled !== bitmap &&
                !bitmap.isRecycled
            ) bitmap.recycle()

            scaled
        }
    }

    private fun extractFloatArray(value: Any): FloatArray {

        return when (value) {

            is FloatArray ->
                value

            is Array<*> ->
                flattenNestedArray(value)

            else ->
                throw IllegalStateException(
                    "Unsupported output type: ${value::class.java.name}"
                )
        }
    }

    private fun flattenNestedArray(
        array: Array<*>
    ): FloatArray {

        val result =
            FloatArray(calculateArraySize(array))

        var index = 0

        fun visit(v: Any?) {

            when (v) {

                is FloatArray -> {

                    System.arraycopy(
                        v,
                        0,
                        result,
                        index,
                        v.size
                    )

                    index += v.size
                }

                is Array<*> ->
                    v.forEach(::visit)

                is Number ->
                    result[index++] = v.toFloat()
            }
        }

        visit(array)

        return result
    }

    private fun calculateArraySize(v: Any?): Int {

        return when (v) {

            is FloatArray ->
                v.size

            is Array<*> ->
                v.sumOf { calculateArraySize(it) }

            is Number ->
                1

            else ->
                0
        }
    }

    private fun detectLayout(
        shape: LongArray,
        defaultLayout: Layout
    ): Layout {

        return when {

            shape.size == 4 &&
                shape[1] == 3L ->
                Layout.NCHW

            shape.size == 4 &&
                shape[3] == 3L ->
                Layout.NHWC

            else ->
                defaultLayout
        }
    }

    private fun resolveDimension(
        value: Long,
        fallback: Int
    ): Int =
        if (value > 0) value.toInt()
        else fallback

    private fun modelValueToByte(value: Float): Int =
        (((value + 1f) * 127.5f).roundToInt())
            .coerceIn(0, 255)

    private fun argb(
        r: Int,
        g: Int,
        b: Int
    ): Int =
        (255 shl 24) or
            (r shl 16) or
            (g shl 8) or
            b

    fun inputNames(): Set<String> =
        session.inputNames

    fun outputNames(): Set<String> =
        session.outputNames

    override fun close() {
        runCatching { session.close() }
        runCatching { environment.close() }
    }
}
