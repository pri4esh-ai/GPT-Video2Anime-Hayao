// FILE: app/src/main/java/com/gptvideo2anime/pipeline/YuvConverter.kt

package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object YuvConverter {

    fun imageToBitmap(
        image: Image
    ): Bitmap {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Unsupported image format: ${image.format}"
        }

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val output = IntArray(width * height)

        for (y in 0 until height) {
            val yRowOffset = y * yRowStride

            for (x in 0 until width) {
                val yIndex =
                    yRowOffset + x * yPixelStride

                val chromaX = x shr 1
                val chromaY = y shr 1

                val uIndex =
                    chromaY * uRowStride +
                        chromaX * uPixelStride

                val vIndex =
                    chromaY * vRowStride +
                        chromaX * vPixelStride

                val yValue =
                    yBuffer.get(
                        yIndex
                    ).toInt() and 0xff

                val uValue =
                    uBuffer.get(
                        uIndex
                    ).toInt() and 0xff

                val vValue =
                    vBuffer.get(
                        vIndex
                    ).toInt() and 0xff

                val r =
                    (
                        yValue +
                            1.402f *
                            (vValue - 128)
                    )
                        .roundToInt()
                        .coerceIn(0, 255)

                val g =
                    (
                        yValue -
                            0.344136f *
                            (uValue - 128) -
                            0.714136f *
                            (vValue - 128)
                    )
                        .roundToInt()
                        .coerceIn(0, 255)

                val b =
                    (
                        yValue +
                            1.772f *
                            (uValue - 128)
                    )
                        .roundToInt()
                        .coerceIn(0, 255)

                output[
                    y * width + x
                ] =
                    (0xff shl 24) or
                        (r shl 16) or
                        (g shl 8) or
                        b
            }
        }

        return Bitmap.createBitmap(
            output,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    fun bitmapToYuv420(
        bitmap: Bitmap
    ): ByteArray {
        val width =
            bitmap.width and -2

        val height =
            bitmap.height and -2

        require(width > 0 && height > 0) {
            "Bitmap must be at least 2x2 pixels."
        }

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

        val ySize =
            width * height

        val uvSize =
            ySize / 2

        val output =
            ByteArray(
                ySize + uvSize
            )

        var yIndex = 0
        var uvIndex = ySize

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel =
                    pixels[
                        y * width + x
                    ]

                val r =
                    (pixel shr 16) and 0xff

                val g =
                    (pixel shr 8) and 0xff

                val b =
                    pixel and 0xff

                val yValue =
                    (
                        0.299f * r +
                            0.587f * g +
                            0.114f * b
                    )
                        .roundToInt()
                        .coerceIn(0, 255)

                output[yIndex++] =
                    yValue.toByte()

                if (
                    y % 2 == 0 &&
                    x % 2 == 0
                ) {
                    val uValue =
                        (
                            -0.169f * r -
                                0.331f * g +
                                0.500f * b +
                                128f
                        )
                            .roundToInt()
                            .coerceIn(0, 255)

                    val vValue =
                        (
                            0.500f * r -
                                0.419f * g -
                                0.081f * b +
                                128f
                        )
                            .roundToInt()
                            .coerceIn(0, 255)

                    output[uvIndex++] =
                        uValue.toByte()

                    output[uvIndex++] =
                        vValue.toByte()
                }
            }
        }

        return output
    }

    fun resize(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Bitmap {
        if (
            bitmap.width == width &&
            bitmap.height == height
        ) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )
    }

    fun bitmapToJpeg(
        bitmap: Bitmap,
        quality: Int = 90
    ): ByteArray {
        val stream =
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            quality.coerceIn(1, 100),
            stream
        )

        return stream.toByteArray()
    }

    fun jpegToBitmap(
        data: ByteArray
    ): Bitmap {
        return BitmapFactory.decodeByteArray(
            data,
            0,
            data.size
        ) ?: throw IllegalStateException(
            "Unable to decode JPEG bitmap."
        )
    }
}
