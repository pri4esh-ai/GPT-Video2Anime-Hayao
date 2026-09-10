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
        private const val DEFAULT_THREADS = 4
    }

    private val environment =
        OrtEnvironment.getEnvironment()

    private val session: OrtSession

    private val inputName: String

    private val inputShape: LongArray

    private val inputLayout: Layout

    private val modelWidth: Int

    private val modelHeight: Int

    private lateinit var pixelBuffer: IntArray

    private lateinit var floatBuffer: FloatArray

    init {

        val options =
            OrtSession.SessionOptions()

        options.setIntraOpNumThreads(
            DEFAULT_THREADS
        )

        options.setInterOpNumThreads(
            1
        )

        options.setOptimizationLevel(
            OrtSession.SessionOptions.OptLevel.ALL_OPT
        )

        session =
            environment.createSession(
                modelPath,
                options
            )

        inputName =
            session.inputNames
                .firstOrNull()
                ?: throw IllegalStateException(
                    "ONNX model has no input."
                )

        val info =
            session.inputInfo[inputName]?.info
                as? TensorInfo
                ?: throw IllegalStateException(
                    "Unable to inspect ONNX input."
                )

        inputShape =
            info.shape.copyOf()

        require(
            inputShape.size == 4
        ) {
            "Expected 4D input tensor, got " +
                inputShape.contentToString()
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
                        512
                    )

                Layout.NHWC ->
                    resolveDimension(
                        inputShape[1],
                        512
                    )
            }

        modelWidth =
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

        require(
            modelWidth > 0 &&
                modelHeight > 0
        ) {
            "Invalid model dimensions."
        }

        pixelBuffer =
            IntArray(
                modelWidth * modelHeight
            )

        floatBuffer =
            FloatArray(
                modelWidth *
                    modelHeight *
                    3
            )
    }

    fun inputNames(): Set<String> =
        session.inputNames

    fun outputNames(): Set<String> =
        session.outputNames

    fun processFrame(
        frame: Bitmap
    ): Bitmap {

        require(
            !frame.isRecycled
        ) {
            "Input bitmap is recycled."
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

            val pixelCount =
                modelWidth * modelHeight

            resized.getPixels(
                pixelBuffer,
                0,
                modelWidth,
                0,
                0,
                modelWidth,
                modelHeight
            )

            /*
             * AnimeGAN models normally expect RGB
             * floating point values in [-1, +1].
             */
            when (inputLayout) {

                Layout.NCHW -> {

                    val plane =
                        pixelCount

                    for (i in 0 until pixelCount) {

                        val pixel =
                            pixelBuffer[i]

                        val red =
                            (pixel shr 16) and 0xFF

                        val green =
                            (pixel shr 8) and 0xFF

                        val blue =
                            pixel and 0xFF

                        floatBuffer[i] =
                            toModelValue(red)

                        floatBuffer[
                            plane + i
                        ] =
                            toModelValue(green)

                        floatBuffer[
                            plane * 2 + i
                        ] =
                            toModelValue(blue)
                    }
                }

                Layout.NHWC -> {

                    var index = 0

                    for (i in 0 until pixelCount) {

                        val pixel =
                            pixelBuffer[i]

                        val red =
                            (pixel shr 16) and 0xFF

                        val green =
                            (pixel shr 8) and 0xFF

                        val blue =
                            pixel and 0xFF

                        floatBuffer[index++] =
                            toModelValue(red)

                        floatBuffer[index++] =
                            toModelValue(green)

                        floatBuffer[index++] =
                            toModelValue(blue)
                    }
                }
            }

            val shape =
                inputShape.copyOf()

            /*
             * Dynamic batch dimensions are commonly -1.
             */
            if (shape[0] <= 0L) {
                shape[0] = 1L
            }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(
                    floatBuffer
                ),
                shape
            ).use { inputTensor ->

                session.run(
                    mapOf(
                        inputName to inputTensor
                    )
                ).use { result ->

                    require(
                        result.size() > 0
                    ) {
                        "AnimeGAN returned no output."
                    }

                    val outputValue =
                        result[0].value

                    val outputInfo =
                        session.outputInfo[
                            session.outputNames.first()
                        ]?.info as? TensorInfo

                    return outputToBitmap(
                        output =
                            outputValue,

                        outputShape =
                            outputInfo?.shape,

                        outputWidth =
                            frame.width,

                        outputHeight =
                            frame.height,

                        fallbackWidth =
                            modelWidth,

                        fallbackHeight =
                            modelHeight
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

    fun infer(
        frame: Bitmap
    ): Bitmap =
        processFrame(frame)

    private fun outputToBitmap(
        output: Any,
        outputShape: LongArray?,
        outputWidth: Int,
        outputHeight: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Bitmap {

        val data =
            extractFloatArray(
                output
            )

        require(
            data.isNotEmpty()
        ) {
            "AnimeGAN returned an empty output."
        }

        val shape =
            outputShape
                ?.takeIf {
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

        val outputModelWidth: Int

        val outputModelHeight: Int

        if (shape != null) {

            when (layout) {

                Layout.NCHW -> {

                    outputModelHeight =
                        resolveDimension(
                            shape[2],
                            fallbackHeight
                        )

                    outputModelWidth =
                        resolveDimension(
                            shape[3],
                            fallbackWidth
                        )
                }

                Layout.NHWC -> {

                    outputModelHeight =
                        resolveDimension(
                            shape[1],
                            fallbackHeight
                        )

                    outputModelWidth =
                        resolveDimension(
                            shape[2],
                            fallbackWidth
                        )
                }
            }

        } else {

            outputModelWidth =
                fallbackWidth

            outputModelHeight =
                fallbackHeight
        }

        val pixelCount =
            outputModelWidth *
                outputModelHeight

        require(
            data.size >=
                pixelCount * 3
        ) {
            "Invalid AnimeGAN output size. " +
                "Expected at least " +
                (pixelCount * 3) +
                " values but received " +
                data.size
        }

        val pixels =
            IntArray(
                pixelCount
            )

        when (layout) {

            Layout.NCHW -> {

                val plane =
                    pixelCount

                for (i in 0 until pixelCount) {

                    val red =
                        modelValueToByte(
                            data[i]
                        )

                    val green =
                        modelValueToByte(
                            data[plane + i]
                        )

                    val blue =
                        modelValueToByte(
                            data[plane * 2 + i]
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
                        modelValueToByte(
                            data[base]
                        )

                    val green =
                        modelValueToByte(
                            data[base + 1]
                        )

                    val blue =
                        modelValueToByte(
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
                outputModelWidth,
                outputModelHeight,
                Bitmap.Config.ARGB_8888
            )

        modelBitmap.setPixels(
            pixels,
            0,
            outputModelWidth,
            0,
            0,
            outputModelWidth,
            outputModelHeight
        )

        if (
            outputModelWidth ==
                outputWidth &&
            outputModelHeight ==
                outputHeight
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

        if (
            !modelBitmap.isRecycled
        ) {
            modelBitmap.recycle()
        }

        return finalBitmap
    }

    /**
     * Converts 8-bit RGB into the range expected
     * by AnimeGAN:
     *
     * [0,255] -> [-1,+1]
     */
    private fun toModelValue(
        value: Int
    ): Float {

        return (
            value / 127.5f
        ) - 1.0f
    }

    /**
     * Converts AnimeGAN output:
     *
     * [-1,+1] -> [0,255]
     *
     * This is deliberately NOT:
     *
     * if(value < 0) ...
     *
     * because positive values such as +0.5
     * must become 0.75 in normalized [0,1]
     * space, not 0.5.
     */
    private fun modelValueToByte(
        value: Float
    ): Int {

        val normalized =
            (
                value + 1.0f
            ) * 0.5f

        return (
            normalized
                .coerceIn(
                    0.0f,
                    1.0f
                ) * 255.0f
            )
                .roundToInt()
                .coerceIn(
                    0,
                    255
                )
    }

    private fun extractFloatArray(
        value: Any
    ): FloatArray {

        return when (value) {

            is FloatArray ->
                value

            is Array<*> -> {

                val values =
                    ArrayList<Float>()

                fun visit(
                    current: Any?
                ) {

                    when (current) {

                        is FloatArray -> {
                            for (item in current) {
                                values.add(item)
                            }
                        }

                        is Array<*> -> {
                            for (item in current) {
                                visit(item)
                            }
                        }

                        is Number -> {
                            values.add(
                                current.toFloat()
                            )
                        }

                        null -> Unit

                        else -> {
                            throw IllegalStateException(
                                "Unsupported ONNX output type: " +
                                    current::class.java.name
                            )
                        }
                    }
                }

                visit(value)

                values.toFloatArray()
            }

            else ->
                throw IllegalStateException(
                    "Unsupported ONNX output type: " +
                        value::class.java.name
                )
        }
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

        require(
            dataSize >= expected
        ) {
            "Unable to infer output layout."
        }

        /*
         * AnimeGAN ONNX exports are normally NCHW.
         */
        return Layout.NCHW
    }

    private fun detectLayout(
        shape: LongArray,
        tensorName: String
    ): Layout {

        require(
            shape.size == 4
        ) {
            "Unsupported $tensorName tensor shape: " +
                shape.contentToString()
        }

        val channelFirst =
            shape[1] == 3L

        val channelLast =
            shape[3] == 3L

        return when {

            channelFirst &&
                !channelLast ->
                Layout.NCHW

            channelLast &&
                !channelFirst ->
                Layout.NHWC

            channelFirst &&
                channelLast ->

                throw IllegalStateException(
                    "Ambiguous $tensorName shape: " +
                        shape.contentToString()
                )

            else ->

                throw IllegalStateException(
                    "Unsupported $tensorName shape: " +
                        shape.contentToString()
                )
        }
    }

    private fun resolveDimension(
        dimension: Long,
        fallback: Int
    ): Int {

        return if (
            dimension > 0L
        ) {
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

        return (
            (255 shl 24) or
                (red shl 16) or
                (green shl 8) or
                blue
            )
    }

    private enum class Layout {
        NCHW,
        NHWC
    }

    override fun close() {
        session.close()
    }
}
