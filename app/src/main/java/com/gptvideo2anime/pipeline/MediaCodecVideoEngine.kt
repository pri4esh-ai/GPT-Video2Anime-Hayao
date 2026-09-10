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

        private const val SAMPLE_BUFFER_SIZE = 8 * 1024 * 1024
    }

    fun inspect(uri: Uri): VideoInfo {

        val extractor = MediaExtractor()

        try {

            setExtractorDataSource(extractor, uri)

            val track = findVideoTrack(extractor)

            require(track >= 0) {
                "No video track found."
            }

            val format =
                extractor.getTrackFormat(track)

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

            val track =
                findVideoTrack(extractor)

            require(track >= 0) {
                "Video track missing."
            }

            extractor.selectTrack(track)

            val format =
                extractor.getTrackFormat(track)

            val inputMime =
                format.getString(MediaFormat.KEY_MIME)!!

            val sourceWidth =
                format.getInteger(MediaFormat.KEY_WIDTH)

            val sourceHeight =
                format.getInteger(MediaFormat.KEY_HEIGHT)

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

            val encoderInfo =
                findH264Encoder()

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
                    sourceWidth and -2,
                    sourceHeight and -2
                ).apply {

                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        calculateBitRate(sourceWidth, sourceHeight, fps)
                    )
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
                muxer.setOrientationHint(rotation)

            processFrames(
                extractor,
                decoder,
                encoder,
                muxer,
                sourceWidth,
                sourceHeight,
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
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}

            if (tempVideo.exists())
                tempVideo.delete()
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
            encoder,
            muxer
        )

    val info =
        MediaCodec.BufferInfo()

    var extractorDone = false
    var decoderDone = false
    var encoderEnded = false

    var processed = 0

    val total =
        estimateFrameCount(
            durationUs,
            fps
        )

    val inferBitmap =
        Bitmap.createBitmap(
            MODEL_SIZE,
            MODEL_SIZE,
            Bitmap.Config.ARGB_8888
        )

    while (!encoderSink.isEndOfStream()) {

        if (!extractorDone) {

            val input =
                decoder.dequeueInputBuffer(TIMEOUT_US)

            if (input >= 0) {

                val buffer =
                    decoder.getInputBuffer(input)!!

                buffer.clear()

                val sampleSize =
                    extractor.readSampleData(buffer, 0)

                if (sampleSize < 0) {

                    decoder.queueInputBuffer(
                        input,
                        0,
                        0,
                        0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )

                    extractorDone = true

                } else {

                    decoder.queueInputBuffer(
                        input,
                        0,
                        sampleSize,
                        extractor.sampleTime,
                        extractor.sampleFlags
                    )

                    extractor.advance()
                }
            }
        }

        var decoderAvailable = true

        while (decoderAvailable) {

            val output =
                decoder.dequeueOutputBuffer(
                    info,
                    0
                )

            when {

                output ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    decoderAvailable = false
                }

                output ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    Log.i(
                        TAG,
                        "Decoder format changed."
                    )
                }

                output >= 0 -> {

                    val eos =
                        (info.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (info.size > 0) {

                        val image =
                            decoder.getOutputImage(output)
                                ?: throw IllegalStateException(
                                    "Decoder image unavailable."
                                )

                        try {

                            val frame =
                                YuvConverter.imageToBitmap(image)

                            try {

                                android.graphics.Canvas(inferBitmap)
                                    .drawBitmap(
                                        frame,
                                        null,
                                        android.graphics.Rect(
                                            0,
                                            0,
                                            MODEL_SIZE,
                                            MODEL_SIZE
                                        ),
                                        null
                                    )

                                val anime512 =
                                    animeEngine.processFrame(inferBitmap)
                                try {

                                    val animeFull =
                                        Bitmap.createScaledBitmap(
                                            anime512,
                                            sourceWidth and -2,
                                            sourceHeight and -2,
                                            true
                                        )

                                    try {

                                        val originalEven =
                                            if (
                                                frame.width == (sourceWidth and -2) &&
                                                frame.height == (sourceHeight and -2)
                                            ) {
                                                frame
                                            } else {
                                                Bitmap.createScaledBitmap(
                                                    frame,
                                                    sourceWidth and -2,
                                                    sourceHeight and -2,
                                                    true
                                                )
                                            }

                                        try {

                                            val finalFrame =
                                                blendFrames(
                                                    originalBitmap = originalEven,
                                                    animeBitmap = animeFull,
                                                    strength = strength
                                                )

                                            try {

                                                val yuv =
                                                    YuvConverter.bitmapToYuv420(
                                                        finalFrame
                                                    )

                                                encoderSink.queueFrame(
                                                    yuv,
                                                    info.presentationTimeUs
                                                        .coerceAtLeast(0L)
                                                )

                                            } finally {

                                                if (
                                                    !finalFrame.isRecycled &&
                                                    finalFrame !== originalEven
                                                ) {
                                                    finalFrame.recycle()
                                                }
                                            }

                                        } finally {

                                            if (
                                                originalEven !== frame &&
                                                !originalEven.isRecycled
                                            ) {
                                                originalEven.recycle()
                                            }
                                        }

                                    } finally {

                                        if (!animeFull.isRecycled) {
                                            animeFull.recycle()
                                        }
                                    }

                                } finally {

                                    if (!anime512.isRecycled) {
                                        anime512.recycle()
                                    }
                                }

                                processed++

                                onProgress(
                                    processed,
                                    total,
                                    "Processing..."
                                )

                            } finally {

                                if (!frame.isRecycled) {
                                    frame.recycle()
                                }
                            }

                        } finally {

                            image.close()
                        }
                    }

                    decoder.releaseOutputBuffer(
                        output,
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
            !encoderEnded
        ) {

            encoderSink.signalEndOfInput()
            encoderEnded = true
        }

        encoderSink.drain(0L)

        if (
            decoderDone &&
            encoderEnded
        ) {

            encoderSink.drain(TIMEOUT_US)

            if (!encoderSink.isEndOfStream()) {
                Thread.yield()
            }
        }
    }

    inferBitmap.recycle()

    if (!encoderSink.isMuxerStarted()) {
        throw IllegalStateException(
            "Encoder produced no output."
        )
    }

    Log.i(
        TAG,
        "Processed frames=$processed"
    )
}
// ---------- Reusable pixel buffers ----------

private var blendOriginalPixels: IntArray? = null
private var blendAnimePixels: IntArray? = null
private var blendResultPixels: IntArray? = null

private fun ensureBlendBuffers(size: Int) {

    if (blendOriginalPixels == null || blendOriginalPixels!!.size != size) {
        blendOriginalPixels = IntArray(size)
        blendAnimePixels = IntArray(size)
        blendResultPixels = IntArray(size)
    }
}

private fun blendFrames(
    originalBitmap: Bitmap,
    animeBitmap: Bitmap,
    strength: Float
): Bitmap {

    val width =
        outputEvenDimension(originalBitmap.width)

    val height =
        outputEvenDimension(originalBitmap.height)

    val original =
        if (originalBitmap.width == width &&
            originalBitmap.height == height
        ) {
            originalBitmap
        } else {
            Bitmap.createScaledBitmap(
                originalBitmap,
                width,
                height,
                true
            )
        }

    val anime =
        if (animeBitmap.width == width &&
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

        if (original !== originalBitmap &&
            !original.isRecycled
        ) {
            original.recycle()
        }

        return anime
    }

    if (strength <= 0.001f) {

        if (anime !== animeBitmap &&
            !anime.isRecycled
        ) {
            anime.recycle()
        }

        return original
    }

    val pixelCount = width * height

    ensureBlendBuffers(pixelCount)

    val originalPixels = blendOriginalPixels!!
    val animePixels = blendAnimePixels!!
    val resultPixels = blendResultPixels!!

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

    val s = (strength * 256f).toInt()
    val inv = 256 - s

    for (i in 0 until pixelCount) {

        val a = originalPixels[i]
        val b = animePixels[i]

        val ar = (a shr 16) and 255
        val ag = (a shr 8) and 255
        val ab = a and 255

        val br = (b shr 16) and 255
        val bg = (b shr 8) and 255
        val bb = b and 255

        val r = ((ar * inv + br * s) shr 8)
        val g = ((ag * inv + bg * s) shr 8)
        val blue = ((ab * inv + bb * s) shr 8)

        resultPixels[i] =
            (255 shl 24) or
                (r shl 16) or
                (g shl 8) or
                blue
    }

    val result =
        Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

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
// ---------- Reusable mux buffer ----------

private val muxBuffer by lazy {
    ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
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

        require(videoTrack >= 0) {
            "Processed video track missing."
        }

        val audioTrack = findAudioTrack(sourceExtractor)

        if (audioTrack < 0) {

            if (outputFile.exists()) {
                outputFile.delete()
            }

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

        val outVideoTrack =
            muxer.addTrack(
                videoExtractor.getTrackFormat(videoTrack)
            )

        val outAudioTrack =
            muxer.addTrack(
                sourceExtractor.getTrackFormat(audioTrack)
            )

        muxer.start()

        copySamples(
            videoExtractor,
            muxer,
            outVideoTrack
        )

        copySamples(
            sourceExtractor,
            muxer,
            outAudioTrack
        )

        muxer.stop()

    } finally {

        try {
            muxer?.release()
        } catch (_: Exception) {}

        try {
            sourceExtractor.release()
        } catch (_: Exception) {}

        try {
            videoExtractor.release()
        } catch (_: Exception) {}
    }
}

private fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    outputTrack: Int
) {

    val info = MediaCodec.BufferInfo()
    val buffer = muxBuffer

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
            extractor.sampleTime.coerceAtLeast(0L),
            extractor.sampleFlags
        )

        buffer.position(0)
        buffer.limit(size)

        muxer.writeSampleData(
            outputTrack,
            buffer,
            info
        )

        extractor.advance()
    }
}

// ---------- Track helpers ----------

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

private fun determineSampleBufferSize(
    extractor: MediaExtractor
): Int {

    val track = findCurrentTrack(extractor)

    if (track >= 0) {

        val format =
            extractor.getTrackFormat(track)

        if (
            format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
        ) {

            return max(
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE),
                1024 * 1024
            )
        }
    }

    return SAMPLE_BUFFER_SIZE
}
// ---------- Encoder helpers ----------

private fun findH264Encoder(): MediaCodecInfo {

    val codecList =
        MediaCodecList(
            MediaCodecList.REGULAR_CODECS
        )

    for (info in codecList.codecInfos) {

        if (!info.isEncoder) continue

        if (
            info.supportedTypes.any {
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
        "No compatible H.264 encoder found."
    )
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
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    )

    for (p in preferred) {
        if (caps.colorFormats.contains(p)) {
            return p
        }
    }

    return null
}

// ---------- Utility ----------

private fun calculateBitRate(
    width: Int,
    height: Int,
    fps: Int
): Int {

    val pixels =
        width.toLong() *
            height.toLong() *
            fps.toLong()

    return (pixels * 0.12)
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
        durationUs.toDouble() /
            1_000_000.0 *
            fps
        ).roundToInt()
        .coerceAtLeast(1)
}

private fun makeEvenDimension(
    value: Int
): Int =
    if (value % 2 == 0)
        value
    else
        value - 1

private fun outputEvenDimension(
    value: Int
): Int =
    makeEvenDimension(
        value.coerceAtLeast(2)
    )

private fun normalizeRotation(
    rotation: Int
): Int {

    var r =
        rotation % 360

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

    if (
        uri.scheme.equals(
            "file",
            true
        )
    ) {

        extractor.setDataSource(
            uri.path!!
        )

    } else {

        context.contentResolver
            .openFileDescriptor(uri, "r")
            ?.use {

                extractor.setDataSource(
                    it.fileDescriptor
                )

            } ?: throw IllegalStateException(
            "Unable to open video."
        )
    }
}

// ---------- EncoderSink ----------

private class EncoderSink(
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer
) {

    private val info =
        MediaCodec.BufferInfo()

    private var started =
        false

    private var videoTrack =
        -1

    private var eos =
        false

    fun queueFrame(
        data: ByteArray,
        pts: Long
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
                    pts,
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

    fun drain(
        timeoutUs: Long
    ) {

        var timeout =
            timeoutUs

        while (!eos) {

            val index =
                encoder.dequeueOutputBuffer(
                    info,
                    timeout
                )

            timeout = 0

            when {

                index ==
                    MediaCodec.INFO_TRY_AGAIN_LATER ->
                    return

                index ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (started)
                        throw IllegalStateException(
                            "Format changed twice."
                        )

                    videoTrack =
                        muxer.addTrack(
                            encoder.outputFormat
                        )

                    muxer.start()

                    started = true
                }

                index ==
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED ->
                    Unit

                index >= 0 -> {

                    val buffer =
                        encoder.getOutputBuffer(index)

                    if (
                        buffer != null &&
                        info.size > 0 &&
                        started &&
                        (info.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {

                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)

                        muxer.writeSampleData(
                            videoTrack,
                            buffer,
                            info
                        )
                    }

                    val finished =
                        (info.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    encoder.releaseOutputBuffer(
                        index,
                        false
                    )

                    if (finished) {
                        eos = true
                        return
                    }
                }
            }
        }
    }

    fun isEndOfStream() =
        eos

    fun isMuxerStarted() =
        started
}
}
