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

    init {
        System.loadLibrary("anime_engine")
    }

    private external fun nativeProcessFrame(bitmap: Bitmap): Bitmap

    private external fun nativeBlendFrames(
        original: Bitmap,
        anime: Bitmap,
        strength: Float
    ): Bitmap

    fun inspect(uri: Uri): VideoMetadata {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = selectVideoTrack(extractor)

            require(trackIndex >= 0) {
                "No valid video track found."
            }

            val format = extractor.getTrackFormat(trackIndex)

            return VideoMetadata(
                width = if (format.containsKey(MediaFormat.KEY_WIDTH))
                    format.getInteger(MediaFormat.KEY_WIDTH)
                else 1280,

                height = if (format.containsKey(MediaFormat.KEY_HEIGHT))
                    format.getInteger(MediaFormat.KEY_HEIGHT)
                else 720,

                frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                else DEFAULT_FRAME_RATE,

                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION)
                else 0L
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

        try {

            extractor.setDataSource(context, inputUri, null)

            val videoTrack = selectVideoTrack(extractor)

            extractor.selectTrack(videoTrack)

            val inputFormat = extractor.getTrackFormat(videoTrack)

            val outputFormat =
                MediaFormat.createVideoFormat(
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

            encoder =
                MediaCodec.createEncoderByType(
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

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            var muxerStarted = false
            var muxerTrack = -1

            decoder =
                MediaCodec.createDecoderByType(
                    inputFormat.getString(MediaFormat.KEY_MIME)!!
                ).apply {

                    configure(inputFormat, null, null, 0)
                    start()
                }

            val bufferInfo = MediaCodec.BufferInfo()

            var processedFrames = 0
            var inputEos = false
            var outputEos = false

            onProgress(
                0,
                totalFrames,
                "Processing..."
            )

            while (!outputEos) {

                if (!inputEos) {

                    val inIndex =
                        decoder.dequeueInputBuffer(TIMEOUT_US)

                    if (inIndex >= 0) {

                        val buffer =
                            decoder.getInputBuffer(inIndex)!!

                        val sample =
                            extractor.readSampleData(buffer, 0)

                        if (sample < 0) {

                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )

                            inputEos = true

                        } else {

                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                sample,
                                extractor.sampleTime,
                                0
                            )

                            extractor.advance()
                        }
                    }
                }

                val outIndex =
                    decoder.dequeueOutputBuffer(
                        bufferInfo,
                        TIMEOUT_US
                    )

                if (outIndex >= 0) {

                    if (
                        bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ) {
                        outputEos = true
                    }

                    val image =
                        decoder.getOutputImage(outIndex)

                    if (image != null) {

                        val original =
                            ImageUtils.imageToBitmap(image)

                        image.close()

                        val anime =
                            nativeProcessFrame(original)

                        val finalBitmap =
                            nativeBlendFrames(
                                original,
                                anime,
                                strength
                            )

                        if (original !== finalBitmap && !original.isRecycled)
                            original.recycle()

                        if (anime !== finalBitmap && !anime.isRecycled)
                            anime.recycle()

                        val yuv =
                            ImageUtils.bitmapToYuv420(
                                finalBitmap,
                                metadata.width,
                                metadata.height
                            )

                        if (!finalBitmap.isRecycled)
                            finalBitmap.recycle()

                        encodeFrame(
                            encoder = encoder,
                            muxer = muxer,
                            yuvData = yuv,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            isEos = outputEos,
                            onTrackReady = {
                                muxerTrack = it
                                muxer.start()
                                muxerStarted = true
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

                    decoder.releaseOutputBuffer(outIndex, false)
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
                muxer?.stop()
                muxer?.release()
            }

            runCatching {
                extractor.release()
            }
        }
    }

    private fun selectVideoTrack(
        extractor: MediaExtractor
    ): Int {

        for (i in 0 until extractor.trackCount) {

            val mime =
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?: ""

            if (mime.startsWith("video/"))
                return i
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

        while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {

            when {

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    val track =
                        muxer.addTrack(encoder.outputFormat)

                    onTrackReady(track)
                }

                outputIndex >= 0 -> {

                    if (isMuxerStarted()) {

                        val encoded =
                            encoder.getOutputBuffer(outputIndex)

                        if (
                            encoded != null &&
                            info.size > 0
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
