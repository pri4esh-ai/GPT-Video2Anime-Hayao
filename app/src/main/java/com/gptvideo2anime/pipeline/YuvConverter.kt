package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.media.Image
import com.gptvideo2anime.util.NativeYuvUtils

object YuvConverter {

    /**
     * Converts a YUV_420_888 Image directly into an existing ARGB Bitmap 
     * using zero-copy direct buffers sent to the C++ native engine.
     */
    fun imageToBitmap(image: Image, targetBitmap: Bitmap): Boolean {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        NativeYuvUtils.convertYuvToBitmapNative(
            yBuffer = yBuffer,
            uBuffer = uBuffer,
            vBuffer = vBuffer,
            width = image.width,
            height = image.height,
            yRowStride = planes[0].rowStride,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
            bitmap = targetBitmap
        )
        return true
    }
}
