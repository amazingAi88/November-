package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Reliable HTTP Range-resumable file downloader (`Range: bytes=<offset>-`).
 * Keeps the `.part` file intact on network drops, timeouts, or coroutine cancellation so
 * large models (like 1.12 GB Qwen 2.5 1.5B GGUF or Whisper GGML) resume seamlessly from the exact
 * last downloaded byte instead of restarting from 0%.
 */
object ResumableHttpDownloader {

    private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    private val CONTENT_RANGE_416_REGEX = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Downloads [url] into [partFile] (appending if server returns HTTP 206 Partial Content),
     * then promotes to [destFile] atomically upon completion and optional [validateComplete].
     */
    suspend fun downloadToFile(
        url: String,
        partFile: File,
        destFile: File,
        fallbackApproxBytes: Long = 0L,
        reportIntervalBytes: Long = 512L * 1024L,
        validateComplete: ((File) -> Boolean)? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        partFile.parentFile?.mkdirs()
        destFile.parentFile?.mkdirs()

        if (destFile.exists() && (validateComplete?.invoke(destFile) != false)) {
            val len = destFile.length()
            onProgress(len, len)
            return@withContext destFile
        }

        var existingBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
        var retriedAfter416 = false

        while (true) {
            coroutineContext.ensureActive()
            val conn = openWithRedirects(
                url = url,
                rangeStart = existingBytes
            )
            try {
                val code = conn.responseCode
                if (code == 416) {
                    val crHeader = conn.getHeaderField("Content-Range")
                    val totalFrom416 = parse416Total(crHeader)
                    if (totalFrom416 != null && totalFrom416 > 0L && existingBytes == totalFrom416) {
                        onProgress(existingBytes, existingBytes)
                        break
                    }
                    // Stored .part size mismatch with remote resource; delete only on explicit 416 mismatch & retry once
                    conn.disconnect()
                    partFile.delete()
                    existingBytes = 0L
                    if (retriedAfter416) {
                        throw IllegalStateException("Server returned HTTP 416 (Range Not Satisfiable).")
                    }
                    retriedAfter416 = true
                    continue
                }

                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code while downloading ${destFile.name}.")
                }

                val isPartial = (code == HttpURLConnection.HTTP_PARTIAL) // 206
                val appendMode = isPartial && existingBytes > 0L
                val totalBytes = resolveTotalBytes(
                    isPartial = isPartial,
                    existingBytes = if (appendMode) existingBytes else 0L,
                    contentLength = conn.contentLengthLong,
                    contentRangeHeader = conn.getHeaderField("Content-Range"),
                    fallbackApproxBytes = fallbackApproxBytes
                )

                var downloaded = if (appendMode) existingBytes else 0L
                if (downloaded > 0L) {
                    onProgress(downloaded, totalBytes)
                }

                var lastReport = downloaded
                val buf = ByteArray(64 * 1024)

                BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (downloaded - lastReport >= reportIntervalBytes) {
                                lastReport = downloaded
                                onProgress(downloaded, maxOf(totalBytes, downloaded))
                            }
                        }
                        output.flush()
                    }
                }
                break
            } finally {
                conn.disconnect()
            }
        }

        if (validateComplete != null && !validateComplete(partFile)) {
            partFile.delete()
            throw IllegalStateException("Downloaded file ${destFile.name} failed integrity verification.")
        }

        if (destFile.exists()) destFile.delete()
        if (!partFile.renameTo(destFile)) {
            partFile.copyTo(destFile, overwrite = true)
            partFile.delete()
        }
        val finalSize = destFile.length()
        onProgress(finalSize, finalSize)
        destFile
    }

    internal fun parse416Total(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val match = CONTENT_RANGE_416_REGEX.find(contentRange.trim()) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    internal fun resolveTotalBytes(
        isPartial: Boolean,
        existingBytes: Long,
        contentLength: Long,
        contentRangeHeader: String?,
        fallbackApproxBytes: Long
    ): Long {
        if (isPartial && !contentRangeHeader.isNullOrBlank()) {
            val match = CONTENT_RANGE_REGEX.find(contentRangeHeader.trim())
            val totalStr = match?.groupValues?.getOrNull(3)
            val parsedTotal = totalStr?.toLongOrNull()
            if (parsedTotal != null && parsedTotal > 0L) return parsedTotal
        }
        if (contentLength > 0L) {
            return if (isPartial) existingBytes + contentLength else contentLength
        }
        return fallbackApproxBytes.coerceAtLeast(existingBytes)
    }

    private suspend fun openWithRedirects(
        url: String,
        rangeStart: Long
    ): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        while (redirects < 8) {
            coroutineContext.ensureActive()
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "HindiMovieDubbingStudio/1.0")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) {
                    throw IllegalStateException("HTTP redirect missing Location header.")
                }
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many HTTP redirects downloading file.")
    }
}
