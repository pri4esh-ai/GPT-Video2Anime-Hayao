package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.media.Image
import com.gptvideo2anime.util.NativeYuvUtils

object YuvConverter {

    /**
     * Converts a YUV_420_888 Image directly into a new ARGB Bitmap 
     * using zero-copy direct buffers sent to the C++ native engine.
     */
    fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        NativeYuvUtils.convertYuvToBitmapNative(
            yBuffer = yBuffer,
            uBuffer = uBuffer,
            vBuffer = vBuffer,
            width = width,
            height = height,
            yRowStride = planes[0].rowStride,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
            bitmap = bitmap
        )
        return bitmap
    }
}
