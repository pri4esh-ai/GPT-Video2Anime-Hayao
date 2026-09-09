package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class VideoProcessor(
    private val context: Context
) {

    data class ProcessingResult(
        val outputFile: File,
        val durationUs: Long,
        val frameCount: Int
    )

    fun processVideo(
        uri: Uri,
        strength: Int,
        onProgress: (
            current: Int,
            total: Int,
            stage: String
        ) -> Unit
    ): ProcessingResult {

        require(strength in 30..60) {
            "Strength must be between 30 and 60."
        }

        val modelManager =
            ModelManager(context)

        val modelPath =
            modelManager.animeModelPath()
                ?: throw IllegalStateException(
                    "Hayao model is not installed."
                )

        val info = inspectVideo(uri)

        val fps = info.frameRate
            .coerceIn(1, 60)

        val totalFrames =
            ((info.durationUs / 1_000_000.0) * fps)
                .roundToInt()
                .coerceAtLeast(1)

        val workDir =
            File(context.cacheDir, "video_processing")

        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        val silentVideo =
            File(
                workDir,
                "anime_${System.currentTimeMillis()}_video.mp4"
            )

        val finalVideo =
            File(
                context.filesDir,
                "anime_${System.currentTimeMillis()}.mp4"
            )

        onProgress(
            0,
            totalFrames,
            "Preparing encoder"
        )

        val encoder =
            VideoEncoder(
                width = makeEven(info.width),
                height = makeEven(info.height),
                frameRate = fps,
                bitRate = calculateBitrate(
                    info.width,
                    info.height,
                    info.bitRate
                )
            )

        val retriever =
            MediaMetadataRetriever()

        var engine: OnnxAnimeEngine? = null

        try {
            retriever.setDataSource(
                context,
                uri
            )

            engine =
                OnnxAnimeEngine(modelPath)

            encoder.start(
                silentVideo
            )

            val durationMs =
                info.durationUs / 1000L

            for (frameIndex in 0 until totalFrames) {
                val timestampUs =
                    (
                        frameIndex.toLong() *
                            1_000_000L
                    ) / fps

                if (timestampUs > info.durationUs) {
                    break
                }

                onProgress(
                    frameIndex,
                    totalFrames,
                    "Anime ${frameIndex + 1}/$totalFrames"
                )

                val frame =
                    retriever.getFrameAtTime(
                        timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                        ?: continue

                try {
                    val evenFrame =
                        resizeToEven(
                            frame,
                            makeEven(info.width),
                            makeEven(info.height)
                        )

                    val anime =
                        engine.processFrame(
                            evenFrame
                        )

                    try {
                        val blended =
                            blendFrames(
                                original = evenFrame,
                                anime = anime,
                                strength = strength / 100f
                            )

                        try {
                            encoder.encode(
                                bitmap = blended,
                                presentationTimeUs = timestampUs
                            )
                        } finally {
                            if (!blended.isRecycled) {
                                blended.recycle()
                            }
                        }
                    } finally {
                        if (!anime.isRecycled) {
                            anime.recycle()
                        }
                    }

                    if (evenFrame !== frame &&
                        !evenFrame.isRecycled
                    ) {
                        evenFrame.recycle()
                    }
                } finally {
                    if (!frame.isRecycled) {
                        frame.recycle()
                    }
                }

                if (frameIndex % 5 == 0) {
                    onProgress(
                        frameIndex + 1,
                        totalFrames,
                        "Processing ${(frameIndex + 1) * 100 / totalFrames}%"
                    )
                }
            }

            encoder.finish()

            onProgress(
                totalFrames,
                totalFrames,
                "Adding original audio"
            )

            muxVideoAndAudio(
                silentVideo = silentVideo,
                original = uri,
                output = finalVideo
            )

            onProgress(
                totalFrames,
                totalFrames,
                "Complete"
            )

            return ProcessingResult(
                outputFile = finalVideo,
                durationUs = info.durationUs,
                frameCount = totalFrames
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }

            try {
                engine?.close()
            } catch (_: Exception) {
            }

            try {
                encoder.release()
            } catch (_: Exception) {
            }

            if (silentVideo.exists()) {
                silentVideo.delete()
            }
        }
    }

    private data class VideoInfo(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val durationUs: Long,
        val bitRate: Int
    )

    private fun inspectVideo(
        uri: Uri
    ): VideoInfo {
        val extractor =
            MediaExtractor()

        try {
            context.contentResolver
                .openFileDescriptor(
                    uri,
                    "r"
                )
                .use { descriptor ->

                    requireNotNull(descriptor) {
                        "Unable to open video."
                    }

                    extractor.setDataSource(
                        descriptor.fileDescriptor
                    )
                }

            for (track in 0 until extractor.trackCount) {
                val format =
                    extractor.getTrackFormat(track)

                val mime =
                    format.getString(
                        MediaFormat.KEY_MIME
                    )

                if (
                    mime != null &&
                    mime.startsWith("video/")
                ) {
                    val width =
                        format.getInteger(
                            MediaFormat.KEY_WIDTH
                        )

                    val height =
                        format.getInteger(
                            MediaFormat.KEY_HEIGHT
                        )

                    val fps =
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
                            throw IllegalStateException(
                                "Video duration unavailable."
                            )
                        }

                    val bitrate =
                        if (
                            format.containsKey(
                                MediaFormat.KEY_BIT_RATE
                            )
                        ) {
                            format.getInteger(
                                MediaFormat.KEY_BIT_RATE
                            )
                        } else {
                            0
                        }

                    return VideoInfo(
                        width = width,
                        height = height,
                        frameRate = fps,
                        durationUs = duration,
                        bitRate = bitrate
                    )
                }
            }

            throw IllegalStateException(
                "No video track found."
            )
        } finally {
            extractor.release()
        }
    }

    private fun resizeToEven(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Bitmap {
        if (
            bitmap.width == width &&
            bitmap.height == height
        ) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )
    }

    private fun blendFrames(
        original: Bitmap,
        anime: Bitmap,
        strength: Float
    ): Bitmap {
        val width = original.width
        val height = original.height

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

        val animeWeight =
            strength.coerceIn(0f, 1f)

        val originalWeight =
            1f - animeWeight

        for (i in outputPixels.indices) {
            val originalPixel =
                originalPixels[i]

            val animePixel =
                animePixels[i]

            val r =
                (
                    ((originalPixel shr 16) and 0xFF) *
                        originalWeight +
                        ((animePixel shr 16) and 0xFF) *
                        animeWeight
                    ).roundToInt()
                        .coerceIn(0, 255)

            val g =
                (
                    ((originalPixel shr 8) and 0xFF) *
                        originalWeight +
                        ((animePixel shr 8) and 0xFF) *
                        animeWeight
                    ).roundToInt()
                        .coerceIn(0, 255)

            val b =
                (
                    (originalPixel and 0xFF) *
                        originalWeight +
                        (animePixel and 0xFF) *
                        animeWeight
                    ).roundToInt()
                        .coerceIn(0, 255)

            outputPixels[i] =
                (255 shl 24) or
                    (r shl 16) or
                    (g shl 8) or
                    b
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

    private fun muxVideoAndAudio(
        silentVideo: File,
        original: Uri,
        output: File
    ) {
        if (output.exists()) {
            output.delete()
        }

        val videoExtractor =
            MediaExtractor()

        val audioExtractor =
            MediaExtractor()

        val muxer =
            MediaMuxer(
                output.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

        try {
            videoExtractor.setDataSource(
                silentVideo.absolutePath
            )

            audioExtractor.setDataSource(
                context,
                original,
                null
            )

            var videoTrack = -1
            var audioTrack = -1

            for (i in 0 until videoExtractor.trackCount) {
                val format =
                    videoExtractor.getTrackFormat(i)

                val mime =
                    format.getString(
                        MediaFormat.KEY_MIME
                    )

                if (
                    mime != null &&
                    mime.startsWith("video/")
                ) {
                    videoExtractor.selectTrack(i)
                    videoTrack =
                        muxer.addTrack(format)
                    break
                }
            }

            for (i in 0 until audioExtractor.trackCount) {
                val format =
                    audioExtractor.getTrackFormat(i)

                val mime =
                    format.getString(
                        MediaFormat.KEY_MIME
                    )

                if (
                    mime != null &&
                    mime.startsWith("audio/")
                ) {
                    audioExtractor.selectTrack(i)
                    audioTrack =
                        muxer.addTrack(format)
                    break
                }
            }

            require(videoTrack >= 0) {
                "Encoded video track missing."
            }

            muxer.start()

            copySamples(
                extractor = videoExtractor,
                muxer = muxer,
                trackIndex = videoTrack
            )

            if (audioTrack >= 0) {
                copySamples(
                    extractor = audioExtractor,
                    muxer = muxer,
                    trackIndex = audioTrack
                )
            }
        } finally {
            try {
                videoExtractor.release()
            } catch (_: Exception) {
            }

            try {
                audioExtractor.release()
            } catch (_: Exception) {
            }

            try {
                muxer.stop()
            } catch (_: Exception) {
            }

            muxer.release()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int
    ) {
        val buffer =
            ByteBuffer.allocateDirect(
                1024 * 1024
            )

        val info =
            MediaCodec.BufferInfo()

        while (true) {
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
                trackIndex,
                buffer,
                info
            )

            extractor.advance()
        }
    }

    private fun calculateBitrate(
        width: Int,
        height: Int,
        originalBitrate: Int
    ): Int {
        if (originalBitrate > 0) {
            return originalBitrate
                .coerceIn(
                    2_000_000,
                    16_000_000
                )
        }

        return (
            width.toLong() *
                height.toLong() *
                5L
            )
            .coerceIn(
                2_000_000L,
                16_000_000L
            )
            .toInt()
    }

    private fun makeEven(
        value: Int
    ): Int {
        return if (value % 2 == 0) {
            value
        } else {
            value - 1
        }.coerceAtLeast(2)
    }

    private class VideoEncoder(
        private val width: Int,
        private val height: Int,
        private val frameRate: Int,
        private val bitRate: Int
    ) {

        private lateinit var codec: MediaCodec

        private var outputFormat: MediaFormat? = null

        private var muxer: MediaMuxer? = null

        private var videoTrack = -1

        private var started = false

        private val colorFormat =
            MediaCodecInfo.CodecCapabilities
                .COLOR_FormatYUV420Flexible

        fun start(
            output: File
        ) {
            codec =
                MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
                )

            val format =
                MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height
                )

            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                colorFormat
            )

            format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                bitRate
            )

            format.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                frameRate
            )

            format.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                2
            )

            codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            muxer =
                MediaMuxer(
                    output.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            codec.start()
            started = true
        }

        fun encode(
            bitmap: Bitmap,
            presentationTimeUs: Long
        ) {
            feedInput(
                bitmap,
                presentationTimeUs
            )

            drainEncoder(false)
        }

        fun finish() {
            if (!started) {
                return
            }

            val inputIndex =
                waitForInputBuffer()

            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )

            drainEncoder(true)

            if (muxer != null) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                }

                muxer?.release()
                muxer = null
            }

            codec.stop()
            started = false
        }

        private fun feedInput(
            bitmap: Bitmap,
            presentationTimeUs: Long
        ) {
            val inputIndex =
                waitForInputBuffer()

            val input =
                codec.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException(
                        "Encoder input buffer unavailable."
                    )

            input.clear()

            val requiredSize =
                width * height * 3 / 2

            val data =
                ByteArray(requiredSize)

            bitmapToI420(
                bitmap,
                data
            )

            input.put(data)

            codec.queueInputBuffer(
                inputIndex,
                0,
                data.size,
                presentationTimeUs,
                0
            )
        }

        private fun waitForInputBuffer(): Int {
            while (true) {
                val index =
                    codec.dequeueInputBuffer(
                        10_000
                    )

                if (index >= 0) {
                    return index
                }

                drainEncoder(false)
            }
        }

        private fun drainEncoder(
            endOfStream: Boolean
        ) {
            val bufferInfo =
                MediaCodec.BufferInfo()

            while (true) {
                val outputIndex =
                    codec.dequeueOutputBuffer(
                        bufferInfo,
                        10_000
                    )

                when {
                    outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) {
                            return
                        }
                    }

                    outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                        if (videoTrack >= 0) {
                            throw IllegalStateException(
                                "Encoder format changed twice."
                            )
                        }

                        outputFormat =
                            codec.outputFormat

                        videoTrack =
                            muxer?.addTrack(
                                outputFormat!!
                            )
                                ?: throw IllegalStateException(
                                    "Unable to create video track."
                                )

                        muxer?.start()
                    }

                    outputIndex >= 0 -> {
                        val output =
                            codec.getOutputBuffer(
                                outputIndex
                            )

                        if (
                            output != null &&
                            bufferInfo.size > 0 &&
                            bufferInfo.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            output.position(
                                bufferInfo.offset
                            )

                            output.limit(
                                bufferInfo.offset +
                                    bufferInfo.size
                            )

                            muxer?.writeSampleData(
                                videoTrack,
                                output,
                                bufferInfo
                            )
                        }

                        codec.releaseOutputBuffer(
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
                }
            }
        }

        private fun bitmapToI420(
            bitmap: Bitmap,
            output: ByteArray
        ) {
            val pixels =
                IntArray(width * height)

            bitmap.getPixels(
                pixels,
                0,
                width,
                0,
                0,
                width,
                height
            )

            val frameSize =
                width * height

            var yIndex = 0
            var uIndex = frameSize
            var vIndex =
                frameSize +
                    frameSize / 4

            for (j in 0 until height) {
                for (i in 0 until width) {
                    val pixel =
                        pixels[j * width + i]

                    val r =
                        (pixel shr 16) and 0xFF

                    val g =
                        (pixel shr 8) and 0xFF

                    val b =
                        pixel and 0xFF

                    val y =
                        (
                            0.257 * r +
                                0.504 * g +
                                0.098 * b +
                                16
                            )
                            .roundToInt()
                            .coerceIn(0, 255)

                    output[yIndex++] =
                        y.toByte()

                    if (
                        j % 2 == 0 &&
                        i % 2 == 0
                    ) {
                        val u =
                            (
                                -0.148 * r -
                                    0.291 * g +
                                    0.439 * b +
                                    128
                                )
                                .roundToInt()
                                .coerceIn(0, 255)

                        val v =
                            (
                                0.439 * r -
                                    0.368 * g -
                                    0.071 * b +
                                    128
                                )
                                .roundToInt()
                                .coerceIn(0, 255)

                        output[uIndex++] =
                            u.toByte()

                        output[vIndex++] =
                            v.toByte()
                    }
                }
            }
        }

        fun release() {
            if (!started) {
                try {
                    muxer?.release()
                } catch (_: Exception) {
                }

                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
