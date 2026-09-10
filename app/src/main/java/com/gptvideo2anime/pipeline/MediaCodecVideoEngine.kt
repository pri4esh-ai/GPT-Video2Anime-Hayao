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
            extractor.setDataSource(context, inputUri, null)

            val videoTrack = findVideoTrack(extractor)

            if (videoTrack < 0) {
                throw IllegalStateException("No video track found.")
            }

            extractor.selectTrack(videoTrack)

            val videoFormat = extractor.getTrackFormat(videoTrack)

            val mime =
                videoFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Video MIME type is unavailable.")

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val frameRate =
                if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    30
                }.coerceAtLeast(1)

            val durationUs =
                if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    videoFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

            val totalFrames =
                estimateFrameCount(durationUs, frameRate)

            decoder =
                MediaCodec.createDecoderByType(mime)

            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            val encoderMime = "video/avc"

            val encoderInfo =
                findByteBufferEncoder(encoderMime)

            val colorFormat =
                chooseColorFormat(encoderInfo)

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
                        calculateBitRate(width, height, frameRate)
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
                MediaCodec.createByCodecName(encoderInfo.name)

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
                    ?: throw IllegalStateException(
                        "Failed to create MediaMuxer."
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
                activeMuxer.setOrientationHint(rotation)
            }

            processFrames(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = activeMuxer,
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
                        activeMuxer.start()
                        muxerStarted = true
                    }
                },
                encoderTrackProvider = {
                    encoderTrack
                }
            )

            if (muxerStarted) {
                activeMuxer.stop()
            }

            activeMuxer.release()
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

    // ---------- CONTINUES IN PART 2 ----------
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
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()

        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderInputDone = false
        var encoderOutputDone = false
        var frameCount = 0

        while (!encoderOutputDone) {

            if (!decoderInputDone) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)

                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Decoder input buffer unavailable.")

                    inputBuffer.clear()

                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

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
                val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 10_000)

                when {
                    outputIndex >= 0 -> {
                        val image = decoder.getOutputImage(outputIndex)

                        if (image != null && decoderInfo.size > 0) {
                            val original = YuvConverter.imageToBitmap(image)
                            image.close()

                            val processed = createProcessedFrame(
                                original,
                                animeEngine,
                                strength,
                                width,
                                height
                            )

                            val yuv = YuvConverter.bitmapToYuv420(processed)

                            queueEncoderFrame(
                                encoder,
                                yuv,
                                decoderInfo.presentationTimeUs
                            )

                            if (processed !== original && !processed.isRecycled) processed.recycle()
                            if (!original.isRecycled) original.recycle()

                            frameCount++
                            onProgress(frameCount, totalFrames, "Processing video")
                        }

                        decoder.releaseOutputBuffer(outputIndex, false)

                        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderOutputDone = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            if (decoderOutputDone && !encoderInputDone) {
                signalEncoderEndOfStream(encoder)
                encoderInputDone = true
            }

            while (true) {
                val encoderIndex = encoder.dequeueOutputBuffer(encoderInfo, 0)

                when {
                    encoderIndex >= 0 -> {
                        val buffer = encoder.getOutputBuffer(encoderIndex)

                        if (buffer != null && encoderInfo.size > 0) {
                            if (!isMuxerStarted()) {
                                throw IllegalStateException("Encoder output appeared before muxer initialization.")
                            }

                            buffer.position(encoderInfo.offset)
                            buffer.limit(encoderInfo.offset + encoderInfo.size)

                            muxer.writeSampleData(
                                encoderTrackProvider(),
                                buffer,
                                encoderInfo
                            )
                        }

                        encoder.releaseOutputBuffer(encoderIndex, false)

                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderOutputDone = true
                            break
                        }
                    }

                    encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (encoderTrackProvider() < 0) {
                            val track = muxer.addTrack(encoder.outputFormat)
                            onEncoderTrackReady(track)
                            startMuxer()
                        }
                    }

                    encoderIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
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
            if (original.width != width || original.height != height) {
                YuvConverter.resize(original, width, height)
            } else {
                original
            }

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

        val originalPixels = IntArray(width * height)
        val animePixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)

        original.getPixels(originalPixels, 0, width, 0, 0, width, height)
        anime.getPixels(animePixels, 0, width, 0, 0, width, height)

        val inverse = 1f - strength

        for (i in resultPixels.indices) {
            val op = originalPixels[i]
            val ap = animePixels[i]

            val r = (((op shr 16 and 0xff) * inverse) + ((ap shr 16 and 0xff) * strength)).roundToInt().coerceIn(0,255)
            val g = (((op shr 8 and 0xff) * inverse) + ((ap shr 8 and 0xff) * strength)).roundToInt().coerceIn(0,255)
            val b = (((op and 0xff) * inverse) + ((ap and 0xff) * strength)).roundToInt().coerceIn(0,255)

            resultPixels[i] =
                (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        result.setPixels(resultPixels,0,width,0,0,width,height)
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
                val buffer = encoder.getInputBuffer(index)
                    ?: throw IllegalStateException("Encoder input buffer unavailable.")

                if (buffer.capacity() < data.size) {
                    throw IllegalStateException("Encoder input buffer is too small.")
                }

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
            val index = encoder.dequeueOutputBuffer(info,0)

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> encoder.releaseOutputBuffer(index,false)
                else -> return
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

    private fun findByteBufferEncoder(mime: String): MediaCodecInfo {
        val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)

        return codecList.codecInfos.firstOrNull { info ->
            info.isEncoder &&
            info.supportedTypes.any { it.equals(mime,true) } &&
            info.getCapabilitiesForType(mime).colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
        } ?: throw IllegalStateException("No H.264 encoder is available.")
    }

    private fun chooseColorFormat(codecInfo: MediaCodecInfo): Int {
        val caps = codecInfo.getCapabilitiesForType("video/avc")

        if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }

        throw IllegalStateException("YUV420SemiPlanar is not supported.")
    }

    private fun muxAudio(inputUri: Uri, videoFile: File, outputFile: File) {
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

            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio =
                if (audioTrack >= 0) muxer.addTrack(extractor.getTrackFormat(audioTrack))
                else -1

            muxer.start()

            copySamples(videoExtractor,videoTrack,outVideo,muxer)

            if (audioTrack >= 0) {
                copySamples(extractor,audioTrack,outAudio,muxer)
            }

            muxer.stop()
        } finally {
            muxer?.release()
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

        val buffer = ByteBuffer.allocateDirect(1024*1024)
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
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun estimateFrameCount(durationUs: Long, frameRate: Int): Int {
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
