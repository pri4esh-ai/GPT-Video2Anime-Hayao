// FILE: app/src/main/java/com/gptvideo2anime/pipeline/YuvConverter.kt

package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object YuvConverter {

    /*
     * Optimized YUV_420_888 -> Bitmap conversion.
     *
     * Main optimizations:
     * - Integer YUV -> RGB math instead of Float math.
     * - Direct ByteBuffer absolute reads.
     * - Chroma values are calculated once per 2x2 block.
     * - Avoids repeated Kotlin floating-point operations.
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

        /*
         * Process two rows together.
         *
         * U/V are shared by a 2x2 Y block in YUV 4:2:0,
         * so we only read chroma once for four pixels.
         */
        var y = 0

        while (y < height) {

            val yRow0 = y * yRowStride

            val chromaY = y shr 1

            val uRow =
                chromaY * uRowStride

            val vRow =
                chromaY * vRowStride

            val row0Output =
                y * width

            val hasSecondRow =
                y + 1 < height

            val yRow1 =
                if (hasSecondRow) {
                    (y + 1) * yRowStride
                } else {
                    0
                }

            val row1Output =
                row0Output + width

            var x = 0

            while (x < width) {

                val chromaX =
                    x shr 1

                val uIndex =
                    uRow +
                        chromaX * uPixelStride

                val vIndex =
                    vRow +
                        chromaX * vPixelStride

                val u =
                    (uBuffer.get(uIndex).toInt() and 0xff) - 128

                val v =
                    (vBuffer.get(vIndex).toInt() and 0xff) - 128

                /*
                 * Fixed-point approximation:
                 *
                 * R = Y + 1.402V
                 * G = Y - 0.344U - 0.714V
                 * B = Y + 1.772U
                 */
                val rOffset =
                    (359 * v) shr 8

                val gOffset =
                    (-88 * u - 183 * v) shr 8

                val bOffset =
                    (454 * u) shr 8

                val x1 =
                    x + 1

                val yIndex0 =
                    yRow0 +
                        x * yPixelStride

                val yValue0 =
                    yBuffer.get(yIndex0).toInt() and 0xff

                output[row0Output + x] =
                    rgbToArgb(
                        yValue0 + rOffset,
                        yValue0 + gOffset,
                        yValue0 + bOffset
                    )

                if (x1 < width) {

                    val yIndex1 =
                        yRow0 +
                            x1 * yPixelStride

                    val yValue1 =
                        yBuffer.get(yIndex1).toInt() and 0xff

                    output[row0Output + x1] =
                        rgbToArgb(
                            yValue1 + rOffset,
                            yValue1 + gOffset,
                            yValue1 + bOffset
                        )
                }

                if (hasSecondRow) {

                    val yValue2 =
                        yBuffer.get(
                            yRow1 +
                                x * yPixelStride
                        )
                            .toInt() and 0xff

                    output[row1Output + x] =
                        rgbToArgb(
                            yValue2 + rOffset,
                            yValue2 + gOffset,
                            yValue2 + bOffset
                        )

                    if (x1 < width) {

                        val yValue3 =
                            yBuffer.get(
                                yRow1 +
                                    x1 * yPixelStride
                            )
                                .toInt() and 0xff

                        output[row1Output + x1] =
                            rgbToArgb(
                                yValue3 + rOffset,
                                yValue3 + gOffset,
                                yValue3 + bOffset
                            )
                    }
                }

                x += 2
            }

            y += 2
        }

        return Bitmap.createBitmap(
            output,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    /*
     * Bitmap -> YUV420 semi-planar.
     *
     * Layout:
     *
     * YYYYYYYY...
     * UVUVUVUV...
     *
     * This matches the COLOR_FormatYUV420SemiPlanar path
     * used by the current MediaCodec encoder.
     */
    fun bitmapToYuv420(
        bitmap: Bitmap
    ): ByteArray {

        val width =
            bitmap.width and -2

        val height =
            bitmap.height and -2

        require(
            width > 0 &&
                height > 0
        ) {
            "Bitmap must be at least 2x2 pixels."
        }

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

        val ySize =
            pixelCount

        val uvSize =
            pixelCount / 2

        val output =
            ByteArray(
                ySize + uvSize
            )

        var yIndex = 0
        var uvIndex = ySize

        var y = 0

        while (y < height) {

            val row =
                y * width

            var x = 0

            while (x < width) {

                val pixel =
                    pixels[row + x]

                val r =
                    (pixel shr 16) and 0xff

                val g =
                    (pixel shr 8) and 0xff

                val b =
                    pixel and 0xff

                /*
                 * Integer approximation:
                 *
                 * Y = 0.299R + 0.587G + 0.114B
                 *
                 * Avoid floating point in the hottest loop.
                 */
                val yValue =
                    (
                        77 * r +
                            150 * g +
                            29 * b +
                            128
                        ) shr 8

                output[yIndex++] =
                    yValue
                        .coerceIn(0, 255)
                        .toByte()

                /*
                 * U/V are generated once per 2x2 block.
                 *
                 * We use the top-left pixel here to preserve
                 * the behavior of the original converter while
                 * reducing the amount of work substantially.
                 */
                if (
                    (y and 1) == 0 &&
                    (x and 1) == 0
                ) {

                    val uValue =
                        (
                            -43 * r -
                                85 * g +
                                128 * b +
                                (128 shl 8)
                            ) shr 8

                    val vValue =
                        (
                            128 * r -
                                107 * g -
                                21 * b +
                                (128 shl 8)
                            ) shr 8

                    output[uvIndex++] =
                        uValue
                            .coerceIn(0, 255)
                            .toByte()

                    output[uvIndex++] =
                        vValue
                            .coerceIn(0, 255)
                            .toByte()
                }

                /*
                 * Process second pixel of the pair.
                 */
                if (x + 1 < width) {

                    val pixel2 =
                        pixels[row + x + 1]

                    val r2 =
                        (pixel2 shr 16) and 0xff

                    val g2 =
                        (pixel2 shr 8) and 0xff

                    val b2 =
                        pixel2 and 0xff

                    val yValue2 =
                        (
                            77 * r2 +
                                150 * g2 +
                                29 * b2 +
                                128
                            ) shr 8

                    output[yIndex++] =
                        yValue2
                            .coerceIn(0, 255)
                            .toByte()
                }

                x += 2
            }

            y += 1
        }

        return output
    }

    private fun rgbToArgb(
        r: Int,
        g: Int,
        b: Int
    ): Int {

        val red =
            r.coerceIn(0, 255)

        val green =
            g.coerceIn(0, 255)

        val blue =
            b.coerceIn(0, 255)

        return (
            (0xff shl 24) or
                (red shl 16) or
                (green shl 8) or
                blue
            )
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
