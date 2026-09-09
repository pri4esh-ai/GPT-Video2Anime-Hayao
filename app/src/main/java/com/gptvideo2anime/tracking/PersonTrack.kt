package com.gptvideo2anime.tracking

import android.graphics.RectF

data class PersonTrack(
    val id: Long,
    var bounds: RectF,
    var confidence: Float,
    var visible: Boolean = true,
    var missedFrames: Int = 0
)
