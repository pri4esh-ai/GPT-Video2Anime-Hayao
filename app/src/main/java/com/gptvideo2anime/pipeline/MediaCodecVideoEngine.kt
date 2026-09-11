package com.gptvideo2anime.pipeline

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
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
    private var surfaceTexture: SurfaceTexture? = null

    private var videoTrackIndex = -1
    private var frameWidth = 0
    private var frameHeight = 0
    private var durationUs = 0L

    /**
     * Prepares the hardware decoder for frame extraction from a local video file.
     */
    fun setup(videoFile: File, outputTextureId: Int) {
        require(videoFile.exists()) { "Input file does not exist: ${videoFile.absolutePath}" }

        // 1. Initialize Extractor
        extractor = MediaExtractor().apply {
            setDataSource(videoFile.absolutePath)
        }

        // 2. Locate Primary Video Track
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

        // 3. Find Suitable Hardware Decoder (Strictly Non-Encoder)
        val decoderInfo = selectDecoder(mime)
            ?: throw RuntimeException("No hardware decoder found supporting MIME: $mime")

        Log.i(TAG, "Selected decoder: ${decoderInfo.name} for MIME: $mime (${frameWidth}x${frameHeight})")

        // 4. Create Output Surface tied to GL Texture ID
        surfaceTexture = SurfaceTexture(outputTextureId).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
        }
        outputSurface = Surface(surfaceTexture)

        // 5. Initialize and Configure Decoder
        decoder = MediaCodec.createByCodecName(decoderInfo.name).apply {
            configure(format, outputSurface, null, 0)
            start()
        }
    }

    /**
     * Decodes next single frame from the media stream onto the configured Surface.
     * @return true if a frame was successfully rendered to the surface, false if end-of-stream (EOS).
     */
    fun decodeNextFrame(): Boolean {
        val codec = decoder ?: throw IllegalStateException("Engine not initialized. Call setup() first.")
        val extract = extractor ?: throw IllegalStateException("Engine not initialized.")

        val bufferInfo = MediaCodec.BufferInfo()
        var inputEof = false
        var outputEof = false

        while (!outputEof) {
            // Feed input data into hardware decoder
            if (!inputEof) {
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex >= 0) {
                    val inputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LPOINTER) {
                        codec.getInputBuffer(inputBufferIndex)
                    } else {
                        @Suppress("DEPRECATION")
                        codec.inputBuffers[inputBufferIndex]
                    }

                    val sampleSize = inputBuffer?.let { extract.readSampleData(it, 0) } ?: -1

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEof = true
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

            // Dequeue decoded output frame
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Decoder needs more input or time
                    if (inputEof) break 
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Decoder output format changed: $newFormat")
                }
                outputBufferIndex >= 0 -> {
                    val isEof = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    // Release buffer to surface (render = true)
                    val renderFrame = bufferInfo.size > 0
                    codec.releaseOutputBuffer(outputBufferIndex, renderFrame)

                    if (renderFrame) {
                        return true // Successfully pushed frame to Surface
                    }

                    if (isEof) {
                        outputEof = true
                    }
                }
            }
        }

        return false
    }

    /**
     * Iterates system codecs to ensure we strictly select a hardware DECODER (not an encoder).
     */
    private fun selectDecoder(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            // Must strictly check !info.isEncoder (fixes CI string matching check)
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

        surfaceTexture?.release()
        surfaceTexture = null
    }
}
