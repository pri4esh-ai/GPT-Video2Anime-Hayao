package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

data class VideoInfo(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val frameRate: Int,
    val rotation: Int
)

class MediaCodecVideoEngine(
    private val context: Context
) {

    companion object {
        private const val TAG = "MediaCodecVideoEngine"
        private const val TIMEOUT_US = 10_000L
        private const val OUTPUT_MIME = "video/avc"
        private const val DEFAULT_FPS = 30
        private const val MIN_BITRATE = 2_000_000
        private const val MAX_BITRATE = 20_000_000
        private const val SAMPLE_BUFFER_SIZE = 8 * 1024 * 1024

        // Optimized inference size
        private const val MODEL_SIZE = 512
    }

    fun inspect(uri: Uri): VideoInfo {

        val extractor = MediaExtractor()

        try {

            setExtractorDataSource(extractor, uri)

            val track = findVideoTrack(extractor)

            if (track < 0)
                throw IllegalStateException("No video track found.")

            val format = extractor.getTrackFormat(track)

            return VideoInfo(
                mimeType = format.getString(MediaFormat.KEY_MIME)!!,
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                durationUs =
                    if (format.containsKey(MediaFormat.KEY_DURATION))
                        format.getLong(MediaFormat.KEY_DURATION)
                    else 0L,
                frameRate =
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                        format.getInteger(MediaFormat.KEY_FRAME_RATE)
                    else DEFAULT_FPS,
                rotation =
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        format.containsKey(MediaFormat.KEY_ROTATION)
                    )
                        format.getInteger(MediaFormat.KEY_ROTATION)
                    else 0
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

        val extractor = MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        val tempVideo =
            File(
                outputFile.parentFile,
                "${outputFile.nameWithoutExtension}_video_only.mp4"
            )

        try {

            outputFile.parentFile?.mkdirs()

            setExtractorDataSource(extractor, inputUri)

            val videoTrack = findVideoTrack(extractor)

            extractor.selectTrack(videoTrack)

            val format = extractor.getTrackFormat(videoTrack)

            val mime =
                format.getString(MediaFormat.KEY_MIME)!!

            val width =
                makeEvenDimension(
                    format.getInteger(MediaFormat.KEY_WIDTH)
                )

            val height =
                makeEvenDimension(
                    format.getInteger(MediaFormat.KEY_HEIGHT)
                )

            val fps =
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                else DEFAULT_FPS

            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION)
                else 0L

            val rotation =
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    format.containsKey(MediaFormat.KEY_ROTATION)
                )
                    format.getInteger(MediaFormat.KEY_ROTATION)
                else 0

            decoder =
                MediaCodec.createDecoderByType(mime)

            decoder.configure(format, null, null, 0)

            decoder.start()

            val encoderInfo =
                findH264Encoder()

            val colorFormat =
                chooseColorFormat(
                    encoderInfo,
                    OUTPUT_MIME
                )!!

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    OUTPUT_MIME,
                    width,
                    height
                ).apply {

                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, calculateBitRate(width, height, fps))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }

            encoder =
                MediaCodec.createByCodecName(encoderInfo.name)

            encoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            encoder.start()

            muxer =
                MediaMuxer(
                    tempVideo.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            if (rotation != 0)
                muxer.setOrientationHint(normalizeRotation(rotation))

            processFrames(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                sourceWidth = width,
                sourceHeight = height,
                outputWidth = width,
                outputHeight = height,
                fps = fps,
                durationUs = durationUs,
                animeEngine = animeEngine,
                strength = strength,
                onProgress = onProgress
            )

            muxer.stop()
            muxer.release()
            muxer = null

            muxAudio(inputUri, tempVideo, outputFile)

            tempVideo.delete()

        } finally {

            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
        private fun processFrames(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        fps: Int,
        durationUs: Long,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        onProgress: (Int, Int, String) -> Unit
    ) {

        val encoderSink = EncoderSink(encoder, muxer)
        val info = MediaCodec.BufferInfo()

        var extractorDone = false
        var decoderDone = false
        var encoderEnded = false

        var processed = 0
        val total = estimateFrameCount(durationUs, fps)

        while (!encoderSink.isEndOfStream()) {

            if (!extractorDone) {

                val input = decoder.dequeueInputBuffer(TIMEOUT_US)

                if (input >= 0) {

                    val buffer = decoder.getInputBuffer(input)!!
                    buffer.clear()

                    val size = extractor.readSampleData(buffer, 0)

                    if (size < 0) {

                        decoder.queueInputBuffer(
                            input,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )

                        extractorDone = true

                    } else {

                        decoder.queueInputBuffer(
                            input,
                            0,
                            size,
                            extractor.sampleTime,
                            extractor.sampleFlags
                        )

                        extractor.advance()
                    }
                }
            }

            while (true) {

                val output = decoder.dequeueOutputBuffer(info, 0)

                if (output == MediaCodec.INFO_TRY_AGAIN_LATER)
                    break

                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
                    continue

                if (output < 0)
                    continue

                val eos =
                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                if (info.size > 0) {

                    val image = decoder.getOutputImage(output)!!

                    val bitmap = try {
                        YuvConverter.imageToBitmap(image)
                    } finally {
                        image.close()
                    }

                    try {

                        // Downscale once for AnimeGAN
                        val inputBitmap =
                            if (bitmap.width == MODEL_SIZE &&
                                bitmap.height == MODEL_SIZE
                            ) bitmap
                            else Bitmap.createScaledBitmap(
                                bitmap,
                                MODEL_SIZE,
                                MODEL_SIZE,
                                true
                            )

                        val animeSmall =
                            animeEngine.processFrame(inputBitmap)

                        val animeFull =
                            if (outputWidth == MODEL_SIZE &&
                                outputHeight == MODEL_SIZE
                            ) animeSmall
                            else Bitmap.createScaledBitmap(
                                animeSmall,
                                outputWidth,
                                outputHeight,
                                true
                            )

                        val originalFull =
                            if (bitmap.width == outputWidth &&
                                bitmap.height == outputHeight
                            ) bitmap
                            else Bitmap.createScaledBitmap(
                                bitmap,
                                outputWidth,
                                outputHeight,
                                true
                            )

                        val finalBitmap =
                            blendFrames(
                                originalFull,
                                animeFull,
                                strength
                            )

                        val yuv =
                            YuvConverter.bitmapToYuv420(finalBitmap)

                        encoderSink.queueFrame(
                            yuv,
                            info.presentationTimeUs
                        )

                        if (inputBitmap !== bitmap)
                            inputBitmap.recycle()

                        if (animeSmall !== animeFull)
                            animeSmall.recycle()

                        animeFull.recycle()

                        if (originalFull !== bitmap)
                            originalFull.recycle()

                        finalBitmap.recycle()

                    } finally {
                        bitmap.recycle()
                    }

                    processed++

                    onProgress(
                        processed,
                        total,
                        "Processing..."
                    )
                }

                decoder.releaseOutputBuffer(output, false)

                if (eos) {
                    decoderDone = true
                    break
                }
            }

            if (decoderDone && !encoderEnded) {
                encoderSink.signalEndOfInput()
                encoderEnded = true
            }

            encoderSink.drain(0)
        }

        if (!encoderSink.isMuxerStarted()) {
            throw IllegalStateException(
                "Encoder never produced output."
            )
        }
    }

    private fun blendFrames(
        original: Bitmap,
        anime: Bitmap,
        strength: Float
    ): Bitmap {

        val width = original.width
        val height = original.height

        val result =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val op = IntArray(width * height)
        val ap = IntArray(width * height)
        val rp = IntArray(width * height)

        original.getPixels(op,0,width,0,0,width,height)
        anime.getPixels(ap,0,width,0,0,width,height)

        val inv = 1f - strength

        for (i in rp.indices) {

            val o = op[i]
            val a = ap[i]

            val r =
                (((o shr 16 and 255) * inv) +
                        ((a shr 16 and 255) * strength))
                    .roundToInt()

            val g =
                (((o shr 8 and 255) * inv) +
                        ((a shr 8 and 255) * strength))
                    .roundToInt()

            val b =
                (((o and 255) * inv) +
                        ((a and 255) * strength))
                    .roundToInt()

            rp[i] =
                (255 shl 24) or
                        (r.coerceIn(0,255) shl 16) or
                        (g.coerceIn(0,255) shl 8) or
                        b.coerceIn(0,255)
        }

        result.setPixels(rp,0,width,0,0,width,height)
        return result
    }

    private fun muxAudio(
        inputUri: Uri,
        processedVideo: File,
        outputFile: File
    ) {

        val source = MediaExtractor()
        val video = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {

            setExtractorDataSource(source,inputUri)
            setExtractorDataSource(video,Uri.fromFile(processedVideo))

            val videoTrack = findVideoTrack(video)
            val audioTrack = findAudioTrack(source)

            if (audioTrack < 0) {

                processedVideo.copyTo(
                    outputFile,
                    overwrite = true
                )

                return
            }

            video.selectTrack(videoTrack)
            source.selectTrack(audioTrack)

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            val outVideo =
                muxer.addTrack(video.getTrackFormat(videoTrack))

            val outAudio =
                muxer.addTrack(source.getTrackFormat(audioTrack))

            muxer.start()

            copySamples(video,muxer,outVideo)
            copySamples(source,muxer,outAudio)

            muxer.stop()

        } finally {

            try { muxer?.release() } catch (_: Exception) {}
            source.release()
            video.release()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int
    ) {

        val buffer =
            ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)

        val info = MediaCodec.BufferInfo()

        while (true) {

            buffer.clear()

            val size =
                extractor.readSampleData(buffer,0)

            if (size < 0) break

            info.set(
                0,
                size,
                extractor.sampleTime,
                extractor.sampleFlags
            )

            muxer.writeSampleData(
                outputTrack,
                buffer,
                info
            )

            extractor.advance()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {

        for (i in 0 until extractor.trackCount) {

            val mime =
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)

            if (mime?.startsWith("video/") == true)
                return i
        }

        return -1
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {

        for (i in 0 until extractor.trackCount) {

            val mime =
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)

            if (mime?.startsWith("audio/") == true)
                return i
        }

        return -1
    }

    private fun findH264Encoder(): MediaCodecInfo {

        return MediaCodecList(
            MediaCodecList.REGULAR_CODECS
        ).codecInfos.first {

            it.isEncoder &&
                    it.supportedTypes.any {
                        type ->
                        type.equals(
                            OUTPUT_MIME,
                            true
                        )
                    }
        }
    }

    private fun chooseColorFormat(
        info: MediaCodecInfo,
        mime: String
    ): Int? {

        return info.getCapabilitiesForType(mime)
            .colorFormats.firstOrNull {

                it ==
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
    }

    private fun calculateBitRate(
        width: Int,
        height: Int,
        fps: Int
    ): Int {

        return (
                width.toLong() *
                        height *
                        fps *
                        0.12
                )
            .roundToInt()
            .coerceIn(
                MIN_BITRATE,
                MAX_BITRATE
            )
    }

    private fun estimateFrameCount(
        durationUs: Long,
        fps: Int
    ): Int {

        if (durationUs <= 0L) return 1

        return (
                durationUs / 1_000_000.0 * fps
                )
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun makeEvenDimension(v: Int): Int =
        if (v % 2 == 0) v else v - 1

    private fun normalizeRotation(r: Int): Int =
        ((r % 360) + 360) % 360

    private fun setExtractorDataSource(
        extractor: MediaExtractor,
        uri: Uri
    ) {

        if (uri.scheme == "file") {

            extractor.setDataSource(uri.path!!)

        } else {

            context.contentResolver
                .openFileDescriptor(uri,"r")
                .use {

                    extractor.setDataSource(
                        it!!.fileDescriptor
                    )
                }
        }
    }

    private class EncoderSink(
        private val encoder: MediaCodec,
        private val muxer: MediaMuxer
    ) {

        private val info =
            MediaCodec.BufferInfo()

        private var started = false
        private var track = -1
        private var eos = false

        fun queueFrame(
            data: ByteArray,
            pts: Long
        ) {

            while (true) {

                val input =
                    encoder.dequeueInputBuffer(TIMEOUT_US)

                if (input >= 0) {

                    val buffer =
                        encoder.getInputBuffer(input)!!

                    buffer.clear()
                    buffer.put(data)

                    encoder.queueInputBuffer(
                        input,
                        0,
                        data.size,
                        pts,
                        0
                    )

                    return
                }

                drain(0)
            }
        }

        fun signalEndOfInput() {

            while (true) {

                val input =
                    encoder.dequeueInputBuffer(TIMEOUT_US)

                if (input >= 0) {

                    encoder.queueInputBuffer(
                        input,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )

                    return
                }

                drain(0)
            }
        }

        fun drain(timeout: Long) {

            while (!eos) {

                val output =
                    encoder.dequeueOutputBuffer(info,timeout)

                when {

                    output == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                        track =
                            muxer.addTrack(
                                encoder.outputFormat
                            )

                        muxer.start()
                        started = true
                    }

                    output >= 0 -> {

                        val buffer =
                            encoder.getOutputBuffer(output)

                        if (
                            buffer != null &&
                            info.size > 0 &&
                            started &&
                            (
                                info.flags and
                                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                ) == 0
                        ) {

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            muxer.writeSampleData(track,buffer,info)
                        }

                        eos =
                            info.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        encoder.releaseOutputBuffer(output,false)
                    }
                }
            }
        }

        fun isEndOfStream() = eos
        fun isMuxerStarted() = started
    }
}
