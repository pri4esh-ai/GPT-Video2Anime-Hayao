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

        private const val MODEL_SIZE = 512

        private const val MIN_BITRATE = 2_000_000

        private const val MAX_BITRATE = 20_000_000

        private const val SAMPLE_BUFFER_SIZE =
            8 * 1024 * 1024
    }

    fun inspect(uri: Uri): VideoInfo {

        val extractor = MediaExtractor()

        try {

            setExtractorDataSource(
                extractor,
                uri
            )

            val track =
                findVideoTrack(extractor)

            require(track >= 0) {
                "No video track found."
            }

            val format =
                extractor.getTrackFormat(track)

            return VideoInfo(
                mimeType =
                    format.getString(
                        MediaFormat.KEY_MIME
                    ) ?: "video/unknown",

                width =
                    format.getInteger(
                        MediaFormat.KEY_WIDTH
                    ),

                height =
                    format.getInteger(
                        MediaFormat.KEY_HEIGHT
                    ),

                durationUs =
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
                    },

                frameRate =
                    if (
                        format.containsKey(
                            MediaFormat.KEY_FRAME_RATE
                        )
                    ) {
                        format.getInteger(
                            MediaFormat.KEY_FRAME_RATE
                        )
                    } else {
                        DEFAULT_FPS
                    },

                rotation =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.M &&
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

        val extractor =
            MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        val tempVideo =
            File(
                outputFile.parentFile,
                "${outputFile.nameWithoutExtension}_video.mp4"
            )

        try {

            outputFile.parentFile?.mkdirs()

            if (tempVideo.exists()) {
                tempVideo.delete()
            }

            setExtractorDataSource(
                extractor,
                inputUri
            )

            val videoTrack =
                findVideoTrack(extractor)

            require(videoTrack >= 0) {
                "Video track missing."
            }

            extractor.selectTrack(videoTrack)

            val format =
                extractor.getTrackFormat(videoTrack)

            val inputMime =
                format.getString(
                    MediaFormat.KEY_MIME
                )
                    ?: throw IllegalStateException(
                        "Video MIME type missing."
                    )

            val originalWidth =
                format.getInteger(
                    MediaFormat.KEY_WIDTH
                )

            val originalHeight =
                format.getInteger(
                    MediaFormat.KEY_HEIGHT
                )

            val sourceWidth =
                makeEvenDimension(
                    originalWidth
                )

            val sourceHeight =
                makeEvenDimension(
                    originalHeight
                )

            require(
                sourceWidth >= 2 &&
                    sourceHeight >= 2
            ) {
                "Invalid video dimensions: " +
                    "${originalWidth}x$originalHeight"
            }

            val fps =
                if (
                    format.containsKey(
                        MediaFormat.KEY_FRAME_RATE
                    )
                ) {
                    format.getInteger(
                        MediaFormat.KEY_FRAME_RATE
                    ).coerceIn(
                        1,
                        120
                    )
                } else {
                    DEFAULT_FPS
                }

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

            val rotation =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M &&
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

            Log.i(
                TAG,
                "Input video: " +
                    "${sourceWidth}x${sourceHeight}, " +
                    "fps=$fps, " +
                    "durationUs=$durationUs, " +
                    "rotation=$rotation"
            )

            /*
             * Decoder
             */

            decoder =
                MediaCodec.createDecoderByType(
                    inputMime
                )

            decoder.configure(
                format,
                null,
                null,
                0
            )

            decoder.start()

            /*
             * Encoder
             */

            val encoderInfo =
                findH264Encoder()

            val colorFormat =
                chooseColorFormat(
                    encoderInfo,
                    OUTPUT_MIME
                )
                    ?: throw IllegalStateException(
                        "No compatible YUV420 encoder color format."
                    )

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    OUTPUT_MIME,
                    sourceWidth,
                    sourceHeight
                ).apply {

                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        colorFormat
                    )

                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        calculateBitRate(
                            sourceWidth,
                            sourceHeight,
                            fps
                        )
                    )

                    setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        fps
                    )

                    setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL,
                        2
                    )

                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.M
                    ) {
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

            /*
             * Temporary video muxer.
             */

            muxer =
                MediaMuxer(
                    tempVideo.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            if (rotation != 0) {

                muxer.setOrientationHint(
                    normalizeRotation(rotation)
                )
            }

            processFrames(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                fps = fps,
                durationUs = durationUs,
                animeEngine = animeEngine,
                strength = strength,
                onProgress = onProgress
            )

            /*
             * processFrames starts/stops the muxer
             * through EncoderSink.
             */
            muxer.release()
            muxer = null

            /*
             * Decoder and encoder are no longer needed.
             */

            try {
                decoder.stop()
            } catch (_: Exception) {
            }

            try {
                decoder.release()
            } catch (_: Exception) {
            }

            decoder = null

            try {
                encoder.stop()
            } catch (_: Exception) {
            }

            try {
                encoder.release()
            } catch (_: Exception) {
            }

            encoder = null

            /*
             * Add original audio back.
             */

            onProgress(
                100,
                100,
                "Adding audio..."
            )

            muxAudio(
                inputUri = inputUri,
                processedVideo = tempVideo,
                outputFile = outputFile
            )

            if (!outputFile.exists()) {
                throw IllegalStateException(
                    "Final output video was not created."
                )
            }

            Log.i(
                TAG,
                "Processing completed: " +
                    outputFile.absolutePath
            )

            onProgress(
                100,
                100,
                "Complete"
            )

        } finally {

            try {
                extractor.release()
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
                encoder?.stop()
            } catch (_: Exception) {
            }

            try {
                encoder?.release()
            } catch (_: Exception) {
            }

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            /*
             * If processing failed, remove temporary video.
             */

            if (tempVideo.exists()) {
                try {
                    tempVideo.delete()
                } catch (_: Exception) {
                }
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

        while (
            !encoderSink.isEndOfStream()
        ) {

            /*
             * Feed compressed data into decoder.
             */

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

                        val sampleTime =
                            extractor.sampleTime
                                .coerceAtLeast(0L)

                        val sampleFlags =
                            extractor.sampleFlags

                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            sampleTime,
                            sampleFlags
                        )

                        extractor.advance()
                    }
                }
            }

            /*
             * Drain decoder.
             */

            var decoderOutputAvailable = true

            while (
                decoderOutputAvailable
            ) {

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

                            val image =
                                decoder.getOutputImage(
                                    outputIndex
                                )

                            if (image != null) {

                                try {

                                    val originalBitmap =
                                        YuvConverter.imageToBitmap(
                                            image
                                        )

                                    try {

                                        onProgress(
                                            processedFrames,
                                            totalFrames,
                                            "Anime..."
                                        )

                                        /*
                                         * Make sure dimensions match
                                         * encoder dimensions.
                                         */

                                        val frameBitmap =
                                            if (
                                                originalBitmap.width ==
                                                    sourceWidth &&
                                                originalBitmap.height ==
                                                    sourceHeight
                                            ) {
                                                originalBitmap
                                            } else {

                                                Bitmap.createScaledBitmap(
                                                    originalBitmap,
                                                    sourceWidth,
                                                    sourceHeight,
                                                    true
                                                )
                                            }

                                        try {

                                            val animeBitmap =
                                                animeEngine.processFrame(
                                                    frameBitmap
                                                )

                                            try {

                                                val finalBitmap =
                                                    blendFrames(
                                                        originalBitmap =
                                                            frameBitmap,
                                                        animeBitmap =
                                                            animeBitmap,
                                                        strength =
                                                            strength
                                                    )

                                                try {

                                                    val yuv =
                                                        YuvConverter
                                                            .bitmapToYuv420(
                                                                finalBitmap
                                                            )

                                                    encoderSink.queueFrame(
                                                        data = yuv,
                                                        pts =
                                                            decoderInfo
                                                                .presentationTimeUs
                                                                .coerceAtLeast(
                                                                    0L
                                                                )
                                                    )

                                                } finally {

                                                    if (
                                                        finalBitmap !==
                                                            frameBitmap &&
                                                        finalBitmap !==
                                                            originalBitmap &&
                                                        !finalBitmap.isRecycled
                                                    ) {
                                                        finalBitmap.recycle()
                                                    }
                                                }

                                            } finally {

                                                if (
                                                    !animeBitmap
                                                        .isRecycled
                                                ) {
                                                    animeBitmap.recycle()
                                                }
                                            }

                                        } finally {

                                            if (
                                                frameBitmap !==
                                                    originalBitmap &&
                                                !frameBitmap.isRecycled
                                            ) {
                                                frameBitmap.recycle()
                                            }
                                        }

                                        processedFrames++

                                        val progress =
                                            if (
                                                totalFrames > 0
                                            ) {
                                                (
                                                    processedFrames
                                                        .toFloat() /
                                                        totalFrames
                                                            .toFloat() *
                                                        100f
                                                    )
                                                    .roundToInt()
                                                    .coerceIn(
                                                        0,
                                                        99
                                                    )
                                            } else {
                                                0
                                            }

                                        onProgress(
                                            progress,
                                            100,
                                            "Encoding..."
                                        )

                                    } finally {

                                        if (
                                            !originalBitmap
                                                .isRecycled
                                        ) {
                                            originalBitmap.recycle()
                                        }
                                    }

                                } finally {

                                    try {
                                        image.close()
                                    } catch (_: Exception) {
                                    }
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

                            decoderOutputAvailable =
                                false
                        }
                    }
                }
            }

            /*
             * Tell encoder that no more frames are coming.
             */

            if (
                decoderDone &&
                !encoderInputEnded
            ) {

                encoderSink.signalEndOfInput()

                encoderInputEnded = true
            }

            /*
             * Drain encoded frames.
             */

            encoderSink.drain(0L)

            if (
                decoderDone &&
                encoderInputEnded
            ) {

                encoderSink.drain(
                    TIMEOUT_US
                )
            }
        }

        if (
            !encoderSink.isMuxerStarted()
        ) {

            throw IllegalStateException(
                "Encoder never produced output format."
            )
        }

        Log.i(
            TAG,
            "Processed frames=$processedFrames"
        )
    }

    private fun blendFrames(
        originalBitmap: Bitmap,
        animeBitmap: Bitmap,
        strength: Float
    ): Bitmap {

        val width =
            originalBitmap.width

        val height =
            originalBitmap.height

        val anime =
            if (
                animeBitmap.width == width &&
                animeBitmap.height == height
            ) {
                animeBitmap
            } else {

                Bitmap.createScaledBitmap(
                    animeBitmap,
                    width,
                    height,
                    true
                )
            }

        /*
         * Full anime.
         */

        if (strength >= 0.999f) {

            return if (
                anime === animeBitmap
            ) {

                anime.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            } else {

                anime
            }
        }

        /*
         * Original frame.
         */

        if (strength <= 0.001f) {

            if (
                anime !== animeBitmap &&
                !anime.isRecycled
            ) {
                anime.recycle()
            }

            return originalBitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )
        }

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

        originalBitmap.getPixels(
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

        val alpha =
            strength.coerceIn(
                0f,
                1f
            )

        val inverse =
            1f - alpha

        for (
            i in originalPixels.indices
        ) {

            val original =
                originalPixels[i]

            val animePixel =
                animePixels[i]

            val r =
                (
                    ((original shr 16) and 255) *
                        inverse +
                        ((animePixel shr 16) and 255) *
                        alpha
                    )
                    .roundToInt()
                    .coerceIn(0, 255)

            val g =
                (
                    ((original shr 8) and 255) *
                        inverse +
                        ((animePixel shr 8) and 255) *
                        alpha
                    )
                    .roundToInt()
                    .coerceIn(0, 255)

            val b =
                (
                    (original and 255) *
                        inverse +
                        (animePixel and 255) *
                        alpha
                    )
                    .roundToInt()
                    .coerceIn(0, 255)

            originalPixels[i] =
                (255 shl 24) or
                    (r shl 16) or
                    (g shl 8) or
                    b
        }

        result.setPixels(
            originalPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        if (
            anime !== animeBitmap &&
            !anime.isRecycled
        ) {
            anime.recycle()
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

            videoExtractor.setDataSource(
                processedVideo.absolutePath
            )

            val videoTrack =
                findVideoTrack(
                    videoExtractor
                )

            val audioTrack =
                findAudioTrack(
                    sourceExtractor
                )

            require(videoTrack >= 0) {
                "Processed video track missing."
            }

            /*
             * No audio: just use processed video.
             */

            if (audioTrack < 0) {

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                processedVideo.copyTo(
                    outputFile,
                    overwrite = true
                )

                processedVideo.delete()

                return
            }

            videoExtractor.selectTrack(
                videoTrack
            )

            sourceExtractor.selectTrack(
                audioTrack
            )

            if (outputFile.exists()) {
                outputFile.delete()
            }

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
                muxer.addTrack(
                    sourceExtractor.getTrackFormat(
                        audioTrack
                    )
                )

            muxer.start()

            copySamples(
                extractor = videoExtractor,
                muxer = muxer,
                outputTrack = outputVideoTrack
            )

            copySamples(
                extractor = sourceExtractor,
                muxer = muxer,
                outputTrack = outputAudioTrack
            )

            muxer.stop()

            processedVideo.delete()

        } finally {

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

            info.offset = 0

            info.size =
                sampleSize

            info.presentationTimeUs =
                extractor.sampleTime
                    .coerceAtLeast(0L)

            info.flags =
                extractor.sampleFlags

            buffer.position(0)

            buffer.limit(
                sampleSize
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

                val size =
                    format.getInteger(
                        MediaFormat.KEY_MAX_INPUT_SIZE
                    )

                if (size > 0) {

                    return max(
                        size,
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

        /*
         * MediaExtractor.currentTrack is not
         * publicly exposed, so determine a suitable
         * selected track from the available tracks.
         */

        for (
            i in 0 until extractor.trackCount
        ) {

            val format =
                extractor.getTrackFormat(i)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )
                    ?: continue

            if (
                mime.startsWith("video/") ||
                mime.startsWith("audio/")
            ) {
                return i
            }
        }

        return -1
    }

    private fun findVideoTrack(
        extractor: MediaExtractor
    ): Int {

        for (
            i in 0 until extractor.trackCount
        ) {

            val mime =
                extractor
                    .getTrackFormat(i)
                    .getString(
                        MediaFormat.KEY_MIME
                    )
                    ?: continue

            if (
                mime.startsWith("video/")
            ) {
                return i
            }
        }

        return -1
    }

    private fun findAudioTrack(
        extractor: MediaExtractor
    ): Int {

        for (
            i in 0 until extractor.trackCount
        ) {

            val mime =
                extractor
                    .getTrackFormat(i)
                    .getString(
                        MediaFormat.KEY_MIME
                    )
                    ?: continue

            if (
                mime.startsWith("audio/")
            ) {
                return i
            }
        }

        return -1
    }

    private fun findH264Encoder():
        MediaCodecInfo {

        val codecList =
            MediaCodecList(
                MediaCodecList.REGULAR_CODECS
            )

        for (
            info in codecList.codecInfos
        ) {

            if (!info.isEncoder) {
                continue
            }

            if (
                info.supportedTypes.any {
                    it.equals(
                        OUTPUT_MIME,
                        ignoreCase = true
                    )
                }
            ) {

                return info
            }
        }

        throw IllegalStateException(
            "H.264 encoder not found."
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

        /*
         * Flexible YUV is preferred because it is
         * the most general Android representation.
         */

        val preferred =
            intArrayOf(
                MediaCodecInfo
                    .CodecCapabilities
                    .COLOR_FormatYUV420Flexible,

                MediaCodecInfo
                    .CodecCapabilities
                    .COLOR_FormatYUV420SemiPlanar,

                MediaCodecInfo
                    .CodecCapabilities
                    .COLOR_FormatYUV420Planar
            )

        for (
            wanted in preferred
        ) {

            if (
                capabilities.colorFormats
                    .contains(wanted)
            ) {

                return wanted
            }
        }

        return null
    }

    private fun calculateBitRate(
        width: Int,
        height: Int,
        fps: Int
    ): Int {

        val bits =
            width.toLong() *
                height.toLong() *
                fps.toLong() *
                8L /
                50L

        return bits
            .coerceIn(
                MIN_BITRATE.toLong(),
                MAX_BITRATE.toLong()
            )
            .toInt()
    }

    private fun estimateFrameCount(
        durationUs: Long,
        fps: Int
    ): Int {

        if (
            durationUs <= 0L ||
            fps <= 0
        ) {
            return 1
        }

        return (
            durationUs
                .toDouble() /
                1_000_000.0 *
                fps.toDouble()
            )
            .roundToInt()
            .coerceAtLeast(1)
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

        var normalized =
            rotation % 360

        if (normalized < 0) {
            normalized += 360
        }

        return when (normalized) {

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

            extractor.setDataSource(
                uri.path
                    ?: throw IllegalStateException(
                        "Invalid file URI."
                    )
            )

            return
        }

        context.contentResolver
            .openFileDescriptor(
                uri,
                "r"
            )
            ?.use { descriptor ->

                extractor.setDataSource(
                    descriptor.fileDescriptor
                )

            }
            ?: throw IllegalStateException(
                "Cannot open selected video."
            )
    }

    private class EncoderSink(
        private val encoder: MediaCodec,
        private val muxer: MediaMuxer
    ) {

        companion object {
            private const val TIMEOUT_US = 10_000L
        }

        private val info =
            MediaCodec.BufferInfo()

        private var started =
            false

        private var track =
            -1

        private var eos =
            false

        fun queueFrame(
            data: ByteArray,
            pts: Long
        ) {

            require(data.isNotEmpty()) {
                "Encoder frame data is empty."
            }

            while (true) {

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

                    inputBuffer.clear()

                    if (
                        data.size >
                        inputBuffer.remaining()
                    ) {

                        throw IllegalStateException(
                            "Encoded frame input is too large. " +
                                "frame=${data.size}, " +
                                "buffer=${inputBuffer.remaining()}"
                        )
                    }

                    inputBuffer.put(data)

                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        data.size,
                        pts.coerceAtLeast(0L),
                        0
                    )

                    break
                }

                drain(0L)

                if (eos) {

                    throw IllegalStateException(
                        "Encoder reached EOS while queueing frame."
                    )
                }
            }
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

                drain(0L)

                if (eos) {
                    return
                }
            }
        }

        fun drain(
            timeoutUs: Long
        ) {

            while (!eos) {

                val outputIndex =
                    encoder.dequeueOutputBuffer(
                        info,
                        timeoutUs
                    )

                when {

                    outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {

                        return
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                        if (!started) {

                            track =
                                muxer.addTrack(
                                    encoder.outputFormat
                                )

                            muxer.start()

                            started = true

                            Log.i(
                                TAG,
                                "Encoder format: " +
                                    encoder.outputFormat
                            )
                        }
                    }

                    outputIndex >= 0 -> {

                        val outputBuffer =
                            encoder.getOutputBuffer(
                                outputIndex
                            )

                        if (
                            outputBuffer != null &&
                            started &&
                            info.size > 0 &&
                            (
                                info.flags and
                                    MediaCodec
                                        .BUFFER_FLAG_CODEC_CONFIG
                                ) == 0
                        ) {

                            val offset =
                                info.offset

                            val limit =
                                info.offset +
                                    info.size

                            if (
                                offset >= 0 &&
                                limit <=
                                    outputBuffer.capacity()
                            ) {

                                outputBuffer.position(
                                    offset
                                )

                                outputBuffer.limit(
                                    limit
                                )

                                muxer.writeSampleData(
                                    track,
                                    outputBuffer,
                                    info
                                )
                            }
                        }

                        val outputEos =
                            (
                                info.flags and
                                    MediaCodec
                                        .BUFFER_FLAG_END_OF_STREAM
                                ) != 0

                        encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                        if (outputEos) {
                            eos = true
                        }
                    }
                }
            }
        }

        fun isEndOfStream(): Boolean =
            eos

        fun isMuxerStarted(): Boolean =
            started
    }
}
