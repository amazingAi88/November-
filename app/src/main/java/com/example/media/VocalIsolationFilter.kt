package com.example.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight on-device DSP vocal isolation pre-filter run strictly on the 16kHz Mono STT stream
 * before Vosk / Whisper transcription. Leaves the 44.1kHz Stereo original & BGM stems untouched.
 *
 * Pipeline:
 * 1. Stereo Center Extraction (when srcChannels >= 2): extracts centered dialogue `0.5*(L+R)` and
 *    gently attenuates wide-panned stereo music/SFX (`|L-R| > |L+R|`).
 * 2. 2nd-order Butterworth High-Pass (160 Hz) to strip bass explosions, rumble & wind thumps.
 * 3. 2nd-order Butterworth Low-Pass (3600 Hz) to strip cymbals, hi-hats & background hiss.
 */
object VocalIsolationFilter {

    /**
     * Stateful biquad filter state preserved across sequential PCM decode buffers so chunk
     * extraction introduces zero clicks or boundary transients.
     */
    class StreamState(val sampleRate: Int = AudioChunkExtractor.STT_SAMPLE_RATE) {
        private val hpf = BiquadFilter.highPass(sampleRate = sampleRate, cutoffHz = 160.0)
        private val lpf = BiquadFilter.lowPass(sampleRate = sampleRate, cutoffHz = 3600.0)

        fun processSttChunk(monoSamples: ShortArray): ShortArray {
            if (monoSamples.isEmpty()) return monoSamples
            val out = ShortArray(monoSamples.size)
            for (i in monoSamples.indices) {
                val hp = hpf.process(monoSamples[i].toDouble())
                val bp = lpf.process(hp)
                // Slight 1.15x vocal presence makeup gain with safe PCM16 clipping
                out[i] = (bp * 1.15).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            return out
        }
    }

    /**
     * Converts interleaved PCM16 stereo/multichannel audio to center-weighted vocal mono at [dstRate]
     * while suppressing wide-panned background music (`Side = L - R`) relative to centered voice (`Mid = L + R`).
     */
    fun extractCenterFocusedMono(
        input: ShortArray,
        srcRate: Int,
        srcChannels: Int,
        dstRate: Int = AudioChunkExtractor.STT_SAMPLE_RATE
    ): ShortArray {
        if (input.isEmpty() || srcChannels <= 0 || srcRate <= 0) return ShortArray(0)
        if (srcChannels == 1) {
            return WavUtils.convertAndTimeScalePcm(
                input = input,
                srcRate = srcRate,
                srcChannels = 1,
                dstRate = dstRate,
                dstChannels = 1,
                timeScale = 1.0f
            )
        }

        val srcFrames = input.size / srcChannels
        if (srcFrames == 0) return ShortArray(0)
        val centerMono = ShortArray(srcFrames)

        for (i in 0 until srcFrames) {
            val l = input[i * srcChannels].toFloat()
            val r = input[i * srcChannels + 1].toFloat()
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            val midAbs = abs(mid)
            val sideAbs = abs(side)
            // When stereo side energy dominates over centered mid energy (e.g. wide stereo score/SFX),
            // attenuate up to 55% to keep centered movie dialogue clear for STT.
            val sideDominance = ((sideAbs - midAbs * 0.45f) / (midAbs + sideAbs + 120f)).coerceIn(0f, 1f)
            val vocalGain = 1.0f - (0.55f * sideDominance)
            centerMono[i] = (mid * vocalGain).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        return WavUtils.convertAndTimeScalePcm(
            input = centerMono,
            srcRate = srcRate,
            srcChannels = 1,
            dstRate = dstRate,
            dstChannels = 1,
            timeScale = 1.0f
        )
    }

    private class BiquadFilter(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            fun highPass(sampleRate: Int, cutoffHz: Double): BiquadFilter {
                val w0 = 2.0 * PI * (cutoffHz / sampleRate.coerceAtLeast(8000))
                val cosW0 = cos(w0)
                val alpha = sin(w0) / sqrt(2.0) // Butterworth Q = 1/sqrt(2)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 + cosW0) / 2.0) / a0,
                    b1 = (-(1.0 + cosW0)) / a0,
                    b2 = ((1.0 + cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            fun lowPass(sampleRate: Int, cutoffHz: Double): BiquadFilter {
                val clampedCutoff = cutoffHz.coerceAtMost(sampleRate * 0.45)
                val w0 = 2.0 * PI * (clampedCutoff / sampleRate.coerceAtLeast(8000))
                val cosW0 = cos(w0)
                val alpha = sin(w0) / sqrt(2.0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 - cosW0) / 2.0) / a0,
                    b1 = (1.0 - cosW0) / a0,
                    b2 = ((1.0 - cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }
        }
    }
}
