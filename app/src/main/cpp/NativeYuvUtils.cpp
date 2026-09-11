package com.gptvideo2anime.util

import android.graphics.Bitmap
import java.nio.ByteBuffer

object NativeYuvUtils {
    init {
        System.loadLibrary("gptvideo2anime")
    }

    external fun convertYuvToBitmapNative(
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
}
