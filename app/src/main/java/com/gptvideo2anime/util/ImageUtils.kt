package com.gptvideo2anime.util

import android.graphics.Bitmap
import android.media.Image
import com.gptvideo2anime.pipeline.YuvConverter

object ImageUtils {

    fun imageToBitmap(image: Image): Bitmap {
        return YuvConverter.imageToBitmap(image)
    }

    fun bitmapToYuv420(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): ByteArray {

        val scaled =
            if (bitmap.width == width && bitmap.height == height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }

        return try {
            YuvConverter.bitmapToNv12(scaled)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }
}
