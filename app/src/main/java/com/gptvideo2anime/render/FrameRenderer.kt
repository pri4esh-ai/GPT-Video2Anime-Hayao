package com.gptvideo2anime.render

import android.graphics.Bitmap

class FrameRenderer {

    fun prepare(
        frame: Bitmap
    ): Bitmap {

        return frame
    }

    fun release(
        frame: Bitmap
    ) {

        if (!frame.isRecycled) {
            frame.recycle()
        }
    }
}
