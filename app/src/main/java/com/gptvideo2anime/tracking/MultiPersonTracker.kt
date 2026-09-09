package com.gptvideo2anime.tracking

import android.graphics.RectF

class MultiPersonTracker {

    private var nextId = 1L

    private val tracks =
        LinkedHashMap<Long, PersonTrack>()

    fun update(
        detections: List<RectF>
    ): List<PersonTrack> {

        val result =
            ArrayList<PersonTrack>()

        for (box in detections) {

            val track =
                PersonTrack(
                    id = nextId++,
                    bounds = RectF(box),
                    confidence = 1.0f
                )

            tracks[track.id] =
                track

            result += track
        }

        return result
    }

    fun activeTracks():
        List<PersonTrack> {

        return tracks.values.toList()
    }

    fun clear() {
        tracks.clear()
    }
}
