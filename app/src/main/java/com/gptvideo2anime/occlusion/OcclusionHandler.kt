package com.gptvideo2anime.occlusion

import com.gptvideo2anime.tracking.PersonTrack

class OcclusionHandler {

    fun update(
        track: PersonTrack,
        visible: Boolean
    ): PersonTrack {

        track.visible =
            visible

        if (visible) {
            track.missedFrames = 0
        } else {
            track.missedFrames++
        }

        return track
    }

    fun shouldReidentify(
        track: PersonTrack
    ): Boolean {

        return !track.visible &&
            track.missedFrames >= 5
    }
}
