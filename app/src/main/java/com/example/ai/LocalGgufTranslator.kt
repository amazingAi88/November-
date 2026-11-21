package com.example.ai

import android.util.Log
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DialogueStyle
import com.example.data.local.SpeakerEntity
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val TAG = "LocalGgufTranslator"

data class LocalLlmSampleResult(
    val hindiText: String,
    val tokensPerSecond: Float
)

/**
 * Runs local open-source GGUF language models (Qwen / Gemma / Llama GGUF) completely offline
 * via native llama.cpp bindings (`dev.ffmpegkit-maintained:llama-android`) for scene-aware,
 * colloquial, unfiltered Hindi cinema dialogue translation.
 *
 * Tuned for mobile ARM64 CPUs:
 * - Uses 4 threads + 1024 context + low temperature (0.15f) for fast, deterministic Devanagari output.
 * - Translates segment-by-segment with tight token caps + fast-paths + incremental DB callbacks
 *   so long chunks never hang or lose progress.
 * - Detects AI refusals / non-Devanagari leakage and heals output using the local JSON dictionary
 *   and optional ML Kit fallback.
 */
class LocalGgufTranslator {

    private val mutex = Mutex()
    private var loadedModelHandle: LlamaModel? = null
    private var loadedModelPath: String? = null

    suspend fun translateSegmentsWithContext(
        modelFile: File,
        segments: List<DialogueSegmentEntity>,
        speakers: List<SpeakerEntity>,
        dialogueStyle: String,
        dictionaryConfig: SlangDictionaryConfig? = null,
        hybridTurbo: Boolean = true,
        fallbackTranslator: (suspend (String) -> String)? = null,
        onLineTranslated: (suspend (line: TranslatedLine, index: Int, total: Int) -> Unit)? = null
    ): List<TranslatedLine> = withContext(Dispatchers.IO) {
        val speechSegments = segments.filter { it.audioType == "SPEECH" && it.englishText.isNotBlank() }
        if (speechSegments.isEmpty()) return@withContext emptyList()

        val speakerMap = speakers.associateBy { it.speakerLabel }
        val systemPrompt = buildCinemaSystemPrompt(dialogueStyle)
        val total = speechSegments.size
        val results = mutableListOf<TranslatedLine>()
        val recentTranslatedHindi = mutableListOf<String>()

        mutex.withLock {
            var handle: LlamaModel? = null

            for ((idx, seg) in speechSegments.withIndex()) {
                currentCoroutineContext().ensureActive()

                val prevText = speechSegments.getOrNull(idx - 1)?.englishText.orEmpty()
                val nextText = speechSegments.getOrNull(idx + 1)?.englishText.orEmpty()
                val sceneMood = TranscriptRepairHelper.inferSceneMood(speechSegments, idx)
                val listenerGender = TranscriptRepairHelper.inferListenerGender(speechSegments, idx, speakerMap)
                val isGarbledAsr = TranscriptRepairHelper.isLikelyAsrGarble(seg.englishText)
                val prevHindiHint = recentTranslatedHindi.lastOrNull()

                val prep = if (dictionaryConfig != null) {
                    SlangDictionaryManager.prepareProtectedText(
                        config = dictionaryConfig,
                        englishText = seg.englishText,
                        prevContext = prevText,
                        nextContext = nextText,
                        emotion = seg.emotion,
                        dialogueStyle = dialogueStyle
                    )
                } else {
                    ProtectedTranslationPrep(
                        originalEnglish = seg.englishText,
                        protectedEnglish = seg.englishText,
                        directOverrideHindi = null,
                        appliedMatches = emptyList(),
                        placeholders = emptyList()
                    )
                }

                // Fast-path 1: exact JSON dictionary line override (0 ms)
                val fastDirect = prep.directOverrideHindi
                // Fast-path 2: common ultra-short movie fillers/interjections (0 ms)
                val fastFiller = if (fastDirect == null) {
                    tryFastShortUtteranceHindi(seg.englishText)
                } else null

                // Fast-path 3 (Hybrid Turbo): routine everyday lines go through ML Kit (~10 ms)
                // while slang/adult/profane/complex/garbled-ASR lines stay on the Local GGUF LLM.
                val useFastMlKit = fastDirect.isNullOrBlank() &&
                    fastFiller.isNullOrBlank() &&
                    hybridTurbo &&
                    fallbackTranslator != null &&
                    !isGarbledAsr &&
                    !requiresLocalLlmContext(
                        englishText = seg.englishText,
                        prevText = prevText,
                        nextText = nextText,
                        hasDictMatch = prep.appliedMatches.isNotEmpty(),
                        dialogueStyle = dialogueStyle
                    )

                val routedMlKitHindi = if (useFastMlKit) {
                    runCatching { fallbackTranslator!!.invoke(prep.protectedEnglish) }
                        .getOrNull()
                        ?.let { raw -> SlangDictionaryManager.restoreAndOverride(raw, prep) }
                        ?.takeIf { containsDevanagari(it) }
                } else null

                val finalHindi = when {
                    !fastDirect.isNullOrBlank() -> fastDirect
                    !fastFiller.isNullOrBlank() -> fastFiller
                    !routedMlKitHindi.isNullOrBlank() -> routedMlKitHindi
                    else -> {
                        val model = handle ?: ensureLoadedLocked(modelFile).also { handle = it }
                        translateSegmentRobustLocked(
                            handle = model,
                            seg = seg,
                            speaker = speakerMap[seg.speakerLabel],
                            listenerGender = listenerGender,
                            sceneMood = sceneMood,
                            prevEnglish = prevText.takeIf { it.isNotBlank() },
                            prevHindi = prevHindiHint,
                            nextEnglish = nextText.takeIf { it.isNotBlank() },
                            asrMayHaveErrors = isGarbledAsr,
                            systemPrompt = systemPrompt,
                            dialogueStyle = dialogueStyle,
                            prep = prep,
                            fallbackTranslator = fallbackTranslator
                        )
                    }
                }

                val resolvedHindi = finalHindi.ifBlank { seg.englishText }
                if (containsDevanagari(resolvedHindi)) {
                    recentTranslatedHindi.add(resolvedHindi.take(60))
                }
                val translated = TranslatedLine(
                    segmentId = seg.id,
                    hindiText = resolvedHindi,
                    emotion = seg.emotion
                )
                results.add(translated)
                onLineTranslated?.invoke(translated, idx, total)
            }
        }
        results
    }

    suspend fun translateSingleSample(
        modelFile: File,
        englishLine: String,
        dialogueStyle: String,
        dictionaryConfig: SlangDictionaryConfig? = null
    ): LocalLlmSampleResult = withContext(Dispatchers.IO) {
        val matches = if (dictionaryConfig != null) {
            SlangDictionaryManager.resolveMatches(
                config = dictionaryConfig,
                englishText = englishLine,
                dialogueStyle = dialogueStyle
            )
        } else emptyList()
        val glossaryHints = SlangDictionaryManager.buildPromptGlossaryHints(matches)

        mutex.withLock {
            val handle = ensureLoadedLocked(modelFile)
            val systemPrompt = buildCinemaSystemPrompt(dialogueStyle)
            val prompt = buildSingleLinePrompt(
                englishLine = englishLine,
                prevEnglish = null,
                speakerGender = null,
                emotion = null,
                glossaryHints = glossaryHints,
                dialogueStyle = dialogueStyle
            )
            val wordCount = englishLine.trim().split(Regex("\\s+")).size
            val res = Llama.complete(
                handle,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = (wordCount * 3 + 36).coerceIn(48, 110)
            )
            val cleaned = extractBestHindiLine(res.text)
            val healed = if (isRefusalOrMissingHindi(cleaned)) {
                val prep = if (dictionaryConfig != null) {
                    SlangDictionaryManager.prepareProtectedText(
                        config = dictionaryConfig,
                        englishText = englishLine,
                        dialogueStyle = dialogueStyle
                    )
                } else null
                if (prep?.directOverrideHindi != null) {
                    prep.directOverrideHindi
                } else {
                    val protectedAttempt = retryWithProtectedPromptLocked(handle, prep?.protectedEnglish ?: englishLine)
                    if (prep != null && !isRefusalOrMissingHindi(protectedAttempt)) {
                        SlangDictionaryManager.restoreAndOverride(protectedAttempt, prep)
                    } else {
                        cleaned.ifBlank { res.text.trim() }
                    }
                }
            } else {
                cleaned
            }
            val finalHindi = SlangDictionaryManager.postProcessLlmOutput(
                llmHindi = healed,
                englishText = englishLine,
                matches = matches
            )
            LocalLlmSampleResult(
                hindiText = finalHindi,
                tokensPerSecond = res.tokensPerSecond
            )
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            releaseLocked()
        }
    }

    fun releaseBlocking() {
        val handle = loadedModelHandle
        loadedModelHandle = null
        loadedModelPath = null
        if (handle != null) {
            runCatching { Llama.releaseModel(handle) }
        }
    }

    private suspend fun ensureLoadedLocked(modelFile: File): LlamaModel {
        val path = modelFile.absolutePath
        val existing = loadedModelHandle
        if (existing != null && loadedModelPath == path) {
            return existing
        }
        releaseLocked()
        require(LocalLlmModelManager.isValidGgufFile(modelFile)) {
            "GGUF model file is missing or invalid: ${modelFile.name}"
        }
        // Cap at 4 threads on mobile ARM64 big.LITTLE SoCs to avoid slow E-core barrier stalls
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        Log.i(TAG, "Loading local GGUF model ${modelFile.name} with $cpuThreads threads (ctx=1024, temp=0.15)")
        val handle = try {
            Llama.loadModel(
                modelPath = path,
                config = LlamaConfig(
                    contextSize = 1024,
                    threads = cpuThreads,
                    gpuLayers = 0,
                    temperature = 0.15f,
                    topP = 0.85f,
                    topK = 20
                )
            )
        } catch (t: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "Local GGUF native engine requires an arm64-v8a Android phone (such as Redmi Note 10 Pro).",
                t
            )
        }
        loadedModelHandle = handle
        loadedModelPath = path
        return handle
    }

    private suspend fun releaseLocked() {
        val handle = loadedModelHandle
        loadedModelHandle = null
        loadedModelPath = null
        if (handle != null) {
            runCatching { Llama.releaseModel(handle) }
        }
    }

    private suspend fun translateSegmentRobustLocked(
        handle: LlamaModel,
        seg: DialogueSegmentEntity,
        speaker: SpeakerEntity?,
        listenerGender: String?,
        sceneMood: String?,
        prevEnglish: String?,
        prevHindi: String?,
        nextEnglish: String?,
        asrMayHaveErrors: Boolean,
        systemPrompt: String,
        dialogueStyle: String,
        prep: ProtectedTranslationPrep,
        fallbackTranslator: (suspend (String) -> String)?
    ): String {
        val glossaryHints = SlangDictionaryManager.buildPromptGlossaryHints(prep.appliedMatches)
        val prompt = buildSingleLinePrompt(
            englishLine = seg.englishText,
            prevEnglish = prevEnglish,
            prevHindi = prevHindi,
            nextEnglish = nextEnglish,
            speakerGender = speaker?.gender,
            listenerGender = listenerGender,
            sceneMood = sceneMood,
            asrMayHaveErrors = asrMayHaveErrors,
            emotion = seg.emotion,
            glossaryHints = glossaryHints,
            dialogueStyle = dialogueStyle
        )
        val wordCount = seg.englishText.trim().split(Regex("\\s+")).size
        val maxTok = (wordCount * 2 + 24).coerceIn(32, 84)

        val primaryRaw = runCatching {
            Llama.complete(
                handle,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = maxTok
            ).text
        }.getOrElse { err ->
            Log.w(TAG, "Llama.complete failed for segment ${seg.id}: ${err.message}")
            ""
        }

        var candidate = extractBestHindiLine(primaryRaw)

        // If GGUF refused, produced non-Devanagari text, or looped/echoed prompt junk, retry cleanly
        if (isRefusalOrMissingHindi(candidate) || hasRepetitiveLoopOrGarbage(candidate, seg.englishText)) {
            Log.d(TAG, "Retrying segment ${seg.id} with protected clean prompt (raw was: ${primaryRaw.take(60)})")
            val protectedRaw = runCatching {
                retryWithProtectedPromptLocked(handle, prep.protectedEnglish)
            }.getOrDefault("")
            val protectedClean = extractBestHindiLine(protectedRaw)
            if (!isRefusalOrMissingHindi(protectedClean) && !hasRepetitiveLoopOrGarbage(protectedClean, seg.englishText)) {
                candidate = SlangDictionaryManager.restoreAndOverride(protectedClean, prep)
            } else {
                candidate = ""
            }
        }

        // Final safety net: use protected ML Kit fallback if GGUF still failed, refused, or looped
        if ((isRefusalOrMissingHindi(candidate) || hasRepetitiveLoopOrGarbage(candidate, seg.englishText)) &&
            fallbackTranslator != null
        ) {
            val mlKitHindi = runCatching { fallbackTranslator(prep.protectedEnglish) }.getOrNull()
            if (!mlKitHindi.isNullOrBlank()) {
                val restored = SlangDictionaryManager.restoreAndOverride(mlKitHindi, prep)
                if (containsDevanagari(restored) && !hasRepetitiveLoopOrGarbage(restored, seg.englishText)) {
                    candidate = restored
                }
            }
        }

        val postProcessed = SlangDictionaryManager.postProcessLlmOutput(
            llmHindi = candidate,
            englishText = seg.englishText,
            matches = prep.appliedMatches
        )
        return postProcessed.ifBlank { seg.englishText }
    }

    private suspend fun retryWithProtectedPromptLocked(
        handle: LlamaModel,
        protectedEnglish: String
    ): String {
        val wordCount = protectedEnglish.trim().split(Regex("\\s+")).size
        val res = Llama.complete(
            handle,
            prompt = "English: \"$protectedEnglish\"\nHindi:",
            systemPrompt = "Translate English text into spoken Hindi in Devanagari script only. Preserve ZXQ tokens exactly. Never repeat phrases.",
            maxTokens = (wordCount * 3 + 24).coerceIn(36, 90)
        )
        return res.text
    }

    private fun buildCinemaSystemPrompt(dialogueStyle: String): String {
        val styleNote = when (dialogueStyle) {
            DialogueStyle.RAW_UNCENSORED ->
                "Style: Raw Uncensored Adult & Street Hindi. Keep street slang, curses, and intimacy raw and natural; never censor profanity into polite words."
            DialogueStyle.CINEMATIC ->
                "Style: Dramatic Bollywood Hindi. Punchy and emotional."
            DialogueStyle.CASUAL ->
                "Style: Everyday street-smart Hindustani."
            else ->
                "Style: Natural spoken Hindi movie dubbing."
        }
        return "You are a professional Hindi film dubbing translator. $styleNote Reply ONLY with the spoken Hindi translation in Devanagari script. Never repeat words or invent unrelated lines."
    }

    internal fun buildSingleLinePrompt(
        englishLine: String,
        prevEnglish: String?,
        prevHindi: String? = null,
        nextEnglish: String? = null,
        speakerGender: String?,
        listenerGender: String? = null,
        sceneMood: String? = null,
        asrMayHaveErrors: Boolean = false,
        emotion: String?,
        glossaryHints: String,
        dialogueStyle: String
    ): String = buildString {
        if (glossaryHints.isNotBlank()) {
            appendLine(glossaryHints)
        }
        if (!sceneMood.isNullOrBlank()) {
            appendLine("Scene Mood: $sceneMood")
        }
        if (!prevEnglish.isNullOrBlank()) {
            val rolling = if (!prevHindi.isNullOrBlank()) " (said: ${prevHindi.take(40)})" else ""
            appendLine("Prev line: \"${prevEnglish.take(50)}\"$rolling")
        }
        if (!nextEnglish.isNullOrBlank()) {
            appendLine("Next line: \"${nextEnglish.take(45)}\"")
        }
        val spG = speakerGender?.takeIf { it.isNotBlank() && it != "NEUTRAL" }
        val lsG = listenerGender?.takeIf { it.isNotBlank() && it != "NEUTRAL" }
        if (spG != null || lsG != null || (!emotion.isNullOrBlank() && emotion != "neutral")) {
            val spPart = spG ?: "UNKNOWN"
            val lsPart = if (lsG != null) " talking to $lsG" else ""
            val moodPart = if (!emotion.isNullOrBlank() && emotion != "neutral") " ($emotion)" else ""
            appendLine("Speaker: $spPart$lsPart$moodPart")
        }
        if (asrMayHaveErrors) {
            appendLine("Note: Audio transcript may have phonetic typos; infer intended movie dialogue from Scene Mood & context.")
        }
        append("Translate ONLY this English line into Hindi (Devanagari only):\nEnglish: \"$englishLine\"\nHindi:")
    }

    companion object {
        private val THINK_BLOCK_REGEX = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
        private val LINE_PREFIX_REGEX = Regex("""^\s*(?:[-*•]+|\d+[\.\)\:\-]+|(?:Hindi|Translation|Output|Dialogue|English)\s*:)\s*""", RegexOption.IGNORE_CASE)
        private val DASH_LOOP_SPLIT_REGEX = Regex("""\s*(?:-+\s*-+|—|–)\s*""")
        private val DEVANAGARI_REGEX = Regex("""[\u0900-\u097F]""")
        private val REFUSAL_PATTERNS = listOf(
            "i cannot", "i can't", "i am unable", "i'm sorry", "as an ai",
            "explicit content", "inappropriate", "cannot fulfill", "cannot translate",
            "against my", "offensive", "nsfw", "sexual content"
        )
        private val PROMPT_LEAK_PATTERNS = listOf(
            "translate only", "previous context", "local json slang", "do not translate",
            "scene mood:", "prev line:", "next line:", "speaker:", "english:", "style:", "hindi:"
        )

        private val FAST_SHORT_UTTERANCES = mapOf(
            "yes" to "हाँ",
            "yeah" to "हाँ",
            "yep" to "हाँ",
            "no" to "नहीं",
            "nope" to "नहीं",
            "ok" to "ठीक है",
            "okay" to "ठीक है",
            "alright" to "ठीक है",
            "fine" to "ठीक है",
            "good" to "अच्छा",
            "hey" to "अरे",
            "hi" to "हाय",
            "hello" to "नमस्ते",
            "bye" to "बाय",
            "wait" to "रुको",
            "stop" to "रुको",
            "please" to "प्लीज़",
            "sorry" to "माफ़ करना",
            "thanks" to "शुक्रिया",
            "thank you" to "धन्यवाद",
            "what" to "क्या",
            "why" to "क्यों",
            "really" to "सच में",
            "sure" to "बिल्कुल",
            "right" to "सही है",
            "come on" to "चलो भी",
            "let's go" to "चलो चलें",
            "shut up" to "बकवास बंद कर"
        )

        private val SLANG_OR_COMPLEX_REGEX = Regex(
            """\b(fuck\w*|motherfuck\w*|shit\w*|bitch\w*|asshole\w*|dick\w*|cock\w*|pussy\w*|bastard\w*|whore\w*|slut\w*|horny|naked|nude|kiss\w*|moan\w*|lust\w*|sexy|desire\w*|screw\w*|damn\w*|hell|crap\w*|suck\w*|bang\w*|wtf|cunt\w*|prick\w*|wank\w*|jerk\w*|moron\w*|idiot\w*|bloody|bullshit|goddamn|kill\w*|murder\w*|swear|kidding|babe|darling|honey)\b""",
            RegexOption.IGNORE_CASE
        )

        private val INTIMATE_SCENE_CONTEXT_REGEX = Regex(
            """\b(bed|bedroom|undress\w*|panties|bra|chest|lips|touch\w*|pleasure|harder|deeper|orgasm|cum\w*|sleep with|make love|turn me on)\b""",
            RegexOption.IGNORE_CASE
        )

        internal fun requiresLocalLlmContext(
            englishText: String,
            prevText: String = "",
            nextText: String = "",
            hasDictMatch: Boolean = false,
            dialogueStyle: String = DialogueStyle.CONVERSATIONAL
        ): Boolean {
            if (hasDictMatch) return true
            val trimmed = englishText.trim()
            if (trimmed.isEmpty()) return false
            if (SLANG_OR_COMPLEX_REGEX.containsMatchIn(trimmed)) return true
            if (dialogueStyle == DialogueStyle.RAW_UNCENSORED) {
                val combined = "$prevText $trimmed $nextText"
                if (INTIMATE_SCENE_CONTEXT_REGEX.containsMatchIn(combined)) return true
            }
            return false
        }

        internal fun tryFastShortUtteranceHindi(englishLine: String): String? {
            val trimmed = englishLine.trim()
            if (trimmed.isEmpty()) return null
            val trailing = trimmed.takeLastWhile { it in charArrayOf('!', '?') }
            val normalized = trimmed.trim('"', '\'', '.', '!', '?', ',', ';', ':')
                .lowercase(Locale.US)
                .replace(Regex("\\s+"), " ")
            val mapped = FAST_SHORT_UTTERANCES[normalized] ?: return null
            return mapped + trailing
        }

        internal fun containsDevanagari(text: String): Boolean = DEVANAGARI_REGEX.containsMatchIn(text)

        internal fun isRefusalOrMissingHindi(text: String): Boolean {
            if (text.isBlank()) return true
            val lower = text.lowercase(Locale.US)
            if (REFUSAL_PATTERNS.any { lower.contains(it) }) return true
            if (PROMPT_LEAK_PATTERNS.any { lower.contains(it) }) return true
            return !containsDevanagari(text)
        }

        internal fun hasRepetitiveLoopOrGarbage(hindi: String, englishText: String = ""): Boolean {
            val trimmed = hindi.trim()
            if (trimmed.isEmpty()) return true
            val lower = trimmed.lowercase(Locale.US)
            if (PROMPT_LEAK_PATTERNS.any { lower.contains(it) }) return true

            // Check repeated chunks around dash/loop artifacts like "हो यहाँ से! - - हो यहाँ से!"
            val dashParts = DASH_LOOP_SPLIT_REGEX.split(trimmed)
                .map { it.trim().trim('"', '\'', '!', '?', '।', '.') }
                .filter { it.isNotBlank() }
            if (dashParts.size >= 2 && dashParts.distinct().size < dashParts.size) {
                return true
            }

            val tokens = trimmed.split(Regex("""[\s,।.!?\-—"']+"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return true

            // Same word repeated 3+ times consecutively
            for (i in 0..tokens.size - 3) {
                if (tokens[i] == tokens[i + 1] && tokens[i + 1] == tokens[i + 2]) {
                    return true
                }
            }

            // Repeated 2-word bigram in a short output
            if (tokens.size >= 4) {
                val bigrams = mutableSetOf<String>()
                var bigramRepeats = 0
                for (i in 0 until tokens.size - 1) {
                    val bg = "${tokens[i]} ${tokens[i + 1]}"
                    if (!bigrams.add(bg)) {
                        bigramRepeats++
                    }
                }
                if (bigramRepeats >= 2 || (tokens.size <= 10 && bigramRepeats >= 1)) {
                    return true
                }
            }

            // Low vocabulary diversity across 6+ word output
            if (tokens.size >= 6) {
                val uniqueRatio = tokens.distinct().size.toFloat() / tokens.size.toFloat()
                if (uniqueRatio < 0.55f) return true
            }

            // Severe length mismatch check when translating long English sentences into tiny stub loops
            val engWords = englishText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            if (engWords >= 12 && tokens.size <= 2 && !englishText.contains("fuck", ignoreCase = true)) {
                return true
            }

            return false
        }

        internal fun extractBestHindiLine(raw: String): String {
            if (raw.isBlank()) return ""
            val withoutThink = THINK_BLOCK_REGEX.replace(raw, " ")
            val candidates = withoutThink.lines()
                .map { line ->
                    var s = line.trim()
                    repeat(2) {
                        s = LINE_PREFIX_REGEX.replace(s, "").trim()
                    }
                    s.removeSurrounding("**")
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                        .trim()
                }
                .filter { it.isNotBlank() }

            if (candidates.isEmpty()) return ""
            val cleanCandidates = candidates.filter { cand ->
                containsDevanagari(cand) &&
                    !REFUSAL_PATTERNS.any { cand.lowercase(Locale.US).contains(it) } &&
                    !PROMPT_LEAK_PATTERNS.any { cand.lowercase(Locale.US).contains(it) }
            }
            val nonLooping = cleanCandidates.firstOrNull { !hasRepetitiveLoopOrGarbage(it) }
            return nonLooping ?: cleanCandidates.firstOrNull() ?: candidates.first()
        }
    }
}
