package com.gptvideo2anime.render

import android.graphics.Bitmap

class FrameRenderer {

    fun prepare(frame: Bitmap): Bitmap {
        // Placeholder: In a full implementation, this would upload the Bitmap 
        // to an OpenGL texture or apply GPU-based filters.
        return frame
    }

    fun release(frame: Bitmap) {
        if (!frame.isRecycled) {
            frame.recycle()
        }
    }
}
