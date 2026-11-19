package com.example.media

import com.example.data.local.DialogueSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.tanh

object AudioMixer {

    data class PositionedTts(
        val segment: DialogueSegmentEntity,
        val wavFile: File
    )

    /**
     * Mixes BGM stem + optional original audio reference + positioned Hindi TTS WAV clips
     * into [outputMixedWav] using streaming 1-second windows (safe for 2-hour movies).
     */
    suspend fun mixChunk(
        origWavFile: File,
        bgmWavFile: File?,
        ttsSegments: List<PositionedTts>,
        chunkStartMs: Long,
        hindiVolume: Float,
        bgmVolume: Float,
        originalVolume: Float,
        outputMixedWav: File
    ) = withContext(Dispatchers.IO) {
        val origHeader = WavUtils.readHeader(origWavFile)
        val sampleRate = origHeader.sampleRate
        val channels = origHeader.channels
        val totalFrames = (origHeader.dataSize / origHeader.bytesPerFrame).toInt()
        outputMixedWav.parentFile?.mkdirs()

        // Precompute sample offsets for each valid TTS clip
        data class TtsClipHandle(
            val startFrame: Long,
            val endFrame: Long,
            val wavFile: File,
            val header: WavHeader
        )

        val clipHandles = ttsSegments.mapNotNull { (seg, file) ->
            if (!file.exists()) return@mapNotNull null
            val hdr = runCatching { WavUtils.readHeader(file) }.getOrNull() ?: return@mapNotNull null
            val relStartMs = (seg.startMs - chunkStartMs).coerceAtLeast(0L)
            val startFrame = (relStartMs * sampleRate) / 1000L
            val clipFrames = hdr.dataSize / hdr.bytesPerFrame.coerceAtLeast(1)
            TtsClipHandle(
                startFrame = startFrame,
                endFrame = startFrame + clipFrames,
                wavFile = file,
                header = hdr
            )
        }

        val bgmHeader = bgmWavFile?.takeIf { it.exists() }?.let { WavUtils.readHeader(it) }

        val windowFrames = sampleRate // 1 second window
        val windowBytes = windowFrames * origHeader.bytesPerFrame

        RandomAccessFile(origWavFile, "r").use { origRaf ->
            val bgmRaf = if (bgmWavFile != null && bgmHeader != null) {
                RandomAccessFile(bgmWavFile, "r").apply { seek(bgmHeader.dataOffset) }
            } else null

            try {
                RandomAccessFile(outputMixedWav, "rw").use { outRaf ->
                    outRaf.setLength(0)
                    WavUtils.writeHeader(outRaf, sampleRate, channels, 16, origHeader.dataSize)
                    origRaf.seek(origHeader.dataOffset)

                    val origBuf = ByteArray(windowBytes)
                    val bgmBuf = ByteArray(windowBytes)
                    val mixAccum = FloatArray(windowFrames * channels)
                    var frameOffset = 0L

                    while (frameOffset < totalFrames) {
                        coroutineContext.ensureActive()
                        val framesInWindow = minOf(windowFrames.toLong(), totalFrames - frameOffset).toInt()
                        val bytesInWindow = framesInWindow * origHeader.bytesPerFrame

                        origRaf.readFully(origBuf, 0, bytesInWindow)
                        val origShorts = WavUtils.bytesToShortArray(origBuf, bytesInWindow)

                        val bgmShorts = if (bgmRaf != null) {
                            val bgmAvail = (bgmRaf.length() - bgmRaf.filePointer).coerceAtLeast(0L)
                            val readNow = minOf(bytesInWindow.toLong(), bgmAvail).toInt()
                            if (readNow > 0) {
                                bgmRaf.readFully(bgmBuf, 0, readNow)
                                WavUtils.bytesToShortArray(bgmBuf, readNow)
                            } else null
                        } else null

                        // Base bed: BGM + Original bleed
                        val sampleCount = framesInWindow * channels
                        for (i in 0 until sampleCount) {
                            val origSample = origShorts[i].toFloat() * originalVolume
                            val bgmSample = if (bgmShorts != null && i < bgmShorts.size) {
                                bgmShorts[i].toFloat() * bgmVolume
                            } else {
                                origShorts[i].toFloat() * bgmVolume
                            }
                            mixAccum[i] = origSample + bgmSample
                        }

                        // Overlay active Hindi TTS segments overlapping [frameOffset, frameOffset + framesInWindow)
                        val winEnd = frameOffset + framesInWindow
                        for (clip in clipHandles) {
                            if (clip.endFrame <= frameOffset || clip.startFrame >= winEnd) continue
                            val overlapStart = maxOf(frameOffset, clip.startFrame)
                            val overlapEnd = minOf(winEnd, clip.endFrame)
                            val overlapFrames = (overlapEnd - overlapStart).toInt()
                            if (overlapFrames <= 0) continue

                            val clipSkipFrames = overlapStart - clip.startFrame
                            val clipBytesOffset = clip.header.dataOffset +
                                clipSkipFrames * clip.header.bytesPerFrame
                            val readBytesCount = overlapFrames * clip.header.bytesPerFrame
                            val clipBuf = ByteArray(readBytesCount)

                            RandomAccessFile(clip.wavFile, "r").use { clipRaf ->
                                if (clipBytesOffset + readBytesCount <= clipRaf.length()) {
                                    clipRaf.seek(clipBytesOffset)
                                    clipRaf.readFully(clipBuf)
                                }
                            }
                            val clipShorts = WavUtils.bytesToShortArray(clipBuf)
                            val dstStartFrame = (overlapStart - frameOffset).toInt()

                            for (f in 0 until overlapFrames) {
                                val dstIdxBase = (dstStartFrame + f) * channels
                                val srcIdxBase = f * clip.header.channels
                                for (c in 0 until channels) {
                                    val srcC = c.coerceAtMost(clip.header.channels - 1)
                                    val s = clipShorts[srcIdxBase + srcC].toFloat() * hindiVolume
                                    // Gentle BGM ducking (-3dB) where Hindi speech is actively speaking
                                    mixAccum[dstIdxBase + c] =
                                        (mixAccum[dstIdxBase + c] * 0.72f) + s
                                }
                            }
                        }

                        // Soft-knee limiter to prevent digital clipping
                        val outShorts = ShortArray(sampleCount)
                        for (i in 0 until sampleCount) {
                            val normalized = mixAccum[i] / 32768f
                            val limited = if (kotlin.math.abs(normalized) > 0.92f) {
                                tanh(normalized.toDouble()).toFloat()
                            } else normalized
                            outShorts[i] = (limited * 32767f)
                                .roundToInt()
                                .coerceIn(-32768, 32767)
                                .toShort()
                        }

                        outRaf.write(WavUtils.shortArrayToBytes(outShorts))
                        frameOffset += framesInWindow
                    }
                }
            } finally {
                runCatching { bgmRaf?.close() }
            }
        }
    }
}
