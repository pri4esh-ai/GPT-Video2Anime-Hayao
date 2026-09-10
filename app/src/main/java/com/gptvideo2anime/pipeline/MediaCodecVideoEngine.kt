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

        private const val MODEL_SIZE = 512
    }

    fun inspect(uri: Uri): VideoInfo {

        val extractor = MediaExtractor()

        try {

            setExtractorDataSource(extractor, uri)

            val track = findVideoTrack(extractor)

            require(track >= 0)

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        format.containsKey(MediaFormat.KEY_ROTATION))
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
                "video_${System.nanoTime()}.mp4"
            )

        try {

            outputFile.parentFile?.mkdirs()

            setExtractorDataSource(extractor, inputUri)

            val videoTrack = findVideoTrack(extractor)

            require(videoTrack >= 0)

            extractor.selectTrack(videoTrack)

            val format = extractor.getTrackFormat(videoTrack)

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    format.containsKey(MediaFormat.KEY_ROTATION))
                    format.getInteger(MediaFormat.KEY_ROTATION)
                else 0

            decoder =
                MediaCodec.createDecoderByType(
                    format.getString(MediaFormat.KEY_MIME)!!
                )

            decoder.configure(format, null, null, 0)
            decoder.start()

            val encoderInfo = findH264Encoder()
            val color = chooseColorFormat(encoderInfo, OUTPUT_MIME)!!

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    OUTPUT_MIME,
                    width,
                    height
                ).apply {

                    setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        calculateBitRate(width, height, fps)
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

            muxer =
                MediaMuxer(
                    tempVideo.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            processFrames(
                extractor,
                decoder,
                encoder,
                muxer,
                width,
                height,
                fps,
                durationUs,
                animeEngine,
                strength,
                onProgress
            )

            muxer.stop()
            muxer.release()
            muxer = null

            muxAudio(
                inputUri,
                tempVideo,
                outputFile
            )

        } finally {

            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}

            extractor.release()

            if (tempVideo.exists()) {
                tempVideo.delete()
            }
        }
    }
    private fun processFrames(
    extractor: MediaExtractor,
    decoder: MediaCodec,
    encoder: MediaCodec,
    muxer: MediaMuxer,
    outputWidth: Int,
    outputHeight: Int,
    fps: Int,
    durationUs: Long,
    animeEngine: OnnxAnimeEngine,
    strength: Float,
    onProgress: (Int, Int, String) -> Unit
) {

    val encoderSink = EncoderSink(
        encoder = encoder,
        muxer = muxer
    )

    val decoderInfo = MediaCodec.BufferInfo()

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
                decoder.dequeueInputBuffer(TIMEOUT_US)

            if (inputIndex >= 0) {

                val inputBuffer =
                    decoder.getInputBuffer(inputIndex)
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

                    decoder.queueInputBuffer(
                        inputIndex,
                        0,
                        sampleSize,
                        extractor.sampleTime.coerceAtLeast(0L),
                        extractor.sampleFlags
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
                        "Decoder output format changed: ${decoder.outputFormat}"
                    )
                }

                outputIndex >= 0 -> {

                    val eos =
                        (
                            decoderInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                    if (decoderInfo.size > 0) {

                        onProgress(
                            processedFrames,
                            totalFrames,
                            "Processing..."
                        )

                        val image =
                            decoder.getOutputImage(outputIndex)
                                ?: throw IllegalStateException(
                                    "Decoder Image unavailable."
                                )

                        val originalBitmap =
                            try {
                                YuvConverter.imageToBitmap(image)
                            } finally {
                                image.close()
                            }

                        try {

                            val modelBitmap =
                                YuvConverter.resize(
                                    originalBitmap,
                                    MODEL_SIZE,
                                    MODEL_SIZE
                                )

                            try {

                                val animeBitmap =
                                    animeEngine.processFrame(modelBitmap)

                                try {

                                    val finalBitmap =
                                        Bitmap.createScaledBitmap(
                                            animeBitmap,
                                            outputWidth,
                                            outputHeight,
                                            true
                                        )

                                    try {

                                        val outputBitmap =
                                            if (strength >= 0.999f) {

                                                finalBitmap

                                            } else {

                                                blendFrames(
                                                    originalBitmap =
                                                        YuvConverter.resize(
                                                            originalBitmap,
                                                            outputWidth,
                                                            outputHeight
                                                        ),
                                                    animeBitmap =
                                                        finalBitmap,
                                                    strength = strength
                                                )
                                            }

                                        try {

                                            val yuv =
                                                YuvConverter.bitmapToYuv420(
                                                    outputBitmap
                                                )

                                            encoderSink.queueFrame(
                                                yuv,
                                                decoderInfo.presentationTimeUs.coerceAtLeast(0L)
                                            )

                                        } finally {

                                            if (
                                                outputBitmap !== finalBitmap &&
                                                !outputBitmap.isRecycled
                                            ) {
                                                outputBitmap.recycle()
                                            }
                                        }

                                    } finally {

                                        if (!finalBitmap.isRecycled) {
                                            finalBitmap.recycle()
                                        }
                                    }

                                } finally {

                                    if (!animeBitmap.isRecycled) {
                                        animeBitmap.recycle()
                                    }
                                }

                            } finally {

                                if (
                                    modelBitmap !== originalBitmap &&
                                    !modelBitmap.isRecycled
                                ) {
                                    modelBitmap.recycle()
                                }
                            }

                            processedFrames++

                            onProgress(
                                processedFrames,
                                totalFrames,
                                "Encoding..."
                            )

                        } finally {

                            if (!originalBitmap.isRecycled) {
                                originalBitmap.recycle()
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (eos) {

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

        encoderSink.drain(0L)

        if (
            decoderDone &&
            encoderInputEnded
        ) {

            encoderSink.drain(TIMEOUT_US)

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
    originalBitmap: Bitmap,
    animeBitmap: Bitmap,
    strength: Float
): Bitmap {

    if (strength >= 0.999f) {
        return animeBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    if (strength <= 0.001f) {
        return originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    val width = originalBitmap.width
    val height = originalBitmap.height

    val result =
        Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

    val originalPixels = IntArray(width * height)
    val animePixels = IntArray(width * height)

    originalBitmap.getPixels(
        originalPixels,
        0,
        width,
        0,
        0,
        width,
        height
    )

    animeBitmap.getPixels(
        animePixels,
        0,
        width,
        0,
        0,
        width,
        height
    )

    val inverse = 1f - strength

    for (i in originalPixels.indices) {

        val a = originalPixels[i]
        val b = animePixels[i]

        val r =
            (
                ((a shr 16) and 255) * inverse +
                    ((b shr 16) and 255) * strength
                )
                .roundToInt()

        val g =
            (
                ((a shr 8) and 255) * inverse +
                    ((b shr 8) and 255) * strength
                )
                .roundToInt()

        val blue =
            (
                (a and 255) * inverse +
                    (b and 255) * strength
                )
                .roundToInt()

        originalPixels[i] =
            (255 shl 24) or
                (r shl 16) or
                (g shl 8) or
                blue
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

    return result
}

private fun muxAudio(
    inputUri: Uri,
    processedVideo: File,
    outputFile: File
) {

    val sourceExtractor = MediaExtractor()
    val videoExtractor = MediaExtractor()
    var muxer: MediaMuxer? = null

    try {

        setExtractorDataSource(sourceExtractor, inputUri)
        setExtractorDataSource(videoExtractor, Uri.fromFile(processedVideo))

        val videoTrack = findVideoTrack(videoExtractor)
        val audioTrack = findAudioTrack(sourceExtractor)

        if (audioTrack < 0) {

            processedVideo.copyTo(
                outputFile,
                overwrite = true
            )

            return
        }

        videoExtractor.selectTrack(videoTrack)
        sourceExtractor.selectTrack(audioTrack)

        muxer =
            MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

        val outVideo =
            muxer.addTrack(
                videoExtractor.getTrackFormat(videoTrack)
            )

        val outAudio =
            muxer.addTrack(
                sourceExtractor.getTrackFormat(audioTrack)
            )

        muxer.start()

        copySamples(
            videoExtractor,
            muxer,
            outVideo
        )

        copySamples(
            sourceExtractor,
            muxer,
            outAudio
        )

        muxer.stop()

    } finally {

        try {
            muxer?.release()
        } catch (_: Exception) {
        }

        sourceExtractor.release()
        videoExtractor.release()
    }
}

private fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    outputTrack: Int
) {

    val buffer =
        ByteBuffer.allocateDirect(
            determineSampleBufferSize(extractor)
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

private fun determineSampleBufferSize(
    extractor: MediaExtractor
): Int {

    val track =
        findCurrentTrack(extractor)

    if (track >= 0) {

        val format =
            extractor.getTrackFormat(track)

        if (
            format.containsKey(
                MediaFormat.KEY_MAX_INPUT_SIZE
            )
        ) {

            return max(
                format.getInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE
                ),
                1024 * 1024
            )
        }
    }

    return SAMPLE_BUFFER_SIZE
}

private fun findCurrentTrack(
    extractor: MediaExtractor
): Int {

    for (i in 0 until extractor.trackCount) {

        val mime =
            extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
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

    for (i in 0 until extractor.trackCount) {

        val mime =
            extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
                ?: continue

        if (mime.startsWith("video/")) {
            return i
        }
    }

    return -1
}

private fun findAudioTrack(
    extractor: MediaExtractor
): Int {

    for (i in 0 until extractor.trackCount) {

        val mime =
            extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
                ?: continue

        if (mime.startsWith("audio/")) {
            return i
        }
    }

    return -1
}
private fun findH264Encoder(): MediaCodecInfo {

    val codecList =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)

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

            if (
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
            info.getCapabilitiesForType(mime)
        } catch (_: Exception) {
            return null
        }

    for (format in capabilities.colorFormats) {
        if (
            format ==
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        ) {
            return format
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

    return if (value % 2 == 0) value else value - 1
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

    var value = rotation % 360

    if (value < 0) value += 360

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

        extractor.setDataSource(
            uri.path
                ?: throw IllegalStateException(
                    "Invalid file URI."
                )
        )

    } else {

        context.contentResolver.openFileDescriptor(
            uri,
            "r"
        )?.use {

            extractor.setDataSource(
                it.fileDescriptor
            )

        } ?: throw IllegalStateException(
            "Unable to open selected video."
        )
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

        while (true) {

            val index =
                encoder.dequeueInputBuffer(TIMEOUT_US)

            if (index >= 0) {

                val buffer =
                    encoder.getInputBuffer(index)
                        ?: throw IllegalStateException(
                            "Encoder input buffer unavailable."
                        )

                buffer.clear()
                buffer.put(data)

                encoder.queueInputBuffer(
                    index,
                    0,
                    data.size,
                    presentationTimeUs,
                    0
                )

                break
            }

            drain(TIMEOUT_US)
        }

        drain(0)
    }

    fun signalEndOfInput() {

        while (true) {

            val index =
                encoder.dequeueInputBuffer(TIMEOUT_US)

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

            drain(TIMEOUT_US)
        }
    }

    fun drain(timeoutUs: Long) {

        var timeout =
            timeoutUs

        while (!endOfStream) {

            val index =
                encoder.dequeueOutputBuffer(
                    bufferInfo,
                    timeout
                )

            timeout = 0

            when (index) {

                MediaCodec.INFO_TRY_AGAIN_LATER -> return

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
                }

                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> {

                    if (index >= 0) {

                        encoder.getOutputBuffer(index)?.let {

                            if (
                                bufferInfo.size > 0 &&
                                muxerStarted &&
                                (
                                    bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                    ) == 0
                            ) {

                                it.position(bufferInfo.offset)

                                it.limit(
                                    bufferInfo.offset +
                                        bufferInfo.size
                                )

                                muxer.writeSampleData(
                                    videoTrack,
                                    it,
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
                            index,
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
    }

    fun isEndOfStream() =
        endOfStream

    fun isMuxerStarted() =
        muxerStarted
}
}
