package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.min

object YuvConverter {

    fun imageToBitmap(image: Image): Bitmap {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Unsupported image format: ${image.format}"
        }

        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

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

        var pixelIdx = 0
        for (y in 0 until height) {
            val yRowStart = y * yRowStride
            val uvRowStart = (y shr 1) * uRowStride
            val vRowStart = (y shr 1) * vRowStride

            for (x in 0 until width) {
                val yVal = yBuffer.get(yRowStart + x).toInt() and 0xFF
                val uvX = x shr 1
                val uVal = uBuffer.get(uvRowStart + uvX * uPixelStride).toInt() and 0xFF
                val vVal = vBuffer.get(vRowStart + uvX * vPixelStride).toInt() and 0xFF

                val c = yVal - 16
                val d = uVal - 128
                val e = vVal - 128

                val r = (298 * c + 409 * e + 128) shr 8
                val g = (298 * c - 100 * d - 208 * e + 128) shr 8
                val b = (298 * c + 516 * d + 128) shr 8

                pixels[pixelIdx++] = Color.rgb(
                    r.coerceIn(0, 255),
                    g.coerceIn(0, 255),
                    b.coerceIn(0, 255)
                )
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Converts Bitmap to NV12 formatted YUV420 byte array.
     */
    fun bitmapToNv12(bitmap: Bitmap): ByteArray {
        val width = (bitmap.width / 2) * 2
        val height = (bitmap.height / 2) * 2
        val frameSize = width * height
        val output = ByteArray(frameSize + frameSize / 2)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = pixels[j * width + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                output[yIndex++] = y.coerceIn(16, 235).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    output[uvIndex++] = u.coerceIn(16, 240).toByte()
                    output[uvIndex++] = v.coerceIn(16, 240).toByte()
                }
            }
        }
        return output
    }
}
