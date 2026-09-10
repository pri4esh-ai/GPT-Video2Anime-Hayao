package com.gptvideo2anime.pipeline

import android.content.Context
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
    }

    fun inspect(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()

        try {
            setExtractorDataSource(extractor, uri)

            val trackIndex = findVideoTrack(extractor)

            if (trackIndex < 0) {
                throw IllegalStateException("No video track found.")
            }

            val format = extractor.getTrackFormat(trackIndex)

            val mime =
                format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException(
                        "Video MIME type unavailable."
                    )

            val width =
                format.getInteger(MediaFormat.KEY_WIDTH)

            val height =
                format.getInteger(MediaFormat.KEY_HEIGHT)

            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

            val frameRate =
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    DEFAULT_FPS
                }

            val rotation =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    format.containsKey(MediaFormat.KEY_ROTATION)
                ) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                }

            return VideoInfo(
                mimeType = mime,
                width = width,
                height = height,
                durationUs = durationUs,
                frameRate = frameRate.coerceIn(1, 120),
                rotation = rotation
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

        require(strength in 0f..1f) {
            "Strength must be between 0 and 1."
        }

        val extractor = MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        val temporaryVideo =
            File(
                outputFile.parentFile,
                "${outputFile.nameWithoutExtension}_video_only_${System.nanoTime()}.mp4"
            )

        try {

            outputFile.parentFile?.mkdirs()

            setExtractorDataSource(
                extractor,
                inputUri
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
                extractor.getTrackFormat(videoTrack)

            val inputMime =
                videoFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException(
                        "Video MIME type unavailable."
                    )

            val sourceWidth =
                videoFormat.getInteger(
                    MediaFormat.KEY_WIDTH
                )

            val sourceHeight =
                videoFormat.getInteger(
                    MediaFormat.KEY_HEIGHT
                )

            val fps =
                if (videoFormat.containsKey(
                        MediaFormat.KEY_FRAME_RATE
                    )
                ) {
                    videoFormat.getInteger(
                        MediaFormat.KEY_FRAME_RATE
                    )
                } else {
                    DEFAULT_FPS
                }.coerceIn(1, 120)

            val durationUs =
                if (videoFormat.containsKey(
                        MediaFormat.KEY_DURATION
                    )
                ) {
                    videoFormat.getLong(
                        MediaFormat.KEY_DURATION
                    )
                } else {
                    0L
                }

            val rotation =
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
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

            val encodeWidth =
                makeEvenDimension(sourceWidth)

            val encodeHeight =
                makeEvenDimension(sourceHeight)

            if (encodeWidth < 2 || encodeHeight < 2) {
                throw IllegalStateException(
                    "Unsupported video dimensions: ${sourceWidth}x${sourceHeight}"
                )
            }

            Log.i(
                TAG,
                "Input=$sourceWidth x $sourceHeight " +
                    "Output=$encodeWidth x $encodeHeight " +
                    "fps=$fps mime=$inputMime"
            )

            onProgress(
                0,
                estimateFrameCount(
                    durationUs,
                    fps
                ),
                "Opening video..."
            )

            decoder =
                MediaCodec.createDecoderByType(
                    inputMime
                )

            decoder.configure(
                videoFormat,
                null,
                null,
                0
            )

            decoder.start()

            val encoderInfo =
                findH264Encoder()

            val colorFormat =
                chooseColorFormat(
                    encoderInfo,
                    OUTPUT_MIME
                )

            if (colorFormat == null) {
                throw IllegalStateException(
                    "No compatible H.264 YUV420 semi-planar encoder found on this device."
                )
            }

            val bitrate =
                calculateBitRate(
                    encodeWidth,
                    encodeHeight,
                    fps
                )

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    OUTPUT_MIME,
                    encodeWidth,
                    encodeHeight
                ).apply {

                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        colorFormat
                    )

                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        bitrate
                    )

                    setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        fps
                    )

                    setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL,
                        2
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(
                            MediaFormat.KEY_PRIORITY,
                            0
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

            muxer =
                MediaMuxer(
                    temporaryVideo.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            if (
                rotation != 0 &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
            ) {
                muxer.setOrientationHint(
                    normalizeRotation(rotation)
                )
            }

            val actualMuxer =
                muxer
                    ?: throw IllegalStateException(
                        "Unable to create video muxer."
                    )

            processFrames(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = actualMuxer,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = encodeWidth,
                outputHeight = encodeHeight,
                fps = fps,
                durationUs = durationUs,
                animeEngine = animeEngine,
                strength = strength,
                onProgress = onProgress
            )

            try {
                actualMuxer.stop()
            } finally {
                actualMuxer.release()
            }

            muxer = null

            if (!temporaryVideo.exists() ||
                temporaryVideo.length() <= 0L
            ) {
                throw IllegalStateException(
                    "Video encoder produced no output."
                )
            }

            onProgress(
                1,
                1,
                "Adding original audio..."
            )

            muxAudio(
                inputUri = inputUri,
                processedVideo = temporaryVideo,
                outputFile = outputFile
            )

            if (!outputFile.exists() ||
                outputFile.length() <= 0L
            ) {
                throw IllegalStateException(
                    "Final output video was not created."
                )
            }

            onProgress(
                1,
                1,
                "Complete"
            )

            Log.i(
                TAG,
                "Processing completed: ${outputFile.absolutePath}"
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Video processing failed",
                exception
            )

            try {
                muxer?.stop()
            } catch (_: Exception) {
            }

            throw exception

        } finally {

            try {
                muxer?.release()
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
                decoder?.stop()
            } catch (_: Exception) {
            }

            try {
                decoder?.release()
            } catch (_: Exception) {
            }

            try {
                extractor.release()
            } catch (_: Exception) {
            }

            if (temporaryVideo.exists()) {
                temporaryVideo.delete()
            }
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

        val encoderSink =
            EncoderSink(
                encoder = encoder,
                muxer = muxer
            )

        val decoderInfo =
            MediaCodec.BufferInfo()

        var extractorDone = false
        var decoderDone = false
        var encoderInputEnded = false

        var processedFrames = 0

        val totalFrames =
            estimateFrameCount(
                durationUs,
                fps
            )

        while (!encoderSink.isEndOfStream()) {

            if (!extractorDone) {

                val inputIndex =
                    decoder.dequeueInputBuffer(
                        TIMEOUT_US
                    )

                if (inputIndex >= 0) {

                    val inputBuffer =
                        decoder.getInputBuffer(
                            inputIndex
                        )
                            ?: throw IllegalStateException(
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

                        extractorDone = true

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

            var decoderOutputAvailable = true

            while (decoderOutputAvailable) {

                val outputIndex =
                    decoder.dequeueOutputBuffer(
                        decoderInfo,
                        0
                    )

                when {

                    outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        decoderOutputAvailable = false
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(
                            TAG,
                            "Decoder output format changed: " +
                                decoder.outputFormat
                        )
                    }

                    outputIndex >= 0 -> {

                        val decoderEos =
                            (
                                decoderInfo.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                ) != 0

                        if (decoderInfo.size > 0) {

                            onProgress(
                                processedFrames,
                                totalFrames,
                                "Decoding frame..."
                            )

                            val image =
                                decoder.getOutputImage(
                                    outputIndex
                                )

                                    ?: throw IllegalStateException(
                                        "Decoder did not provide an Image for output frame."
                                    )

                            val originalBitmap =
                                try {
                                    YuvConverter.imageToBitmap(
                                        image
                                    )
                                } finally {
                                    image.close()
                                }

                            try {

                                onProgress(
                                    processedFrames,
                                    totalFrames,
                                    "Applying anime effect..."
                                )

                                val preparedBitmap =
                                    if (
                                        originalBitmap.width !=
                                            outputWidth ||
                                        originalBitmap.height !=
                                            outputHeight
                                    ) {
                                        android.graphics.Bitmap
                                            .createScaledBitmap(
                                                originalBitmap,
                                                outputWidth,
                                                outputHeight,
                                                true
                                            )
                                    } else {
                                        originalBitmap
                                    }

                                val animeBitmap =
                                    try {
                                        animeEngine.processFrame(
                                            preparedBitmap
                                        )
                                    } finally {
                                        if (
                                            preparedBitmap !==
                                                originalBitmap &&
                                            !preparedBitmap.isRecycled
                                        ) {
                                            preparedBitmap.recycle()
                                        }
                                    }

                                try {

                                    val finalBitmap =
                                        blendFrames(
                                            originalBitmap =
                                                if (
                                                    originalBitmap.width ==
                                                        outputWidth &&
                                                    originalBitmap.height ==
                                                        outputHeight
                                                ) {
                                                    originalBitmap
                                                } else {
                                                    android.graphics.Bitmap
                                                        .createScaledBitmap(
                                                            originalBitmap,
                                                            outputWidth,
                                                            outputHeight,
                                                            true
                                                        )
                                                },
                                            animeBitmap =
                                                animeBitmap,
                                            strength = strength
                                        )

                                    try {

                                        onProgress(
                                            processedFrames,
                                            totalFrames,
                                            "Encoding frame..."
                                        )

                                        val yuvData =
                                            YuvConverter.bitmapToYuv420(
                                                finalBitmap
                                            )

                                        encoderSink.queueFrame(
                                            yuvData,
                                            decoderInfo.presentationTimeUs
                                                .coerceAtLeast(0L)
                                        )

                                    } finally {

                                        if (
                                            !finalBitmap.isRecycled &&
                                            finalBitmap !== originalBitmap
                                        ) {
                                            finalBitmap.recycle()
                                        }
                                    }

                                } finally {
                                    if (
                                        !animeBitmap.isRecycled
                                    ) {
                                        animeBitmap.recycle()
                                    }
                                }

                                processedFrames++

                                onProgress(
                                    processedFrames,
                                    totalFrames,
                                    "Encoding..."
                                )

                            } finally {

                                if (
                                    !originalBitmap.isRecycled
                                ) {
                                    originalBitmap.recycle()
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                        if (decoderEos) {
                            decoderDone = true
                            extractorDone = true
                            break
                        }
                    }
                }
            }

            if (
                decoderDone &&
                !encoderInputEnded
            ) {

                encoderSink.signalEndOfInput()

                encoderInputEnded = true
            }

            encoderSink.drain(
                0L
            )

            if (
                decoderDone &&
                encoderInputEnded
            ) {

                encoderSink.drain(
                    TIMEOUT_US
                )

                if (!encoderSink.isEndOfStream()) {
                    Thread.yield()
                }
            }
        }

        if (!encoderSink.isMuxerStarted()) {
            throw IllegalStateException(
                "Encoder never produced an output format."
            )
        }

        Log.i(
            TAG,
            "Processed frames=$processedFrames"
        )
    }

    private fun blendFrames(
        originalBitmap: android.graphics.Bitmap,
        animeBitmap: android.graphics.Bitmap,
        strength: Float
    ): android.graphics.Bitmap {

        val width =
            outputEvenDimension(
                originalBitmap.width
            )

        val height =
            outputEvenDimension(
                originalBitmap.height
            )

        val original =
            if (
                originalBitmap.width == width &&
                originalBitmap.height == height
            ) {
                originalBitmap
            } else {
                android.graphics.Bitmap.createScaledBitmap(
                    originalBitmap,
                    width,
                    height,
                    true
                )
            }

        val anime =
            if (
                animeBitmap.width == width &&
                animeBitmap.height == height
            ) {
                animeBitmap
            } else {
                android.graphics.Bitmap.createScaledBitmap(
                    animeBitmap,
                    width,
                    height,
                    true
                )
            }

        if (strength >= 0.999f) {

            if (anime !== animeBitmap) {
                animeBitmap.recycle()
            }

            if (original !== originalBitmap) {
                original.recycle()
            }

            return anime
        }

        if (strength <= 0.001f) {

            if (anime !== animeBitmap) {
                anime.recycle()
            }

            if (original !== originalBitmap) {
                originalBitmap.copy(
                    android.graphics.Bitmap.Config.ARGB_8888,
                    false
                ).also {
                    original.recycle()
                }
            }

            return original
        }

        val result =
            android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.ARGB_8888
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

        for (index in resultPixels.indices) {

            val a =
                originalPixels[index]

            val b =
                animePixels[index]

            val ar =
                android.graphics.Color.red(a)

            val ag =
                android.graphics.Color.green(a)

            val ab =
                android.graphics.Color.blue(a)

            val br =
                android.graphics.Color.red(b)

            val bg =
                android.graphics.Color.green(b)

            val bb =
                android.graphics.Color.blue(b)

            val r =
                (ar * inverse + br * strength)
                    .roundToInt()
                    .coerceIn(0, 255)

            val g =
                (ag * inverse + bg * strength)
                    .roundToInt()
                    .coerceIn(0, 255)

            val blue =
                (ab * inverse + bb * strength)
                    .roundToInt()
                    .coerceIn(0, 255)

            resultPixels[index] =
                android.graphics.Color.argb(
                    255,
                    r,
                    g,
                    blue
                )
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

        if (anime !== animeBitmap &&
            !anime.isRecycled
        ) {
            anime.recycle()
        }

        if (original !== originalBitmap &&
            !original.isRecycled
        ) {
            original.recycle()
        }

        return result
    }

    private fun muxAudio(
        inputUri: Uri,
        processedVideo: File,
        outputFile: File
    ) {

        val sourceExtractor =
            MediaExtractor()

        val videoExtractor =
            MediaExtractor()

        var muxer: MediaMuxer? = null

        try {

            setExtractorDataSource(
                sourceExtractor,
                inputUri
            )

            setExtractorDataSource(
                videoExtractor,
                Uri.fromFile(processedVideo)
            )

            val processedVideoTrack =
                findVideoTrack(
                    videoExtractor
                )

            if (processedVideoTrack < 0) {
                throw IllegalStateException(
                    "Processed video track not found."
                )
            }

            val audioTrack =
                findAudioTrack(
                    sourceExtractor
                )

            if (audioTrack < 0) {

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                if (!processedVideo.renameTo(outputFile)) {
                    processedVideo.copyTo(
                        outputFile,
                        overwrite = true
                    )
                    processedVideo.delete()
                }

                return
            }

            videoExtractor.selectTrack(
                processedVideoTrack
            )

            sourceExtractor.selectTrack(
                audioTrack
            )

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            val videoFormat =
                videoExtractor.getTrackFormat(
                    processedVideoTrack
                )

            val audioFormat =
                sourceExtractor.getTrackFormat(
                    audioTrack
                )

            val outputVideoTrack =
                muxer.addTrack(
                    videoFormat
                )

            val outputAudioTrack =
                muxer.addTrack(
                    audioFormat
                )

            val actualMuxer =
                muxer
                    ?: throw IllegalStateException(
                        "Unable to create final muxer."
                    )

            actualMuxer.start()

            copySamples(
                extractor = videoExtractor,
                muxer = actualMuxer,
                outputTrack = outputVideoTrack
            )

            copySamples(
                extractor = sourceExtractor,
                muxer = actualMuxer,
                outputTrack = outputAudioTrack
            )

            actualMuxer.stop()
            actualMuxer.release()

            muxer = null

        } finally {

            try {
                muxer?.stop()
            } catch (_: Exception) {
            }

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            try {
                sourceExtractor.release()
            } catch (_: Exception) {
            }

            try {
                videoExtractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int
    ) {

        val bufferSize =
            determineSampleBufferSize(
                extractor
            )

        val buffer =
            ByteBuffer.allocateDirect(
                bufferSize
            )

        val info =
            MediaCodec.BufferInfo()

        while (true) {

            buffer.clear()

            val sampleSize =
                extractor.readSampleData(
                    buffer,
                    0
                )

            if (sampleSize < 0) {
                break
            }

            val sampleTimeUs =
                extractor.sampleTime
                    .coerceAtLeast(0L)

            buffer.position(0)
            buffer.limit(
                sampleSize.coerceAtMost(
                    buffer.capacity()
                )
            )

            info.set(
                0,
                sampleSize.coerceAtMost(
                    buffer.capacity()
                ),
                sampleTimeUs,
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

    private fun determineSampleBufferSize(
        extractor: MediaExtractor
    ): Int {

        val trackIndex =
            findCurrentTrack(
                extractor
            )

        if (trackIndex >= 0) {

            val format =
                extractor.getTrackFormat(
                    trackIndex
                )

            if (
                format.containsKey(
                    MediaFormat.KEY_MAX_INPUT_SIZE
                )
            ) {

                val value =
                    format.getInteger(
                        MediaFormat.KEY_MAX_INPUT_SIZE
                    )

                if (value > 0) {
                    return max(
                        value,
                        1024 * 1024
                    )
                }
            }
        }

        return SAMPLE_BUFFER_SIZE
    }

    private fun findCurrentTrack(
        extractor: MediaExtractor
    ): Int {

        for (index in 0 until extractor.trackCount) {

            val format =
                extractor.getTrackFormat(index)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )
                    ?: continue

            if (
                mime.startsWith("video/") ||
                mime.startsWith("audio/")
            ) {
                return index
            }
        }

        return -1
    }

    private fun findVideoTrack(
        extractor: MediaExtractor
    ): Int {

        for (index in 0 until extractor.trackCount) {

            val format =
                extractor.getTrackFormat(index)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )
                    ?: continue

            if (mime.startsWith("video/")) {
                return index
            }
        }

        return -1
    }

    private fun findAudioTrack(
        extractor: MediaExtractor
    ): Int {

        for (index in 0 until extractor.trackCount) {

            val format =
                extractor.getTrackFormat(index)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )
                    ?: continue

            if (mime.startsWith("audio/")) {
                return index
            }
        }

        return -1
    }

    private fun findH264Encoder(): MediaCodecInfo {

        val codecList =
            MediaCodecList(
                MediaCodecList.REGULAR_CODECS
            )

        for (info in codecList.codecInfos) {

            if (!info.isEncoder) {
                continue
            }

            val types =
                info.supportedTypes

            if (
                types.any {
                    it.equals(
                        OUTPUT_MIME,
                        ignoreCase = true
                    )
                }
            ) {

                val capabilities =
                    try {
                        info.getCapabilitiesForType(
                            OUTPUT_MIME
                        )
                    } catch (_: Exception) {
                        null
                    }

                if (
                    capabilities != null &&
                    chooseColorFormat(
                        info,
                        OUTPUT_MIME
                    ) != null
                ) {
                    return info
                }
            }
        }

        throw IllegalStateException(
            "No H.264 encoder with COLOR_FormatYUV420SemiPlanar was found."
        )
    }

    private fun chooseColorFormat(
        info: MediaCodecInfo,
        mime: String
    ): Int? {

        val capabilities =
            try {
                info.getCapabilitiesForType(
                    mime
                )
            } catch (_: Exception) {
                return null
            }

        for (
            colorFormat
            in capabilities.colorFormats
        ) {

            if (
                colorFormat ==
                    MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatYUV420SemiPlanar
            ) {
                return colorFormat
            }
        }

        return null
    }

    private fun calculateBitRate(
        width: Int,
        height: Int,
        fps: Int
    ): Int {

        val pixelsPerSecond =
            width.toLong() *
                height.toLong() *
                fps.toLong()

        val calculated =
            (pixelsPerSecond * 0.12)
                .roundToInt()

        return calculated.coerceIn(
            MIN_BITRATE,
            MAX_BITRATE
        )
    }

    private fun estimateFrameCount(
        durationUs: Long,
        fps: Int
    ): Int {

        if (durationUs <= 0L) {
            return 1
        }

        val frames =
            (
                durationUs.toDouble() /
                    1_000_000.0 *
                    fps.toDouble()
                )
                .roundToInt()

        return frames.coerceAtLeast(1)
    }

    private fun makeEvenDimension(
        value: Int
    ): Int {

        return if (
            value % 2 == 0
        ) {
            value
        } else {
            value - 1
        }
    }

    private fun outputEvenDimension(
        value: Int
    ): Int {
        return makeEvenDimension(
            value.coerceAtLeast(2)
        )
    }

    private fun normalizeRotation(
        rotation: Int
    ): Int {

        var value =
            rotation % 360

        if (value < 0) {
            value += 360
        }

        return when (value) {
            90 -> 90
            180 -> 180
            270 -> 270
            else -> 0
        }
    }

    private fun setExtractorDataSource(
        extractor: MediaExtractor,
        uri: Uri
    ) {

        if (
            uri.scheme.equals(
                "file",
                ignoreCase = true
            )
        ) {

            val path =
                uri.path
                    ?: throw IllegalStateException(
                        "Invalid file URI."
                    )

            extractor.setDataSource(
                path
            )

        } else {

            val fileDescriptor =
                context.contentResolver.openFileDescriptor(
                    uri,
                    "r"
                )
                    ?: throw IllegalStateException(
                        "Unable to open selected video."
                    )

            fileDescriptor.use {
                extractor.setDataSource(
                    it.fileDescriptor
                )
            }
        }
    }

    private class EncoderSink(
        private val encoder: MediaCodec,
        private val muxer: MediaMuxer
    ) {

        private val bufferInfo =
            MediaCodec.BufferInfo()

        private var muxerStarted =
            false

        private var videoTrack =
            -1

        private var endOfStream =
            false

        fun queueFrame(
            data: ByteArray,
            presentationTimeUs: Long
        ) {

            var queued = false

            while (!queued) {

                val inputIndex =
                    encoder.dequeueInputBuffer(
                        TIMEOUT_US
                    )

                if (inputIndex >= 0) {

                    val inputBuffer =
                        encoder.getInputBuffer(
                            inputIndex
                        )
                            ?: throw IllegalStateException(
                                "Encoder input buffer unavailable."
                            )

                    if (
                        inputBuffer.capacity() <
                            data.size
                    ) {
                        throw IllegalStateException(
                            "Encoder input buffer too small: " +
                                "${inputBuffer.capacity()} < ${data.size}"
                        )
                    }

                    inputBuffer.clear()
                    inputBuffer.put(data)

                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        data.size,
                        presentationTimeUs,
                        0
                    )

                    queued = true

                } else {

                    drain(
                        TIMEOUT_US
                    )

                    if (endOfStream) {
                        throw IllegalStateException(
                            "Encoder ended before frame could be queued."
                        )
                    }
                }
            }

            drain(0L)
        }

        fun signalEndOfInput() {

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

                drain(
                    TIMEOUT_US
                )

                if (endOfStream) {
                    return
                }
            }
        }

        fun drain(
            timeoutUs: Long
        ) {

            var firstTimeout =
                timeoutUs

            while (!endOfStream) {

                val outputIndex =
                    encoder.dequeueOutputBuffer(
                        bufferInfo,
                        firstTimeout
                    )

                firstTimeout = 0L

                when {

                    outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        return
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                        if (muxerStarted) {
                            throw IllegalStateException(
                                "Encoder output format changed twice."
                            )
                        }

                        videoTrack =
                            muxer.addTrack(
                                encoder.outputFormat
                            )

                        muxer.start()

                        muxerStarted = true

                        Log.i(
                            TAG,
                            "Encoder output format: " +
                                encoder.outputFormat
                        )
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // Deprecated on newer Android versions.
                    }

                    outputIndex >= 0 -> {

                        val outputBuffer =
                            encoder.getOutputBuffer(
                                outputIndex
                            )

                        if (
                            outputBuffer != null &&
                            bufferInfo.size > 0 &&
                            muxerStarted &&
                            (
                                bufferInfo.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                ) == 0
                        ) {

                            val start =
                                bufferInfo.offset

                            val end =
                                bufferInfo.offset +
                                    bufferInfo.size

                            if (
                                start >= 0 &&
                                end <= outputBuffer.capacity()
                            ) {

                                outputBuffer.position(
                                    start
                                )

                                outputBuffer.limit(
                                    end
                                )

                                muxer.writeSampleData(
                                    videoTrack,
                                    outputBuffer,
                                    bufferInfo
                                )
                            }
                        }

                        val eos =
                            (
                                bufferInfo.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                ) != 0

                        encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                        if (eos) {
                            endOfStream = true
                            return
                        }
                    }
                }
            }
        }

        fun isEndOfStream(): Boolean =
            endOfStream

        fun isMuxerStarted(): Boolean =
            muxerStarted
    }
}
