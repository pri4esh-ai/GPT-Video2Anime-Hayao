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

    /**
     * Converts Android YUV_420_888 Image to ARGB Bitmap.
     *
     * This conversion handles:
     * - row stride
     * - pixel stride
     * - planar/semi-planar chroma layouts
     *
     * Output is an ordinary ARGB_8888 Bitmap.
     */
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

        /*
         * NV21 layout:
         *
         * YYYYYYYYY
         * VUVUVUVUV
         *
         * Y plane first.
         * Then VU interleaved.
         *
         * YuvImage understands NV21.
         */
        val ySize = width * height
        val uvSize = uvWidth * uvHeight

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
     * Converts Bitmap to YUV420 semi-planar NV12.
     *
     * IMPORTANT:
     *
     * MediaCodec COLOR_FormatYUV420SemiPlanar
     * generally expects:
     *
     * YYYYYYYYY
     * UVUVUVUV
     *
     * NOT:
     *
     * YYYYYYYYY
     * VUVUVUVU
     *
     * The old implementation emitted VU and could
     * therefore produce blue/purple skin.
     */
    fun bitmapToYuv420(
        bitmap: Bitmap
    ): ByteArray {

        val width =
            (bitmap.width and -2)
                .coerceAtLeast(2)

        val height =
            (bitmap.height and -2)
                .coerceAtLeast(2)

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
         * UV interleaved plane
         */
        val output =
            ByteArray(
                frameSize +
                    chromaSize * 2
            )

        var yIndex = 0

        /*
         * First pass:
         * generate full-resolution Y.
         */
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
                 * BT.601 limited-range Y.
                 *
                 * Y = 16 + 0.257R +
                 *          0.504G +
                 *          0.098B
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
                    y
                        .coerceIn(
                            16,
                            235
                        )
                        .toByte()
            }
        }

        /*
         * Second pass:
         * generate 2x2 chroma blocks.
         *
         * NV12 requires:
         *
         * U
         * V
         *
         * in that exact order.
         */
        var uvIndex =
            frameSize

        for (j in 0 until height step 2) {

            for (i in 0 until width step 2) {

                var sumU = 0
                var sumV = 0

                var samples = 0

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
                         * BT.601 limited-range chroma.
                         */
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

                        samples++
                    }
                }

                val u =
                    (sumU / samples)
                        .coerceIn(
                            16,
                            240
                        )

                val v =
                    (sumV / samples)
                        .coerceIn(
                            16,
                            240
                        )

                /*
                 * CRITICAL:
                 *
                 * NV12 = U V
                 *
                 * NOT V U.
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

        val width =
            (
                bitmap.width *
                    scale
                )
                .toInt()
                .coerceAtLeast(2)
                and -2

        val height =
            (
                bitmap.height *
                    scale
                )
                .toInt()
                .coerceAtLeast(2)
                and -2

        return Bitmap.createScaledBitmap(
            bitmap,
            width.coerceAtLeast(2),
            height.coerceAtLeast(2),
            true
        )
    }

    fun bitmapToJpeg(
        bitmap: Bitmap,
        quality: Int
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
