package com.example.media

import com.example.ai.OnDeviceSpeakerAnalyzer

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Parses `.srt` (SubRip) and `.vtt` (WebVTT) subtitle files cleanly into millisecond-accurate cues.
 * When a subtitle track is provided, the pipeline skips slow/error-prone speech recognition and
 * feeds exact movie dialogue straight into pitch diarization & Hindi scene translation.
 */
object SrtSubtitleParser {

    private val TIMESTAMP_LINE_REGEX = Regex(
        """^\s*((?:\d{1,2}:)?\d{1,2}:\d{2}[,\.]\d{3})\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}[,\.]\d{3})"""
    )
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val ASS_TAG_REGEX = Regex("""\{[^}]+\}""")
    private val SPEAKER_PREFIX_REGEX = Regex("""^\s*\[?[A-Z][A-Za-z0-9\s]{0,18}\]?\s*:\s*""")

    fun parse(rawContent: String): List<SubtitleCue> {
        if (rawContent.isBlank()) return emptyList()
        val normalized = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val cues = mutableListOf<SubtitleCue>()
        val blocks = normalized.split(Regex("""\n{2,}"""))

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines.first().startsWith("WEBVTT", ignoreCase = true) ||
                lines.first().startsWith("NOTE", ignoreCase = true) ||
                lines.first().startsWith("STYLE", ignoreCase = true)
            ) {
                continue
            }

            val tsIdx = lines.indexOfFirst { TIMESTAMP_LINE_REGEX.containsMatchIn(it) }
            if (tsIdx < 0) continue

            val match = TIMESTAMP_LINE_REGEX.find(lines[tsIdx]) ?: continue
            val startMs = parseTimestampMs(match.groupValues[1]) ?: continue
            val endMs = parseTimestampMs(match.groupValues[2]) ?: continue
            if (endMs <= startMs) continue

            val textLines = lines.drop(tsIdx + 1)
                .map { cleanCueLine(it) }
                .filter { it.isNotBlank() }

            if (textLines.isEmpty()) continue
            val combined = textLines.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
            if (combined.isBlank()) continue

            cues.add(
                SubtitleCue(
                    startMs = startMs,
                    endMs = maxOf(endMs, startMs + 250L),
                    text = combined
                )
            )
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Extracts subtitle cues falling within [chunkStartMs, chunkEndMs] as relative chunk offsets.
     */
    fun slicesForChunk(
        cues: List<SubtitleCue>,
        chunkStartMs: Long,
        chunkEndMs: Long
    ): List<OnDeviceSpeakerAnalyzer.SpeechRawPhrase> {
        val chunkDur = (chunkEndMs - chunkStartMs).coerceAtLeast(1000L)
        return cues.mapNotNull { cue ->
            if (cue.endMs <= chunkStartMs || cue.startMs >= chunkEndMs) {
                null
            } else {
                val relStart = (cue.startMs - chunkStartMs).coerceIn(0L, chunkDur - 150L)
                val relEnd = (cue.endMs - chunkStartMs).coerceIn(relStart + 150L, chunkDur)
                OnDeviceSpeakerAnalyzer.SpeechRawPhrase(
                    startOffsetMs = relStart,
                    endOffsetMs = relEnd,
                    text = cue.text
                )
            }
        }
    }

    internal fun parseTimestampMs(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        val parts = cleaned.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val secParts = parts[2].split('.')
                val s = secParts.getOrNull(0)?.toLongOrNull() ?: return null
                val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
                (h * 3_600_000L) + (m * 60_000L) + (s * 1_000L) + ms
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val secParts = parts[1].split('.')
                val s = secParts.getOrNull(0)?.toLongOrNull() ?: return null
                val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
                (m * 60_000L) + (s * 1_000L) + ms
            }
            else -> null
        }
    }

    fun detectLikelyLanguage(cues: List<SubtitleCue>): String {
        if (cues.isEmpty()) return com.example.data.local.SubtitleLanguage.ENGLISH
        val sample = cues.take(20).joinToString(" ") { it.text }
        var devanagariCount = 0
        var alphaCount = 0
        for (ch in sample) {
            if (ch in '\u0900'..'\u097F') devanagariCount++
            if (ch.isLetter()) alphaCount++
        }
        return if (alphaCount > 0 && devanagariCount * 4 >= alphaCount) {
            com.example.data.local.SubtitleLanguage.HINDI
        } else {
            com.example.data.local.SubtitleLanguage.ENGLISH
        }
    }

    private fun cleanCueLine(line: String): String {
        var s = ASS_TAG_REGEX.replace(line, " ")
        s = HTML_TAG_REGEX.replace(s, " ")
        // Strip leading SDH speaker prefix like "- " or "JOHN:"
        s = s.trim()
        if (s.startsWith("- ")) s = s.removePrefix("- ").trim()
        s = SPEAKER_PREFIX_REGEX.replace(s, "")
        // Skip purely music symbols lines unless explicit bracketed sound
        if (s.all { it == '♪' || it == '♫' || it.isWhitespace() }) return "[Music]"
        return s.replace("♪", "").replace("♫", "").trim()
    }
}
