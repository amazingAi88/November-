package com.example.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

data class WavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val durationMs: Long
        get() = if (sampleRate > 0 && bytesPerFrame > 0) {
            (dataSize * 1000L) / (sampleRate.toLong() * bytesPerFrame)
        } else 0L
}

object WavUtils {

    fun writeHeader(
        raf: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int = 16,
        dataSize: Long = 0L
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // PCM format
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
        raf.seek(0)
        raf.write(header.array())
    }

    fun patchDataSize(raf: RandomAccessFile, dataSize: Long) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        raf.seek(4)
        bb.putInt((36L + dataSize).toInt())
        raf.write(bb.array())

        bb.clear()
        raf.seek(40)
        bb.putInt(dataSize.toInt())
        raf.write(bb.array())
    }

    fun readHeader(file: File): WavHeader {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(12)
            raf.readFully(buf)
            val riff = String(buf, 0, 4, Charsets.US_ASCII)
            val wave = String(buf, 8, 4, Charsets.US_ASCII)
            require(riff == "RIFF" && wave == "WAVE") { "Invalid WAV file: ${file.name}" }

            var sampleRate = 44100
            var channels = 2
            var bitsPerSample = 16
            var dataOffset = 44L
            var dataSize = (file.length() - 44L).coerceAtLeast(0L)

            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtLeast(16))
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // audioFormat
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt()
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size.coerceAtMost(raf.length() - dataOffset)
                        break
                    }
                    else -> {
                        raf.seek(raf.filePointer + size + (size % 2))
                    }
                }
            }
            return WavHeader(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
        }
    }

    fun writePcmBytesAsWav(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        outFile: File
    ) {
        outFile.parentFile?.mkdirs()
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            writeHeader(raf, sampleRate, channels, 16, pcmBytes.size.toLong())
            raf.write(pcmBytes)
        }
    }

    fun bytesToShortArray(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        val count = length / 2
        val shorts = ShortArray(count)
        ByteBuffer.wrap(bytes, 0, count * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }

    fun shortArrayToBytes(shorts: ShortArray, count: Int = shorts.size): ByteArray {
        val bytes = ByteArray(count * 2)
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(shorts, 0, count)
        return bytes
    }

    /**
     * Converts interleaved PCM16 from (srcRate, srcChannels) to (dstRate, dstChannels)
     * with optional timeScale (>1.0f = faster speech, <1.0f = slower speech).
     */
    fun convertAndTimeScalePcm(
        input: ShortArray,
        srcRate: Int,
        srcChannels: Int,
        dstRate: Int,
        dstChannels: Int,
        timeScale: Float = 1.0f
    ): ShortArray {
        if (input.isEmpty() || srcChannels <= 0 || srcRate <= 0) return ShortArray(0)
        val srcFrames = input.size / srcChannels
        if (srcFrames == 0) return ShortArray(0)

        val effectiveScale = timeScale.coerceIn(0.6f, 1.6f)
        val dstFrames = ((srcFrames.toDouble() * dstRate / srcRate) / effectiveScale)
            .roundToInt()
            .coerceAtLeast(1)

        val output = ShortArray(dstFrames * dstChannels)
        val step = (srcFrames - 1).coerceAtLeast(0).toDouble() / dstFrames.coerceAtLeast(1)

        for (i in 0 until dstFrames) {
            val pos = (i * step).coerceIn(0.0, (srcFrames - 1).toDouble())
            val idx0 = pos.toInt()
            val idx1 = (idx0 + 1).coerceAtMost(srcFrames - 1)
            val frac = (pos - idx0).toFloat()

            if (srcChannels == 1 && dstChannels == 2) {
                val s0 = input[idx0].toFloat()
                val s1 = input[idx1].toFloat()
                val sample = (s0 + (s1 - s0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
                output[i * 2] = sample
                output[i * 2 + 1] = sample
            } else if (srcChannels >= 2 && dstChannels == 1) {
                val l0 = input[idx0 * srcChannels].toFloat()
                val r0 = input[idx0 * srcChannels + 1].toFloat()
                val l1 = input[idx1 * srcChannels].toFloat()
                val r1 = input[idx1 * srcChannels + 1].toFloat()
                val m0 = (l0 + r0) * 0.5f
                val m1 = (l1 + r1) * 0.5f
                output[i] = (m0 + (m1 - m0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
            } else {
                for (c in 0 until dstChannels) {
                    val srcCh = c.coerceAtMost(srcChannels - 1)
                    val s0 = input[idx0 * srcChannels + srcCh].toFloat()
                    val s1 = input[idx1 * srcChannels + srcCh].toFloat()
                    output[i * dstChannels + c] =
                        (s0 + (s1 - s0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
        }
        return output
    }

    fun formatTimecode(ms: Long, includeMillis: Boolean = false): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val millis = clamped % 1000L
        return if (hours > 0) {
            if (includeMillis) {
                String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
            } else {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            }
        } else {
            if (includeMillis) {
                String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}
