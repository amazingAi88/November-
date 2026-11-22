package com.example.ai

import android.util.Log
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DialogueStyle
import com.example.data.local.SpeakerEntity
import com.example.data.local.TranslationSource
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
        hybridTurbo: Boolean = false,
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

        data class PreparedItem(
            val index: Int,
            val seg: DialogueSegmentEntity,
            val prevText: String,
            val nextText: String,
            val sceneMood: String,
            val listenerGender: String?,
            val isGarbledAsr: Boolean,
            val prep: ProtectedTranslationPrep
        )

        val prepared = speechSegments.mapIndexed { idx, seg ->
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
            PreparedItem(
                index = idx,
                seg = seg,
                prevText = prevText,
                nextText = nextText,
                sceneMood = TranscriptRepairHelper.inferSceneMood(speechSegments, idx),
                listenerGender = TranscriptRepairHelper.inferListenerGender(speechSegments, idx, speakerMap),
                isGarbledAsr = TranscriptRepairHelper.isLikelyAsrGarble(seg.englishText),
                prep = prep
            )
        }

        mutex.withLock {
            var handle: LlamaModel? = null

            suspend fun emitLine(item: PreparedItem, hindiText: String, source: String) {
                val resolvedHindi = hindiText.ifBlank { item.seg.englishText }
                if (containsDevanagari(resolvedHindi)) {
                    recentTranslatedHindi.add(resolvedHindi.take(70))
                }
                val translated = TranslatedLine(
                    segmentId = item.seg.id,
                    hindiText = resolvedHindi,
                    emotion = item.seg.emotion,
                    translationSource = source
                )
                results.add(translated)
                onLineTranslated?.invoke(translated, item.index, total)
            }

            suspend fun flushSceneBlock(block: List<PreparedItem>) {
                if (block.isEmpty()) return
                val model = handle ?: ensureLoadedLocked(modelFile).also { handle = it }

                if (block.size == 1) {
                    val item = block[0]
                    val (hindi, src) = translateSegmentRobustLocked(
                        handle = model,
                        seg = item.seg,
                        speaker = speakerMap[item.seg.speakerLabel],
                        listenerGender = item.listenerGender,
                        sceneMood = item.sceneMood,
                        prevEnglish = item.prevText.takeIf { it.isNotBlank() },
                        prevHindi = recentTranslatedHindi.lastOrNull(),
                        nextEnglish = item.nextText.takeIf { it.isNotBlank() },
                        asrMayHaveErrors = item.isGarbledAsr,
                        systemPrompt = systemPrompt,
                        dialogueStyle = dialogueStyle,
                        prep = item.prep,
                        fallbackTranslator = fallbackTranslator
                    )
                    emitLine(item, hindi, src)
                    return
                }

                val blockMatches = block.flatMap { it.prep.appliedMatches }
                val glossaryHints = SlangDictionaryManager.buildPromptGlossaryHints(blockMatches)
                val blockInputs = block.map { item ->
                    SceneBlockLineInput(
                        speakerGender = speakerMap[item.seg.speakerLabel]?.gender,
                        listenerGender = item.listenerGender,
                        emotion = item.seg.emotion,
                        asrMayHaveErrors = item.isGarbledAsr,
                        englishText = item.seg.englishText
                    )
                }
                val blockPrompt = buildSceneBlockPrompt(
                    lines = blockInputs,
                    sceneMood = block.firstOrNull { it.sceneMood != "Casual Cinema Dialogue" }?.sceneMood
                        ?: block.first().sceneMood,
                    prevHindiLines = recentTranslatedHindi.takeLast(2),
                    nextEnglishPreview = block.last().nextText.takeIf { it.isNotBlank() },
                    glossaryHints = glossaryHints
                )
                val totalWords = block.sumOf { it.seg.englishText.trim().split(Regex("\\s+")).size }
                val blockRaw = runCatching {
                    Llama.complete(
                        model,
                        prompt = blockPrompt,
                        systemPrompt = systemPrompt,
                        maxTokens = (totalWords * 4 + 48).coerceIn(64, 320)
                    ).text
                }.getOrElse { err ->
                    Log.w(TAG, "Scene block Llama.complete failed: ${err.message}")
                    ""
                }
                val parsedBySlot = parseSceneBlockResponse(blockRaw, block.size)

                block.forEachIndexed { slotIdx, item ->
                    currentCoroutineContext().ensureActive()
                    val candidate = parsedBySlot[slotIdx]
                    if (candidate != null &&
                        !isRefusalOrMissingHindi(candidate) &&
                        !hasRepetitiveLoopOrGarbage(candidate, item.seg.englishText)
                    ) {
                        val healed = SlangDictionaryManager.postProcessLlmOutput(
                            llmHindi = candidate,
                            englishText = item.seg.englishText,
                            matches = item.prep.appliedMatches
                        )
                        emitLine(item, healed, TranslationSource.LOCAL_GGUF)
                    } else {
                        val (hindi, src) = translateSegmentRobustLocked(
                            handle = model,
                            seg = item.seg,
                            speaker = speakerMap[item.seg.speakerLabel],
                            listenerGender = item.listenerGender,
                            sceneMood = item.sceneMood,
                            prevEnglish = item.prevText.takeIf { it.isNotBlank() },
                            prevHindi = recentTranslatedHindi.lastOrNull(),
                            nextEnglish = item.nextText.takeIf { it.isNotBlank() },
                            asrMayHaveErrors = item.isGarbledAsr,
                            systemPrompt = systemPrompt,
                            dialogueStyle = dialogueStyle,
                            prep = item.prep,
                            fallbackTranslator = fallbackTranslator
                        )
                        emitLine(item, hindi, src)
                    }
                }
            }

            val pendingBlock = mutableListOf<PreparedItem>()
            for (item in prepared) {
                currentCoroutineContext().ensureActive()

                val fastDirect = item.prep.directOverrideHindi
                val fastFiller = if (fastDirect == null) {
                    tryFastShortUtteranceHindi(item.seg.englishText)
                } else null

                if (!fastDirect.isNullOrBlank()) {
                    flushSceneBlock(pendingBlock.toList())
                    pendingBlock.clear()
                    emitLine(item, fastDirect, TranslationSource.DICTIONARY)
                    continue
                }
                if (!fastFiller.isNullOrBlank()) {
                    flushSceneBlock(pendingBlock.toList())
                    pendingBlock.clear()
                    emitLine(item, fastFiller, TranslationSource.DICTIONARY)
                    continue
                }

                // Hybrid Fast bypasses LLM for clean non-slang lines ONLY when explicitly selected
                val useFastMlKit = hybridTurbo &&
                    fallbackTranslator != null &&
                    !item.isGarbledAsr &&
                    !requiresLocalLlmContext(
                        englishText = item.seg.englishText,
                        prevText = item.prevText,
                        nextText = item.nextText,
                        hasDictMatch = item.prep.appliedMatches.isNotEmpty(),
                        dialogueStyle = dialogueStyle
                    )

                val routedMlKitHindi = if (useFastMlKit) {
                    runCatching { fallbackTranslator!!.invoke(item.prep.protectedEnglish) }
                        .getOrNull()
                        ?.let { raw -> SlangDictionaryManager.restoreAndOverride(raw, item.prep) }
                        ?.takeIf { containsDevanagari(it) }
                } else null

                if (!routedMlKitHindi.isNullOrBlank()) {
                    flushSceneBlock(pendingBlock.toList())
                    pendingBlock.clear()
                    emitLine(item, routedMlKitHindi, TranslationSource.ML_KIT)
                    continue
                }

                if (hybridTurbo) {
                    val model = handle ?: ensureLoadedLocked(modelFile).also { handle = it }
                    val (hindi, src) = translateSegmentRobustLocked(
                        handle = model,
                        seg = item.seg,
                        speaker = speakerMap[item.seg.speakerLabel],
                        listenerGender = item.listenerGender,
                        sceneMood = item.sceneMood,
                        prevEnglish = item.prevText.takeIf { it.isNotBlank() },
                        prevHindi = recentTranslatedHindi.lastOrNull(),
                        nextEnglish = item.nextText.takeIf { it.isNotBlank() },
                        asrMayHaveErrors = item.isGarbledAsr,
                        systemPrompt = systemPrompt,
                        dialogueStyle = dialogueStyle,
                        prep = item.prep,
                        fallbackTranslator = fallbackTranslator
                    )
                    emitLine(item, hindi, src)
                } else {
                    pendingBlock.add(item)
                    if (pendingBlock.size >= 4) {
                        flushSceneBlock(pendingBlock.toList())
                        pendingBlock.clear()
                    }
                }
            }
            if (pendingBlock.isNotEmpty()) {
                flushSceneBlock(pendingBlock.toList())
                pendingBlock.clear()
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
    ): Pair<String, String> {
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
        // Devanagari tokens require ~3-5 tokens per Hindi word; ensure lines never truncate mid-sentence
        val maxTok = (wordCount * 4 + 40).coerceIn(48, 140)

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
        var usedMlKitFallback = false

        // If GGUF refused, produced non-Devanagari text, or looped/echoed prompt junk, retry WITH context preserved
        if (isRefusalOrMissingHindi(candidate) || hasRepetitiveLoopOrGarbage(candidate, seg.englishText)) {
            Log.d(TAG, "Retrying segment ${seg.id} with protected context prompt (raw was: ${primaryRaw.take(60)})")
            val protectedRaw = runCatching {
                retryWithProtectedPromptLocked(
                    handle = handle,
                    protectedEnglish = prep.protectedEnglish,
                    speakerGender = speaker?.gender,
                    listenerGender = listenerGender,
                    sceneMood = sceneMood
                )
            }.getOrDefault("")
            val protectedClean = extractBestHindiLine(protectedRaw)
            if (!isRefusalOrMissingHindi(protectedClean) && !hasRepetitiveLoopOrGarbage(protectedClean, seg.englishText)) {
                candidate = SlangDictionaryManager.restoreAndOverride(protectedClean, prep)
            } else {
                candidate = ""
            }
        }

        // Final safety net: use protected ML Kit fallback ONLY if GGUF failed twice, and report ML_KIT honestly
        if ((isRefusalOrMissingHindi(candidate) || hasRepetitiveLoopOrGarbage(candidate, seg.englishText)) &&
            fallbackTranslator != null
        ) {
            val mlKitHindi = runCatching { fallbackTranslator(prep.protectedEnglish) }.getOrNull()
            if (!mlKitHindi.isNullOrBlank()) {
                val restored = SlangDictionaryManager.restoreAndOverride(mlKitHindi, prep)
                if (containsDevanagari(restored) && !hasRepetitiveLoopOrGarbage(restored, seg.englishText)) {
                    candidate = restored
                    usedMlKitFallback = true
                }
            }
        }

        val postProcessed = SlangDictionaryManager.postProcessLlmOutput(
            llmHindi = candidate,
            englishText = seg.englishText,
            matches = prep.appliedMatches
        )
        val source = if (usedMlKitFallback) TranslationSource.ML_KIT else TranslationSource.LOCAL_GGUF
        return postProcessed.ifBlank { seg.englishText } to source
    }

    private suspend fun retryWithProtectedPromptLocked(
        handle: LlamaModel,
        protectedEnglish: String,
        speakerGender: String? = null,
        listenerGender: String? = null,
        sceneMood: String? = null
    ): String {
        val wordCount = protectedEnglish.trim().split(Regex("\\s+")).size
        val ctxPrefix = buildString {
            if (!sceneMood.isNullOrBlank()) appendLine("Scene Mood: $sceneMood")
            val sp = speakerGender?.takeIf { it.isNotBlank() && it != "NEUTRAL" }
            val ls = listenerGender?.takeIf { it.isNotBlank() && it != "NEUTRAL" }
            if (sp != null || ls != null) {
                appendLine("Speaker: ${sp ?: "UNKNOWN"}${if (ls != null) " talking to $ls" else ""}")
            }
        }
        val res = Llama.complete(
            handle,
            prompt = "${ctxPrefix}English: \"$protectedEnglish\"\nHindi:",
            systemPrompt = "Translate English dialogue into natural spoken Hindi in Devanagari script matching speaker/listener gender. Preserve ZXQ tokens exactly. Never repeat phrases.",
            maxTokens = (wordCount * 4 + 40).coerceIn(48, 140)
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

    data class SceneBlockLineInput(
        val speakerGender: String?,
        val listenerGender: String?,
        val emotion: String?,
        val asrMayHaveErrors: Boolean,
        val englishText: String
    )

    internal fun buildSceneBlockPrompt(
        lines: List<SceneBlockLineInput>,
        sceneMood: String,
        prevHindiLines: List<String> = emptyList(),
        nextEnglishPreview: String? = null,
        glossaryHints: String = ""
    ): String = buildString {
        if (glossaryHints.isNotBlank()) {
            appendLine(glossaryHints)
        }
        appendLine("Scene Mood: $sceneMood")
        if (prevHindiLines.isNotEmpty()) {
            appendLine("Previous Hindi context (do NOT re-translate):")
            prevHindiLines.forEach { appendLine("- $it") }
        }
        appendLine("Translate ALL ${lines.size} numbered scene lines below into natural spoken Hindi (Devanagari only) keeping consistent speaker/listener gender and pronoun tone (तुम/आप/तू).")
        lines.forEachIndexed { idx, line ->
            val sp = line.speakerGender?.takeIf { it.isNotBlank() && it != "NEUTRAL" } ?: "UNKNOWN"
            val ls = line.listenerGender?.takeIf { it.isNotBlank() && it != "NEUTRAL" }
            val lsPart = if (ls != null) " -> $ls" else ""
            val emoPart = if (!line.emotion.isNullOrBlank() && line.emotion != "neutral") ", ${line.emotion}" else ""
            val asrTag = if (line.asrMayHaveErrors) " [ASR-garbled: infer from scene]" else ""
            appendLine("[${idx + 1} | $sp$lsPart$emoPart$asrTag]: \"${line.englishText}\"")
        }
        if (!nextEnglishPreview.isNullOrBlank()) {
            appendLine("Following context: \"${nextEnglishPreview.take(55)}\"")
        }
        append("Return exact numbered lines [1]..[${lines.size}] in Hindi:\n[1]")
    }

    internal fun parseSceneBlockResponse(raw: String, expectedCount: Int): Map<Int, String> {
        if (raw.isBlank() || expectedCount <= 0) return emptyMap()
        val withoutThink = THINK_BLOCK_REGEX.replace(raw, " ").trim()
        // If prompt ended with "[1]", the model may start directly with line 1 text before "[2]"
        val normalized = if (!withoutThink.startsWith("[1]")) "[1] $withoutThink" else withoutThink
        val slotPattern = Regex("""\[(\d+)\]\s*(.*?)(?=\[\d+\]|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val out = mutableMapOf<Int, String>()
        slotPattern.findAll(normalized).forEach { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return@forEach
            val zeroIdx = num - 1
            if (zeroIdx in 0 until expectedCount && !out.containsKey(zeroIdx)) {
                val cleaned = extractBestHindiLine(m.groupValues[2])
                if (cleaned.isNotBlank()) {
                    out[zeroIdx] = cleaned
                }
            }
        }
        return out
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
