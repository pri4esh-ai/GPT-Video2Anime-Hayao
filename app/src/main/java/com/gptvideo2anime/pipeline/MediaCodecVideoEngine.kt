// FILE: app/src/main/java/com/gptvideo2anime/pipeline/MediaCodecVideoEngine.kt

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

    fun inspect(
        uri: Uri
    ): VideoInfo {
        val extractor =
            MediaExtractor()

        try {
            extractor.setDataSource(
                context,
                uri,
                null
            )

            val trackIndex =
                findVideoTrack(extractor)

            if (trackIndex < 0) {
                throw IllegalStateException(
                    "The selected file does not contain a video track."
                )
            }

            extractor.selectTrack(trackIndex)

            val format =
                extractor.getTrackFormat(trackIndex)

            val mime =
                format.getString(
                    MediaFormat.KEY_MIME
                ) ?: throw IllegalStateException(
                    "Video MIME type is unavailable."
                )

            val duration =
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
                }

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
                durationUs = duration,
                width = width,
                height = height,
                frameRate = frameRate.coerceAtLeast(1),
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

        val inputExtractor =
            MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            inputExtractor.setDataSource(
                context,
                inputUri,
                null
            )

            val videoTrack =
                findVideoTrack(inputExtractor)

            if (videoTrack < 0) {
                throw IllegalStateException(
                    "No video track found."
                )
            }

            inputExtractor.selectTrack(videoTrack)

            val videoFormat =
                inputExtractor.getTrackFormat(videoTrack)

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
                }

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
                MediaCodec.createDecoderByType(mime)

            decoder.configure(
                videoFormat,
                null,
                null,
                0
            )

            decoder.start()

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    "video/avc",
                    width,
                    height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities
                            .COLOR_FormatYUV420Flexible
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
                MediaCodec.createEncoderByType(
                    "video/avc"
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
                extractor = inputExtractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                width = width,
                height = height,
                totalFrames = totalFrames,
                animeEngine = animeEngine,
                strength = strength,
                onProgress = onProgress
            )

            muxer.stop()
            muxer.release()
            muxer = null

            copyAudioTrack(
                inputUri = inputUri,
                outputVideo = outputFile
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
                muxer?.release()
            } catch (_: Exception) {
            }

            inputExtractor.release()
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
        ) -> Unit
    ) {
        val decoderInfo =
            MediaCodec.BufferInfo()

        val encoderInfo =
            MediaCodec.BufferInfo()

        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var frameCount = 0

        var encoderTrack =
            -1

        while (!encoderDone) {

            if (!inputDone) {
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

                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )

                        extractor.advance()
                    }
                }
            }

            if (!decoderDone) {
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

                        if (outputImage != null) {
                            val bitmap =
                                YuvConverter.imageToBitmap(
                                    outputImage
                                )

                            val animeBitmap =
                                animeEngine.infer(
                                    bitmap
                                )

                            val blended =
                                blendBitmaps(
                                    original = bitmap,
                                    anime = animeBitmap,
                                    strength = strength
                                )

                            val yuv =
                                YuvConverter.bitmapToYuv420(
                                    blended
                                )

                            queueEncoderFrame(
                                encoder = encoder,
                                data = yuv,
                                presentationTimeUs =
                                    decoderInfo.presentationTimeUs,
                                bufferInfo = encoderInfo,
                                muxer = muxer,
                                encoderTrackProvider = {
                                    encoderTrack
                                },
                                encoderTrackSetter = {
                                    encoderTrack = it
                                }
                            )

                            bitmap.recycle()

                            if (
                                animeBitmap !== bitmap &&
                                !animeBitmap.isRecycled
                            ) {
                                animeBitmap.recycle()
                            }

                            if (
                                blended !== bitmap &&
                                !blended.isRecycled
                            ) {
                                blended.recycle()
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
                            decoderDone = true

                            signalEncoderEndOfStream(
                                encoder = encoder,
                                bufferInfo = encoderInfo,
                                muxer = muxer,
                                encoderTrackProvider = {
                                    encoderTrack
                                },
                                encoderTrackSetter = {
                                    encoderTrack = it
                                }
                            )
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

            drainEncoder(
                encoder = encoder,
                bufferInfo = encoderInfo,
                muxer = muxer,
                encoderTrackProvider = {
                    encoderTrack
                },
                encoderTrackSetter = {
                    encoderTrack = it
                }
            )

            if (
                encoderTrack >= 0 &&
                encoderOutputEnded(
                    encoder,
                    encoderInfo
                )
            ) {
                encoderDone = true
            }
        }
    }

    private fun queueEncoderFrame(
        encoder: MediaCodec,
        data: ByteArray,
        presentationTimeUs: Long,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        encoderTrackProvider: () -> Int,
        encoderTrackSetter: (Int) -> Unit
    ) {
        var queued = false

        while (!queued) {
            val inputIndex =
                encoder.dequeueInputBuffer(
                    10_000
                )

            if (inputIndex < 0) {
                drainEncoder(
                    encoder = encoder,
                    bufferInfo = bufferInfo,
                    muxer = muxer,
                    encoderTrackProvider =
                        encoderTrackProvider,
                    encoderTrackSetter =
                        encoderTrackSetter
                )

                continue
            }

            val inputBuffer =
                encoder.getInputBuffer(
                    inputIndex
                ) ?: throw IllegalStateException(
                    "Encoder input buffer unavailable."
                )

            if (inputBuffer.capacity() < data.size) {
                throw IllegalStateException(
                    "Encoder input buffer is too small: " +
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
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        encoderTrackProvider: () -> Int,
        encoderTrackSetter: (Int) -> Unit
    ) {
        while (true) {
            val outputIndex =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    0
                )

            when {
                outputIndex >= 0 -> {
                    val outputBuffer =
                        encoder.getOutputBuffer(
                            outputIndex
                        )

                    if (
                        outputBuffer != null &&
                        bufferInfo.size > 0
                    ) {
                        val track =
                            encoderTrackProvider()

                        if (track < 0) {
                            val newTrack =
                                muxer.addTrack(
                                    encoder.outputFormat
                                )

                            encoderTrackSetter(
                                newTrack
                            )

                            muxer.start()
                        }

                        outputBuffer.position(
                            bufferInfo.offset
                        )

                        outputBuffer.limit(
                            bufferInfo.offset +
                                bufferInfo.size
                        )

                        muxer.writeSampleData(
                            encoderTrackProvider(),
                            outputBuffer,
                            bufferInfo
                        )
                    }

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (
                        bufferInfo.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ) {
                        return
                    }
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (
                        encoderTrackProvider() < 0
                    ) {
                        val track =
                            muxer.addTrack(
                                encoder.outputFormat
                            )

                        encoderTrackSetter(track)

                        if (!isMuxerStarted(muxer)) {
                            muxer.start()
                        }
                    }
                }

                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return
                }
            }
        }
    }

    private fun signalEncoderEndOfStream(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        encoderTrackProvider: () -> Int,
        encoderTrackSetter: (Int) -> Unit
    ) {
        var sent = false

        while (!sent) {
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

                sent = true
            } else {
                drainEncoder(
                    encoder = encoder,
                    bufferInfo = bufferInfo,
                    muxer = muxer,
                    encoderTrackProvider =
                        encoderTrackProvider,
                    encoderTrackSetter =
                        encoderTrackSetter
                )
            }
        }

        while (true) {
            val outputIndex =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    10_000
                )

            if (outputIndex < 0) {
                if (
                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {
                    if (
                        encoderTrackProvider() < 0
                    ) {
                        val track =
                            muxer.addTrack(
                                encoder.outputFormat
                            )

                        encoderTrackSetter(track)

                        if (!isMuxerStarted(muxer)) {
                            muxer.start()
                        }
                    }
                }

                continue
            }

            val outputBuffer =
                encoder.getOutputBuffer(
                    outputIndex
                )

            if (
                outputBuffer != null &&
                bufferInfo.size > 0 &&
                encoderTrackProvider() >= 0
            ) {
                outputBuffer.position(
                    bufferInfo.offset
                )

                outputBuffer.limit(
                    bufferInfo.offset +
                        bufferInfo.size
                )

                muxer.writeSampleData(
                    encoderTrackProvider(),
                    outputBuffer,
                    bufferInfo
                )
            }

            encoder.releaseOutputBuffer(
                outputIndex,
                false
            )

            if (
                bufferInfo.flags and
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            ) {
                break
            }
        }
    }

    private fun encoderOutputEnded(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo
    ): Boolean {
        return bufferInfo.flags and
            MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
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
                mime?.startsWith("video/") == true
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
                .times(frameRate.toLong())

        return (
            pixelsPerSecond
                .times(0.12)
                .roundToInt()
        )
            .coerceIn(
                2_000_000,
                20_000_000
            )
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

        val output =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val originalPixels =
            IntArray(width * height)

        val animePixels =
            IntArray(width * height)

        val outputPixels =
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
            index in outputPixels.indices
        ) {
            val originalPixel =
                originalPixels[index]

            val animePixel =
                animePixels[index]

            val originalRed =
                (originalPixel shr 16) and 0xff

            val originalGreen =
                (originalPixel shr 8) and 0xff

            val originalBlue =
                originalPixel and 0xff

            val animeRed =
                (animePixel shr 16) and 0xff

            val animeGreen =
                (animePixel shr 8) and 0xff

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

            outputPixels[index] =
                (0xff shl 24) or
                    (red shl 16) or
                    (green shl 8) or
                    blue
        }

        output.setPixels(
            outputPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return output
    }

    private fun copyAudioTrack(
        inputUri: Uri,
        outputVideo: File
    ) {
        val temporaryVideo =
            File(
                outputVideo.parentFile,
                "${outputVideo.nameWithoutExtension}_video.mp4"
            )

        if (!outputVideo.renameTo(temporaryVideo)) {
            throw IllegalStateException(
                "Unable to prepare the processed video for audio muxing."
            )
        }

        val extractor =
            MediaExtractor()

        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(
                context,
                inputUri,
                null
            )

            val videoExtractor =
                MediaExtractor()

            videoExtractor.setDataSource(
                temporaryVideo.absolutePath
            )

            val videoTrack =
                findVideoTrack(videoExtractor)

            val audioTrack =
                findAudioTrack(extractor)

            if (videoTrack < 0) {
                throw IllegalStateException(
                    "Processed video track is missing."
                )
            }

            muxer =
                MediaMuxer(
                    outputVideo.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            val outputVideoTrack =
                muxer.addTrack(
                    videoExtractor.getTrackFormat(
                        videoTrack
                    )
                )

            var outputAudioTrack =
                -1

            if (audioTrack >= 0) {
                outputAudioTrack =
                    muxer.addTrack(
                        extractor.getTrackFormat(
                            audioTrack
                        )
                    )
            }

            muxer.start()

            copySamples(
                extractor = videoExtractor,
                trackIndex = videoTrack,
                muxer = muxer,
                outputTrack = outputVideoTrack
            )

            if (audioTrack >= 0) {
                copySamples(
                    extractor = extractor,
                    trackIndex = audioTrack,
                    muxer = muxer,
                    outputTrack = outputAudioTrack
                )
            }

            muxer.stop()
            muxer.release()
            muxer = null
        } finally {
            videoExtractor.release()
            extractor.release()

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            temporaryVideo.delete()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        outputTrack: Int
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
                mime?.startsWith("audio/") == true
            ) {
                return index
            }
        }

        return -1
    }

    private fun isMuxerStarted(
        muxer: MediaMuxer
    ): Boolean {
        return try {
            muxer.toString().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
