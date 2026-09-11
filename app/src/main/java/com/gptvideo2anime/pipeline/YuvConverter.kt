package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

object YuvConverter {

    init {
        // Load the native library directly here
        System.loadLibrary("gptvideo2anime")
    }

    // Declare the external function directly in this file
    private external fun convertYuvToBitmapNative(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        bitmap: Bitmap
    )

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

        convertYuvToBitmapNative(
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
