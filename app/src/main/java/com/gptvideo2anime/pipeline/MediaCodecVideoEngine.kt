package com.gptvideo2anime.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.gptvideo2anime.inference.OnnxAnimeEngine
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

data class VideoInfo(
    val mimeType: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val rotationDegrees: Int
)

class MediaCodecVideoEngine(
    private val context: Context
) {

    fun inspect(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = findVideoTrack(extractor)

            if (trackIndex < 0) {
                throw IllegalStateException("No video track found.")
            }

            val format = extractor.getTrackFormat(trackIndex)

            val mime =
                format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Video MIME type is unavailable.")

            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)

            val frameRate =
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    30
                }.coerceAtLeast(1)

            val rotation =
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                }

            return VideoInfo(
                mimeType = mime,
                durationUs = durationUs,
                width = width,
                height = height,
                frameRate = frameRate,
                rotationDegrees = rotation
            )
        } finally {
            extractor.release()
        }
    }

    fun processVideo(
        inputUri: Uri,
        outputFile: File,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        onProgress: (Int, Int, String) -> Unit
    ) {
        require(strength in 0f..1f)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var muxerStarted = false
        var encoderTrack = -1

        try {
            extractor.setDataSource(context, inputUri, null)

            val videoTrack = findVideoTrack(extractor)
            if (videoTrack < 0) throw IllegalStateException("No video track found.")

            extractor.selectTrack(videoTrack)

            val videoFormat = extractor.getTrackFormat(videoTrack)
            val mime =
                videoFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Video MIME type is unavailable.")

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val frameRate =
                if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                    videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                else 30

            val durationUs =
                if (videoFormat.containsKey(MediaFormat.KEY_DURATION))
                    videoFormat.getLong(MediaFormat.KEY_DURATION)
                else 0L

            val totalFrames = estimateFrameCount(durationUs, frameRate)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            val encoderInfo = findByteBufferEncoder("video/avc")
            val colorFormat = chooseColorFormat(encoderInfo)

            val encoderFormat =
                MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, calculateBitRate(width, height, frameRate))
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }

            encoder = MediaCodec.createByCodecName(encoderInfo.name)
            encoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            encoder.start()

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val activeMuxer =
                muxer ?: throw IllegalStateException("Failed to create MediaMuxer.")

            val rotation =
                if (videoFormat.containsKey(MediaFormat.KEY_ROTATION))
                    videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                else 0

            if (rotation == 90 || rotation == 180 || rotation == 270) {
                activeMuxer.setOrientationHint(rotation)
            }

            processFrames(
                extractor,
                decoder,
                encoder,
                activeMuxer,
                width,
                height,
                totalFrames,
                animeEngine,
                strength,
                onProgress,
                { encoderTrack = it },
                { muxerStarted },
                {
                    if (!muxerStarted) {
                        activeMuxer.start()
                        muxerStarted = true
                    }
                },
                { encoderTrack }
            )

            if (muxerStarted) activeMuxer.stop()
            activeMuxer.release()
            muxer = null

            val videoOnly =
                File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_video.mp4")

            if (outputFile.renameTo(videoOnly)) {
                muxAudio(inputUri, videoOnly, outputFile)
                videoOnly.delete()
            }

        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (!muxerStarted) muxer?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun processFrames(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        width: Int,
        height: Int,
        totalFrames: Int,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        onProgress: (Int, Int, String) -> Unit,
        onEncoderTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        encoderTrackProvider: () -> Int
    ) {
        // Keep your existing implementation exactly as you pasted.
    }

    private fun createProcessedFrame(
        original: android.graphics.Bitmap,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        width: Int,
        height: Int
    ): android.graphics.Bitmap {
        val source =
            if (original.width != width || original.height != height)
                YuvConverter.resize(original, width, height)
            else original

        val anime = animeEngine.infer(source)
        val result = blendBitmaps(source, anime, strength)

        if (anime !== source && !anime.isRecycled) anime.recycle()
        if (source !== original && !source.isRecycled) source.recycle()

        return result
    }

    private fun blendBitmaps(
        original: android.graphics.Bitmap,
        anime: android.graphics.Bitmap,
        strength: Float
    ): android.graphics.Bitmap {
        val width = minOf(original.width, anime.width)
        val height = minOf(original.height, anime.height)

        val result = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888
        )

        val op = IntArray(width * height)
        val ap = IntArray(width * height)
        val rp = IntArray(width * height)

        original.getPixels(op, 0, width, 0, 0, width, height)
        anime.getPixels(ap, 0, width, 0, 0, width, height)

        val inv = 1f - strength

        for (i in rp.indices) {
            val o = op[i]
            val a = ap[i]

            val r = (((o shr 16 and 255) * inv) + ((a shr 16 and 255) * strength)).roundToInt()
            val g = (((o shr 8 and 255) * inv) + ((a shr 8 and 255) * strength)).roundToInt()
            val b = (((o and 255) * inv) + ((a and 255) * strength)).roundToInt()

            rp[i] =
                (255 shl 24) or
                    (r.coerceIn(0,255) shl 16) or
                    (g.coerceIn(0,255) shl 8) or
                    b.coerceIn(0,255)
        }

        result.setPixels(rp,0,width,0,0,width,height)
        return result
    }

    private fun queueEncoderFrame(
        encoder: MediaCodec,
        data: ByteArray,
        presentationTimeUs: Long
    ) {
        while (true) {
            val index = encoder.dequeueInputBuffer(10_000)

            if (index >= 0) {
                val buffer =
                    encoder.getInputBuffer(index)
                        ?: throw IllegalStateException("Encoder input buffer unavailable.")

                buffer.clear()
                buffer.put(data)

                encoder.queueInputBuffer(index,0,data.size,presentationTimeUs,0)
                return
            }

            drainEncoderWithoutWriting(encoder)
        }
    }

    private fun drainEncoderWithoutWriting(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info,0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> if (index >= 0) encoder.releaseOutputBuffer(index,false) else return
            }
        }
    }

    private fun signalEncoderEndOfStream(encoder: MediaCodec) {
        while (true) {
            val index = encoder.dequeueInputBuffer(10_000)

            if (index >= 0) {
                encoder.queueInputBuffer(
                    index,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }

            drainEncoderWithoutWriting(encoder)
        }
    }
        private fun findByteBufferEncoder(
        mime: String
    ): MediaCodecInfo {

        val codecList =
            android.media.MediaCodecList(
                android.media.MediaCodecList.REGULAR_CODECS
            )

        return codecList.codecInfos.firstOrNull { info ->

            if (!info.isEncoder) {
                return@firstOrNull false
            }

            if (
                !info.supportedTypes.any {
                    it.equals(
                        mime,
                        ignoreCase = true
                    )
                }
            ) {
                return@firstOrNull false
            }

            val capabilities =
                info.getCapabilitiesForType(mime)

            capabilities.colorFormats.any { format ->
                format ==
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }

        } ?: throw IllegalStateException(
            "No H.264 encoder with YUV420 semi-planar input is available on this device."
        )
    }

    private fun chooseColorFormat(codecInfo: MediaCodecInfo): Int {
        val caps = codecInfo.getCapabilitiesForType("video/avc")

        if (
            caps.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
        ) {
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }

        throw IllegalStateException("YUV420SemiPlanar is not supported.")
    }

    private fun muxAudio(
        inputUri: Uri,
        videoFile: File,
        outputFile: File
    ) {
        val extractor = MediaExtractor()
        val videoExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context,inputUri,null)
            videoExtractor.setDataSource(videoFile.absolutePath)

            val videoTrack = findVideoTrack(videoExtractor)
            if (videoTrack < 0) throw IllegalStateException("Processed video track is missing.")

            val audioTrack = findAudioTrack(extractor)

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val outVideo =
                muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))

            val outAudio =
                if (audioTrack >= 0)
                    muxer.addTrack(extractor.getTrackFormat(audioTrack))
                else -1

            muxer.start()

            copySamples(videoExtractor,videoTrack,outVideo,muxer)

            if (audioTrack >= 0) {
                copySamples(extractor,audioTrack,outAudio,muxer)
            }

            muxer.stop()

        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            videoExtractor.release()
            extractor.release()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        trackIndex: Int,
        outputTrack: Int,
        muxer: MediaMuxer
    ) {
        extractor.selectTrack(trackIndex)

        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
        val info = MediaCodec.BufferInfo()

        while (true) {
            buffer.clear()

            val size = extractor.readSampleData(buffer,0)
            if (size < 0) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(outputTrack,buffer,info)
            extractor.advance()
        }

        extractor.unselectTrack(trackIndex)
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime =
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)

            if (mime?.startsWith("video/") == true) return i
        }

        return -1
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime =
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)

            if (mime?.startsWith("audio/") == true) return i
        }

        return -1
    }

    private fun estimateFrameCount(
        durationUs: Long,
        frameRate: Int
    ): Int {
        if (durationUs <= 0L) return 1

        return (
            durationUs.toDouble() /
            1_000_000.0 *
            frameRate
        ).roundToInt().coerceAtLeast(1)
    }

    private fun calculateBitRate(
        width: Int,
        height: Int,
        frameRate: Int
    ): Int {
        val pixelsPerSecond =
            width.toLong() * height * frameRate

        return (pixelsPerSecond * 0.12)
            .roundToInt()
            .coerceIn(2_000_000,20_000_000)
    }
}
