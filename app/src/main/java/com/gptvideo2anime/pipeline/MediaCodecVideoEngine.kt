package com.gptvideo2anime.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance hardware video frame extractor powered by Android MediaCodec & MediaExtractor.
 */
class MediaCodecVideoEngine : AutoCloseable {

    companion object {
        private const val TAG = "MediaCodecVideoEngine"
        private const val TIMEOUT_USEC = 10000L // 10ms timeout for frame polling
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var frameWidth = 0
    private var frameHeight = 0
    private var durationUs = 0L

    private var isInputEof = false
    private var isOutputEof = false

    fun getWidth(): Int = frameWidth
    fun getHeight(): Int = frameHeight
    fun getDurationUs(): Long = durationUs

    fun setup(videoFile: File, surface: Surface) {
        initInternal(videoFile, surface)
    }

    private fun initInternal(videoFile: File, surface: Surface) {
        require(videoFile.exists()) { "Input file does not exist: ${videoFile.absolutePath}" }

        isInputEof = false
        isOutputEof = false

        extractor = MediaExtractor().apply {
            setDataSource(videoFile.absolutePath)
        }

        videoTrackIndex = selectVideoTrack(extractor!!)
        if (videoTrackIndex < 0) {
            throw IllegalStateException("No valid video track found in ${videoFile.name}")
        }
        extractor!!.selectTrack(videoTrackIndex)

        val format = extractor!!.getTrackFormat(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("Missing MIME type for track $videoTrackIndex")

        frameWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        frameHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val decoderInfo = selectDecoder(mime)
            ?: throw RuntimeException("No hardware decoder found supporting MIME: $mime")

        Log.i(TAG, "Selected decoder: ${decoderInfo.name} for MIME: $mime (${frameWidth}x${frameHeight})")

        decoder = MediaCodec.createByCodecName(decoderInfo.name).apply {
            configure(format, surface, null, 0)
            start()
        }
    }

    fun decodeNextFrame(): Boolean {
        val codec = decoder ?: throw IllegalStateException("Engine not initialized. Call setup() first.")
        val extract = extractor ?: throw IllegalStateException("Engine not initialized.")

        if (isOutputEof) return false

        val bufferInfo = MediaCodec.BufferInfo()

        while (!isOutputEof) {
            if (!isInputEof) {
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex >= 0) {
                    // FIXED: Removed invalid Build.VERSION_CODES.LPOINTER check. 
                    // minSdk is 26, so getInputBuffer is always available and safe.
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)

                    val sampleSize = inputBuffer?.let { extract.readSampleData(it, 0) } ?: -1

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        isInputEof = true
                    } else {
                        val presentationTimeUs = extract.sampleTime
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            presentationTimeUs,
                            0
                        )
                        extract.advance()
                    }
                }
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (isInputEof) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Decoder output format changed: ${codec.outputFormat}")
                }
                outputBufferIndex >= 0 -> {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEof = true
                    }

                    val renderFrame = bufferInfo.size > 0
                    codec.releaseOutputBuffer(outputBufferIndex, renderFrame)

                    if (renderFrame) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun selectDecoder(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) {
                val types = info.supportedTypes
                for (type in types) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        return info
                    }
                }
            }
        }
        return null
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        return -1
    }

    override fun close() {
        try {
            decoder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        } finally {
            decoder = null
        }

        try {
            extractor?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaExtractor", e)
        } finally {
            extractor = null
        }

        outputSurface?.release()
        outputSurface = null
    }
}
