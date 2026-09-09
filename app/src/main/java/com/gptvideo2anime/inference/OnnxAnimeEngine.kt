// FILE: app/src/main/java/com/gptvideo2anime/inference/OnnxAnimeEngine.kt

package com.gptvideo2anime.inference

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OnnxAnimeEngine(
    private val modelPath: String
) : AutoCloseable {

    private val environment =
        OrtEnvironment.getEnvironment()

    private val session: OrtSession

    private val inputName: String

    private val inputWidth = 256
    private val inputHeight = 256

    init {
        val options =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(
                    max(
                        1,
                        Runtime.getRuntime().availableProcessors() / 2
                    )
                )

                setInterOpNumThreads(1)
            }

        session =
            environment.createSession(
                modelPath,
                options
            )

        inputName =
            session.inputNames.firstOrNull()
                ?: throw IllegalStateException(
                    "AnimeGAN model has no input tensor."
                )
    }

    fun infer(
        bitmap: Bitmap
    ): Bitmap {
        val source =
            if (
                bitmap.width != inputWidth ||
                bitmap.height != inputHeight
            ) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    inputWidth,
                    inputHeight,
                    true
                )
            } else {
                bitmap
            }

        val inputData =
            createInputTensorData(
                source
            )

        val inputTensor =
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                longArrayOf(
                    1,
                    3,
                    inputHeight.toLong(),
                    inputWidth.toLong()
                )
            )

        try {
            val inputs =
                mapOf(
                    inputName to inputTensor
                )

            val results =
                session.run(inputs)

            try {
                if (results.size == 0) {
                    throw IllegalStateException(
                        "AnimeGAN returned no output."
                    )
                }

                val output =
                    results[0].value

                return outputToBitmap(
                    output = output,
                    width = inputWidth,
                    height = inputHeight
                )
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()

            if (source !== bitmap) {
                source.recycle()
            }
        }
    }

    private fun createInputTensorData(
        bitmap: Bitmap
    ): FloatArray {
        val pixels =
            IntArray(
                inputWidth * inputHeight
            )

        bitmap.getPixels(
            pixels,
            0,
            inputWidth,
            0,
            0,
            inputWidth,
            inputHeight
        )

        val planeSize =
            inputWidth * inputHeight

        val data =
            FloatArray(
                planeSize * 3
            )

        for (index in pixels.indices) {
            val pixel =
                pixels[index]

            val red =
                (pixel shr 16) and 0xff

            val green =
                (pixel shr 8) and 0xff

            val blue =
                pixel and 0xff

            data[index] =
                red / 127.5f - 1.0f

            data[
                planeSize + index
            ] =
                green / 127.5f - 1.0f

            data[
                planeSize * 2 + index
            ] =
                blue / 127.5f - 1.0f
        }

        return data
    }

    private fun outputToBitmap(
        output: Any?,
        width: Int,
        height: Int
    ): Bitmap {
        val values =
            flattenOutput(output)

        val expected =
            width * height * 3

        if (values.size < expected) {
            throw IllegalStateException(
                "AnimeGAN output is too small: " +
                    "${values.size} values, expected $expected."
            )
        }

        val pixels =
            IntArray(
                width * height
            )

        val planeSize =
            width * height

        for (index in pixels.indices) {
            val red =
                toByte(
                    values[index]
                )

            val green =
                toByte(
                    values[
                        planeSize + index
                    ]
                )

            val blue =
                toByte(
                    values[
                        planeSize * 2 + index
                    ]
                )

            pixels[index] =
                (0xff shl 24) or
                    (red shl 16) or
                    (green shl 8) or
                    blue
        }

        return Bitmap.createBitmap(
            pixels,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun flattenOutput(
        value: Any?
    ): FloatArray {
        return when (value) {
            is FloatArray -> {
                value
            }

            is Array<*> -> {
                val output =
                    ArrayList<Float>()

                flattenArray(
                    value,
                    output
                )

                output.toFloatArray()
            }

            else -> {
                throw IllegalStateException(
                    "Unsupported AnimeGAN output type: " +
                        "${value?.javaClass?.name}"
                )
            }
        }
    }

    private fun flattenArray(
        value: Any?,
        output: MutableList<Float>
    ) {
        when (value) {
            is FloatArray -> {
                for (item in value) {
                    output.add(item)
                }
            }

            is Array<*> -> {
                for (item in value) {
                    flattenArray(
                        item,
                        output
                    )
                }
            }

            else -> {
                throw IllegalStateException(
                    "Unsupported nested AnimeGAN output type: " +
                        "${value?.javaClass?.name}"
                )
            }
        }
    }

    private fun toByte(
        value: Float
    ): Int {
        val normalized =
            if (
                value >= -1.1f &&
                value <= 1.1f
            ) {
                (value + 1f) * 127.5f
            } else {
                value
            }

        return min(
            255,
            max(
                0,
                normalized.roundToInt()
            )
        )
    }

    override fun close() {
        session.close()
    }
}
