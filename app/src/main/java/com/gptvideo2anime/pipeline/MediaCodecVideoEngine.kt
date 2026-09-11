package com.gptvideo2anime.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.util.ImageUtils
import java.io.File

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationUs: Long
)

class MediaCodecVideoEngine(
    private val context: Context
) {

    companion object {
        private const val TAG = "MediaCodecVideoEngine"
        private const val DEFAULT_FRAME_RATE = 30
        private const val TIMEOUT_US = 10_000L
    }

    fun inspect(uri: Uri): VideoMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = selectVideoTrack(extractor)
            require(track >= 0) { "No valid video track found." }

            val format = extractor.getTrackFormat(track)

            return VideoMetadata(
                width = if (format.containsKey(MediaFormat.KEY_WIDTH))
                    format.getInteger(MediaFormat.KEY_WIDTH) else 1280,
                height = if (format.containsKey(MediaFormat.KEY_HEIGHT))
                    format.getInteger(MediaFormat.KEY_HEIGHT) else 720,
                frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    format.getInteger(MediaFormat.KEY_FRAME_RATE) else DEFAULT_FRAME_RATE,
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) else 0L
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

        val metadata = inspect(inputUri)

        val totalFrames =
            if (metadata.durationUs > 0L)
                ((metadata.durationUs / 1_000_000.0) * metadata.frameRate).toInt()
            else 0

        val extractor = MediaExtractor()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var muxerStarted = false
        var muxerTrack = -1

        try {

            extractor.setDataSource(context, inputUri, null)

            val videoTrack = selectVideoTrack(extractor)
            extractor.selectTrack(videoTrack)

            val inputFormat = extractor.getTrackFormat(videoTrack)

            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                metadata.width,
                metadata.height
            ).apply {

                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )

                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    metadata.width * metadata.height * 4
                )

                setInteger(
                    MediaFormat.KEY_FRAME_RATE,
                    metadata.frameRate
                )

                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    1
                )
            }

            encoder = MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            ).apply {
                configure(
                    outputFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                start()
            }

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            decoder = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME)!!
            ).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()

            var processedFrames = 0
            var inputEos = false
            var outputEos = false

            onProgress(0, totalFrames, "Processing...")

            while (!outputEos) {

                if (!inputEos) {

                    val inputIndex =
                        decoder.dequeueInputBuffer(TIMEOUT_US)

                    if (inputIndex >= 0) {

                        val inputBuffer =
                            decoder.getInputBuffer(inputIndex)!!

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

                            inputEos = true

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

                val outputIndex =
                    decoder.dequeueOutputBuffer(
                        bufferInfo,
                        TIMEOUT_US
                    )

                if (outputIndex >= 0) {

                    if (
                        bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ) {
                        outputEos = true
                    }

                    val image =
                        decoder.getOutputImage(outputIndex)

                    if (image != null) {

                        val originalBitmap =
                            ImageUtils.imageToBitmap(image)

                        image.close()

                        val animeBitmap =
                            animeEngine.infer(originalBitmap)

                        val finalBitmap =
                            blendFrames(
                                originalBitmap,
                                animeBitmap,
                                strength
                            )

                        if (
                            originalBitmap !== finalBitmap &&
                            !originalBitmap.isRecycled
                        ) {
                            originalBitmap.recycle()
                        }

                        if (
                            animeBitmap !== finalBitmap &&
                            !animeBitmap.isRecycled
                        ) {
                            animeBitmap.recycle()
                        }

                        val yuvData =
                            ImageUtils.bitmapToYuv420(
                                finalBitmap,
                                metadata.width,
                                metadata.height
                            )

                        if (!finalBitmap.isRecycled) {
                            finalBitmap.recycle()
                        }

                        encodeFrame(
                            encoder = encoder,
                            muxer = muxer,
                            yuvData = yuvData,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            isEos = outputEos,
                            onTrackReady = {
                                muxerTrack = it
                                if (!muxerStarted) {
                                    muxer.start()
                                    muxerStarted = true
                                }
                            },
                            isMuxerStarted = { muxerStarted },
                            muxerTrackIndex = muxerTrack
                        )

                        processedFrames++

                        onProgress(
                            processedFrames,
                            totalFrames,
                            "Frame $processedFrames"
                        )
                    }

                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }

        } catch (e: Exception) {

            Log.e(TAG, "Pipeline failed", e)
            throw e

        } finally {

            runCatching {
                decoder?.stop()
                decoder?.release()
            }

            runCatching {
                encoder?.stop()
                encoder?.release()
            }

            runCatching {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            }

            runCatching {
                extractor.release()
            }
        }
    }

    private fun blendFrames(
        original: Bitmap,
        anime: Bitmap,
        strength: Float
    ): Bitmap {

        val alpha =
            strength.coerceIn(0f, 1f)

        if (alpha >= 0.99f) {
            return anime.copy(
                anime.config ?: Bitmap.Config.ARGB_8888,
                true
            )
        }

        if (alpha <= 0.01f) {
            return original.copy(
                original.config ?: Bitmap.Config.ARGB_8888,
                true
            )
        }

        val width = original.width
        val height = original.height

        val scaledAnime =
            if (
                anime.width != width ||
                anime.height != height
            ) {
                Bitmap.createScaledBitmap(
                    anime,
                    width,
                    height,
                    true
                )
            } else {
                anime
            }

        val size = width * height

        val originalPixels = IntArray(size)
        val animePixels = IntArray(size)
        val resultPixels = IntArray(size)

        original.getPixels(
            originalPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        scaledAnime.getPixels(
            animePixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val inv = 1f - alpha

        for (i in 0 until size) {

            val p1 = originalPixels[i]
            val p2 = animePixels[i]

            val r =
                (((p1 shr 16) and 255) * inv +
                 ((p2 shr 16) and 255) * alpha)
                    .toInt()
                    .coerceIn(0, 255)

            val g =
                (((p1 shr 8) and 255) * inv +
                 ((p2 shr 8) and 255) * alpha)
                    .toInt()
                    .coerceIn(0, 255)

            val b =
                (((p1) and 255) * inv +
                 ((p2) and 255) * alpha)
                    .toInt()
                    .coerceIn(0, 255)

            resultPixels[i] =
                (255 shl 24) or
                (r shl 16) or
                (g shl 8) or
                b
        }

        if (
            scaledAnime !== anime &&
            !scaledAnime.isRecycled
        ) {
            scaledAnime.recycle()
        }

        val output =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        output.setPixels(
            resultPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return output
    }

    private fun selectVideoTrack(
        extractor: MediaExtractor
    ): Int {

        for (i in 0 until extractor.trackCount) {

            val mime =
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?: ""

            if (mime.startsWith("video/")) {
                return i
            }
        }

        return -1
    }

    private fun encodeFrame(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        yuvData: ByteArray,
        presentationTimeUs: Long,
        isEos: Boolean,
        onTrackReady: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        muxerTrackIndex: Int
    ) {

        val inputIndex =
            encoder.dequeueInputBuffer(TIMEOUT_US)

        if (inputIndex >= 0) {

            val buffer =
                encoder.getInputBuffer(inputIndex)!!

            buffer.clear()
            buffer.put(yuvData)

            encoder.queueInputBuffer(
                inputIndex,
                0,
                yuvData.size,
                presentationTimeUs,
                if (isEos)
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                else
                    0
            )
        }

        val info = MediaCodec.BufferInfo()

        var outputIndex =
            encoder.dequeueOutputBuffer(
                info,
                TIMEOUT_US
            )

        while (
            outputIndex !=
            MediaCodec.INFO_TRY_AGAIN_LATER
        ) {

            when {

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    val track =
                        muxer.addTrack(
                            encoder.outputFormat
                        )

                    onTrackReady(track)
                }

                outputIndex >= 0 -> {

                    if (isMuxerStarted()) {

                        val encoded =
                            encoder.getOutputBuffer(outputIndex)

                        if (
                            encoded != null &&
                            info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {

                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)

                            muxer.writeSampleData(
                                muxerTrackIndex,
                                encoded,
                                info
                            )
                        }
                    }

                    encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )
                }
            }

            outputIndex =
                encoder.dequeueOutputBuffer(
                    info,
                    0L
                )
        }
    }
}
