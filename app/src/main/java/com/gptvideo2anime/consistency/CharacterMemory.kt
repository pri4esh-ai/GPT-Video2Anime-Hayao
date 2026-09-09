package com.gptvideo2anime.consistency

class CharacterMemory {

    data class CharacterState(
        val trackId: Long,
        var lastSeenFrame: Long = 0L,
        var appearanceConfidence: Float = 0f
    )

    private val states =
        HashMap<Long, CharacterState>()

    fun getOrCreate(
        trackId: Long
    ): CharacterState {

        return states.getOrPut(trackId) {
            CharacterState(
                trackId = trackId
            )
        }
    }

    fun update(
        trackId: Long,
        frameIndex: Long,
        confidence: Float
    ) {

        val state =
            getOrCreate(trackId)

        state.lastSeenFrame =
            frameIndex

        state.appearanceConfidence =
            confidence
    }

    fun clear() {
        states.clear()
    }
}
