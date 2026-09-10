package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min

object YuvConverter {

    /**
     * Direct YUV_420_888 -> ARGB_8888 conversion.
     *
     * This deliberately avoids:
     *
     * YUV -> JPEG -> Bitmap
     *
     * because that is extremely slow for video processing.
     */
    fun imageToBitmap(
        image: Image
    ): Bitmap {

        require(
            image.format == android.graphics.ImageFormat.YUV_420_888
        ) {
            "Unsupported image format: ${image.format}"
        }

        val width = image.width
        val height = image.height

        val bitmap =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val pixels =
            IntArray(width * height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        for (y in 0 until height) {

            val yRow =
                y * yRowStride

            val uvRow =
                (y shr 1)

            val uRowStart =
                uvRow * uRowStride

            val vRowStart =
                uvRow * vRowStride

            for (x in 0 until width) {

                val yIndex =
                    yRow + x

                val uvX =
                    x shr 1

                val uIndex =
                    uRowStart +
                        uvX * uPixelStride

                val vIndex =
                    vRowStart +
                        uvX * vPixelStride

                val yValue =
                    yBuffer
                        .get(yIndex)
                        .toInt() and 0xFF

                val uValue =
                    uBuffer
                        .get(uIndex)
                        .toInt() and 0xFF

                val vValue =
                    vBuffer
                        .get(vIndex)
                        .toInt() and 0xFF

                /*
                 * BT.601 YUV -> RGB.
                 *
                 * This is the standard conversion used
                 * for ordinary camera/video YUV.
                 */
                val c =
                    yValue - 16

                val d =
                    uValue - 128

                val e =
                    vValue - 128

                val red =
                    (
                        298 * c +
                            409 * e +
                            128
                        ) shr 8

                val green =
                    (
                        298 * c -
                            100 * d -
                            208 * e +
                            128
                        ) shr 8

                val blue =
                    (
                        298 * c +
                            516 * d +
                            128
                        ) shr 8

                pixels[
                    y * width + x
                ] =
                    Color.rgb(
                        red.coerceIn(0, 255),
                        green.coerceIn(0, 255),
                        blue.coerceIn(0, 255)
                    )
            }
        }

        bitmap.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return bitmap
    }

    /**
     * Bitmap -> YUV420.
     *
     * This method produces NV12:
     *
     * YYYYYYYY
     * UVUVUVUV
     *
     * It is retained for compatibility with the
     * existing MediaCodec pipeline.
     */
    fun bitmapToYuv420(
        bitmap: Bitmap
    ): ByteArray {

        var width =
            bitmap.width

        var height =
            bitmap.height

        if (width % 2 != 0) {
            width--
        }

        if (height % 2 != 0) {
            height--
        }

        width =
            width.coerceAtLeast(2)

        height =
            height.coerceAtLeast(2)

        val pixels =
            IntArray(
                width * height
            )

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val frameSize =
            width * height

        val output =
            ByteArray(
                frameSize +
                    frameSize / 2
            )

        /*
         * Y plane.
         */
        var yIndex = 0

        for (j in 0 until height) {

            for (i in 0 until width) {

                val color =
                    pixels[
                        j * width + i
                    ]

                val r =
                    (color shr 16) and 0xFF

                val g =
                    (color shr 8) and 0xFF

                val b =
                    color and 0xFF

                val y =
                    (
                        16 +
                            66 * r +
                            129 * g +
                            25 * b +
                            128
                        ) shr 8

                output[yIndex++] =
                    y
                        .coerceIn(16, 235)
                        .toByte()
            }
        }

        /*
         * UV plane.
         *
         * NV12 = U,V
         */
        var uvIndex =
            frameSize

        for (j in 0 until height step 2) {

            for (i in 0 until width step 2) {

                var sumU = 0
                var sumV = 0
                var count = 0

                for (dy in 0..1) {

                    for (dx in 0..1) {

                        val x =
                            (i + dx)
                                .coerceAtMost(width - 1)

                        val y =
                            (j + dy)
                                .coerceAtMost(height - 1)

                        val color =
                            pixels[
                                y * width + x
                            ]

                        val r =
                            (color shr 16) and 0xFF

                        val g =
                            (color shr 8) and 0xFF

                        val b =
                            color and 0xFF

                        val u =
                            (
                                128 -
                                    38 * r -
                                    74 * g +
                                    112 * b +
                                    128
                                ) shr 8

                        val v =
                            (
                                128 +
                                    112 * r -
                                    94 * g -
                                    18 * b +
                                    128
                                ) shr 8

                        sumU += u
                        sumV += v
                        count++
                    }
                }

                val u =
                    (sumU / count)
                        .coerceIn(16, 240)

                val v =
                    (sumV / count)
                        .coerceIn(16, 240)

                output[uvIndex++] =
                    u.toByte()

                output[uvIndex++] =
                    v.toByte()
            }
        }

        return output
    }

    fun resizeBitmap(
        bitmap: Bitmap,
        maxSize: Int
    ): Bitmap {

        require(maxSize > 0)

        val scale =
            min(
                maxSize.toFloat() /
                    bitmap.width,

                maxSize.toFloat() /
                    bitmap.height
            )

        if (scale >= 1f) {
            return bitmap
        }

        var width =
            (
                bitmap.width * scale
            ).toInt()

        var height =
            (
                bitmap.height * scale
            ).toInt()

        if (width % 2 != 0) {
            width--
        }

        if (height % 2 != 0) {
            height--
        }

        width =
            width.coerceAtLeast(2)

        height =
            height.coerceAtLeast(2)

        return Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )
    }

    fun bitmapToJpeg(
        bitmap: Bitmap,
        quality: Int = 95
    ): ByteArray {

        val output =
            java.io.ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            quality.coerceIn(0, 100),
            output
        )

        return output.toByteArray()
    }
}
