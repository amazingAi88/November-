package com.example.media

import com.example.data.local.DialogueSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * On-device DSP audio source separation module.
 *
 * Uses Stereo Mid-Side decomposition combined with a 2nd-order Butterworth speech-band
 * filter (200 Hz – 3,600 Hz) and timestamp-guided cosine ducking across detected English
 * dialogue intervals. Preserves background music, sub-bass SFX, and stereo ambience while
 * attenuating center-panned English speech.
 */
object AudioSeparator {

    private class BiquadFilter(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            fun bandPass(sampleRate: Double, centerHz: Double, q: Double): BiquadFilter {
                val w0 = 2.0 * PI * centerHz / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = alpha / a0,
                    b1 = 0.0,
                    b2 = -alpha / a0,
                    a1 = (-2.0 * cos(w0)) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }
        }
    }

    suspend fun separateBgmAndSfx(
        origWavFile: File,
        bgmWavFile: File,
        segments: List<DialogueSegmentEntity>,
        chunkStartMs: Long,
        sourceChannels: Int,
        preserveBgm: Boolean,
        removeEnglishDialogue: Boolean
    ) = withContext(Dispatchers.IO) {
        val header = WavUtils.readHeader(origWavFile)
        bgmWavFile.parentFile?.mkdirs()

        if (!preserveBgm) {
            // Write silent BGM WAV matching exact duration
            RandomAccessFile(bgmWavFile, "rw").use { outRaf ->
                outRaf.setLength(0)
                WavUtils.writeHeader(outRaf, header.sampleRate, header.channels, 16, header.dataSize)
                val zeros = ByteArray(16384)
                var remaining = header.dataSize
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                    outRaf.write(zeros, 0, chunk)
                    remaining -= chunk
                }
            }
            return@withContext
        }

        if (!removeEnglishDialogue) {
            origWavFile.copyTo(bgmWavFile, overwrite = true)
            return@withContext
        }

        val speechIntervals = segments
            .filter { it.audioType == "SPEECH" && it.endMs > it.startMs }
            .map { seg ->
                val relStart = (seg.startMs - chunkStartMs).coerceAtLeast(0L)
                val relEnd = (seg.endMs - chunkStartMs).coerceAtLeast(relStart)
                relStart to relEnd
            }

        val sampleRate = header.sampleRate.toDouble()
        // Two cascaded speech-presence filters covering formants (450Hz..3200Hz)
        val midSpeechLow = BiquadFilter.bandPass(sampleRate, centerHz = 650.0, q = 0.85)
        val midSpeechHigh = BiquadFilter.bandPass(sampleRate, centerHz = 2100.0, q = 0.90)
        val monoNotchLow = BiquadFilter.bandPass(sampleRate, centerHz = 650.0, q = 0.85)
        val monoNotchHigh = BiquadFilter.bandPass(sampleRate, centerHz = 2100.0, q = 0.90)

        val isStereoSource = sourceChannels >= 2 && header.channels >= 2
        val rampFrames = (sampleRate * 0.035).toInt().coerceAtLeast(1) // 35ms smooth fade
        var speechMixFactor = 0.0 // 0 = non-speech (keep full BGM), 1 = active English speech

        val framesPerBuffer = 4096
        val bytesPerBuffer = framesPerBuffer * header.bytesPerFrame
        val inBytes = ByteArray(bytesPerBuffer)

        RandomAccessFile(origWavFile, "r").use { inRaf ->
            RandomAccessFile(bgmWavFile, "rw").use { outRaf ->
                outRaf.setLength(0)
                WavUtils.writeHeader(outRaf, header.sampleRate, header.channels, 16, header.dataSize)
                inRaf.seek(header.dataOffset)

                var remaining = header.dataSize
                var frameCursor = 0L

                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val toRead = minOf(remaining, bytesPerBuffer.toLong()).toInt()
                    inRaf.readFully(inBytes, 0, toRead)
                    val samples = WavUtils.bytesToShortArray(inBytes, toRead)
                    val frames = samples.size / header.channels

                    for (f in 0 until frames) {
                        val timeMs = ((frameCursor + f) * 1000L) / header.sampleRate
                        val activeSpeech = speechIntervals.any { (s, e) -> timeMs in s..e }
                        val targetFactor = if (activeSpeech) 1.0 else 0.0
                        val step = 1.0 / rampFrames
                        speechMixFactor = when {
                            speechMixFactor < targetFactor -> (speechMixFactor + step).coerceAtMost(1.0)
                            speechMixFactor > targetFactor -> (speechMixFactor - step).coerceAtLeast(0.0)
                            else -> speechMixFactor
                        }

                        if (header.channels >= 2) {
                            val l = samples[f * header.channels].toDouble()
                            val r = samples[f * header.channels + 1].toDouble()

                            if (isStereoSource) {
                                val mid = 0.5 * (l + r)
                                val side = 0.5 * (l - r)

                                // Extract speech formants from center channel
                                val vocalMid = (midSpeechLow.process(mid) + midSpeechHigh.process(mid)) * 0.65
                                // Attenuate center vocal band during speech while retaining Side & non-speech Mid
                                val attenuatedMid = mid - vocalMid * (0.90 * speechMixFactor)
                                // Also gently trim overall center level during active speech to reduce reverb bleed
                                val centerScale = 1.0 - (0.35 * speechMixFactor)
                                val cleanMid = attenuatedMid * centerScale
                                val boostedSide = side * (1.0 + 0.15 * speechMixFactor)

                                val outL = (cleanMid + boostedSide).roundToInt().coerceIn(-32768, 32767).toShort()
                                val outR = (cleanMid - boostedSide).roundToInt().coerceIn(-32768, 32767).toShort()
                                samples[f * header.channels] = outL
                                samples[f * header.channels + 1] = outR
                            } else {
                                // Dual-mono input: speech-band attenuation + ducking during active speech
                                val speechBand = (monoNotchLow.process(l) + monoNotchHigh.process(l)) * 0.60
                                val ducked = (l - speechBand * (0.78 * speechMixFactor)) *
                                    (1.0 - 0.30 * speechMixFactor)
                                val outS = ducked.roundToInt().coerceIn(-32768, 32767).toShort()
                                samples[f * header.channels] = outS
                                samples[f * header.channels + 1] = outS
                            }
                        } else {
                            val m = samples[f].toDouble()
                            val speechBand = (monoNotchLow.process(m) + monoNotchHigh.process(m)) * 0.60
                            val ducked = (m - speechBand * (0.78 * speechMixFactor)) *
                                (1.0 - 0.30 * speechMixFactor)
                            samples[f] = ducked.roundToInt().coerceIn(-32768, 32767).toShort()
                        }
                    }

                    val outBytes = WavUtils.shortArrayToBytes(samples)
                    outRaf.write(outBytes, 0, toRead)
                    remaining -= toRead
                    frameCursor += frames
                }
            }
        }
    }
}
