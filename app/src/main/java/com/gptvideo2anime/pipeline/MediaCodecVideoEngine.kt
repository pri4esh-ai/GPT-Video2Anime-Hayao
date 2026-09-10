package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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
        private const val EOS_TIMEOUT_US = 100_000L
    }

    fun inspect(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()

        return try {
            extractor.setDataSource(
                context,
                uri,
                null
            )

            val trackIndex =
                findVideoTrack(extractor)

            if (trackIndex < 0) {
                throw IllegalStateException(
                    "No video track found."
                )
            }

            val format =
                extractor.getTrackFormat(trackIndex)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                ) ?: throw IllegalStateException(
                    "Video MIME type is unavailable."
                )

            val durationUs =
                if (
                    format.containsKey(
                        MediaFormat.KEY_DURATION
                    )
                ) {
                    format.getLong(
                        MediaFormat.KEY_DURATION
                    )
                } else {
                    0L
                }

            val width =
                format.getInteger(
                    MediaFormat.KEY_WIDTH
                )

            val height =
                format.getInteger(
                    MediaFormat.KEY_HEIGHT
                )

            val frameRate =
                if (
                    format.containsKey(
                        MediaFormat.KEY_FRAME_RATE
                    )
                ) {
                    format.getInteger(
                        MediaFormat.KEY_FRAME_RATE
                    )
                } else {
                    30
                }.coerceAtLeast(1)

            val rotation =
                if (
                    format.containsKey(
                        MediaFormat.KEY_ROTATION
                    )
                ) {
                    format.getInteger(
                        MediaFormat.KEY_ROTATION
                    )
                } else {
                    0
                }

            VideoInfo(
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
        animeEngine: com.gptvideo2anime.inference.OnnxAnimeEngine,
        strength: Float,
        onProgress: (Int, Int, String) -> Unit
    ) {
        require(strength in 0f..1f) {
            "Strength must be between 0 and 1."
        }

        val extractor =
            MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var muxerStarted = false
        var encoderTrack = -1

        var videoOnlyFile: File? = null

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

            val videoFormat =
                extractor.getTrackFormat(
                    videoTrack
                )

            val inputMime =
                videoFormat.getString(
                    MediaFormat.KEY_MIME
                ) ?: throw IllegalStateException(
                    "Video MIME type is unavailable."
                )

            val sourceWidth =
                videoFormat.getInteger(
                    MediaFormat.KEY_WIDTH
                )

            val sourceHeight =
                videoFormat.getInteger(
                    MediaFormat.KEY_HEIGHT
                )

            require(
                sourceWidth > 0 &&
                    sourceHeight > 0
            ) {
                "Invalid video dimensions."
            }

            val frameRate =
                if (
                    videoFormat.containsKey(
                        MediaFormat.KEY_FRAME_RATE
                    )
                ) {
                    videoFormat.getInteger(
                        MediaFormat.KEY_FRAME_RATE
                    )
                } else {
                    30
                }.coerceAtLeast(1)

            val durationUs =
                if (
                    videoFormat.containsKey(
                        MediaFormat.KEY_DURATION
                    )
                ) {
                    videoFormat.getLong(
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

            /*
             * H.264 YUV420 requires even dimensions.
             */
            val outputWidth =
                sourceWidth and -2

            val outputHeight =
                sourceHeight and -2

            require(
                outputWidth >= 2 &&
                    outputHeight >= 2
            ) {
                "Video is too small."
            }

            /*
             * Decode using Surface -> ImageReader would be more
             * efficient, but the existing project uses Image output.
             */
            decoder =
                MediaCodec.createDecoderByType(
                    inputMime
                )

            val decoderFormat =
                videoFormat

            decoder.configure(
                decoderFormat,
                null,
                null,
                0
            )

            decoder.start()

            val encoderInfo =
                findByteBufferEncoder(
                    VIDEO_MIME
                )

            val colorFormat =
                chooseColorFormat(
                    encoderInfo
                )

            val encoderBitRate =
                calculateBitRate(
                    outputWidth,
                    outputHeight,
                    frameRate
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
                        encoderBitRate
                    )

                    setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        frameRate
                    )

                    setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL,
                        2
                    )

                    if (
                        android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.M
                    ) {
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel
                                .AVCProfileHigh
                        )
                    }
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

            outputFile.parentFile?.mkdirs()

            if (outputFile.exists()) {
                outputFile.delete()
            }

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            val activeMuxer =
                muxer

            val rotation =
                if (
                    videoFormat.containsKey(
                        MediaFormat.KEY_ROTATION
                    )
                ) {
                    videoFormat.getInteger(
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
                activeMuxer.setOrientationHint(
                    rotation
                )
            }

            val frameResult =
                processFrames(
                    extractor = extractor,
                    decoder = decoder,
                    encoder = encoder,
                    muxer = activeMuxer,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    totalFrames = totalFrames,
                    animeEngine = animeEngine,
                    strength = strength,
                    onProgress = onProgress,
                    onEncoderTrackReady = {
                        encoderTrack = it
                    },
                    isMuxerStarted = {
                        muxerStarted
                    },
                    startMuxer = {
                        if (!muxerStarted) {
                            activeMuxer.start()
                            muxerStarted = true
                        }
                    },
                    encoderTrackProvider = {
                        encoderTrack
                    }
                )

            if (!frameResult) {
                throw IllegalStateException(
                    "Video frame processing failed."
                )
            }

            if (muxerStarted) {
                activeMuxer.stop()
                muxerStarted = false
            }

            activeMuxer.release()
            muxer = null

            /*
             * Keep processed video in a temporary file while
             * audio from the original video is muxed back in.
             */
            videoOnlyFile =
                File(
                    outputFile.parentFile,
                    "${outputFile.nameWithoutExtension}_video.mp4"
                )

            if (outputFile.renameTo(videoOnlyFile)) {

                muxAudio(
                    inputUri = inputUri,
                    videoFile = videoOnlyFile,
                    outputFile = outputFile
                )

                videoOnlyFile.delete()
                videoOnlyFile = null

            } else {

                throw IllegalStateException(
                    "Unable to prepare processed video for audio muxing."
                )
            }

            if (!outputFile.exists() ||
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

            try {
                videoOnlyFile?.delete()
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
        animeEngine: com.gptvideo2anime.inference.OnnxAnimeEngine,
        strength: Float,
        onProgress: (Int, Int, String) -> Unit,
        onEncoderTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        encoderTrackProvider: () -> Int
    ): Boolean {

        val decoderInfo =
            MediaCodec.BufferInfo()

        val encoderInfo =
            MediaCodec.BufferInfo()

        var inputDone = false
        var decoderDone = false
        var encoderDone = false

        var frameNumber = 0

        var lastPresentationTimeUs =
            0L

        while (!encoderDone) {

            /*
             * Feed decoder.
             */
            if (!inputDone) {

                val inputIndex =
                    decoder.dequeueInputBuffer(
                        TIMEOUT_US
                    )

                if (inputIndex >= 0) {

                    val inputBuffer =
                        decoder.getInputBuffer(
                            inputIndex
                        )

                    if (inputBuffer == null) {
                        throw IllegalStateException(
                            "Decoder input buffer unavailable."
                        )
                    }

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

                        inputDone = true

                    } else {

                        val presentationTimeUs =
                            extractor.sampleTime
                                .coerceAtLeast(0L)

                        val flags =
                            extractor.sampleFlags

                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            presentationTimeUs,
                            flags
                        )

                        extractor.advance()
                    }
                }
            }

            /*
             * Drain decoder.
             */
            if (!decoderDone) {

                val decoderIndex =
                    decoder.dequeueOutputBuffer(
                        decoderInfo,
                        TIMEOUT_US
                    )

                when {

                    decoderIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Try again.
                    }

                    decoderIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Decoder output format changed.
                    }

                    decoderIndex >= 0 -> {

                        val endOfStream =
                            (
                                decoderInfo.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                        val size =
                            decoderInfo.size

                        val presentationTimeUs =
                            decoderInfo.presentationTimeUs

                        if (
                            size > 0 &&
                            !endOfStream
                        ) {

                            val image =
                                decoder.getOutputImage(
                                    decoderIndex
                                )

                            if (image != null) {

                                var originalBitmap:
                                    Bitmap? = null

                                var processedBitmap:
                                    Bitmap? = null

                                try {

                                    onProgress(
                                        frameNumber,
                                        totalFrames,
                                        "Processing frame ${frameNumber + 1}"
                                    )

                                    originalBitmap =
                                        YuvConverter.imageToBitmap(
                                            image
                                        )

                                    processedBitmap =
                                        createProcessedFrame(
                                            original =
                                                originalBitmap,
                                            animeEngine =
                                                animeEngine,
                                            strength =
                                                strength,
                                            width =
                                                outputWidth,
                                            height =
                                                outputHeight
                                        )

                                    val yuv =
                                        YuvConverter.bitmapToYuv420(
                                            processedBitmap
                                        )

                                    queueEncoderFrame(
                                        encoder =
                                            encoder,
                                        data =
                                            yuv,
                                        presentationTimeUs =
                                            presentationTimeUs,
                                        encoderInfo =
                                            encoderInfo,
                                        muxer =
                                            muxer,
                                        onEncoderTrackReady =
                                            onEncoderTrackReady,
                                        isMuxerStarted =
                                            isMuxerStarted,
                                        startMuxer =
                                            startMuxer,
                                        encoderTrackProvider =
                                            encoderTrackProvider
                                    )

                                    frameNumber++

                                    lastPresentationTimeUs =
                                        presentationTimeUs

                                } finally {

                                    if (
                                        processedBitmap != null &&
                                        !processedBitmap.isRecycled
                                    ) {
                                        processedBitmap.recycle()
                                    }

                                    if (
                                        originalBitmap != null &&
                                        !originalBitmap.isRecycled
                                    ) {
                                        originalBitmap.recycle()
                                    }

                                    image.close()
                                }
                            }

                        }

                        decoder.releaseOutputBuffer(
                            decoderIndex,
                            false
                        )

                        if (endOfStream) {
                            decoderDone = true
                        }
                    }
                }
            }

            /*
             * Once decoder reaches EOS, finish encoder.
             */
            if (
                decoderDone &&
                !encoderDone
            ) {

                signalEncoderEndOfStream(
                    encoder
                )

                encoderDone =
                    drainEncoderToMuxer(
                        encoder =
                            encoder,
                        bufferInfo =
                            encoderInfo,
                        muxer =
                            muxer,
                        onEncoderTrackReady =
                            onEncoderTrackReady,
                        isMuxerStarted =
                            isMuxerStarted,
                        startMuxer =
                            startMuxer,
                        encoderTrackProvider =
                            encoderTrackProvider,
                        endOfStreamRequired =
                            true
                    )

            } else if (!encoderDone) {

                /*
                 * Drain any encoded frames that may already
                 * be available.
                 */
                drainEncoderToMuxer(
                    encoder =
                        encoder,
                    bufferInfo =
                        encoderInfo,
                    muxer =
                        muxer,
                    onEncoderTrackReady =
                        onEncoderTrackReady,
                    isMuxerStarted =
                        isMuxerStarted,
                    startMuxer =
                        startMuxer,
                    encoderTrackProvider =
                        encoderTrackProvider,
                    endOfStreamRequired =
                        false
                )
            }
        }

        if (frameNumber == 0) {
            throw IllegalStateException(
                "No video frames were decoded."
            )
        }

        return true
    }

    private fun createProcessedFrame(
        original: Bitmap,
        animeEngine: com.gptvideo2anime.inference.OnnxAnimeEngine,
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

        require(strength in 0f..1f)

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
            IntArray(
                width * height
            )

        val animePixels =
            IntArray(
                width * height
            )

        val resultPixels =
            IntArray(
                width * height
            )

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

            val originalPixel =
                originalPixels[i]

            val animePixel =
                animePixels[i]

            val originalR =
                (originalPixel shr 16) and 255

            val originalG =
                (originalPixel shr 8) and 255

            val originalB =
                originalPixel and 255

            val animeR =
                (animePixel shr 16) and 255

            val animeG =
                (animePixel shr 8) and 255

            val animeB =
                animePixel and 255

            val r =
                (
                    originalR * inverse +
                        animeR * strength
                    ).roundToInt()
                        .coerceIn(0, 255)

            val g =
                (
                    originalG * inverse +
                        animeG * strength
                    ).roundToInt()
                        .coerceIn(0, 255)

            val b =
                (
                    originalB * inverse +
                        animeB * strength
                    ).roundToInt()
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
        onEncoderTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        encoderTrackProvider: () -> Int
    ) {

        while (true) {

            val inputIndex =
                encoder.dequeueInputBuffer(
                    TIMEOUT_US
                )

            if (inputIndex >= 0) {

                val buffer =
                    encoder.getInputBuffer(
                        inputIndex
                    ) ?: throw IllegalStateException(
                        "Encoder input buffer unavailable."
                    )

                buffer.clear()

                if (
                    data.size >
                    buffer.remaining()
                ) {
                    throw IllegalStateException(
                        "YUV frame is larger than encoder input buffer."
                    )
                }

                buffer.put(data)

                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    presentationTimeUs,
                    0
                )

                drainEncoderToMuxer(
                    encoder =
                        encoder,
                    bufferInfo =
                        encoderInfo,
                    muxer =
                        muxer,
                    onEncoderTrackReady =
                        onEncoderTrackReady,
                    isMuxerStarted =
                        isMuxerStarted,
                    startMuxer =
                        startMuxer,
                    encoderTrackProvider =
                        encoderTrackProvider,
                    endOfStreamRequired =
                        false
                )

                return
            }

            drainEncoderToMuxer(
                encoder =
                    encoder,
                bufferInfo =
                    encoderInfo,
                muxer =
                    muxer,
                onEncoderTrackReady =
                    onEncoderTrackReady,
                isMuxerStarted =
                    isMuxerStarted,
                startMuxer =
                    startMuxer,
                encoderTrackProvider =
                    encoderTrackProvider,
                endOfStreamRequired =
                    false
            )
        }
    }

    private fun drainEncoderToMuxer(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        onEncoderTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        encoderTrackProvider: () -> Int,
        endOfStreamRequired: Boolean
    ): Boolean {

        var sawEndOfStream =
            false

        while (true) {

            val outputIndex =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    if (endOfStreamRequired) {
                        EOS_TIMEOUT_US
                    } else {
                        0L
                    }
                )

            when {

                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return sawEndOfStream
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (
                        isMuxerStarted()
                    ) {
                        throw IllegalStateException(
                            "Encoder output format changed twice."
                        )
                    }

                    val outputFormat =
                        encoder.outputFormat

                    val track =
                        muxer.addTrack(
                            outputFormat
                        )

                    onEncoderTrackReady(
                        track
                    )

                    startMuxer()
                }

                outputIndex >= 0 -> {

                    val outputBuffer =
                        encoder.getOutputBuffer(
                            outputIndex
                        )

                    if (outputBuffer == null) {

                        encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                        continue
                    }

                    if (
                        bufferInfo.size > 0
                    ) {

                        if (
                            !isMuxerStarted()
                        ) {
                            throw IllegalStateException(
                                "Encoder produced data before muxer started."
                            )
                        }

                        val track =
                            encoderTrackProvider()

                        if (track < 0) {
                            throw IllegalStateException(
                                "Encoder track is unavailable."
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

                    val endOfStream =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        ) != 0

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (endOfStream) {
                        sawEndOfStream = true
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

            val inputIndex =
                encoder.dequeueInputBuffer(
                    TIMEOUT_US
                )

            if (inputIndex >= 0) {

                encoder.queueInputBuffer(
                    inputIndex,
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

                val outputIndex =
                    encoder.dequeueOutputBuffer(
                        info,
                        0
                    )

                if (
                    outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER
                ) {
                    break
                }

                if (
                    outputIndex >= 0
                ) {
                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )
                }
            }
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
                try {
                    info.getCapabilitiesForType(
                        mime
                    )
                } catch (_: Exception) {
                    return@firstOrNull false
                }

            capabilities.colorFormats.any {
                it ==
                    MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatYUV420SemiPlanar
            }

        } ?: throw IllegalStateException(
            "No H.264 encoder with YUV420 semi-planar input is available on this device."
        )
    }

    private fun chooseColorFormat(
        codecInfo: MediaCodecInfo
    ): Int {

        val capabilities =
            codecInfo.getCapabilitiesForType(
                VIDEO_MIME
            )

        if (
            capabilities.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities
                    .COLOR_FormatYUV420SemiPlanar
            )
        ) {
            return MediaCodecInfo.CodecCapabilities
                .COLOR_FormatYUV420SemiPlanar
        }

        throw IllegalStateException(
            "YUV420 semi-planar encoding is not supported."
        )
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
                extractor =
                    videoExtractor,
                trackIndex =
                    videoTrack,
                outputTrack =
                    outputVideoTrack,
                muxer =
                    muxer
            )

            if (audioTrack >= 0) {

                copySamples(
                    extractor =
                        inputExtractor,
                    trackIndex =
                        audioTrack,
                    outputTrack =
                        outputAudioTrack,
                    muxer =
                        muxer
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
