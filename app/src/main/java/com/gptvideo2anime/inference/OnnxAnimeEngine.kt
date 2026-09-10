package com.gptvideo2anime.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class OnnxAnimeEngine(
    modelPath: String
) : AutoCloseable {

    companion object {
        private const val TAG = "OnnxAnimeEngine"
    }

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

        val inputTensorInfo =
            session.inputInfo[inputName]?.info as? TensorInfo
                ?: throw IllegalStateException(
                    "Unable to inspect ONNX input."
                )

        val inputShape =
            inputTensorInfo.shape

        require(inputShape.size == 4) {
            "Unsupported ONNX input rank: ${inputShape.size}. " +
                "Expected rank 4."
        }

        val inputLayout =
            detectLayout(
                inputShape,
                "input"
            )

        val modelHeight =
            when (inputLayout) {
                Layout.NCHW ->
                    resolveDimension(
                        inputShape[2],
                        512
                    )

                Layout.NHWC ->
                    resolveDimension(
                        inputShape[1],
                        512
                    )
            }

        val modelWidth =
            when (inputLayout) {
                Layout.NCHW ->
                    resolveDimension(
                        inputShape[3],
                        512
                    )

                Layout.NHWC ->
                    resolveDimension(
                        inputShape[2],
                        512
                    )
            }

        require(modelWidth > 0 && modelHeight > 0) {
            "Invalid ONNX input dimensions: " +
                "${modelWidth}x$modelHeight"
        }

        Log.i(
            TAG,
            "Input name=$inputName " +
                "shape=${inputShape.contentToString()} " +
                "layout=$inputLayout " +
                "size=${modelWidth}x$modelHeight"
        )

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

        val ownsResizedBitmap =
            resized !== frame

        try {

            val tensorData =
                when (inputLayout) {

                    Layout.NCHW ->
                        bitmapToNchwFloatArray(
                            resized
                        )

                    Layout.NHWC ->
                        bitmapToNhwcFloatArray(
                            resized
                        )
                }

            val shape =
                when (inputLayout) {

                    Layout.NCHW ->
                        longArrayOf(
                            1L,
                            3L,
                            modelHeight.toLong(),
                            modelWidth.toLong()
                        )

                    Layout.NHWC ->
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
                    mapOf(
                        inputName to inputTensor
                    )
                ).use { result ->

                    require(result.size() > 0) {
                        "ONNX model returned no output."
                    }

                    val outputValue =
                        result[0].value

                    val outputInfo =
                        session.outputInfo[
                            session.outputNames.first()
                        ]?.info as? TensorInfo

                    val outputShape =
                        outputInfo?.shape

                    Log.i(
                        TAG,
                        "Output shape=" +
                            outputShape?.contentToString() +
                            " type=" +
                            outputValue::class.java.name
                    )

                    return outputToBitmap(
                        output = outputValue,
                        outputShape = outputShape,
                        outputWidth = frame.width,
                        outputHeight = frame.height,
                        fallbackWidth = modelWidth,
                        fallbackHeight = modelHeight
                    )
                }
            }

        } finally {

            if (
                ownsResizedBitmap &&
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

    private fun bitmapToNchwFloatArray(
        bitmap: Bitmap
    ): FloatArray {

        val width =
            bitmap.width

        val height =
            bitmap.height

        val pixelCount =
            width * height

        val pixels =
            IntArray(pixelCount)

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

            val pixel =
                pixels[i]

            val red =
                ((pixel shr 16) and 0xFF) / 127.5f - 1f

            val green =
                ((pixel shr 8) and 0xFF) / 127.5f - 1f

            val blue =
                (pixel and 0xFF) / 127.5f - 1f

            data[i] =
                red

            data[pixelCount + i] =
                green

            data[pixelCount * 2 + i] =
                blue
        }

        return data
    }

    private fun bitmapToNhwcFloatArray(
        bitmap: Bitmap
    ): FloatArray {

        val width =
            bitmap.width

        val height =
            bitmap.height

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

        var index =
            0

        for (pixel in pixels) {

            data[index++] =
                ((pixel shr 16) and 0xFF) / 127.5f - 1f

            data[index++] =
                ((pixel shr 8) and 0xFF) / 127.5f - 1f

            data[index++] =
                (pixel and 0xFF) / 127.5f - 1f
        }

        return data
    }

    private fun outputToBitmap(
        output: Any,
        outputShape: LongArray?,
        outputWidth: Int,
        outputHeight: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Bitmap {

        val data =
            extractFloatArray(output)

        require(data.isNotEmpty()) {
            "ONNX output is empty."
        }

        val shape =
            outputShape?.takeIf {
                it.size == 4
            }

        val layout =
            if (shape != null) {
                detectLayout(
                    shape,
                    "output"
                )
            } else {
                inferOutputLayout(
                    data.size,
                    fallbackWidth,
                    fallbackHeight
                )
            }

        val modelWidth: Int
        val modelHeight: Int
        val pixelCount: Int

        if (shape != null) {

            when (layout) {

                Layout.NCHW -> {

                    modelHeight =
                        resolveDimension(
                            shape[2],
                            fallbackHeight
                        )

                    modelWidth =
                        resolveDimension(
                            shape[3],
                            fallbackWidth
                        )
                }

                Layout.NHWC -> {

                    modelHeight =
                        resolveDimension(
                            shape[1],
                            fallbackHeight
                        )

                    modelWidth =
                        resolveDimension(
                            shape[2],
                            fallbackWidth
                        )
                }
            }

            pixelCount =
                modelWidth * modelHeight

        } else {

            modelWidth =
                fallbackWidth

            modelHeight =
                fallbackHeight

            pixelCount =
                modelWidth * modelHeight
        }

        require(
            data.size >= pixelCount * 3
        ) {
            "ONNX output contains insufficient pixel data. " +
                "elements=${data.size}, " +
                "required=${pixelCount * 3}, " +
                "shape=${shape?.contentToString()}"
        }

        val pixels =
            IntArray(pixelCount)

        when (layout) {

            Layout.NCHW -> {

                for (i in 0 until pixelCount) {

                    val red =
                        outputValueToByte(
                            data[i]
                        )

                    val green =
                        outputValueToByte(
                            data[pixelCount + i]
                        )

                    val blue =
                        outputValueToByte(
                            data[pixelCount * 2 + i]
                        )

                    pixels[i] =
                        argb(
                            red,
                            green,
                            blue
                        )
                }
            }

            Layout.NHWC -> {

                for (i in 0 until pixelCount) {

                    val base =
                        i * 3

                    val red =
                        outputValueToByte(
                            data[base]
                        )

                    val green =
                        outputValueToByte(
                            data[base + 1]
                        )

                    val blue =
                        outputValueToByte(
                            data[base + 2]
                        )

                    pixels[i] =
                        argb(
                            red,
                            green,
                            blue
                        )
                }
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

        val finalBitmap =
            Bitmap.createScaledBitmap(
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

    private fun inferOutputLayout(
        dataSize: Int,
        width: Int,
        height: Int
    ): Layout {

        val expected =
            width * height * 3

        require(dataSize >= expected) {
            "Unable to infer ONNX output layout. " +
                "elements=$dataSize expectedAtLeast=$expected"
        }

        /*
         * AnimeGANv3's normal ONNX export is NCHW.
         *
         * If the output TensorInfo is unavailable,
         * use NCHW rather than guessing from pixel values.
         */
        return Layout.NCHW
    }

    private fun detectLayout(
        shape: LongArray,
        tensorName: String
    ): Layout {

        require(shape.size == 4) {
            "Unsupported $tensorName tensor rank: " +
                "${shape.size}. " +
                "Expected rank 4."
        }

        val channelFirst =
            shape[1] == 3L

        val channelLast =
            shape[3] == 3L

        return when {

            channelFirst && !channelLast ->
                Layout.NCHW

            channelLast && !channelFirst ->
                Layout.NHWC

            channelFirst && channelLast ->
                throw IllegalStateException(
                    "Ambiguous $tensorName tensor shape: " +
                        shape.contentToString()
                )

            else ->
                throw IllegalStateException(
                    "Unsupported $tensorName tensor shape: " +
                        shape.contentToString() +
                        ". Expected NCHW or NHWC RGB tensor."
                )
        }
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

                fun visit(
                    current: Any?
                ) {

                    when (current) {

                        is FloatArray ->
                            current.forEach {
                                list.add(it)
                            }

                        is Array<*> ->
                            current.forEach {
                                visit(it)
                            }

                        is Number ->
                            list.add(
                                current.toFloat()
                            )

                        null -> Unit

                        else ->
                            throw IllegalStateException(
                                "Unsupported ONNX output element: " +
                                    current::class.java.name
                            )
                    }
                }

                visit(value)

                list.toFloatArray()
            }

            else ->
                throw IllegalStateException(
                    "Unsupported ONNX output type: " +
                        value::class.java.name
                )
        }
    }

    private fun outputValueToByte(
        value: Float
    ): Int {

        /*
         * AnimeGANv3 outputs RGB values in [-1, 1].
         */
        val normalized =
            ((value + 1f) * 0.5f)
                .coerceIn(0f, 1f)

        return (
            normalized * 255f
        ).roundToInt()
            .coerceIn(0, 255)
    }

    private fun argb(
        red: Int,
        green: Int,
        blue: Int
    ): Int {

        return (255 shl 24) or
            (red shl 16) or
            (green shl 8) or
            blue
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

    private enum class Layout {
        NCHW,
        NHWC
    }

    override fun close() {
        session.close()
    }
}
