package com.gptvideo2anime.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class OnnxAnimeEngine(
    modelPath: String
) : AutoCloseable {

    companion object {

        private const val DEFAULT_MODEL_SIZE = 512

        private const val INPUT_CHANNELS = 3

        private const val ORT_THREADS = 4
    }

    private val environment =
        OrtEnvironment.getEnvironment()

    private val session: OrtSession

    private val inputName: String

    private val inputShape: LongArray

    private val inputLayout: Layout

    private val modelWidth: Int

    private val modelHeight: Int

    private val inputFloatBuffer: FloatBuffer

    private val inputPixels: IntArray

    private val inputDataSize: Int

    init {

        val sessionOptions =
            OrtSession.SessionOptions()

        sessionOptions.setIntraOpNumThreads(
            ORT_THREADS
        )

        sessionOptions.setInterOpNumThreads(
            1
        )

        sessionOptions.setOptimizationLevel(
            OrtSession.SessionOptions.OptLevel.ALL_OPT
        )

        session =
            environment.createSession(
                modelPath,
                sessionOptions
            )

        inputName =
            session.inputNames.firstOrNull()
                ?: throw IllegalStateException(
                    "ONNX model has no input."
                )

        val inputTensorInfo =
            session.inputInfo[inputName]?.info as? TensorInfo
                ?: throw IllegalStateException(
                    "Unable to inspect ONNX input."
                )

        inputShape =
            inputTensorInfo.shape

        require(inputShape.size == 4) {
            "Unsupported ONNX input rank: " +
                "${inputShape.size}. Expected rank 4."
        }

        inputLayout =
            detectLayout(
                inputShape,
                "input"
            )

        modelHeight =
            when (inputLayout) {

                Layout.NCHW ->
                    resolveDimension(
                        inputShape[2],
                        DEFAULT_MODEL_SIZE
                    )

                Layout.NHWC ->
                    resolveDimension(
                        inputShape[1],
                        DEFAULT_MODEL_SIZE
                    )
            }

        modelWidth =
            when (inputLayout) {

                Layout.NCHW ->
                    resolveDimension(
                        inputShape[3],
                        DEFAULT_MODEL_SIZE
                    )

                Layout.NHWC ->
                    resolveDimension(
                        inputShape[2],
                        DEFAULT_MODEL_SIZE
                    )
            }

        require(
            modelWidth > 0 &&
                modelHeight > 0
        ) {
            "Invalid ONNX input dimensions: " +
                "${modelWidth}x$modelHeight"
        }

        inputDataSize =
            modelWidth *
                modelHeight *
                INPUT_CHANNELS

        inputFloatBuffer =
            FloatBuffer.allocate(
                inputDataSize
            )

        inputPixels =
            IntArray(
                modelWidth * modelHeight
            )
    }

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

            val tensor =
                createInputTensor(
                    resized
                )

            tensor.use { inputTensor ->

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

    private fun createInputTensor(
        bitmap: Bitmap
    ): OnnxTensor {

        bitmap.getPixels(
            inputPixels,
            0,
            modelWidth,
            0,
            0,
            modelWidth,
            modelHeight
        )

        inputFloatBuffer.clear()

        if (inputLayout == Layout.NCHW) {

            val pixelCount =
                modelWidth * modelHeight

            /*
             * R plane
             */
            for (i in 0 until pixelCount) {

                val pixel =
                    inputPixels[i]

                inputFloatBuffer.put(
                    i,
                    ((pixel shr 16) and 0xFF) /
                        127.5f - 1f
                )
            }

            /*
             * G plane
             */
            for (i in 0 until pixelCount) {

                val pixel =
                    inputPixels[i]

                inputFloatBuffer.put(
                    pixelCount + i,
                    ((pixel shr 8) and 0xFF) /
                        127.5f - 1f
                )
            }

            /*
             * B plane
             */
            for (i in 0 until pixelCount) {

                val pixel =
                    inputPixels[i]

                inputFloatBuffer.put(
                    pixelCount * 2 + i,
                    (pixel and 0xFF) /
                        127.5f - 1f
                )
            }

        } else {

            var index = 0

            for (pixel in inputPixels) {

                inputFloatBuffer.put(
                    index++,
                    ((pixel shr 16) and 0xFF) /
                        127.5f - 1f
                )

                inputFloatBuffer.put(
                    index++,
                    ((pixel shr 8) and 0xFF) /
                        127.5f - 1f
                )

                inputFloatBuffer.put(
                    index++,
                    (pixel and 0xFF) /
                        127.5f - 1f
                )
            }
        }

        inputFloatBuffer.position(0)

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

        return OnnxTensor.createTensor(
            environment,
            inputFloatBuffer,
            shape
        )
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

        } else {

            modelWidth =
                fallbackWidth

            modelHeight =
                fallbackHeight
        }

        val pixelCount =
            modelWidth * modelHeight

        require(
            data.size >= pixelCount * 3
        ) {
            "ONNX output contains insufficient pixel data. " +
                "elements=${data.size}, " +
                "required=${pixelCount * 3}"
        }

        val pixels =
            IntArray(pixelCount)

        if (layout == Layout.NCHW) {

            val greenOffset =
                pixelCount

            val blueOffset =
                pixelCount * 2

            for (i in 0 until pixelCount) {

                val red =
                    outputValueToByte(
                        data[i]
                    )

                val green =
                    outputValueToByte(
                        data[greenOffset + i]
                    )

                val blue =
                    outputValueToByte(
                        data[blueOffset + i]
                    )

                pixels[i] =
                    argb(
                        red,
                        green,
                        blue
                    )
            }

        } else {

            var dataIndex = 0

            for (i in 0 until pixelCount) {

                val red =
                    outputValueToByte(
                        data[dataIndex++]
                    )

                val green =
                    outputValueToByte(
                        data[dataIndex++]
                    )

                val blue =
                    outputValueToByte(
                        data[dataIndex++]
                    )

                pixels[i] =
                    argb(
                        red,
                        green,
                        blue
                    )
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
            width *
                height *
                3

        require(dataSize >= expected) {
            "Unable to infer ONNX output layout. " +
                "elements=$dataSize expectedAtLeast=$expected"
        }

        return Layout.NCHW
    }

    private fun detectLayout(
        shape: LongArray,
        tensorName: String
    ): Layout {

        require(shape.size == 4) {
            "Unsupported $tensorName tensor rank: " +
                "${shape.size}. Expected rank 4."
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

        val normalized =
            ((value + 1f) * 0.5f)
                .coerceIn(
                    0f,
                    1f
                )

        return (
            normalized * 255f
        ).roundToInt()
            .coerceIn(
                0,
                255
            )
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
