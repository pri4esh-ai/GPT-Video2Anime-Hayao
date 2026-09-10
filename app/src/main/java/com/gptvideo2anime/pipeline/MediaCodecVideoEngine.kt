package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
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

    companion object {
        private const val VIDEO_MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L
    }

    fun inspect(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()

        return try {
            extractor.setDataSource(context, uri, null)

            val track =
                findVideoTrack(extractor)

            if (track < 0) {
                throw IllegalStateException(
                    "No video track found."
                )
            }

            val format =
                extractor.getTrackFormat(track)

            val mime =
                format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException(
                        "Video MIME type unavailable."
                    )

            val duration =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

            val width =
                format.getInteger(MediaFormat.KEY_WIDTH)

            val height =
                format.getInteger(MediaFormat.KEY_HEIGHT)

            val fps =
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

            VideoInfo(
                mimeType = mime,
                durationUs = duration,
                width = width,
                height = height,
                frameRate = fps,
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

        val extractor =
            MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var muxerStarted = false
        var encoderTrack = -1

        try {
            onProgress(
                0,
                1,
                "Opening video..."
            )

            extractor.setDataSource(
                context,
                inputUri,
                null
            )

            val videoTrack =
                findVideoTrack(extractor)

            if (videoTrack < 0) {
                throw IllegalStateException(
                    "No video track found."
                )
            }

            extractor.selectTrack(videoTrack)

            val inputFormat =
                extractor.getTrackFormat(videoTrack)

            val inputMime =
                inputFormat.getString(
                    MediaFormat.KEY_MIME
                ) ?: throw IllegalStateException(
                    "Video MIME type unavailable."
                )

            val width =
                inputFormat.getInteger(
                    MediaFormat.KEY_WIDTH
                )

            val height =
                inputFormat.getInteger(
                    MediaFormat.KEY_HEIGHT
                )

            val frameRate =
                if (
                    inputFormat.containsKey(
                        MediaFormat.KEY_FRAME_RATE
                    )
                ) {
                    inputFormat.getInteger(
                        MediaFormat.KEY_FRAME_RATE
                    )
                } else {
                    30
                }.coerceAtLeast(1)

            val durationUs =
                if (
                    inputFormat.containsKey(
                        MediaFormat.KEY_DURATION
                    )
                ) {
                    inputFormat.getLong(
                        MediaFormat.KEY_DURATION
                    )
                } else {
                    0L
                }

            val totalFrames =
                estimateFrameCount(
                    durationUs,
                    frameRate
                )

            val outputWidth =
                width and -2

            val outputHeight =
                height and -2

            require(
                outputWidth >= 2 &&
                    outputHeight >= 2
            ) {
                "Video dimensions are too small."
            }

            /*
             * Decoder.
             */
            decoder =
                MediaCodec.createDecoderByType(
                    inputMime
                )

            decoder.configure(
                inputFormat,
                null,
                null,
                0
            )

            decoder.start()

            /*
             * Encoder.
             */
            val encoderInfo =
                findEncoder(VIDEO_MIME)

            val colorFormat =
                chooseColorFormat(
                    encoderInfo
                )

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    VIDEO_MIME,
                    outputWidth,
                    outputHeight
                ).apply {

                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        colorFormat
                    )

                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        calculateBitRate(
                            outputWidth,
                            outputHeight,
                            frameRate
                        )
                    )

                    setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        frameRate
                    )

                    setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL,
                        2
                    )
                }

            encoder =
                MediaCodec.createByCodecName(
                    encoderInfo.name
                )

            encoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            encoder.start()

            /*
             * Output MP4.
             */
            outputFile.parentFile?.mkdirs()

            if (outputFile.exists()) {
                outputFile.delete()
            }

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            val rotation =
                if (
                    inputFormat.containsKey(
                        MediaFormat.KEY_ROTATION
                    )
                ) {
                    inputFormat.getInteger(
                        MediaFormat.KEY_ROTATION
                    )
                } else {
                    0
                }

            if (
                rotation == 90 ||
                rotation == 180 ||
                rotation == 270
            ) {
                muxer.setOrientationHint(
                    rotation
                )
            }

            processFrames(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                sourceWidth = width,
                sourceHeight = height,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                totalFrames = totalFrames,
                animeEngine = animeEngine,
                strength = strength,
                onProgress = onProgress,
                onTrackReady = {
                    encoderTrack = it
                },
                isMuxerStarted = {
                    muxerStarted
                },
                startMuxer = {
                    if (!muxerStarted) {
                        muxer.start()
                        muxerStarted = true
                    }
                },
                getEncoderTrack = {
                    encoderTrack
                }
            )

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }

            muxer.release()
            muxer = null

            /*
             * Add original audio.
             */
            val videoOnly =
                File(
                    outputFile.parentFile,
                    "${outputFile.nameWithoutExtension}_video.mp4"
                )

            if (!outputFile.renameTo(videoOnly)) {
                throw IllegalStateException(
                    "Unable to prepare video for audio muxing."
                )
            }

            try {
                muxAudio(
                    inputUri = inputUri,
                    videoFile = videoOnly,
                    outputFile = outputFile
                )
            } finally {
                videoOnly.delete()
            }

            if (
                !outputFile.exists() ||
                outputFile.length() <= 0L
            ) {
                throw IllegalStateException(
                    "Final output video was not created."
                )
            }

            onProgress(
                totalFrames,
                totalFrames,
                "Video processing complete"
            )

        } finally {

            try {
                decoder?.stop()
            } catch (_: Exception) {
            }

            try {
                decoder?.release()
            } catch (_: Exception) {
            }

            try {
                encoder?.stop()
            } catch (_: Exception) {
            }

            try {
                encoder?.release()
            } catch (_: Exception) {
            }

            try {
                if (!muxerStarted) {
                    muxer?.release()
                }
            } catch (_: Exception) {
            }

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
        totalFrames: Int,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        onProgress: (Int, Int, String) -> Unit,
        onTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        getEncoderTrack: () -> Int
    ) {
        val decoderInfo =
            MediaCodec.BufferInfo()

        val encoderInfo =
            MediaCodec.BufferInfo()

        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderEosSent = false
        var encoderOutputDone = false

        var frameNumber = 0

        while (!encoderOutputDone) {

            /*
             * Feed decoder input.
             */
            if (!decoderInputDone) {

                val inputIndex =
                    decoder.dequeueInputBuffer(
                        TIMEOUT_US
                    )

                if (inputIndex >= 0) {

                    val inputBuffer =
                        decoder.getInputBuffer(
                            inputIndex
                        ) ?: throw IllegalStateException(
                            "Decoder input buffer unavailable."
                        )

                    inputBuffer.clear()

                    val sampleSize =
                        extractor.readSampleData(
                            inputBuffer,
                            0
                        )

                    if (sampleSize < 0) {

                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )

                        decoderInputDone = true

                    } else {

                        val timestamp =
                            extractor.sampleTime
                                .coerceAtLeast(0L)

                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            timestamp,
                            extractor.sampleFlags
                        )

                        extractor.advance()
                    }
                }
            }

            /*
             * Drain decoder.
             */
            if (!decoderOutputDone) {

                val outputIndex =
                    decoder.dequeueOutputBuffer(
                        decoderInfo,
                        TIMEOUT_US
                    )

                when {

                    outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    }

                    outputIndex >= 0 -> {

                        val eos =
                            (
                                decoderInfo.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                        if (
                            decoderInfo.size > 0 &&
                            !eos
                        ) {

                            val image =
                                decoder.getOutputImage(
                                    outputIndex
                                )

                            if (image != null) {

                                var original: Bitmap? =
                                    null

                                var processed: Bitmap? =
                                    null

                                try {

                                    onProgress(
                                        frameNumber,
                                        totalFrames,
                                        "Processing frame ${frameNumber + 1}"
                                    )

                                    original =
                                        YuvConverter.imageToBitmap(
                                            image
                                        )

                                    processed =
                                        createProcessedFrame(
                                            original = original,
                                            animeEngine = animeEngine,
                                            strength = strength,
                                            width = outputWidth,
                                            height = outputHeight
                                        )

                                    val yuv =
                                        YuvConverter.bitmapToYuv420(
                                            processed
                                        )

                                    queueEncoderFrame(
                                        encoder = encoder,
                                        data = yuv,
                                        presentationTimeUs =
                                            decoderInfo.presentationTimeUs,
                                        encoderInfo = encoderInfo,
                                        muxer = muxer,
                                        onTrackReady = onTrackReady,
                                        isMuxerStarted = isMuxerStarted,
                                        startMuxer = startMuxer,
                                        getEncoderTrack = getEncoderTrack
                                    )

                                    frameNumber++

                                } finally {

                                    image.close()

                                    if (
                                        processed != null &&
                                        processed !== original &&
                                        !processed.isRecycled
                                    ) {
                                        processed.recycle()
                                    }

                                    if (
                                        original != null &&
                                        !original.isRecycled
                                    ) {
                                        original.recycle()
                                    }
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                        if (eos) {
                            decoderOutputDone = true
                        }
                    }
                }
            }

            /*
             * Finish encoder after decoder EOS.
             */
            if (
                decoderOutputDone &&
                !encoderEosSent
            ) {

                signalEncoderEndOfStream(
                    encoder
                )

                encoderEosSent = true
            }

            /*
             * Drain encoder.
             */
            if (
                encoderEosSent ||
                frameNumber > 0
            ) {

                encoderOutputDone =
                    drainEncoder(
                        encoder = encoder,
                        bufferInfo = encoderInfo,
                        muxer = muxer,
                        onTrackReady = onTrackReady,
                        isMuxerStarted = isMuxerStarted,
                        startMuxer = startMuxer,
                        getEncoderTrack = getEncoderTrack,
                        waitForEos = encoderEosSent
                    )
            }
        }

        if (frameNumber <= 0) {
            throw IllegalStateException(
                "No video frames were processed."
            )
        }
    }

    private fun createProcessedFrame(
        original: Bitmap,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        width: Int,
        height: Int
    ): Bitmap {

        val source =
            if (
                original.width != width ||
                original.height != height
            ) {
                YuvConverter.resize(
                    original,
                    width,
                    height
                )
            } else {
                original
            }

        val anime =
            animeEngine.processFrame(
                source
            )

        return try {

            blendBitmaps(
                original = source,
                anime = anime,
                strength = strength
            )

        } finally {

            if (
                anime !== source &&
                !anime.isRecycled
            ) {
                anime.recycle()
            }

            if (
                source !== original &&
                !source.isRecycled
            ) {
                source.recycle()
            }
        }
    }

    private fun blendBitmaps(
        original: Bitmap,
        anime: Bitmap,
        strength: Float
    ): Bitmap {

        val width =
            minOf(
                original.width,
                anime.width
            )

        val height =
            minOf(
                original.height,
                anime.height
            )

        val result =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val originalPixels =
            IntArray(width * height)

        val animePixels =
            IntArray(width * height)

        val resultPixels =
            IntArray(width * height)

        original.getPixels(
            originalPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        anime.getPixels(
            animePixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val inverse =
            1f - strength

        for (i in resultPixels.indices) {

            val o =
                originalPixels[i]

            val a =
                animePixels[i]

            val r =
                (
                    ((o shr 16) and 255) *
                        inverse +
                        ((a shr 16) and 255) *
                        strength
                    )
                    .roundToInt()
                    .coerceIn(0, 255)

            val g =
                (
                    ((o shr 8) and 255) *
                        inverse +
                        ((a shr 8) and 255) *
                        strength
                    )
                    .roundToInt()
                    .coerceIn(0, 255)

            val b =
                (
                    (o and 255) *
                        inverse +
                        (a and 255) *
                        strength
                    )
                    .roundToInt()
                    .coerceIn(0, 255)

            resultPixels[i] =
                (255 shl 24) or
                    (r shl 16) or
                    (g shl 8) or
                    b
        }

        result.setPixels(
            resultPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return result
    }

    private fun queueEncoderFrame(
        encoder: MediaCodec,
        data: ByteArray,
        presentationTimeUs: Long,
        encoderInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        onTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        getEncoderTrack: () -> Int
    ) {

        while (true) {

            val inputIndex =
                encoder.dequeueInputBuffer(
                    TIMEOUT_US
                )

            if (inputIndex >= 0) {

                val inputBuffer =
                    encoder.getInputBuffer(
                        inputIndex
                    ) ?: throw IllegalStateException(
                        "Encoder input buffer unavailable."
                    )

                inputBuffer.clear()

                require(
                    data.size <= inputBuffer.remaining()
                ) {
                    "YUV frame is larger than encoder input buffer."
                }

                inputBuffer.put(data)

                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    presentationTimeUs,
                    0
                )

                drainEncoder(
                    encoder = encoder,
                    bufferInfo = encoderInfo,
                    muxer = muxer,
                    onTrackReady = onTrackReady,
                    isMuxerStarted = isMuxerStarted,
                    startMuxer = startMuxer,
                    getEncoderTrack = getEncoderTrack,
                    waitForEos = false
                )

                return
            }

            drainEncoder(
                encoder = encoder,
                bufferInfo = encoderInfo,
                muxer = muxer,
                onTrackReady = onTrackReady,
                isMuxerStarted = isMuxerStarted,
                startMuxer = startMuxer,
                getEncoderTrack = getEncoderTrack,
                waitForEos = false
            )
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        onTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        getEncoderTrack: () -> Int,
        waitForEos: Boolean
    ): Boolean {

        var eos =
            false

        while (true) {

            val timeout =
                if (waitForEos) {
                    100_000L
                } else {
                    0L
                }

            val outputIndex =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    timeout
                )

            when {

                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return eos
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (isMuxerStarted()) {
                        throw IllegalStateException(
                            "Encoder output format changed more than once."
                        )
                    }

                    val outputFormat =
                        encoder.outputFormat

                    val track =
                        muxer.addTrack(
                            outputFormat
                        )

                    onTrackReady(track)

                    startMuxer()
                }

                outputIndex >= 0 -> {

                    val outputBuffer =
                        encoder.getOutputBuffer(
                            outputIndex
                        )

                    if (outputBuffer != null) {

                        if (
                            bufferInfo.size > 0
                        ) {

                            if (!isMuxerStarted()) {
                                throw IllegalStateException(
                                    "Encoder produced data before muxer started."
                                )
                            }

                            val track =
                                getEncoderTrack()

                            if (track < 0) {
                                throw IllegalStateException(
                                    "Encoder track unavailable."
                                )
                            }

                            outputBuffer.position(
                                bufferInfo.offset
                            )

                            outputBuffer.limit(
                                bufferInfo.offset +
                                    bufferInfo.size
                            )

                            muxer.writeSampleData(
                                track,
                                outputBuffer,
                                bufferInfo
                            )
                        }
                    }

                    val outputEos =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        ) != 0

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (outputEos) {
                        eos = true
                        return true
                    }
                }
            }
        }
    }

    private fun signalEncoderEndOfStream(
        encoder: MediaCodec
    ) {

        while (true) {

            val index =
                encoder.dequeueInputBuffer(
                    TIMEOUT_US
                )

            if (index >= 0) {

                encoder.queueInputBuffer(
                    index,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )

                return
            }

            val info =
                MediaCodec.BufferInfo()

            while (true) {

                val output =
                    encoder.dequeueOutputBuffer(
                        info,
                        0L
                    )

                if (
                    output ==
                    MediaCodec.INFO_TRY_AGAIN_LATER
                ) {
                    break
                }

                if (output >= 0) {
                    encoder.releaseOutputBuffer(
                        output,
                        false
                    )
                }
            }
        }
    }

    private fun findEncoder(
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
                try {
                    info.getCapabilitiesForType(
                        mime
                    )
                } catch (_: Exception) {
                    return@firstOrNull false
                }

            capabilities.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities
                    .COLOR_FormatYUV420SemiPlanar
            )

        } ?: throw IllegalStateException(
            "No H.264 YUV420 semi-planar encoder is available."
        )
    }

    private fun chooseColorFormat(
        codecInfo: MediaCodecInfo
    ): Int {

        val capabilities =
            codecInfo.getCapabilitiesForType(
                VIDEO_MIME
            )

        return if (
            capabilities.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities
                    .COLOR_FormatYUV420SemiPlanar
            )
        ) {
            MediaCodecInfo.CodecCapabilities
                .COLOR_FormatYUV420SemiPlanar
        } else {
            throw IllegalStateException(
                "YUV420 semi-planar format is not supported."
            )
        }
    }

    private fun muxAudio(
        inputUri: Uri,
        videoFile: File,
        outputFile: File
    ) {

        val inputExtractor =
            MediaExtractor()

        val videoExtractor =
            MediaExtractor()

        var muxer: MediaMuxer? =
            null

        try {

            inputExtractor.setDataSource(
                context,
                inputUri,
                null
            )

            videoExtractor.setDataSource(
                videoFile.absolutePath
            )

            val videoTrack =
                findVideoTrack(
                    videoExtractor
                )

            if (videoTrack < 0) {
                throw IllegalStateException(
                    "Processed video track is missing."
                )
            }

            val audioTrack =
                findAudioTrack(
                    inputExtractor
                )

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            val outputVideoTrack =
                muxer.addTrack(
                    videoExtractor.getTrackFormat(
                        videoTrack
                    )
                )

            val outputAudioTrack =
                if (audioTrack >= 0) {
                    muxer.addTrack(
                        inputExtractor.getTrackFormat(
                            audioTrack
                        )
                    )
                } else {
                    -1
                }

            muxer.start()

            copySamples(
                extractor = videoExtractor,
                trackIndex = videoTrack,
                outputTrack = outputVideoTrack,
                muxer = muxer
            )

            if (audioTrack >= 0) {

                copySamples(
                    extractor = inputExtractor,
                    trackIndex = audioTrack,
                    outputTrack = outputAudioTrack,
                    muxer = muxer
                )
            }

            muxer.stop()

        } finally {

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            videoExtractor.release()
            inputExtractor.release()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        trackIndex: Int,
        outputTrack: Int,
        muxer: MediaMuxer
    ) {

        extractor.selectTrack(
            trackIndex
        )

        val buffer =
            ByteBuffer.allocateDirect(
                4 * 1024 * 1024
            )

        val info =
            MediaCodec.BufferInfo()

        try {

            while (true) {

                buffer.clear()

                val size =
                    extractor.readSampleData(
                        buffer,
                        0
                    )

                if (size < 0) {
                    break
                }

                info.offset = 0
                info.size = size
                info.presentationTimeUs =
                    extractor.sampleTime
                        .coerceAtLeast(0L)

                info.flags =
                    extractor.sampleFlags

                muxer.writeSampleData(
                    outputTrack,
                    buffer,
                    info
                )

                extractor.advance()
            }

        } finally {

            extractor.unselectTrack(
                trackIndex
            )
        }
    }

    private fun findVideoTrack(
        extractor: MediaExtractor
    ): Int {

        for (
            index in 0 until extractor.trackCount
        ) {

            val mime =
                extractor
                    .getTrackFormat(index)
                    .getString(
                        MediaFormat.KEY_MIME
                    )

            if (
                mime?.startsWith(
                    "video/",
                    ignoreCase = true
                ) == true
            ) {
                return index
            }
        }

        return -1
    }

    private fun findAudioTrack(
        extractor: MediaExtractor
    ): Int {

        for (
            index in 0 until extractor.trackCount
        ) {

            val mime =
                extractor
                    .getTrackFormat(index)
                    .getString(
                        MediaFormat.KEY_MIME
                    )

            if (
                mime?.startsWith(
                    "audio/",
                    ignoreCase = true
                ) == true
            ) {
                return index
            }
        }

        return -1
    }

    private fun estimateFrameCount(
        durationUs: Long,
        frameRate: Int
    ): Int {

        if (durationUs <= 0L) {
            return 1
        }

        return (
            durationUs.toDouble() /
                1_000_000.0 *
                frameRate.toDouble()
            )
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun calculateBitRate(
        width: Int,
        height: Int,
        frameRate: Int
    ): Int {

        val pixelsPerSecond =
            width.toLong() *
                height.toLong() *
                frameRate.toLong()

        return (
            pixelsPerSecond *
                0.12
            )
            .roundToInt()
            .coerceIn(
                2_000_000,
                20_000_000
            )
    }
}
