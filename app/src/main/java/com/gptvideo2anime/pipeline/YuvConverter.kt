package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.media.Image
import com.gptvideo2anime.util.NativeYuvUtils

object YuvConverter {

    private var cachedNv12Bytes: ByteArray? = null

    /**
     * Converts a YUV_420_888 Image directly into an existing ARGB Bitmap 
     * using ARM NEON native acceleration.
     */
    fun imageToBitmap(image: Image, targetBitmap: Bitmap): Boolean {
        val width = image.width
        val height = image.height
        val nv12Bytes = extractNv12Fast(image, width, height)
        
        return NativeYuvUtils.convertNv12ToBitmap(nv12Bytes, width, height, targetBitmap)
    }

    private fun extractNv12Fast(image: Image, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val totalSize = ySize + (ySize / 2)
        
        val nv12 = cachedNv12Bytes?.takeIf { it.size >= totalSize } 
            ?: ByteArray(totalSize).also { cachedNv12Bytes = it }

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        // 1. Extract Y Plane
        yBuffer.rewind()
        if (yRowStride == width) {
            yBuffer.get(nv12, 0, ySize)
        } else {
            var dstOffset = 0
            for (i in 0 until height) {
                yBuffer.position(i * yRowStride)
                yBuffer.get(nv12, dstOffset, width)
                dstOffset += width
            }
        }

        // 2. Interleave UV Planes into NV12 format
        val uvHeight = height / 2
        val uvWidth = width / 2
        var uvDestIndex = ySize

        val uBytes = ByteArray(uBuffer.remaining())
        val vBytes = ByteArray(vBuffer.remaining())
        uBuffer.rewind(); uBuffer.get(uBytes)
        vBuffer.rewind(); vBuffer.get(vBytes)

        for (i in 0 until uvHeight) {
            for (j in 0 until uvWidth) {
                val uIndex = i * uvRowStride + j * uvPixelStride
                val vIndex = i * uvRowStride + j * uvPixelStride
                nv12[uvDestIndex++] = uBytes[uIndex]
                nv12[uvDestIndex++] = vBytes[vIndex]
            }
        }

        return nv12
    }
}
