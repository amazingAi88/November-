package com.example.ai

import java.util.Locale

/**
 * Classifies non-lexical vocalizations, mouth sounds, moans, sighs, laughter, and reaction
 * fillers (e.g., "ah ha", "ha", "ya", "amh", "oh my god", "ph ph", "ah", "oh yeah",
 * "umhh", "uhh", "umm") as vocal SFX / musical vocal sounds so they are preserved from the
 * original soundtrack instead of being translated and overlaid with robotic TTS.
 */
object VocalSfxClassifier {

    private val BRACKETED_TAG_REGEX = Regex("""^\s*[\[\(\*<].*[\]\)\*>]\s*$""")

    // Multi-word vocalization / reaction phrases (case-insensitive, space-normalized)
    private val MULTI_WORD_VOCAL_PHRASES = listOf(
        "oh my god",
        "oh my gosh",
        "oh yeah",
        "oh yea",
        "oh ya",
        "oh yes",
        "oh god",
        "oh my",
        "my god",
        "ah yeah",
        "ah yea",
        "ah ya",
        "ah ha",
        "a ha",
        "ah ah",
        "uh huh",
        "uh oh",
        "nuh uh",
        "mm hmm",
        "hmm hmm",
        "ph ph",
        "ha ha",
        "ha ha ha",
        "ho ho",
        "he he",
        "la la",
        "na na"
    ).sortedByDescending { it.length }

    // Single-token non-lexical vocal sounds & elongated mouth-music exclamations
    private val SINGLE_VOCAL_TOKEN_PATTERNS = listOf(
        Regex("""^a+h+$"""),                  // ah, ahh, aah, aaah
        Regex("""^a+h+a+$"""),                // aha, ahha
        Regex("""^o+h+$"""),                  // oh, ohh, ooh, oooh
        Regex("""^u+h+$"""),                  // uh, uhh, uhhh
        Regex("""^u+m+h*$"""),                // um, umm, umh, umhh, umhhh
        Regex("""^a+m+h+$"""),                // amh, amhh (excludes English verb "am")
        Regex("""^a+m{2,}$"""),               // amm, ammm (excludes "am")
        Regex("""^m{2,}h*$"""),               // mm, mmm, mmh
        Regex("""^m+h+m+$"""),                // mhm, mhmm
        Regex("""^h+m+$"""),                  // hm, hmm, hmmm
        Regex("""^h+a+h*$"""),                // ha, haa, hah
        Regex("""^(?:ha){2,}h*$"""),          // haha, hahaha
        Regex("""^h+e+h+(?:e+h*)*$"""),       // heh, hehe (excludes pronoun "he")
        Regex("""^h+o+h+(?:o+h*)*$"""),       // hoh, hoho
        Regex("""^y+a+h*$"""),                // ya, yaa, yah, yaah
        Regex("""^p+h+$"""),                  // ph, phh
        Regex("""^p+f+$"""),                  // pf, pff, pfff
        Regex("""^p+h+e+w+$"""),              // phew
        Regex("""^p+s+h+$"""),                // psh
        Regex("""^u+g+h+$"""),                // ugh, uggh
        Regex("""^e+w+$"""),                  // ew, eww, ewww
        Regex("""^h+u+h+$"""),                // huh
        Regex("""^a+w+$"""),                  // aw, aww, awww
        Regex("""^o+w+$"""),                  // ow, oww
        Regex("""^o+u+c+h+$"""),              // ouch
        Regex("""^w+o+w+$"""),                // wow
        Regex("""^w+h+o+a+$"""),              // whoa
        Regex("""^w+o+a+h+$"""),              // woah
        Regex("""^s+h{2,}$"""),               // shh, shhh
        Regex("""^b+r{2,}$"""),               // brr
        Regex("""^t+s+k+$"""),                // tsk
        Regex("""^(?:sigh|gasp|moan|groan|laugh|laughter|music|sfx)$""")
    )

    // Isolated 1-word Vosk breath/ambient noise false-positives that are meaningless alone
    private val ISOLATED_STT_NOISE_PARTICLES = setOf(
        "of", "the", "an", "and", "or", "er", "erm", "var"
    )

    fun isNonLexicalVocalSound(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return false
        if (BRACKETED_TAG_REGEX.matches(trimmed)) return true

        val normalized = trimmed
            .lowercase(Locale.US)
            .replace('-', ' ')
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (normalized.isEmpty()) return true

        val rawTokens = normalized.split(' ').filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return true

        // Isolated single-token Vosk ambient noise artifact (e.g., lone "of" -> "का")
        if (rawTokens.size == 1 && rawTokens[0] in ISOLATED_STT_NOISE_PARTICLES) {
            return true
        }

        // First strip any recognized multi-word vocalization phrases from the normalized string
        var remainder = " $normalized "
        var matchedPhraseCount = 0
        for (phrase in MULTI_WORD_VOCAL_PHRASES) {
            val target = " $phrase "
            while (remainder.contains(target)) {
                remainder = remainder.replace(target, " ")
                matchedPhraseCount++
            }
        }

        val remainingTokens = remainder.trim().split(' ').filter { it.isNotBlank() }
        if (remainingTokens.isEmpty()) {
            return matchedPhraseCount > 0
        }

        // Note: "god" / "gosh" alone shouldn't trigger unless part of a vocal phrase ("oh my god")
        // or accompanied by other vocal tokens; handle strictly inside token check:
        return remainingTokens.all { tok -> isSingleVocalToken(tok) }
    }

    private fun isSingleVocalToken(token: String): Boolean {
        return SINGLE_VOCAL_TOKEN_PATTERNS.any { it.matches(token) }
    }
}
