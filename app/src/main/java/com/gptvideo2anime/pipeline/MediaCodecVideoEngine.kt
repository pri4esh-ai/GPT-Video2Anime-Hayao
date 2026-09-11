package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import java.io.File
import java.nio.ByteBuffer

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationUs: Long
)

class MediaCodecVideoEngine(
    private val context: Context
) {

    companion object {
        private const val TAG = "MediaCodecVideoEngine"
        private const val DEFAULT_FRAME_RATE = 30
    }

    fun inspect(uri: Uri): VideoMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectVideoTrack(extractor)
            require(trackIndex >= 0) { "No valid video track found in input file." }

            val format = extractor.getTrackFormat(trackIndex)
            val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 1280
            val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 720
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else DEFAULT_FRAME_RATE
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            return VideoMetadata(width, height, frameRate, durationUs)
        } finally {
            extractor.release()
        }
    }

    fun processVideo(
        inputUri: Uri,
        outputFile: File,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        onProgress: (current: Int, total: Int, stage: String) -> Unit
    ) {
        val metadata = inspect(inputUri)
        val totalFramesEstimate = if (metadata.durationUs > 0) {
            ((metadata.durationUs / 1_000_000.0) * metadata.frameRate).toInt()
        } else 0

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            val videoTrack = selectVideoTrack(extractor)
            extractor.selectTrack(videoTrack)
            val inputFormat = extractor.getTrackFormat(videoTrack)

            // Setup Encoder
            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                metadata.width,
                metadata.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, metadata.width * metadata.height * 4)
                setInteger(MediaFormat.KEY_FRAME_RATE, metadata.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var trackIndex = -1

            // Setup Decoder
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var processedFrames = 0
            var isInputEOS = false
            var isOutputEOS = false

            onProgress(0, totalFramesEstimate, "Processing frames with Hayao model...")

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inBufferIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inBufferIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inBufferIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inBufferIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isOutputEOS = true
                    }

                    val image = decoder.getOutputImage(outBufferIndex)
                    if (image != null) {
                        val originalBitmap = ImageUtils.imageToBitmap(image)
                        
                        // 1. Run AnimeGAN inference directly
                        val animeBitmap = animeEngine.infer(originalBitmap)

                        // 2. Linear CPU Alpha Blend based on user selected strength
                        val finalBitmap = blendFrames(originalBitmap, animeBitmap, strength)

                        // Clean up temporary bitmaps
                        if (animeBitmap !== finalBitmap) animeBitmap.recycle()
                        if (originalBitmap !== finalBitmap) originalBitmap.recycle()

                        // 3. Encode processed bitmap back to output stream
                        val yuvData = ImageUtils.bitmapToYuv420(finalBitmap, metadata.width, metadata.height)
                        finalBitmap.recycle()

                        encodeFrame(
                            encoder = encoder,
                            muxer = muxer,
                            yuvData = yuvData,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            isEos = isOutputEOS,
                            onTrackReady = { index ->
                                trackIndex = index
                                muxer.start()
                                muxerStarted = true
                            },
                            isMuxerStarted = { muxerStarted },
                            muxerTrackIndex = trackIndex
                        )

                        processedFrames++
                        onProgress(processedFrames, totalFramesEstimate, "Processing frames ($processedFrames)")
                    }

                    decoder.releaseOutputBuffer(outBufferIndex, false)
                }
            }
        } finally {
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { encoder?.stop(); encoder?.release() }
            runCatching { if (muxer != null) { muxer.stop(); muxer.release() } }
            runCatching { extractor.release() }
        }
    }

    private fun blendFrames(
        original: Bitmap,
        anime: Bitmap,
        strength: Float
    ): Bitmap {
        if (strength >= 1.0f) return anime

        val width = original.width
        val height = original.height

        val scaledAnime = if (anime.width != width || anime.height != height) {
            Bitmap.createScaledBitmap(anime, width, height, true)
        } else {
            anime
        }

        val size = width * height
        val origPixels = IntArray(size)
        val animePixels = IntArray(size)
        val resultPixels = IntArray(size)

        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        scaledAnime.getPixels(animePixels, 0, width, 0, 0, width, height)

        val alpha = strength.coerceIn(0f, 1f)
        val invAlpha = 1f - alpha

        for (i in 0 until size) {
            val p1 = origPixels[i]
            val p2 = animePixels[i]

            val r = (((p1 shr 16 and 0xFF) * invAlpha) + ((p2 shr 16 and 0xFF) * alpha)).toInt().coerceIn(0, 255)
            val g = (((p1 shr 8 and 0xFF) * invAlpha) + ((p2 shr 8 and 0xFF) * alpha)).toInt().coerceIn(0, 255)
            val b = (((p1 and 0xFF) * invAlpha) + ((p2 and 0xFF) * alpha)).toInt().coerceIn(0, 255)

            resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        if (scaledAnime !== anime && !scaledAnime.isRecycled) {
            scaledAnime.recycle()
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                return i
            }
        }
        return -1
    }

    private fun encodeFrame(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        yuvData: ByteArray,
        presentationTimeUs: Long,
        isEos: Boolean,
        onTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        muxerTrackIndex: Int
    ) {
        val inputIndex = encoder.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            val buffer = encoder.getInputBuffer(inputIndex)!!
            buffer.clear()
            buffer.put(yuvData)
            val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(inputIndex, 0, yuvData.size, presentationTimeUs, flags)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)

        while (outputIndex >= 0) {
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val track = muxer.addTrack(newFormat)
                onTrackReady(track)
            } else if (outputIndex >= 0 && isMuxerStarted()) {
                val encodedData = encoder.getOutputBuffer(outputIndex)!!
                if (bufferInfo.size > 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            }
            outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
        }
    }
}
