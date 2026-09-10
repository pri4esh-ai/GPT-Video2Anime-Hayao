package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.min

object YuvConverter {

    fun imageToBitmap(
        image: Image
    ): Bitmap {

        require(
            image.format == ImageFormat.YUV_420_888
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
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2

        val ySize = width * height
        val uvSize = uvWidth * uvHeight

        /*
         * YUV_420_888 -> NV21
         *
         * NV21:
         * YYYYYYYY
         * VUVUVUVU
         */
        val nv21 = ByteArray(
            ySize + uvSize * 2
        )

        var yIndex = 0

        for (row in 0 until height) {

            val rowStart =
                row * yRowStride

            for (col in 0 until width) {

                val sourceIndex =
                    rowStart + col

                nv21[yIndex++] =
                    yBuffer.get(sourceIndex)
            }
        }

        var uvIndex = ySize

        for (row in 0 until uvHeight) {

            val uRowStart =
                row * uRowStride

            val vRowStart =
                row * vRowStride

            for (col in 0 until uvWidth) {

                val uIndex =
                    uRowStart +
                        col * uPixelStride

                val vIndex =
                    vRowStart +
                        col * vPixelStride

                nv21[uvIndex++] =
                    vBuffer.get(vIndex)

                nv21[uvIndex++] =
                    uBuffer.get(uIndex)
            }
        }

        val yuvImage =
            YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            )

        val output =
            ByteArrayOutputStream(
                min(
                    width * height / 2,
                    1024 * 1024
                )
            )

        yuvImage.compressToJpeg(
            Rect(
                0,
                0,
                width,
                height
            ),
            100,
            output
        )

        val bytes =
            output.toByteArray()

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        )
            ?: throw IllegalStateException(
                "Failed to decode YUV frame."
            )
    }

    /**
     * Bitmap -> NV12.
     *
     * COLOR_FormatYUV420SemiPlanar normally expects:
     *
     * YYYYYYYY
     * UVUVUVUV
     *
     * The previous implementation produced VU,
     * which can cause strongly blue/purple skin.
     */
    fun bitmapToYuv420(
        bitmap: Bitmap
    ): ByteArray {

        var width =
            bitmap.width

        var height =
            bitmap.height

        /*
         * H.264 YUV420 requires even dimensions.
         */
        width =
            if (width % 2 == 0) {
                width
            } else {
                width - 1
            }

        height =
            if (height % 2 == 0) {
                height
            } else {
                height - 1
            }

        width =
            width.coerceAtLeast(2)

        height =
            height.coerceAtLeast(2)

        val argb =
            IntArray(
                width * height
            )

        bitmap.getPixels(
            argb,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val frameSize =
            width * height

        val chromaWidth =
            width / 2

        val chromaHeight =
            height / 2

        val chromaSize =
            chromaWidth * chromaHeight

        /*
         * NV12:
         *
         * Y plane
         * UV plane
         */
        val output =
            ByteArray(
                frameSize +
                    chromaSize * 2
            )

        /*
         * -------------------------
         * Y PLANE
         * -------------------------
         */
        var yIndex = 0

        for (j in 0 until height) {

            for (i in 0 until width) {

                val color =
                    argb[
                        j * width + i
                    ]

                val r =
                    (color shr 16) and 0xFF

                val g =
                    (color shr 8) and 0xFF

                val b =
                    color and 0xFF

                /*
                 * BT.601 limited range.
                 *
                 * Y = 16 +
                 *     0.257R +
                 *     0.504G +
                 *     0.098B
                 */
                val y =
                    (
                        16 +
                            66 * r +
                            129 * g +
                            25 * b +
                            128
                    ) shr 8

                output[yIndex++] =
                    y.coerceIn(
                        16,
                        235
                    ).toByte()
            }
        }

        /*
         * -------------------------
         * UV PLANE
         * -------------------------
         *
         * 2x2 chroma subsampling.
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
                                .coerceAtMost(
                                    width - 1
                                )

                        val y =
                            (j + dy)
                                .coerceAtMost(
                                    height - 1
                                )

                        val color =
                            argb[
                                y * width + x
                            ]

                        val r =
                            (color shr 16) and 0xFF

                        val g =
                            (color shr 8) and 0xFF

                        val b =
                            color and 0xFF

                        /*
                         * BT.601 limited-range U.
                         */
                        val u =
                            (
                                128 -
                                    38 * r -
                                    74 * g +
                                    112 * b +
                                    128
                            ) shr 8

                        /*
                         * BT.601 limited-range V.
                         */
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
                        .coerceIn(
                            16,
                            240
                        )

                val v =
                    (sumV / count)
                        .coerceIn(
                            16,
                            240
                        )

                /*
                 * IMPORTANT:
                 *
                 * NV12 = U V
                 *
                 * Do NOT change this to V U.
                 */
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

        require(
            maxSize > 0
        ) {
            "maxSize must be greater than zero."
        }

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

        /*
         * Calculate dimensions first.
         */
        var width =
            (
                bitmap.width * scale
            )
                .toInt()
                .coerceAtLeast(2)

        var height =
            (
                bitmap.height * scale
            )
                .toInt()
                .coerceAtLeast(2)

        /*
         * Force even dimensions.
         *
         * Kotlin does not support:
         *
         * value and -2
         *
         * in this context.
         *
         * So use modulo instead.
         */
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
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            quality.coerceIn(
                0,
                100
            ),
            output
        )

        return output.toByteArray()
    }
}
