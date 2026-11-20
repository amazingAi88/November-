package com.example.ai

import android.util.Log
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DialogueStyle
import com.example.data.local.SpeakerEntity
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LocalGgufTranslator"

data class LocalLlmSampleResult(
    val hindiText: String,
    val tokensPerSecond: Float
)

/**
 * Runs local open-source GGUF language models (Qwen / Gemma / Llama GGUF) completely offline
 * via native llama.cpp bindings (`dev.ffmpegkit-maintained:llama-android`) for scene-aware,
 * colloquial, unfiltered Hindi cinema dialogue translation.
 */
class LocalGgufTranslator {

    private val mutex = Mutex()
    private var loadedModelHandle: LlamaModel? = null
    private var loadedModelPath: String? = null

    suspend fun translateSegmentsWithContext(
        modelFile: File,
        segments: List<DialogueSegmentEntity>,
        speakers: List<SpeakerEntity>,
        dialogueStyle: String
    ): List<TranslatedLine> = withContext(Dispatchers.IO) {
        val speechSegments = segments.filter { it.audioType == "SPEECH" && it.englishText.isNotBlank() }
        if (speechSegments.isEmpty()) return@withContext emptyList()

        val speakerMap = speakers.associateBy { it.speakerLabel }

        mutex.withLock {
            val handle = ensureLoadedLocked(modelFile)
            val results = mutableListOf<TranslatedLine>()
            val systemPrompt = buildCinemaSystemPrompt(dialogueStyle)

            // Process in small 4-line batches with preceding dialogue context for accuracy & speed on mobile RAM
            val batchSize = 4
            for (batchStart in speechSegments.indices step batchSize) {
                val batchEnd = minOf(batchStart + batchSize, speechSegments.size)
                val batch = speechSegments.subList(batchStart, batchEnd)
                val prevLine = if (batchStart > 0) speechSegments[batchStart - 1] else null

                val parsedMap = if (batch.size > 1) {
                    val batchPrompt = buildBatchPrompt(batch, prevLine, speakerMap)
                    val completion = Llama.complete(
                        handle,
                        prompt = batchPrompt,
                        systemPrompt = systemPrompt,
                        maxTokens = (batch.size * 70).coerceIn(128, 420)
                    )
                    parseNumberedHindiLines(completion.text, batch.size)
                } else {
                    emptyMap()
                }

                for ((localIdx, seg) in batch.withIndex()) {
                    val batchText = parsedMap[localIdx]
                    val finalHindi = if (!batchText.isNullOrBlank()) {
                        batchText
                    } else {
                        // Single-line contextual fallback so no segment is ever skipped
                        val prevCtx = when {
                            localIdx > 0 -> batch[localIdx - 1].englishText
                            prevLine != null -> prevLine.englishText
                            else -> null
                        }
                        val nextCtx = batch.getOrNull(localIdx + 1)?.englishText
                        translateSingleInternal(handle, seg, speakerMap[seg.speakerLabel], prevCtx, nextCtx, systemPrompt)
                    }

                    results.add(
                        TranslatedLine(
                            segmentId = seg.id,
                            hindiText = finalHindi.ifBlank { seg.englishText },
                            emotion = seg.emotion
                        )
                    )
                }
            }
            results
        }
    }

    suspend fun translateSingleSample(
        modelFile: File,
        englishLine: String,
        dialogueStyle: String
    ): LocalLlmSampleResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val handle = ensureLoadedLocked(modelFile)
            val systemPrompt = buildCinemaSystemPrompt(dialogueStyle)
            val prompt = buildString {
                appendLine("Translate this English movie dialogue into spoken Hindi (Devanagari script only).")
                appendLine("Dialogue: \"$englishLine\"")
                append("Hindi:")
            }
            val res = Llama.complete(
                handle,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = 140
            )
            val cleaned = cleanSingleHindiOutput(res.text)
            LocalLlmSampleResult(
                hindiText = cleaned.ifBlank { res.text.trim() },
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
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        Log.i(TAG, "Loading local GGUF model ${modelFile.name} with $cpuThreads threads")
        val handle = try {
            Llama.loadModel(
                modelPath = path,
                config = LlamaConfig(
                    contextSize = 2048,
                    threads = cpuThreads
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

    private suspend fun translateSingleInternal(
        handle: LlamaModel,
        seg: DialogueSegmentEntity,
        speaker: SpeakerEntity?,
        prevEnglish: String?,
        nextEnglish: String?,
        systemPrompt: String
    ): String {
        val prompt = buildString {
            if (!prevEnglish.isNullOrBlank()) {
                appendLine("Previous line: \"$prevEnglish\"")
            }
            if (!nextEnglish.isNullOrBlank()) {
                appendLine("Next line: \"$nextEnglish\"")
            }
            appendLine("Speaker: ${seg.speakerLabel} (${speaker?.gender ?: "NEUTRAL"}, mood: ${seg.emotion})")
            appendLine("Translate ONLY this target line into spoken Hindi (Devanagari script only, no English/explanation):")
            appendLine("Target: \"${seg.englishText}\"")
            append("Hindi:")
        }
        val res = Llama.complete(
            handle,
            prompt = prompt,
            systemPrompt = systemPrompt,
            maxTokens = 120
        )
        return cleanSingleHindiOutput(res.text)
    }

    private fun buildCinemaSystemPrompt(dialogueStyle: String): String {
        val styleRule = when (dialogueStyle) {
            DialogueStyle.RAW_UNCENSORED ->
                "STYLE: Raw Uncensored OTT / Adult & Crime Cinema Hindi. NEVER sanitize, soften, censor, or replace profanity, slang, sexual/adult terms, or street insults with polite dictionary words like 'लानत' or 'मूर्ख'. Match the exact raw intensity, slang, intimacy, or street bluntness naturally in spoken Hindi."
            DialogueStyle.CINEMATIC ->
                "STYLE: Dramatic Bollywood Cinema Hindi. Punchy, emotional, impactful spoken dialogues."
            DialogueStyle.CASUAL ->
                "STYLE: Casual Everyday Hindustani. Street-smart, natural conversational Hindi/Urdu mix."
            else ->
                "STYLE: Natural Conversational Hindi movie dubbing. Fluent spoken rhythm, not textbook translation."
        }
        return """
            You are an expert Bollywood/OTT Hindi film dubbing dialogue adapter.
            $styleRule
            STRICT RULES:
            - Output ONLY spoken Hindi in Devanagari script.
            - Never write literal dictionary translations; adapt idioms, slang, and sarcasm based on scene context.
            - Keep line length concise so a voice actor can dub it in roughly the same speaking duration.
            - Do NOT include notes, parentheses, translations labels, or censorship apologies.
        """.trimIndent()
    }

    private fun buildBatchPrompt(
        batch: List<DialogueSegmentEntity>,
        prevLine: DialogueSegmentEntity?,
        speakerMap: Map<String, SpeakerEntity>
    ): String = buildString {
        if (prevLine != null && prevLine.englishText.isNotBlank()) {
            appendLine("[PREV CONTEXT ONLY - DO NOT TRANSLATE]: ${prevLine.speakerLabel}: ${prevLine.englishText}")
            appendLine()
        }
        appendLine("Translate each numbered line below into natural spoken Hindi (Devanagari only). Keep exact numbering 1..${batch.size}:")
        batch.forEachIndexed { idx, seg ->
            val gender = speakerMap[seg.speakerLabel]?.gender ?: "NEUTRAL"
            appendLine("${idx + 1}. [${seg.speakerLabel}/$gender/${seg.emotion}] ${seg.englishText}")
        }
        appendLine()
        append("Output ONLY lines starting with 1., 2., etc.:")
    }

    private fun parseNumberedHindiLines(raw: String, expectedCount: Int): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val lineRegex = Regex("""^\s*(\d+)[\.\)\:\-]\s*(.+)$""")
        raw.lines().forEach { line ->
            val m = lineRegex.find(line) ?: return@forEach
            val num = m.groupValues[1].toIntOrNull() ?: return@forEach
            val idx = num - 1
            if (idx in 0 until expectedCount) {
                val cleaned = cleanSingleHindiOutput(m.groupValues[2])
                if (cleaned.isNotBlank()) {
                    map[idx] = cleaned
                }
            }
        }
        return map
    }

    private fun cleanSingleHindiOutput(raw: String): String {
        val firstMeaningful = raw.trim()
            .removePrefix("Hindi:")
            .removePrefix("Translation:")
            .trim()
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: ""
        return firstMeaningful
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }
}
