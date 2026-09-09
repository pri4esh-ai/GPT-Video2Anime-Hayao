package com.gptvideo2anime.pipeline
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
class MediaCodecVideoEngine(private val context: Context) {
    data class VideoInfo(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val durationUs: Long,
        val mime: String,
        val rotationDegrees: Int,
        val bitrate: Int
    )
    fun inspect(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                requireNotNull(descriptor) { "Unable to open input video" }
                extractor.setDataSource(descriptor.fileDescriptor)
            }
            var videoFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoFormat = format
                    break
                }
            }
            val format = requireNotNull(videoFormat) { "No video track found" }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                format.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else {
                30
            }.coerceIn(1, 240)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                format.getInteger(MediaFormat.KEY_BIT_RATE)
            } else {
                0
            }
            return VideoInfo(
                width = width,
                height = height,
                frameRate = frameRate,
                durationUs = durationUs,
                mime = mime,
                rotationDegrees = rotationDegrees,
                bitrate = bitrate
            )
        } finally {
            extractor.release()
        }
    }
    fun findDecoder(mime: String): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { type ->
                type.equals(mime, ignoreCase = true)
            }
        }
    }
    fun findEncoder(mime: String): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { type ->
                type.equals(mime, ignoreCase = true)
            }
        }
    }
    fun bestEncoderMime(inputMime: String): String {
        val preferred = when (inputMime.lowercase()) {
            "video/hevc" -> listOf("video/hevc", "video/avc")
            "video/av01" -> listOf("video/av01", "video/hevc", "video/avc")
            "video/x-vnd.on2.vp9" -> listOf("video/x-vnd.on2.vp9", "video/hevc", "video/avc")
            else -> listOf("video/avc", "video/hevc")
        }
        return preferred.firstOrNull { findEncoder(it) != null } ?: "video/avc"
    }
    fun hasHardwareDecoder(mime: String): Boolean {
        return findDecoder(mime) != null
    }
    fun hasEncoder(mime: String): Boolean {
        return findEncoder(mime) != null
    }
}
