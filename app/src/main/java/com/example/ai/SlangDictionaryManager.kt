package com.example.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class SlangOverrideRule(
    val id: String,
    val sourceTerm: String,
    val hindiReplacement: String,
    val contextKeywords: List<String> = emptyList(),
    val dialogueStyles: List<String> = emptyList(), // Empty = applies to all styles
    val wholeWord: Boolean = true,
    val enabled: Boolean = true,
    val note: String = ""
)

data class SlangDictionaryConfig(
    val version: Int = 1,
    val enabled: Boolean = true,
    val rules: List<SlangOverrideRule> = emptyList()
)

data class MatchedSlangRule(
    val rule: SlangOverrideRule,
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val contextMatchCount: Int
)

data class ProtectedTranslationPrep(
    val originalEnglish: String,
    val protectedEnglish: String,
    val directOverrideHindi: String?,
    val appliedMatches: List<MatchedSlangRule>,
    val placeholders: List<Pair<String, MatchedSlangRule>>
)

/**
 * Manages a local JSON-based slang, colloquialism, and context-dependent translation dictionary
 * stored in the app's internal storage (`filesDir/slang_override_dictionary.json`).
 *
 * Designed to override censored/textbook machine translation output (e.g., ML Kit) and enforce
 * authentic spoken Hindi mappings inside Local GGUF LLM and Cloud prompts.
 */
object SlangDictionaryManager {

    const val DICTIONARY_FILE_NAME = "slang_override_dictionary.json"
    private val fileLock = Any()

    fun dictionaryFile(context: Context): File =
        File(context.filesDir, DICTIONARY_FILE_NAME)

    fun ensureInitialized(context: Context): SlangDictionaryConfig = synchronized(fileLock) {
        val file = dictionaryFile(context)
        if (!file.exists() || file.length() == 0L) {
            val defaults = defaultConfig()
            writeConfigLocked(file, defaults)
            return defaults
        }
        return runCatching {
            parseConfigJson(file.readText(Charsets.UTF_8))
        }.getOrElse {
            val defaults = defaultConfig()
            writeConfigLocked(file, defaults)
            defaults
        }
    }

    fun loadConfig(context: Context): SlangDictionaryConfig = ensureInitialized(context)

    fun saveConfig(context: Context, config: SlangDictionaryConfig): SlangDictionaryConfig = synchronized(fileLock) {
        val file = dictionaryFile(context)
        writeConfigLocked(file, config)
        config
    }

    fun readRawJson(context: Context): String {
        ensureInitialized(context)
        return synchronized(fileLock) {
            dictionaryFile(context).readText(Charsets.UTF_8)
        }
    }

    fun importRawJson(context: Context, jsonText: String): SlangDictionaryConfig {
        val parsed = parseConfigJson(jsonText)
        return saveConfig(context, parsed)
    }

    fun resetToDefaults(context: Context): SlangDictionaryConfig = synchronized(fileLock) {
        val defaults = defaultConfig()
        writeConfigLocked(dictionaryFile(context), defaults)
        defaults
    }

    fun setDictionaryEnabled(context: Context, enabled: Boolean): SlangDictionaryConfig {
        val current = loadConfig(context)
        return saveConfig(context, current.copy(enabled = enabled))
    }

    fun upsertRule(context: Context, rule: SlangOverrideRule): SlangDictionaryConfig {
        val current = loadConfig(context)
        val cleanSource = rule.sourceTerm.trim()
        val cleanHindi = rule.hindiReplacement.trim()
        require(cleanSource.isNotEmpty() && cleanHindi.isNotEmpty()) {
            "Both English term and Hindi replacement are required."
        }
        val normalized = rule.copy(
            id = rule.id.ifBlank { UUID.randomUUID().toString() },
            sourceTerm = cleanSource,
            hindiReplacement = cleanHindi,
            contextKeywords = rule.contextKeywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            dialogueStyles = rule.dialogueStyles.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            note = rule.note.trim()
        )
        val idx = current.rules.indexOfFirst { it.id == normalized.id }
        val updated = if (idx >= 0) {
            current.rules.toMutableList().apply { set(idx, normalized) }
        } else {
            listOf(normalized) + current.rules
        }
        return saveConfig(context, current.copy(rules = updated))
    }

    fun toggleRuleEnabled(context: Context, ruleId: String, enabled: Boolean): SlangDictionaryConfig {
        val current = loadConfig(context)
        val updated = current.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
        return saveConfig(context, current.copy(rules = updated))
    }

    fun deleteRule(context: Context, ruleId: String): SlangDictionaryConfig {
        val current = loadConfig(context)
        return saveConfig(context, current.copy(rules = current.rules.filterNot { it.id == ruleId }))
    }

    /**
     * Resolves all non-overlapping dictionary matches in [englishText], prioritizing:
     * 1) Context-keyword matches (higher matched keyword count in line + prev/next scene + emotion)
     * 2) Longer phrase length (e.g., "what the fuck" > "fuck you" > "fuck")
     */
    fun resolveMatches(
        config: SlangDictionaryConfig,
        englishText: String,
        prevContext: String = "",
        nextContext: String = "",
        emotion: String = "",
        dialogueStyle: String = ""
    ): List<MatchedSlangRule> {
        if (!config.enabled || englishText.isBlank()) return emptyList()

        val contextHaystack = buildString {
            append(englishText)
            if (prevContext.isNotBlank()) { append(' '); append(prevContext) }
            if (nextContext.isNotBlank()) { append(' '); append(nextContext) }
            if (emotion.isNotBlank()) { append(' '); append(emotion) }
        }.lowercase(Locale.US)

        data class Candidate(
            val rule: SlangOverrideRule,
            val start: Int,
            val end: Int,
            val matchedText: String,
            val contextHits: Int
        )

        val candidates = mutableListOf<Candidate>()
        for (rule in config.rules) {
            if (!rule.enabled || rule.sourceTerm.isBlank() || rule.hindiReplacement.isBlank()) continue
            val styleOk = rule.dialogueStyles.isEmpty() ||
                dialogueStyle.isBlank() ||
                rule.dialogueStyles.any {
                    it.equals(dialogueStyle, ignoreCase = true) || it.equals("ALL", ignoreCase = true)
                }
            if (!styleOk) continue

            val contextHits = if (rule.contextKeywords.isEmpty()) {
                0
            } else {
                val hits = rule.contextKeywords.count { kw ->
                    val cleanKw = kw.trim().lowercase(Locale.US)
                    cleanKw.isNotEmpty() && containsTerm(contextHaystack, cleanKw, wholeWord = !cleanKw.contains(' '))
                }
                if (hits == 0) continue // Context-scoped rule only triggers if context keyword matches
                hits
            }

            val regex = buildTermRegex(rule.sourceTerm, rule.wholeWord)
            regex.findAll(englishText).forEach { m ->
                candidates.add(
                    Candidate(
                        rule = rule,
                        start = m.range.first,
                        end = m.range.last + 1,
                        matchedText = m.value,
                        contextHits = contextHits
                    )
                )
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Sort by context match priority first, then longer term length, then earlier position
        val prioritized = candidates.sortedWith(
            compareByDescending<Candidate> { it.contextHits }
                .thenByDescending { it.rule.sourceTerm.length }
                .thenByDescending { it.end - it.start }
                .thenBy { it.start }
        )

        val accepted = mutableListOf<MatchedSlangRule>()
        val claimed = BooleanArray(englishText.length)
        for (c in prioritized) {
            val overlaps = (c.start until c.end).any { claimed[it] }
            if (!overlaps) {
                for (i in c.start until c.end) claimed[i] = true
                accepted.add(
                    MatchedSlangRule(
                        rule = c.rule,
                        startIndex = c.start,
                        endIndex = c.end,
                        matchedText = c.matchedText,
                        contextMatchCount = c.contextHits
                    )
                )
            }
        }
        return accepted.sortedBy { it.startIndex }
    }

    /**
     * Prepares an English sentence for standard ML Kit translation:
     * - If the whole line is an exact rule match, returns [ProtectedTranslationPrep.directOverrideHindi] directly.
     * - Otherwise replaces matched slang spans with neutral uppercase tokens (`ZXQ0QXZ`, `ZXQ1QXZ`, ...)
     *   so ML Kit cannot sanitize, drop, or mis-translate them into textbook words like "लानत" / "मूर्ख".
     */
    fun prepareProtectedText(
        config: SlangDictionaryConfig,
        englishText: String,
        prevContext: String = "",
        nextContext: String = "",
        emotion: String = "",
        dialogueStyle: String = ""
    ): ProtectedTranslationPrep {
        val matches = resolveMatches(
            config = config,
            englishText = englishText,
            prevContext = prevContext,
            nextContext = nextContext,
            emotion = emotion,
            dialogueStyle = dialogueStyle
        )
        if (matches.isEmpty()) {
            return ProtectedTranslationPrep(
                originalEnglish = englishText,
                protectedEnglish = englishText,
                directOverrideHindi = null,
                appliedMatches = emptyList(),
                placeholders = emptyList()
            )
        }

        // Exact full-line match (ignoring surrounding whitespace/punctuation)
        val trimmedCore = englishText.trim().trim('"', '\'', '.', '!', '?', ',', ';', ':')
        if (matches.size == 1 &&
            matches[0].matchedText.trim().equals(trimmedCore, ignoreCase = true)
        ) {
            val trailing = englishText.trim().takeLastWhile { it in charArrayOf('!', '?') }
            return ProtectedTranslationPrep(
                originalEnglish = englishText,
                protectedEnglish = englishText,
                directOverrideHindi = matches[0].rule.hindiReplacement + trailing,
                appliedMatches = matches,
                placeholders = emptyList()
            )
        }

        val sb = StringBuilder()
        val placeholders = mutableListOf<Pair<String, MatchedSlangRule>>()
        var cursor = 0
        matches.forEachIndexed { idx, match ->
            if (match.startIndex > cursor) {
                sb.append(englishText, cursor, match.startIndex)
            }
            val token = "ZXQ${idx}QXZ"
            sb.append(token)
            placeholders.add(token to match)
            cursor = match.endIndex
        }
        if (cursor < englishText.length) {
            sb.append(englishText, cursor, englishText.length)
        }

        return ProtectedTranslationPrep(
            originalEnglish = englishText,
            protectedEnglish = sb.toString(),
            directOverrideHindi = null,
            appliedMatches = matches,
            placeholders = placeholders
        )
    }

    /**
     * Restores neutral tokens in [machineTranslatedHindi] with exact dictionary Hindi replacements,
     * and cleans up any stray sanitized NMT artifacts if a placeholder was altered.
     */
    fun restoreAndOverride(
        machineTranslatedHindi: String,
        prep: ProtectedTranslationPrep
    ): String {
        if (prep.directOverrideHindi != null) return prep.directOverrideHindi
        if (prep.placeholders.isEmpty()) return machineTranslatedHindi

        var result = machineTranslatedHindi
        for ((token, match) in prep.placeholders) {
            val tokenRegex = Regex("""\b${Regex.escape(token)}\b""", RegexOption.IGNORE_CASE)
            if (tokenRegex.containsMatchIn(result)) {
                result = tokenRegex.replace(result, Regex.escapeReplacement(match.rule.hindiReplacement))
            } else {
                // Fallback if translator stripped spaces/punctuation around token
                val looseRegex = Regex(Regex.escape(token), RegexOption.IGNORE_CASE)
                if (looseRegex.containsMatchIn(result)) {
                    result = looseRegex.replace(result, Regex.escapeReplacement(match.rule.hindiReplacement))
                } else if (!result.contains(match.rule.hindiReplacement)) {
                    // Replace sanitized fallback words or ensure the override appears
                    result = replaceKnownSanitizedWords(result, match.rule.hindiReplacement)
                }
            }
        }
        return result.replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Formats matched dictionary rules as mandatory glossary directives for Local GGUF LLM / Gemini prompts.
     */
    fun buildPromptGlossaryHints(matches: List<MatchedSlangRule>): String {
        if (matches.isEmpty()) return ""
        val uniquePairs = matches
            .distinctBy { it.rule.sourceTerm.lowercase(Locale.US) to it.rule.hindiReplacement }
        return buildString {
            appendLine("LOCAL JSON SLANG DICTIONARY OVERRIDES (MANDATORY - DO NOT CENSOR):")
            uniquePairs.forEach { m ->
                val notePart = if (m.rule.note.isNotBlank()) " [${m.rule.note}]" else ""
                appendLine("- \"${m.rule.sourceTerm}\" -> \"${m.rule.hindiReplacement}\"$notePart")
            }
        }.trimEnd()
    }

    /**
     * Ensures exact single-line overrides or sanitized leakage from LLMs are corrected post-generation.
     */
    fun postProcessLlmOutput(
        llmHindi: String,
        englishText: String,
        matches: List<MatchedSlangRule>
    ): String {
        if (matches.isEmpty()) return llmHindi
        val trimmedCore = englishText.trim().trim('"', '\'', '.', '!', '?', ',', ';', ':')
        if (matches.size == 1 && matches[0].matchedText.trim().equals(trimmedCore, ignoreCase = true)) {
            val trailing = englishText.trim().takeLastWhile { it in charArrayOf('!', '?') }
            return matches[0].rule.hindiReplacement + trailing
        }
        var updated = llmHindi
        for (m in matches) {
            if (!updated.contains(m.rule.hindiReplacement)) {
                updated = replaceKnownSanitizedWords(updated, m.rule.hindiReplacement)
            }
        }
        return updated
    }

    private fun replaceKnownSanitizedWords(hindiText: String, replacement: String): String {
        val sanitizedPatterns = listOf("लानत है", "लानत", "धिक्कार", "मूर्ख", "बेवकूफ", "कमबख्त", "बकवास")
        for (sanitized in sanitizedPatterns) {
            if (hindiText.contains(sanitized)) {
                return hindiText.replaceFirst(sanitized, replacement)
            }
        }
        return "$hindiText $replacement".trim()
    }

    private fun containsTerm(haystackLower: String, termLower: String, wholeWord: Boolean): Boolean {
        return buildTermRegex(termLower, wholeWord).containsMatchIn(haystackLower)
    }

    private fun buildTermRegex(term: String, wholeWord: Boolean): Regex {
        val escaped = Regex.escape(term.trim())
        val pattern = if (wholeWord) """(?i)\b$escaped\b""" else """(?i)$escaped"""
        return Regex(pattern)
    }

    fun toPrettyJson(config: SlangDictionaryConfig): String {
        val rulesArr = JSONArray()
        config.rules.forEach { rule ->
            rulesArr.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("sourceTerm", rule.sourceTerm)
                    put("hindiReplacement", rule.hindiReplacement)
                    put("contextKeywords", JSONArray(rule.contextKeywords))
                    put("dialogueStyles", JSONArray(rule.dialogueStyles))
                    put("wholeWord", rule.wholeWord)
                    put("enabled", rule.enabled)
                    put("note", rule.note)
                }
            )
        }
        return JSONObject().apply {
            put("version", config.version)
            put("enabled", config.enabled)
            put("rules", rulesArr)
        }.toString(2)
    }

    fun parseConfigJson(raw: String): SlangDictionaryConfig {
        val root = JSONObject(raw)
        val version = root.optInt("version", 1)
        val enabled = root.optBoolean("enabled", true)
        val arr = root.optJSONArray("rules") ?: JSONArray()
        val parsedRules = mutableListOf<SlangOverrideRule>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val src = obj.optString("sourceTerm", "").trim()
            val hindi = obj.optString("hindiReplacement", "").trim()
            if (src.isEmpty() || hindi.isEmpty()) continue

            val ctxArr = obj.optJSONArray("contextKeywords")
            val ctxList = mutableListOf<String>()
            if (ctxArr != null) {
                for (j in 0 until ctxArr.length()) {
                    val kw = ctxArr.optString(j, "").trim()
                    if (kw.isNotEmpty()) ctxList.add(kw)
                }
            }

            val styleArr = obj.optJSONArray("dialogueStyles")
            val styleList = mutableListOf<String>()
            if (styleArr != null) {
                for (j in 0 until styleArr.length()) {
                    val st = styleArr.optString(j, "").trim()
                    if (st.isNotEmpty()) styleList.add(st)
                }
            }

            parsedRules.add(
                SlangOverrideRule(
                    id = obj.optString("id", "").ifBlank { "rule_$i" },
                    sourceTerm = src,
                    hindiReplacement = hindi,
                    contextKeywords = ctxList,
                    dialogueStyles = styleList,
                    wholeWord = obj.optBoolean("wholeWord", true),
                    enabled = obj.optBoolean("enabled", true),
                    note = obj.optString("note", "")
                )
            )
        }
        return SlangDictionaryConfig(version = version, enabled = enabled, rules = parsedRules)
    }

    private fun writeConfigLocked(file: File, config: SlangDictionaryConfig) {
        file.parentFile?.mkdirs()
        file.writeText(toPrettyJson(config), Charsets.UTF_8)
    }

    fun defaultConfig(): SlangDictionaryConfig = SlangDictionaryConfig(
        version = 1,
        enabled = true,
        rules = listOf(
            SlangOverrideRule(
                id = "slang_wtf",
                sourceTerm = "what the fuck",
                hindiReplacement = "ये क्या बकचोदी है",
                note = "Shock / disbelief colloquial phrase"
            ),
            SlangOverrideRule(
                id = "slang_fuck_off",
                sourceTerm = "fuck off",
                hindiReplacement = "चल निकल भोसड़ीके",
                note = "Aggressive street dismissal"
            ),
            SlangOverrideRule(
                id = "slang_fuck_you",
                sourceTerm = "fuck you",
                hindiReplacement = "भाड़ में जा मादरचोद",
                note = "Confrontational curse"
            ),
            SlangOverrideRule(
                id = "slang_mf",
                sourceTerm = "motherfucker",
                hindiReplacement = "मादरचोद",
                note = "Heavy crime / action cinema slang"
            ),
            SlangOverrideRule(
                id = "slang_mf_spaced",
                sourceTerm = "mother fucker",
                hindiReplacement = "मादरचोद",
                note = "Spaced STT transcription variant"
            ),
            SlangOverrideRule(
                id = "slang_fucker",
                sourceTerm = "fucker",
                hindiReplacement = "चुतिया",
                note = "Overrides ML Kit 'मूर्ख' translation"
            ),
            SlangOverrideRule(
                id = "slang_fuck_intimate",
                sourceTerm = "fuck",
                hindiReplacement = "चोदना",
                contextKeywords = listOf("bed", "sex", "wanna", "want", "baby", "babe", "tonight", "hard", "kiss", "body", "touch", "naked", "pussy", "cock"),
                note = "Erotic / intimate scene context"
            ),
            SlangOverrideRule(
                id = "slang_fuck_anger",
                sourceTerm = "fuck",
                hindiReplacement = "बकचोदी",
                contextKeywords = listOf("hell", "shit", "damn", "wrong", "happened", "shut", "man"),
                note = "Anger / frustration context"
            ),
            SlangOverrideRule(
                id = "slang_fuck_default",
                sourceTerm = "fuck",
                hindiReplacement = "चोदना",
                note = "Uncensored fallback (replaces ML Kit 'लानत')"
            ),
            SlangOverrideRule(
                id = "slang_fucking_intimate",
                sourceTerm = "fucking",
                hindiReplacement = "चोदने",
                contextKeywords = listOf("bed", "sex", "hard", "slow", "fast", "hot", "her", "him"),
                note = "Intimate scene action"
            ),
            SlangOverrideRule(
                id = "slang_fucking_default",
                sourceTerm = "fucking",
                hindiReplacement = "बहनचोद",
                note = "Colloquial emphasis intensifier"
            ),
            SlangOverrideRule(
                id = "slang_bullshit",
                sourceTerm = "bullshit",
                hindiReplacement = "बकचोदी",
                note = "Informal dismissal"
            ),
            SlangOverrideRule(
                id = "slang_asshole",
                sourceTerm = "asshole",
                hindiReplacement = "गांडू",
                note = "Street insult"
            ),
            SlangOverrideRule(
                id = "slang_sob",
                sourceTerm = "son of a bitch",
                hindiReplacement = "कुत्ते की औलाद",
                note = "Dramatic confrontation"
            ),
            SlangOverrideRule(
                id = "slang_bitch_adult",
                sourceTerm = "bitch",
                hindiReplacement = "रंडी",
                contextKeywords = listOf("pay", "money", "bed", "cheap", "whore", "slut", "night", "strip"),
                note = "Adult / nightlife scene context"
            ),
            SlangOverrideRule(
                id = "slang_bitch_default",
                sourceTerm = "bitch",
                hindiReplacement = "कुतिया",
                note = "General confrontation"
            ),
            SlangOverrideRule(
                id = "slang_bastard",
                sourceTerm = "bastard",
                hindiReplacement = "हरामी",
                note = "OTT crime / villain slang"
            ),
            SlangOverrideRule(
                id = "slang_pussy_adult",
                sourceTerm = "pussy",
                hindiReplacement = "चूत",
                contextKeywords = listOf("wet", "lick", "fuck", "tight", "touch", "eat", "spread"),
                note = "Adult / intimate scene context"
            ),
            SlangOverrideRule(
                id = "slang_pussy_coward",
                sourceTerm = "pussy",
                hindiReplacement = "फट्टू",
                contextKeywords = listOf("scared", "afraid", "coward", "run", "hide", "weak", "cry"),
                note = "Cowardice slang context"
            ),
            SlangOverrideRule(
                id = "slang_cock",
                sourceTerm = "cock",
                hindiReplacement = "लंड",
                note = "Adult anatomical slang"
            ),
            SlangOverrideRule(
                id = "slang_dick_adult",
                sourceTerm = "dick",
                hindiReplacement = "लंड",
                contextKeywords = listOf("suck", "lick", "hard", "big", "wet", "mouth", "throat"),
                note = "Adult / intimate context"
            ),
            SlangOverrideRule(
                id = "slang_dick_insult",
                sourceTerm = "dick",
                hindiReplacement = "लौड़े",
                note = "Casual insult fallback"
            ),
            SlangOverrideRule(
                id = "slang_screw_you",
                sourceTerm = "screw you",
                hindiReplacement = "भाड़ में जा",
                note = "Informal dismissal"
            ),
            SlangOverrideRule(
                id = "slang_what_the_hell",
                sourceTerm = "what the hell",
                hindiReplacement = "ये क्या बकवास है",
                note = "Casual frustration"
            ),
            SlangOverrideRule(
                id = "slang_badass",
                sourceTerm = "badass",
                hindiReplacement = "दबंग",
                note = "Bollywood action colloquialism"
            )
        )
    )
}
