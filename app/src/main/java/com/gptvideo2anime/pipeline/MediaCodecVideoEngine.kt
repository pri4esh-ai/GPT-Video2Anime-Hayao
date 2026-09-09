// FILE: app/src/main/java/com/gptvideo2anime/pipeline/MediaCodecVideoEngine.kt

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

    fun inspect(
        uri: Uri
    ): VideoInfo {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(
                context,
                uri,
                null
            )

            val trackIndex = findVideoTrack(extractor)

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
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
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
        onProgress: (
            currentFrame: Int,
            totalFrames: Int,
            stage: String
        ) -> Unit
    ) {
        require(strength in 0f..1f) {
            "Strength must be between 0 and 1."
        }

        val extractor = MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var muxerStarted = false
        var encoderTrack = -1

        try {
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
                extractor.getTrackFormat(videoTrack)

            val mime =
                videoFormat.getString(
                    MediaFormat.KEY_MIME
                ) ?: throw IllegalStateException(
                    "Video MIME type is unavailable."
                )

            val width =
                videoFormat.getInteger(
                    MediaFormat.KEY_WIDTH
                )

            val height =
                videoFormat.getInteger(
                    MediaFormat.KEY_HEIGHT
                )

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

            decoder =
                MediaCodec.createDecoderByType(
                    mime
                )

            decoder.configure(
                videoFormat,
                null,
                null,
                0
            )

            decoder.start()

            val encoderMime = "video/avc"

            val encoderInfo =
                findByteBufferEncoder(
                    encoderMime
                )

            val colorFormat =
                chooseColorFormat(
                    encoderInfo
                )

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    encoderMime,
                    width,
                    height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        colorFormat
                    )

                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        calculateBitRate(
                            width,
                            height,
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
                muxer.setOrientationHint(rotation)
            }

            processFrames(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                width = width,
                height = height,
                totalFrames = totalFrames,
                animeEngine = animeEngine,
                strength = strength,
                onProgress = onProgress,
                onEncoderTrackReady = { track ->
                    encoderTrack = track
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
                encoderTrackProvider = {
                    encoderTrack
                }
            )

            if (muxerStarted) {
                muxer.stop()
            }

            muxer.release()
            muxer = null

            if (!outputFile.exists()) {
                throw IllegalStateException(
                    "Encoded video was not created."
                )
            }

            if (outputFile.length() == 0L) {
                throw IllegalStateException(
                    "Encoded video is empty."
                )
            }

            val videoOnlyFile =
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
            }
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
        width: Int,
        height: Int,
        totalFrames: Int,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        onProgress: (
            currentFrame: Int,
            totalFrames: Int,
            stage: String
        ) -> Unit,
        onEncoderTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        startMuxer: () -> Unit,
        encoderTrackProvider: () -> Int
    ) {
        val decoderInfo =
            MediaCodec.BufferInfo()

        val encoderInfo =
            MediaCodec.BufferInfo()

        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderInputDone = false
        var encoderOutputDone = false

        var frameCount = 0

        while (!encoderOutputDone) {

            if (!decoderInputDone) {
                val inputIndex =
                    decoder.dequeueInputBuffer(
                        10_000
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
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )

                        decoderInputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            extractor.sampleFlags
                        )

                        extractor.advance()
                    }
                }
            }

            if (!decoderOutputDone) {
                val outputIndex =
                    decoder.dequeueOutputBuffer(
                        decoderInfo,
                        10_000
                    )

                when {
                    outputIndex >= 0 -> {
                        val outputImage =
                            decoder.getOutputImage(
                                outputIndex
                            )

                        if (
                            outputImage != null &&
                            decoderInfo.size > 0
                        ) {
                            val originalBitmap =
                                YuvConverter.imageToBitmap(
                                    outputImage
                                )

                            outputImage.close()

                            val processedBitmap =
                                createProcessedFrame(
                                    original = originalBitmap,
                                    animeEngine = animeEngine,
                                    strength = strength,
                                    width = width,
                                    height = height
                                )

                            val yuvData =
                                YuvConverter.bitmapToYuv420(
                                    processedBitmap
                                )

                            queueEncoderFrame(
                                encoder = encoder,
                                data = yuvData,
                                presentationTimeUs =
                                    decoderInfo.presentationTimeUs
                            )

                            if (
                                processedBitmap !==
                                    originalBitmap &&
                                !processedBitmap.isRecycled
                            ) {
                                processedBitmap.recycle()
                            }

                            if (
                                !originalBitmap.isRecycled
                            ) {
                                originalBitmap.recycle()
                            }

                            frameCount++

                            onProgress(
                                frameCount,
                                totalFrames,
                                "Processing video"
                            )
                        }

                        decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                        if (
                            decoderInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        ) {
                            decoderOutputDone = true
                        }
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    }

                    outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    }
                }
            }

            if (
                decoderOutputDone &&
                !encoderInputDone
            ) {
                signalEncoderEndOfStream(
                    encoder
                )

                encoderInputDone = true
            }

            while (true) {
                val encoderIndex =
                    encoder.dequeueOutputBuffer(
                        encoderInfo,
                        0
                    )

                when {
                    encoderIndex >= 0 -> {
                        val outputBuffer =
                            encoder.getOutputBuffer(
                                encoderIndex
                            )

                        if (
                            outputBuffer != null &&
                            encoderInfo.size > 0
                        ) {
                            if (
                                !isMuxerStarted()
                            ) {
                                throw IllegalStateException(
                                    "Encoder output appeared before muxer initialization."
                                )
                            }

                            outputBuffer.position(
                                encoderInfo.offset
                            )

                            outputBuffer.limit(
                                encoderInfo.offset +
                                    encoderInfo.size
                            )

                            muxer.writeSampleData(
                                encoderTrackProvider(),
                                outputBuffer,
                                encoderInfo
                            )
                        }

                        encoder.releaseOutputBuffer(
                            encoderIndex,
                            false
                        )

                        if (
                            encoderInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        ) {
                            encoderOutputDone = true
                            break
                        }
                    }

                    encoderIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (
                            encoderTrackProvider() < 0
                        ) {
                            val track =
                                muxer.addTrack(
                                    encoder.outputFormat
                                )

                            onEncoderTrackReady(
                                track
                            )

                            startMuxer()
                        }
                    }

                    encoderIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        break
                    }
                }
            }
        }
    }

    private fun createProcessedFrame(
        original: android.graphics.Bitmap,
        animeEngine: OnnxAnimeEngine,
        strength: Float,
        width: Int,
        height: Int
    ): android.graphics.Bitmap {
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
            animeEngine.infer(
                source
            )

        val result =
            blendBitmaps(
                original = source,
                anime = anime,
                strength = strength
            )

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

        return result
    }

    private fun blendBitmaps(
        original: android.graphics.Bitmap,
        anime: android.graphics.Bitmap,
        strength: Float
    ): android.graphics.Bitmap {
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

        for (
            index in resultPixels.indices
        ) {
            val originalPixel =
                originalPixels[index]

            val animePixel =
                animePixels[index]

            val originalRed =
                originalPixel shr 16 and 0xff

            val originalGreen =
                originalPixel shr 8 and 0xff

            val originalBlue =
                originalPixel and 0xff

            val animeRed =
                animePixel shr 16 and 0xff

            val animeGreen =
                animePixel shr 8 and 0xff

            val animeBlue =
                animePixel and 0xff

            val red =
                (
                    originalRed * inverse +
                        animeRed * strength
                )
                    .roundToInt()
                    .coerceIn(0, 255)

            val green =
                (
                    originalGreen * inverse +
                        animeGreen * strength
                )
                    .roundToInt()
                    .coerceIn(0, 255)

            val blue =
                (
                    originalBlue * inverse +
                        animeBlue * strength
                )
                    .roundToInt()
                    .coerceIn(0, 255)

            resultPixels[index] =
                (0xff shl 24) or
                    (red shl 16) or
                    (green shl 8) or
                    blue
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
        presentationTimeUs: Long
    ) {
        while (true) {
            val inputIndex =
                encoder.dequeueInputBuffer(
                    10_000
                )

            if (inputIndex >= 0) {
                val inputBuffer =
                    encoder.getInputBuffer(
                        inputIndex
                    ) ?: throw IllegalStateException(
                        "Encoder input buffer unavailable."
                    )

                if (
                    inputBuffer.capacity() <
                        data.size
                ) {
                    throw IllegalStateException(
                        "Encoder input buffer is too small."
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

                return
            }

            drainEncoderWithoutWriting(
                encoder
            )
        }
    }

    private fun drainEncoderWithoutWriting(
        encoder: MediaCodec
    ) {
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
                return
            }

            if (
                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {
                continue
            }

            if (outputIndex >= 0) {
                encoder.releaseOutputBuffer(
                    outputIndex,
                    false
                )
            } else {
                return
            }
        }
    }

    private fun signalEncoderEndOfStream(
        encoder: MediaCodec
    ) {
        while (true) {
            val inputIndex =
                encoder.dequeueInputBuffer(
                    10_000
                )

            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )

                return
            }

            drainEncoderWithoutWriting(
                encoder
            )
        }
    }

    private fun findByteBufferEncoder(
        mime: String
    ): MediaCodecInfo {
        val codecList =
            android.media.MediaCodecList(
                android.media.MediaCodecList.REGULAR_CODECS
            )

        return codecList.codecInfos
            .firstOrNull { info ->
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
                    info.getCapabilitiesForType(
                        mime
                    )

                capabilities.colorFormats.any {
                    it ==
                        MediaCodecInfo.CodecCapabilities
                            .COLOR_FormatYUV420SemiPlanar
                }
            }
            ?: throw IllegalStateException(
                "No H.264 encoder with YUV420 semi-planar input is available on this device."
            )
    }

    private fun chooseColorFormat(
        codecInfo: MediaCodecInfo
    ): Int {
        val capabilities =
            codecInfo.getCapabilitiesForType(
                "video/avc"
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
            "The available H.264 encoder does not support YUV420 semi-planar input."
        )
    }

    private fun muxAudio(
        inputUri: Uri,
        videoFile: File,
        outputFile: File
    ) {
        val extractor =
            MediaExtractor()

        val videoExtractor =
            MediaExtractor()

        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(
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
                    extractor
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
                        extractor.getTrackFormat(
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
                    extractor = extractor,
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

        val buffer =
            ByteBuffer.allocateDirect(
                1024 * 1024
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
            info.size = sampleSize
            info.presentationTimeUs =
                extractor.sampleTime
            info.flags =
                extractor.sampleFlags

            muxer.writeSampleData(
                outputTrack,
                buffer,
                info
            )

            extractor.advance()
        }

        extractor.unselectTrack(trackIndex)
    }

    private fun findVideoTrack(
        extractor: MediaExtractor
    ): Int {
        for (
            index in 0 until extractor.trackCount
        ) {
            val format =
                extractor.getTrackFormat(index)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )

            if (
                mime?.startsWith(
                    "video/"
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
            val format =
                extractor.getTrackFormat(index)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                )

            if (
                mime?.startsWith(
                    "audio/"
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
            durationUs
                .toDouble()
                .div(1_000_000.0)
                .times(frameRate)
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
            width.toLong()
                .times(height)
                .times(frameRate)

        return (
            pixelsPerSecond
                .times(0.12)
                .roundToInt()
        ).coerceIn(
            2_000_000,
            20_000_000
        )
    }
}
