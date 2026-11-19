package com.example.ai

import com.example.media.AudioChunkExtractor
import com.example.media.WavUtils
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight on-device DSP speech & pitch analyzer for 16kHz Mono 16-bit PCM WAV files.
 * Estimates fundamental voice pitch (F0 in Hz) via normalized autocorrelation to distinguish
 * deeper Male voices, higher Female voices, and ambient Music/SFX vs Silence intervals
 * without requiring cloud diarization or heavy neural models (Android Go compatible).
 */
object OnDeviceSpeakerAnalyzer {

    data class SpeechRawPhrase(
        val startOffsetMs: Long,
        val endOffsetMs: Long,
        val text: String
    )

    fun analyzeSpeakersAndBuildTimeline(
        sttWavFile: File,
        phrases: List<SpeechRawPhrase>,
        chunkDurationMs: Long
    ): ChunkTranscriptionResult {
        val header = WavUtils.readHeader(sttWavFile)
        val sampleRate = header.sampleRate.coerceAtLeast(AudioChunkExtractor.STT_SAMPLE_RATE)
        val totalFrames = (header.dataSize / (header.channels * 2L)).toInt().coerceAtLeast(0)

        val pcmMono = ShortArray(totalFrames)
        if (totalFrames > 0) {
            val rawBytes = ByteArray(totalFrames * 2)
            RandomAccessFile(sttWavFile, "r").use { raf ->
                raf.seek(header.dataOffset)
                val toRead = minOf(rawBytes.size.toLong(), header.dataSize).toInt()
                raf.readFully(rawBytes, 0, toRead)
            }
            for (i in 0 until totalFrames) {
                val lo = rawBytes[i * 2].toInt() and 0xFF
                val hi = rawBytes[i * 2 + 1].toInt()
                pcmMono[i] = ((hi shl 8) or lo).toShort()
            }
        }

        val discoveredSpeakers = linkedMapOf<String, TranscribedSpeaker>()
        val timeline = mutableListOf<TranscribedSegment>()

        var cursorMs = 0L
        val sortedPhrases = phrases
            .filter { it.text.isNotBlank() && it.endOffsetMs > it.startOffsetMs }
            .sortedBy { it.startOffsetMs }

        for (phrase in sortedPhrases) {
            val sMs = phrase.startOffsetMs.coerceIn(0L, chunkDurationMs)
            val eMs = phrase.endOffsetMs.coerceIn(sMs + 100L, chunkDurationMs)

            // Fill gap before this phrase as MUSIC or SILENCE based on measured RMS energy
            if (sMs - cursorMs >= 700L) {
                val gapRms = computeIntervalRms(pcmMono, sampleRate, cursorMs, sMs)
                val isAmbientSound = gapRms > 0.015f
                timeline.add(
                    TranscribedSegment(
                        startOffsetMs = cursorMs,
                        endOffsetMs = sMs,
                        speakerLabel = "AMBIENT",
                        englishText = if (isAmbientSound) "[Music / SFX]" else "[Silence]",
                        emotion = "neutral",
                        isOverlapping = false,
                        audioType = if (isAmbientSound) "MUSIC" else "SILENCE"
                    )
                )
            }

            val pitchHz = estimateMedianPitchHz(pcmMono, sampleRate, sMs, eMs)
            val (speakerId, gender, traits) = classifySpeakerFromPitch(pitchHz)

            if (!discoveredSpeakers.containsKey(speakerId)) {
                discoveredSpeakers[speakerId] = TranscribedSpeaker(
                    speakerLabel = speakerId,
                    inferredGender = gender,
                    traits = traits
                )
            }

            timeline.add(
                TranscribedSegment(
                    startOffsetMs = sMs,
                    endOffsetMs = eMs,
                    speakerLabel = speakerId,
                    englishText = phrase.text.trim(),
                    emotion = estimateEmotionFromEnergy(pcmMono, sampleRate, sMs, eMs),
                    isOverlapping = false,
                    audioType = "SPEECH"
                )
            )
            cursorMs = maxOf(cursorMs, eMs)
        }

        if (chunkDurationMs - cursorMs >= 700L) {
            val tailRms = computeIntervalRms(pcmMono, sampleRate, cursorMs, chunkDurationMs)
            val isAmbientSound = tailRms > 0.015f
            timeline.add(
                TranscribedSegment(
                    startOffsetMs = cursorMs,
                    endOffsetMs = chunkDurationMs,
                    speakerLabel = "AMBIENT",
                    englishText = if (isAmbientSound) "[Music / SFX]" else "[Silence]",
                    emotion = "neutral",
                    isOverlapping = false,
                    audioType = if (isAmbientSound) "MUSIC" else "SILENCE"
                )
            )
        }

        return ChunkTranscriptionResult(
            speakers = discoveredSpeakers.values.toList(),
            segments = timeline
        )
    }

    private fun classifySpeakerFromPitch(pitchHz: Float?): Triple<String, String, String> {
        return when {
            pitchHz == null -> Triple("SPEAKER_1", "MALE", "Default voice profile (pitch ambiguous)")
            pitchHz < 130f -> Triple("SPEAKER_1", "MALE", "Deep male pitch (~${pitchHz.roundToInt()} Hz)")
            pitchHz < 166f -> Triple("SPEAKER_3", "MALE", "Warm male pitch (~${pitchHz.roundToInt()} Hz)")
            pitchHz <= 280f -> Triple("SPEAKER_2", "FEMALE", "Female pitch (~${pitchHz.roundToInt()} Hz)")
            else -> Triple("SPEAKER_4", "NEUTRAL", "High/youthful pitch (~${pitchHz.roundToInt()} Hz)")
        }
    }

    private fun estimateEmotionFromEnergy(
        pcm: ShortArray,
        sampleRate: Int,
        startMs: Long,
        endMs: Long
    ): String {
        val rms = computeIntervalRms(pcm, sampleRate, startMs, endMs)
        return when {
            rms > 0.16f -> "energetic"
            rms < 0.03f -> "calm"
            else -> "natural"
        }
    }

    private fun computeIntervalRms(
        pcm: ShortArray,
        sampleRate: Int,
        startMs: Long,
        endMs: Long
    ): Float {
        if (pcm.isEmpty()) return 0f
        // Trim up to 80ms from both edges when interval is long enough so speech boundary tails don't dominate
        val marginMs = if (endMs - startMs > 400L) 80L else 0L
        val startSample = (((startMs + marginMs) * sampleRate) / 1000L).toInt().coerceIn(0, pcm.size - 1)
        val endSample = (((endMs - marginMs) * sampleRate) / 1000L).toInt().coerceIn(startSample + 1, pcm.size)
        var sumSq = 0.0
        val count = endSample - startSample
        for (i in startSample until endSample) {
            val v = pcm[i] / 32768.0
            sumSq += v * v
        }
        return sqrt(sumSq / count.coerceAtLeast(1)).toFloat()
    }

    /**
     * Estimates median fundamental frequency (F0 in Hz) across 30ms windows using
     * normalized autocorrelation within human vocal range (75 Hz .. 300 Hz).
     */
    fun estimateMedianPitchHz(
        pcm: ShortArray,
        sampleRate: Int,
        startMs: Long,
        endMs: Long
    ): Float? {
        if (pcm.isEmpty()) return null
        val startSample = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, pcm.size - 1)
        val endSample = ((endMs * sampleRate) / 1000L).toInt().coerceIn(startSample, pcm.size)
        val windowSize = (sampleRate * 0.030).toInt().coerceAtLeast(256) // 30ms
        val hop = windowSize
        val minLag = (sampleRate / 300f).toInt().coerceAtLeast(20)
        val maxLag = (sampleRate / 75f).toInt().coerceAtMost(windowSize / 2)
        if (maxLag <= minLag) return null

        val estimates = mutableListOf<Float>()
        var pos = startSample
        while (pos + windowSize <= endSample && estimates.size < 25) {
            var energy = 0.0
            for (i in 0 until windowSize) {
                val v = pcm[pos + i] / 32768.0
                energy += v * v
            }
            val rms = sqrt(energy / windowSize)
            if (rms > 0.025) {
                var bestLag = -1
                var bestCorr = 0.0
                for (lag in minLag..maxLag) {
                    var num = 0.0
                    var d1 = 0.0
                    var d2 = 0.0
                    val limit = windowSize - lag
                    for (i in 0 until limit) {
                        val a = pcm[pos + i].toDouble()
                        val b = pcm[pos + i + lag].toDouble()
                        num += a * b
                        d1 += a * a
                        d2 += b * b
                    }
                    val denom = sqrt(d1 * d2)
                    if (denom > 1e-6) {
                        val corr = num / denom
                        if (corr > bestCorr) {
                            bestCorr = corr
                            bestLag = lag
                        }
                    }
                }
                if (bestLag > 0 && bestCorr > 0.55) {
                    val f0 = sampleRate.toFloat() / bestLag.toFloat()
                    if (f0 in 75f..300f && abs(f0) > 1f) {
                        estimates.add(f0)
                    }
                }
            }
            pos += hop
        }

        if (estimates.isEmpty()) return null
        estimates.sort()
        return estimates[estimates.size / 2]
    }
}
