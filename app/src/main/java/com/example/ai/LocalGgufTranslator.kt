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
        fallbackTranslator: (suspend (String) -> String)? = null,
        onLineTranslated: (suspend (line: TranslatedLine, index: Int, total: Int) -> Unit)? = null
    ): List<TranslatedLine> = withContext(Dispatchers.IO) {
        val speechSegments = segments.filter { it.audioType == "SPEECH" && it.englishText.isNotBlank() }
        if (speechSegments.isEmpty()) return@withContext emptyList()

        val speakerMap = speakers.associateBy { it.speakerLabel }
        val systemPrompt = buildCinemaSystemPrompt(dialogueStyle)
        val total = speechSegments.size
        val results = mutableListOf<TranslatedLine>()

        mutex.withLock {
            var handle: LlamaModel? = null

            for ((idx, seg) in speechSegments.withIndex()) {
                currentCoroutineContext().ensureActive()

                val prevText = speechSegments.getOrNull(idx - 1)?.englishText.orEmpty()
                val nextText = speechSegments.getOrNull(idx + 1)?.englishText.orEmpty()

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

                val finalHindi = when {
                    !fastDirect.isNullOrBlank() -> fastDirect
                    !fastFiller.isNullOrBlank() -> fastFiller
                    else -> {
                        val model = handle ?: ensureLoadedLocked(modelFile).also { handle = it }
                        translateSegmentRobustLocked(
                            handle = model,
                            seg = seg,
                            speaker = speakerMap[seg.speakerLabel],
                            prevEnglish = prevText.takeIf { it.isNotBlank() },
                            systemPrompt = systemPrompt,
                            dialogueStyle = dialogueStyle,
                            prep = prep,
                            fallbackTranslator = fallbackTranslator
                        )
                    }
                }

                val translated = TranslatedLine(
                    segmentId = seg.id,
                    hindiText = finalHindi.ifBlank { seg.englishText },
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
        prevEnglish: String?,
        systemPrompt: String,
        dialogueStyle: String,
        prep: ProtectedTranslationPrep,
        fallbackTranslator: (suspend (String) -> String)?
    ): String {
        val glossaryHints = SlangDictionaryManager.buildPromptGlossaryHints(prep.appliedMatches)
        val prompt = buildSingleLinePrompt(
            englishLine = seg.englishText,
            prevEnglish = prevEnglish,
            speakerGender = speaker?.gender,
            emotion = seg.emotion,
            glossaryHints = glossaryHints,
            dialogueStyle = dialogueStyle
        )
        val wordCount = seg.englishText.trim().split(Regex("\\s+")).size
        val maxTok = (wordCount * 3 + 28).coerceIn(40, 96)

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

        // If GGUF refused or produced non-Devanagari text, retry once using neutral placeholder tokens
        if (isRefusalOrMissingHindi(candidate)) {
            Log.d(TAG, "Retrying segment ${seg.id} with protected tokens (raw was: ${primaryRaw.take(60)})")
            val protectedRaw = runCatching {
                retryWithProtectedPromptLocked(handle, prep.protectedEnglish)
            }.getOrDefault("")
            val protectedClean = extractBestHindiLine(protectedRaw)
            if (!isRefusalOrMissingHindi(protectedClean)) {
                candidate = SlangDictionaryManager.restoreAndOverride(protectedClean, prep)
            }
        }

        // Final safety net: use protected ML Kit fallback if GGUF still failed/refused
        if (isRefusalOrMissingHindi(candidate) && fallbackTranslator != null) {
            val mlKitHindi = runCatching { fallbackTranslator(prep.protectedEnglish) }.getOrNull()
            if (!mlKitHindi.isNullOrBlank()) {
                val restored = SlangDictionaryManager.restoreAndOverride(mlKitHindi, prep)
                if (containsDevanagari(restored)) {
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
            prompt = "Translate to natural spoken Hindi (Devanagari script only, keep any ZXQ tokens unchanged):\n\"$protectedEnglish\"",
            systemPrompt = "Translate English text into spoken Hindi in Devanagari script. Keep tokens like ZXQ0QXZ exact.",
            maxTokens = (wordCount * 3 + 28).coerceIn(40, 96)
        )
        return res.text
    }

    private fun buildCinemaSystemPrompt(dialogueStyle: String): String {
        val styleNote = when (dialogueStyle) {
            DialogueStyle.RAW_UNCENSORED ->
                "Style: Raw Uncensored OTT & Adult Hindi. Keep street slang, curses, and intimacy raw and natural (never replace profanity with polite words like 'लानत' or 'मूर्ख')."
            DialogueStyle.CINEMATIC ->
                "Style: Dramatic Bollywood Hindi. Punchy and emotional."
            DialogueStyle.CASUAL ->
                "Style: Everyday street-smart Hindustani."
            else ->
                "Style: Natural spoken Hindi movie dubbing."
        }
        return "You are a professional Hindi film dubbing translator. $styleNote Reply ONLY with the spoken Hindi translation in Devanagari script. No English, no notes, no quotes."
    }

    private fun buildSingleLinePrompt(
        englishLine: String,
        prevEnglish: String?,
        speakerGender: String?,
        emotion: String?,
        glossaryHints: String,
        dialogueStyle: String
    ): String = buildString {
        if (dialogueStyle == DialogueStyle.RAW_UNCENSORED) {
            appendLine("Examples:")
            appendLine("\"Get the hell out!\" -> \"दफ़ा हो जाओ यहाँ से!\"")
            appendLine("\"I want you so bad right now.\" -> \"मुझे अभी के अभी तुम चाहिए।\"")
        } else {
            appendLine("Examples:")
            appendLine("\"Wait, don't leave me here.\" -> \"रुको, मुझे यहाँ छोड़कर मत जाओ।\"")
            appendLine("\"What are you doing?\" -> \"तुम क्या कर रहे हो?\"")
        }
        if (glossaryHints.isNotBlank()) {
            appendLine(glossaryHints)
        }
        if (!prevEnglish.isNullOrBlank()) {
            appendLine("Previous scene line: \"${prevEnglish.take(70)}\"")
        }
        if (!speakerGender.isNullOrBlank() && speakerGender != "NEUTRAL") {
            val moodPart = if (!emotion.isNullOrBlank() && emotion != "neutral") ", $emotion" else ""
            appendLine("Speaker: $speakerGender$moodPart")
        }
        append("Translate to Hindi: \"$englishLine\"")
    }

    companion object {
        private val THINK_BLOCK_REGEX = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
        private val LINE_PREFIX_REGEX = Regex("""^\s*(?:[-*•]+|\d+[\.\)\:\-]+|(?:Hindi|Translation|Output|Dialogue)\s*:)\s*""", RegexOption.IGNORE_CASE)
        private val DEVANAGARI_REGEX = Regex("""[\u0900-\u097F]""")
        private val REFUSAL_PATTERNS = listOf(
            "i cannot", "i can't", "i am unable", "i'm sorry", "as an ai",
            "explicit content", "inappropriate", "cannot fulfill", "cannot translate",
            "against my", "offensive", "nsfw", "sexual content"
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
            "oh" to "ओह",
            "uh" to "अह",
            "um" to "हम्म्म",
            "hmm" to "हम्म्म",
            "huh" to "हैं",
            "ah" to "आह",
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
            "shut up" to "बकवास बंद कर",
            "oh god" to "हे भगवान",
            "oh my god" to "हे भगवान"
        )

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
            return !containsDevanagari(text)
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
            // Prefer the first line that actually contains Hindi Devanagari script
            val devanagariLine = candidates.firstOrNull { containsDevanagari(it) && !REFUSAL_PATTERNS.any { r -> it.lowercase(Locale.US).contains(r) } }
            return devanagariLine ?: candidates.first()
        }
    }
}
