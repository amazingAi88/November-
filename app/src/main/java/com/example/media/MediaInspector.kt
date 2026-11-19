package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class InspectedMovie(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val containerFormat: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val videoMime: String,
    val audioMime: String,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val availableStorageBytes: Long,
    val thumbnailTempFile: File?
)

object MediaInspector {

    val SUPPORTED_VIDEO_MIME_TYPES = arrayOf(
        "video/mp4",
        "video/x-matroska",
        "video/webm",
        "video/quicktime",
        "video/x-msvideo",
        "video/avi",
        "video/3gpp",
        "video/3gpp2",
        "video/mp2t",
        "video/x-flv",
        "video/x-m4v",
        "video/mpeg",
        "video/ogg",
        "video/*"
    )

    suspend fun inspectMovie(context: Context, uri: Uri): InspectedMovie = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var fileName = "movie.mp4"
        var sizeBytes = 0L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }

        if (sizeBytes <= 0L) {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) sizeBytes = afd.length
            }
        }

        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var width = 0
        var height = 0
        var thumbFile: File? = null

        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            val frameTimeUs = if (durationMs > 2000L) (durationMs * 1000L) / 5L else 0L
            val bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                val cacheThumb = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
                FileOutputStream(cacheThumb).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                bitmap.recycle()
                thumbFile = cacheThumb
            }
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        var videoMime = "unknown"
        var audioMime = "none"
        var audioChannels = 0
        var audioSampleRate = 0

        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == "unknown") {
                    videoMime = mime
                    if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (durationMs == 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    }
                } else if (mime.startsWith("audio/") && audioMime == "none") {
                    audioMime = mime
                    audioChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2
                    audioSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 44100
                }
            }
        } finally {
            runCatching { extractor.release() }
        }

        require(videoMime != "unknown") { "Selected file does not contain a valid video track." }
        require(audioMime != "none") { "Selected video has no audio track to transcribe or dub." }
        require(durationMs > 0L) { "Could not read video duration from selected file." }

        val title = fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
            .trim().ifBlank { "Untitled Movie" }

        val containerFormat = detectContainerFormat(fileName, resolver.getType(uri), videoMime)

        InspectedMovie(
            uri = uri,
            fileName = fileName,
            title = title,
            containerFormat = containerFormat,
            durationMs = durationMs,
            width = width,
            height = height,
            fileSizeBytes = sizeBytes,
            videoMime = videoMime,
            audioMime = audioMime,
            audioChannels = audioChannels,
            audioSampleRate = audioSampleRate,
            availableStorageBytes = getAvailableStorageBytes(context),
            thumbnailTempFile = thumbFile
        )
    }

    fun detectContainerFormat(fileName: String, resolverMime: String?, videoMime: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "MP4"
            "mkv" -> "MKV (Matroska)"
            "webm" -> "WebM"
            "mov" -> "MOV (QuickTime)"
            "avi" -> "AVI"
            "3gp", "3gpp", "3g2" -> "3GP"
            "m4v" -> "M4V"
            "ts", "m2ts", "mts" -> "MPEG-TS"
            "flv" -> "FLV"
            "ogv", "ogg" -> "OGV"
            "mpg", "mpeg" -> "MPEG"
            else -> when (resolverMime?.lowercase()) {
                "video/x-matroska" -> "MKV"
                "video/webm" -> "WebM"
                "video/quicktime" -> "MOV"
                "video/x-msvideo", "video/avi" -> "AVI"
                "video/3gpp", "video/3gpp2" -> "3GP"
                "video/mp2t" -> "MPEG-TS"
                else -> if (videoMime.contains("vp8") || videoMime.contains("vp9")) "WebM/MKV" else "MP4/Video"
            }
        }
    }

    fun getAvailableStorageBytes(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }
}
