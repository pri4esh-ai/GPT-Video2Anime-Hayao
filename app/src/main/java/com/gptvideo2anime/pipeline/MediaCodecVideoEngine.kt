package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
        private const val SAMPLE_BUFFER_SIZE = 8 * 1024 * 1024
    }

    private val inferPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val upscalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun inspect(uri: Uri): VideoInfo {

        val extractor = MediaExtractor()

        try {

            setExtractorDataSource(extractor, uri)

            val track = findVideoTrack(extractor)

            require(track >= 0) {
                "No video track found."
            }

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
                    ) format.getInteger(MediaFormat.KEY_ROTATION)
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
                "${outputFile.nameWithoutExtension}_video.mp4"
            )

        try {

            outputFile.parentFile?.mkdirs()

            setExtractorDataSource(extractor, inputUri)

            val track = findVideoTrack(extractor)

            require(track >= 0) {
                "Video track missing."
            }

            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)

            val inputMime = format.getString(MediaFormat.KEY_MIME)!!

            val sourceWidth =
                makeEvenDimension(
                    format.getInteger(MediaFormat.KEY_WIDTH)
                )

            val sourceHeight =
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
                ) format.getInteger(MediaFormat.KEY_ROTATION)
                else 0

            decoder =
                MediaCodec.createDecoderByType(inputMime)

            decoder.configure(format, null, null, 0)
            decoder.start()

            val encoderInfo = findH264Encoder()

            val colorFormat =
                chooseColorFormat(
                    encoderInfo,
                    OUTPUT_MIME
                ) ?: throw IllegalStateException(
                    "No compatible encoder."
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

    val inferBitmap =
        Bitmap.createBitmap(
            512,
            512,
            Bitmap.Config.ARGB_8888
        )

    val inferCanvas =
        Canvas(inferBitmap)

    val upscaleBitmap =
        Bitmap.createBitmap(
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )

    val upscaleCanvas =
        Canvas(upscaleBitmap)

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
                        "Decoder output format changed."
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
                            decoder.getOutputImage(outputIndex)
                                ?: throw IllegalStateException(
                                    "Decoder image unavailable."
                                )

                        try {

                            val originalBitmap =
                                YuvConverter.imageToBitmap(image)

                            try {

                                inferCanvas.drawBitmap(
                                    originalBitmap,
                                    null,
                                    android.graphics.Rect(
                                        0,
                                        0,
                                        512,
                                        512
                                    ),
                                    null
                                )

                                val anime512 =
                                    animeEngine.processFrame(
                                        inferBitmap
                                    )
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
    val decoderInfo = MediaCodec.BufferInfo()

    var extractorDone = false
    var decoderDone = false
    var encoderInputEnded = false

    var processedFrames = 0
    val totalFrames = estimateFrameCount(durationUs, fps)

    while (!encoderSink.isEndOfStream()) {

        if (!extractorDone) {

            val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)

            if (inputIndex >= 0) {

                val inputBuffer =
                    decoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Decoder input buffer unavailable.")

                inputBuffer.clear()

                val sampleSize =
                    extractor.readSampleData(inputBuffer, 0)

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
                decoder.dequeueOutputBuffer(decoderInfo, 0)

            when {

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    decoderOutputAvailable = false
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Decoder format changed")
                }

                outputIndex >= 0 -> {

                    val decoderEos =
                        (decoderInfo.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (decoderInfo.size > 0) {

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

                            onProgress(
                                processedFrames,
                                totalFrames,
                                "Anime..."
                            )

                            val animeBitmap =
                                animeEngine.processFrame(originalBitmap)

                            try {

                                val finalBitmap =
                                    blendFrames(
                                        originalBitmap = originalBitmap,
                                        animeBitmap = animeBitmap,
                                        strength = strength
                                    )

                                try {

                                    val yuv =
                                        YuvConverter.bitmapToYuv420(
                                            finalBitmap
                                        )

                                    encoderSink.queueFrame(
                                        yuv,
                                        decoderInfo.presentationTimeUs
                                            .coerceAtLeast(0L)
                                    )

                                } finally {

                                    if (
                                        finalBitmap !== originalBitmap &&
                                        !finalBitmap.isRecycled
                                    ) {
                                        finalBitmap.recycle()
                                    }
                                }

                            } finally {

                                if (!animeBitmap.isRecycled) {
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

                            if (!originalBitmap.isRecycled) {
                                originalBitmap.recycle()
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(outputIndex, false)

                    if (decoderEos) {
                        decoderDone = true
                        extractorDone = true
                        break
                    }
                }
            }
        }

        if (decoderDone && !encoderInputEnded) {
            encoderSink.signalEndOfInput()
            encoderInputEnded = true
        }

        encoderSink.drain(0L)

        if (decoderDone && encoderInputEnded) {
            encoderSink.drain(TIMEOUT_US)
        }
    }

    if (!encoderSink.isMuxerStarted()) {
        throw IllegalStateException(
            "Encoder never produced output format."
        )
    }

    Log.i(TAG, "Processed frames=$processedFrames")
}
private fun blendFrames(
    originalBitmap: Bitmap,
    animeBitmap: Bitmap,
    strength: Float
): Bitmap {

    val width = originalBitmap.width
    val height = originalBitmap.height

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

    if (strength >= 0.999f) {
        return if (anime === animeBitmap) {
            anime.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            anime
        }
    }

    if (strength <= 0.001f) {
        if (anime !== animeBitmap && !anime.isRecycled) {
            anime.recycle()
        }
        return originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

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

    anime.getPixels(
        animePixels,
        0,
        width,
        0,
        0,
        width,
        height
    )

    val alpha = strength
    val inv = 1f - alpha

    for (i in originalPixels.indices) {

        val o = originalPixels[i]
        val a = animePixels[i]

        val r =
            (((o shr 16) and 255) * inv +
                ((a shr 16) and 255) * alpha)
                .toInt()

        val g =
            (((o shr 8) and 255) * inv +
                ((a shr 8) and 255) * alpha)
                .toInt()

        val b =
            ((o and 255) * inv +
                (a and 255) * alpha)
                .toInt()

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

    if (anime !== animeBitmap && !anime.isRecycled) {
        anime.recycle()
    }

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
        videoExtractor.setDataSource(processedVideo.absolutePath)

        val videoTrack = findVideoTrack(videoExtractor)
        val audioTrack = findAudioTrack(sourceExtractor)

        if (videoTrack < 0) {
            throw IllegalStateException("Processed video track missing.")
        }

        if (audioTrack < 0) {

            if (outputFile.exists()) outputFile.delete()

            processedVideo.copyTo(outputFile, overwrite = true)
            processedVideo.delete()
            return
        }

        videoExtractor.selectTrack(videoTrack)
        sourceExtractor.selectTrack(audioTrack)

        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        val outputVideoTrack =
            muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))

        val outputAudioTrack =
            muxer.addTrack(sourceExtractor.getTrackFormat(audioTrack))

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

    } finally {

        try { muxer?.release() } catch (_: Exception) {}
        try { sourceExtractor.release() } catch (_: Exception) {}
        try { videoExtractor.release() } catch (_: Exception) {}
    }
}

private fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    outputTrack: Int
) {

    val bufferSize = determineSampleBufferSize(extractor)
    val buffer = ByteBuffer.allocateDirect(bufferSize)
    val info = MediaCodec.BufferInfo()

    while (true) {

        buffer.clear()

        val sampleSize = extractor.readSampleData(buffer, 0)

        if (sampleSize < 0) break

        info.offset = 0
        info.size = sampleSize
        info.presentationTimeUs = extractor.sampleTime
        info.flags = extractor.sampleFlags

        buffer.position(0)
        buffer.limit(sampleSize)

        muxer.writeSampleData(outputTrack, buffer, info)

        extractor.advance()
    }
}
private fun determineSampleBufferSize(
    extractor: MediaExtractor
): Int {

    val trackIndex = findCurrentTrack(extractor)

    if (trackIndex >= 0) {

        val format = extractor.getTrackFormat(trackIndex)

        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {

            val size =
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)

            if (size > 0) {
                return max(size, 1024 * 1024)
            }
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

        if (!info.isEncoder) continue

        if (
            info.supportedTypes.any {
                it.equals(OUTPUT_MIME, true)
            }
        ) {
            return info
        }
    }

    throw IllegalStateException("H.264 encoder not found.")
}

private fun chooseColorFormat(
    info: MediaCodecInfo,
    mime: String
): Int? {

    val caps =
        try {
            info.getCapabilitiesForType(mime)
        } catch (_: Exception) {
            return null
        }

    val preferred = intArrayOf(
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    )

    for (want in preferred) {
        if (caps.colorFormats.contains(want)) {
            return want
        }
    }

    return caps.colorFormats.firstOrNull()
}

private fun calculateBitRate(
    width: Int,
    height: Int,
    fps: Int
): Int {

    val bits =
        (width.toLong() *
            height *
            fps *
            8L / 50L)

    return bits.toInt().coerceIn(
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
        durationUs / 1_000_000f * fps
    ).roundToInt().coerceAtLeast(1)
}

private fun makeEvenDimension(
    value: Int
): Int =
    if (value % 2 == 0) value else value - 1

private fun outputEvenDimension(
    value: Int
): Int =
    makeEvenDimension(value.coerceAtLeast(2))

private fun normalizeRotation(
    rotation: Int
): Int {

    var r = rotation % 360

    if (r < 0) r += 360

    return when (r) {
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

    if (uri.scheme.equals("file", true)) {

        extractor.setDataSource(
            uri.path
                ?: throw IllegalStateException("Invalid file URI.")
        )

    } else {

        context.contentResolver
            .openFileDescriptor(uri, "r")
            ?.use {
                extractor.setDataSource(it.fileDescriptor)
            }
            ?: throw IllegalStateException("Cannot open selected video.")
    }
}

private class EncoderSink(
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer
) {

    private val info = MediaCodec.BufferInfo()

    private var started = false
    private var track = -1
    private var eos = false

    fun queueFrame(
        data: ByteArray,
        pts: Long
    ) {

        while (true) {

            val index =
                encoder.dequeueInputBuffer(TIMEOUT_US)

            if (index >= 0) {

                encoder.getInputBuffer(index)?.apply {
                    clear()
                    put(data)
                }

                encoder.queueInputBuffer(
                    index,
                    0,
                    data.size,
                    pts,
                    0
                )

                break
            }

            drain(0)
        }
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

                break
            }

            drain(0)
        }
    }

    fun drain(timeoutUs: Long) {

        while (!eos) {

            val out =
                encoder.dequeueOutputBuffer(info, timeoutUs)

            when {

                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (!started) {

                        track =
                            muxer.addTrack(encoder.outputFormat)

                        muxer.start()
                        started = true
                    }
                }

                out >= 0 -> {

                    encoder.getOutputBuffer(out)?.let { buffer ->

                        if (
                            started &&
                            info.size > 0 &&
                            (info.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            muxer.writeSampleData(
                                track,
                                buffer,
                                info
                            )
                        }
                    }

                    eos =
                        (info.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    encoder.releaseOutputBuffer(out, false)
                }
            }
        }
    }

    fun isEndOfStream() = eos

    fun isMuxerStarted() = started
}
