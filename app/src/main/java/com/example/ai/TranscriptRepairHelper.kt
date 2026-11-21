package com.example.ai

import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.SpeakerEntity
import java.util.Locale

/**
 * Scene-context intelligence & broken-ASR cleanup helper:
 * 1. Merges micro-chopped STT bursts (`gap <= 320ms`) and removes stutter duplicates.
 * 2. Infers the surrounding scene mood across a +/-2 line window (Intimate, Confrontation, Action, Emotional, Casual).
 * 3. Infers listener gender from adjacent turn-taking speakers so Hindi verbs/pronouns (`कर रहे हो` vs `कर रही हो`) agree naturally.
 * 4. Flags garbled phonetic ASR lines (`warming three mean more stone`) so LLMs infer the intended cinema dialogue from scene context.
 */
object TranscriptRepairHelper {

    private val ROMANTIC_INTIMATE_REGEX = Regex(
        """\b(love|kiss\w*|baby|babe|darling|honey|touch|feel|bed|night|closer|hold me|miss you|want you|desire|naked|harder|please don't stop)\b""",
        RegexOption.IGNORE_CASE
    )
    private val CONFLICT_ANGER_REGEX = Regex(
        """\b(fuck\w*|shit|bitch|asshole|bastard|shut up|get out|leave me|hate|liar|stupid|idiot|kill|hell|damn|scream\w*|cheat\w*)\b""",
        RegexOption.IGNORE_CASE
    )
    private val URGENT_ACTION_REGEX = Regex(
        """\b(run|hurry|quick|gun|shoot|police|cops|escape|bomb|car|drive|fast|help|watch out|careful|move|now)\b""",
        RegexOption.IGNORE_CASE
    )
    private val EMOTIONAL_DRAMA_REGEX = Regex(
        """\b(sorry|cry|tears|hurt|trust|promise|family|die|dead|pain|alone|truth|forgive|goodbye)\b""",
        RegexOption.IGNORE_CASE
    )

    private val COMMON_VERBS_OR_FUNCTION_WORDS = setOf(
        "i", "you", "we", "they", "he", "she", "it", "this", "that", "is", "are", "was", "were",
        "am", "be", "been", "do", "does", "did", "have", "has", "had", "will", "would", "can",
        "could", "should", "want", "need", "know", "think", "tell", "say", "said", "go", "going",
        "come", "coming", "stop", "wait", "look", "see", "hear", "feel", "love", "hate", "get",
        "got", "make", "made", "take", "took", "give", "gave", "let", "don't", "can't", "won't",
        "didn't", "i'm", "you're", "it's", "what", "why", "how", "where", "who", "when", "please",
        "yes", "no", "okay", "ok", "not"
    )

    /**
     * Merges choppy adjacent STT bursts (e.g. Vosk splitting one sentence on a micro-breath <=320ms)
     * and cleans consecutive duplicate filler words ("what what do you mean").
     */
    fun mergeAndRepairPhrases(
        phrases: List<OnDeviceSpeakerAnalyzer.SpeechRawPhrase>
    ): List<OnDeviceSpeakerAnalyzer.SpeechRawPhrase> {
        if (phrases.isEmpty()) return emptyList()
        val sorted = phrases
            .filter { it.text.isNotBlank() && it.endOffsetMs > it.startOffsetMs }
            .sortedBy { it.startOffsetMs }
        if (sorted.isEmpty()) return emptyList()

        val cleaned = sorted.map { p ->
            p.copy(text = collapseImmediateDuplicateWords(p.text.trim()))
        }

        val merged = mutableListOf<OnDeviceSpeakerAnalyzer.SpeechRawPhrase>()
        for (current in cleaned) {
            val prev = merged.lastOrNull()
            if (prev != null && shouldMergeAdjacent(prev, current)) {
                merged[merged.lastIndex] = prev.copy(
                    endOffsetMs = maxOf(prev.endOffsetMs, current.endOffsetMs),
                    text = collapseImmediateDuplicateWords("${prev.text} ${current.text}")
                )
            } else {
                merged.add(current)
            }
        }
        return merged
    }

    private fun shouldMergeAdjacent(
        prev: OnDeviceSpeakerAnalyzer.SpeechRawPhrase,
        next: OnDeviceSpeakerAnalyzer.SpeechRawPhrase
    ): Boolean {
        // Keep non-lexical vocal SFX separate so they stay preserved as VOCAL_SFX
        if (VocalSfxClassifier.isNonLexicalVocalSound(prev.text) ||
            VocalSfxClassifier.isNonLexicalVocalSound(next.text)
        ) {
            return false
        }
        val gapMs = next.startOffsetMs - prev.endOffsetMs
        if (gapMs > 320L || gapMs < -200L) return false
        val prevTrimmed = prev.text.trim()
        if (prevTrimmed.endsWith('.') || prevTrimmed.endsWith('?') || prevTrimmed.endsWith('!')) {
            return false
        }
        val prevWords = prevTrimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        val nextWords = next.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        // Merge if previous burst was a short unfinished clause and combined line stays concise
        return prevWords <= 5 && (prevWords + nextWords) <= 16
    }

    internal fun collapseImmediateDuplicateWords(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size <= 1) return text.trim()
        val out = mutableListOf<String>()
        for (tok in tokens) {
            val prev = out.lastOrNull()
            if (prev != null && prev.equals(tok, ignoreCase = true) && tok.length <= 6) {
                continue
            }
            out.add(tok)
        }
        return out.joinToString(" ")
    }

    /**
     * Infers scene mood from a +/-2 segment window around [centerIndex] using both dialogue cues
     * and detected audio delivery emotion.
     */
    fun inferSceneMood(segments: List<DialogueSegmentEntity>, centerIndex: Int): String {
        val from = (centerIndex - 2).coerceAtLeast(0)
        val to = (centerIndex + 2).coerceAtMost(segments.lastIndex)
        if (to < from) return "Casual Cinema Dialogue"

        val window = segments.subList(from, to + 1)
        val combinedText = window.joinToString(" ") { it.englishText }
        val emotions = window.map { it.emotion.lowercase(Locale.US) }

        return when {
            ROMANTIC_INTIMATE_REGEX.containsMatchIn(combinedText) ||
                emotions.count { it == "whisper" || it == "calm" } >= 2 &&
                window.any { it.audioType == "VOCAL_SFX" } -> "Intimate / Romantic"
            CONFLICT_ANGER_REGEX.containsMatchIn(combinedText) ||
                emotions.count { it == "angry" } >= 1 -> "Heated Confrontation / Angry"
            URGENT_ACTION_REGEX.containsMatchIn(combinedText) ||
                emotions.count { it == "fearful" || it == "excited" || it == "energetic" } >= 2 -> "Urgent Action / Thriller"
            EMOTIONAL_DRAMA_REGEX.containsMatchIn(combinedText) ||
                emotions.count { it == "sad" } >= 1 -> "Emotional / Drama"
            else -> "Casual Cinema Dialogue"
        }
    }

    /**
     * Resolves the probable listener gender from adjacent segments spoken by a different speaker,
     * enabling accurate Hindi second-person verb agreement (`तुम कर रही हो` vs `कर रहे हो`).
     */
    fun inferListenerGender(
        segments: List<DialogueSegmentEntity>,
        centerIndex: Int,
        speakerMap: Map<String, SpeakerEntity>
    ): String? {
        val currentSpeaker = segments.getOrNull(centerIndex)?.speakerLabel ?: return null
        val offsets = listOf(-1, 1, -2, 2)
        for (delta in offsets) {
            val neighbor = segments.getOrNull(centerIndex + delta) ?: continue
            if (neighbor.audioType == "SPEECH" && neighbor.speakerLabel != currentSpeaker) {
                val gender = speakerMap[neighbor.speakerLabel]?.gender
                if (!gender.isNullOrBlank() && gender != "NEUTRAL") {
                    return gender
                }
            }
        }
        return null
    }

    /**
     * Detects phrases likely corrupted by background noise in offline ASR
     * (e.g., `"warming three mean more stone"` — long chain of nouns/adjectives with almost no
     * conversational pronouns, auxiliaries, or punctuation).
     */
    fun isLikelyAsrGarble(englishText: String): Boolean {
        val tokens = englishText.lowercase(Locale.US)
            .split(Regex("""[\s,!?\."']+"""))
            .filter { it.isNotBlank() }
        if (tokens.size < 4) return false
        val knownHits = tokens.count { it in COMMON_VERBS_OR_FUNCTION_WORDS }
        val ratio = knownHits.toFloat() / tokens.size.toFloat()
        return knownHits == 0 || (tokens.size >= 5 && ratio < 0.18f)
    }
}
