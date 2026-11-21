package com.example.ai

import android.util.Log
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.SpeakerEntity
import com.example.media.AudioChunkExtractor
import com.example.media.WavUtils
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

private const val TAG = "GeminiDubbingService"

data class VoicePreset(
    val id: String,
    val label: String,
    val gender: String, // MALE, FEMALE, NEUTRAL
    val description: String
)

object HindiVoicePresets {
    val all = listOf(
        VoicePreset("Charon", "Charon (Deep Male)", "MALE", "Grounded, authoritative cinema baritone"),
        VoicePreset("Fenrir", "Fenrir (Bold Male)", "MALE", "Dramatic, intense action & hero voice"),
        VoicePreset("Orus", "Orus (Warm Male)", "MALE", "Natural conversational male lead"),
        VoicePreset("Puck", "Puck (Youthful Male)", "MALE", "Energetic, expressive younger male"),
        VoicePreset("Kore", "Kore (Clear Female)", "FEMALE", "Crisp, expressive lead actress tone"),
        VoicePreset("Aoede", "Aoede (Warm Female)", "FEMALE", "Smooth, emotional conversational tone"),
        VoicePreset("Leda", "Leda (Soft Female)", "FEMALE", "Gentle, reflective narrative voice"),
        VoicePreset("Zephyr", "Zephyr (Balanced Neutral)", "NEUTRAL", "Versatile neutral narrator or supporting voice")
    )

    fun defaultForGender(gender: String, index: Int = 0): VoicePreset {
        val matches = all.filter { it.gender.equals(gender, ignoreCase = true) }
        val pool = if (matches.isNotEmpty()) matches else all
        return pool[index % pool.size]
    }
}

data class TranscribedSpeaker(
    val speakerLabel: String,
    val inferredGender: String,
    val traits: String
)

data class TranscribedSegment(
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val speakerLabel: String,
    val englishText: String,
    val emotion: String,
    val isOverlapping: Boolean,
    val audioType: String
)

data class ChunkTranscriptionResult(
    val speakers: List<TranscribedSpeaker>,
    val segments: List<TranscribedSegment>
)

data class TranslatedLine(
    val segmentId: Long,
    val hindiText: String,
    val emotion: String
)

data class GeminiCloudSampleResult(
    val hindiText: String,
    val emotion: String,
    val latencyMs: Long
)

class GeminiApiException(
    message: String,
    val isRateLimitOrQuota: Boolean = false,
    val isAppCheckOrAuth: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

class GeminiDubbingService {

    private val sttSchema = Schema.obj(
        properties = mapOf(
            "speakers" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "speakerId" to Schema.string(description = "Consistent speaker tag like SPEAKER_1, SPEAKER_2"),
                        "inferredGender" to Schema.enumeration(
                            values = listOf("MALE", "FEMALE", "NEUTRAL"),
                            description = "Estimated vocal register"
                        ),
                        "traits" to Schema.string(description = "Brief vocal & personality note")
                    )
                ),
                description = "Detected speakers in this audio segment"
            ),
            "segments" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "startOffsetMs" to Schema.integer(description = "Start timestamp in milliseconds from clip start (0)"),
                        "endOffsetMs" to Schema.integer(description = "End timestamp in milliseconds from clip start"),
                        "speakerId" to Schema.string(description = "Speaker tag matching speakers list, or AMBIENT for non-speech"),
                        "englishText" to Schema.string(description = "Verbatim English dialogue, or brief audio cue like [Music] / [Silence]"),
                        "emotion" to Schema.enumeration(
                            values = listOf("neutral", "happy", "sad", "angry", "fearful", "excited", "whisper", "serious"),
                            description = "Detected emotional delivery"
                        ),
                        "isOverlapping" to Schema.boolean(description = "True if speakers talk simultaneously"),
                        "audioType" to Schema.enumeration(
                            values = listOf("SPEECH", "MUSIC", "SFX", "SILENCE"),
                            description = "Primary audio classification"
                        )
                    )
                ),
                description = "Chronological timeline of speech, music, SFX, and silence"
            )
        )
    )

    private val translationSchema = Schema.obj(
        properties = mapOf(
            "translations" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "segmentId" to Schema.integer(description = "Exact segmentId from input"),
                        "hindiText" to Schema.string(description = "Natural conversational spoken Hindi in Devanagari script"),
                        "emotion" to Schema.string(description = "Preserved or refined emotion tag")
                    )
                )
            )
        )
    )

    private val sttModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = sttSchema
            }
        )
    }

    private val translationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.35f
                responseMimeType = "application/json"
                responseSchema = translationSchema
            }
        )
    }

    suspend fun transcribeAudioChunk(
        sttWavFile: File,
        chunkDurationMs: Long,
        knownSpeakers: List<SpeakerEntity>
    ): ChunkTranscriptionResult = withContext(Dispatchers.IO) {
        val wavBytes = sttWavFile.readBytes()
        require(wavBytes.size <= 19 * 1024 * 1024) {
            "Audio chunk (${wavBytes.size / (1024 * 1024)} MB) exceeds 20 MB inline limit. Choose a shorter chunk duration."
        }

        val knownHints = if (knownSpeakers.isNotEmpty()) {
            "Previously detected speakers in earlier chunks: " +
                knownSpeakers.joinToString("; ") { "${it.speakerLabel} (${it.gender}, ${it.traits})" } +
                ". Reuse those speaker IDs if the same voices appear."
        } else ""

        val prompt = """
            Analyze this ${chunkDurationMs}ms movie audio clip carefully.
            1. Transcribe ALL spoken English dialogue accurately in strict chronological order.
            2. Identify distinct speakers (SPEAKER_1, SPEAKER_2, etc.) and infer their vocal gender (MALE, FEMALE, NEUTRAL) and traits.
            3. Provide exact startOffsetMs and endOffsetMs relative to the beginning of this clip (0 to $chunkDurationMs).
            4. Mark overlapping dialogue with isOverlapping=true.
            5. Include significant non-speech intervals (MUSIC, SFX, SILENCE) so background music and effects are preserved.
            $knownHints
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                sttModel.generateContent(
                    content {
                        inlineData(wavBytes, "audio/wav")
                        text(prompt)
                    }
                )
            }
            val jsonStr = response.text ?: throw GeminiApiException("Gemini returned empty transcription.")
            parseTranscriptionJson(jsonStr, chunkDurationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "STT timed out", e)
            throw GeminiApiException("Transcription timed out after 60s. Check network and retry.", cause = e)
        } catch (e: Exception) {
            throw mapException("English speech recognition failed", e)
        }
    }

    suspend fun translateToHindi(
        segments: List<DialogueSegmentEntity>,
        speakers: List<SpeakerEntity>,
        dialogueStyle: String,
        dictionaryConfig: SlangDictionaryConfig? = null
    ): List<TranslatedLine> = withContext(Dispatchers.IO) {
        val speechSegments = segments.filter { it.audioType == "SPEECH" && it.englishText.isNotBlank() }
        if (speechSegments.isEmpty()) return@withContext emptyList()

        val speakerMap = speakers.associateBy { it.speakerLabel }
        val inputJson = JSONArray()
        val matchesBySegId = mutableMapOf<Long, List<MatchedSlangRule>>()
        speechSegments.forEachIndexed { idx, seg ->
            val sp = speakerMap[seg.speakerLabel]
            val prevText = speechSegments.getOrNull(idx - 1)?.englishText.orEmpty()
            val nextText = speechSegments.getOrNull(idx + 1)?.englishText.orEmpty()
            val sceneMood = TranscriptRepairHelper.inferSceneMood(speechSegments, idx)
            val listenerGender = TranscriptRepairHelper.inferListenerGender(speechSegments, idx, speakerMap)
            val isGarbled = TranscriptRepairHelper.isLikelyAsrGarble(seg.englishText)
            val segMatches = if (dictionaryConfig != null) {
                SlangDictionaryManager.resolveMatches(
                    config = dictionaryConfig,
                    englishText = seg.englishText,
                    prevContext = prevText,
                    nextContext = nextText,
                    emotion = seg.emotion,
                    dialogueStyle = dialogueStyle
                )
            } else emptyList()
            matchesBySegId[seg.id] = segMatches

            inputJson.put(
                JSONObject().apply {
                    put("segmentId", seg.id)
                    put("speaker", seg.speakerLabel)
                    put("gender", sp?.gender ?: "NEUTRAL")
                    if (!listenerGender.isNullOrBlank()) put("listenerGender", listenerGender)
                    put("sceneMood", sceneMood)
                    if (isGarbled) put("asrMayHaveErrors", true)
                    put("characterNotes", sp?.traits ?: "")
                    put("emotion", seg.emotion)
                    put("targetDurationMs", (seg.endMs - seg.startMs).coerceAtLeast(300L))
                    put("englishText", seg.englishText)
                }
            )
        }

        val glossarySection = SlangDictionaryManager.buildPromptGlossaryHints(matchesBySegId.values.flatten())
        val prompt = """
            You are an expert Bollywood/cinema Hindi dialogue writer.
            Translate EVERY English dialogue segment below into Hindi (Devanagari script).
            Style requested: $dialogueStyle.
            Strict requirements:
            - Natural, fluent spoken Hindi/Hindustani (avoid stiff literal textbook translations).
            - Match speaker gender and listenerGender in Hindi verb & pronoun endings (e.g., कर रहे हो vs कर रही हो).
            - Use `sceneMood` and surrounding lines to keep scene continuity and repair any phonetically garbled lines (`asrMayHaveErrors: true`) into natural intended movie dialogue.
            - Keep Hindi line length appropriate so it can be spoken naturally inside targetDurationMs.
            - Do NOT omit or merge any segmentId. Return one translation object per input segmentId.
            ${if (glossarySection.isNotBlank()) "\n$glossarySection\n" else ""}
            Segments JSON:
            $inputJson
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                translationModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw GeminiApiException("Gemini returned empty translation.")
            val rawParsed = parseTranslationJson(jsonStr)
            val segById = speechSegments.associateBy { it.id }
            rawParsed.map { line ->
                val segMatches = matchesBySegId[line.segmentId].orEmpty()
                val origEng = segById[line.segmentId]?.englishText.orEmpty()
                line.copy(
                    hindiText = SlangDictionaryManager.postProcessLlmOutput(
                        llmHindi = line.hindiText,
                        englishText = origEng,
                        matches = segMatches
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Translation timed out", e)
            throw GeminiApiException("Hindi translation timed out after 60s.", cause = e)
        } catch (e: Exception) {
            throw mapException("Hindi translation failed", e)
        }
    }

    suspend fun testGeminiCloudSample(
        englishLine: String,
        dialogueStyle: String,
        dictionaryConfig: SlangDictionaryConfig? = null
    ): GeminiCloudSampleResult = withContext(Dispatchers.IO) {
        val cleanLine = englishLine.trim()
        require(cleanLine.isNotBlank()) { "Sample English dialogue cannot be blank." }
        val startNs = System.nanoTime()
        val dummySegment = DialogueSegmentEntity(
            id = 1L,
            projectId = 0L,
            chunkIndex = 0,
            orderIndex = 0,
            startMs = 0L,
            endMs = 3500L,
            speakerLabel = "SPEAKER_1",
            englishText = cleanLine,
            emotion = "serious"
        )
        val dummySpeaker = SpeakerEntity(
            projectId = 0L,
            speakerLabel = "SPEAKER_1",
            displayName = "Speaker 1",
            gender = "MALE",
            voicePresetName = "Charon",
            traits = "Lead cinema character"
        )
        val matches = if (dictionaryConfig != null) {
            SlangDictionaryManager.resolveMatches(
                config = dictionaryConfig,
                englishText = cleanLine,
                prevContext = "",
                nextContext = "",
                emotion = "serious",
                dialogueStyle = dialogueStyle
            )
        } else emptyList()
        val glossarySection = SlangDictionaryManager.buildPromptGlossaryHints(matches)
        val inputJson = JSONArray().put(
            JSONObject().apply {
                put("segmentId", dummySegment.id)
                put("speaker", dummySpeaker.speakerLabel)
                put("gender", dummySpeaker.gender)
                put("sceneMood", "dramatic")
                put("emotion", dummySegment.emotion)
                put("targetDurationMs", 3500L)
                put("englishText", cleanLine)
            }
        )
        val prompt = """
            You are an expert Bollywood/cinema Hindi dialogue writer.
            Translate the English dialogue segment below into natural spoken Hindi (Devanagari script).
            Style requested: $dialogueStyle.
            ${if (glossarySection.isNotBlank()) "\n$glossarySection\n" else ""}
            Segments JSON:
            $inputJson
        """.trimIndent()

        try {
            val response = withTimeout(30_000L) {
                translationModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw GeminiApiException("Gemini returned an empty response.")
            val parsed = parseTranslationJson(jsonStr).firstOrNull { it.hindiText.isNotBlank() }
                ?: throw GeminiApiException("Gemini returned no translated Hindi line.")
            val finalHindi = SlangDictionaryManager.postProcessLlmOutput(
                llmHindi = parsed.hindiText,
                englishText = cleanLine,
                matches = matches
            )
            val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            GeminiCloudSampleResult(
                hindiText = finalHindi,
                emotion = parsed.emotion,
                latencyMs = latencyMs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini sample test timed out", e)
            throw GeminiApiException("Gemini Cloud request timed out after 30s. Check internet connection.", cause = e)
        } catch (e: GeminiApiException) {
            throw e
        } catch (e: Exception) {
            throw mapException("Gemini Cloud connection test failed", e)
        }
    }

    /**
     * Generates Hindi spoken audio for a single dialogue segment using Gemini TTS
     * (`gemini-3.1-flash-tts-preview`) and writes a normalized 44.1kHz Stereo WAV to [outWavFile].
     * Returns actual duration in milliseconds.
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun generateHindiTts(
        hindiText: String,
        voicePresetName: String,
        emotion: String,
        speedMultiplier: Float,
        targetSlotDurationMs: Long,
        outWavFile: File,
        tempCacheDir: File
    ): Long = withContext(Dispatchers.IO) {
        require(hindiText.isNotBlank()) { "Hindi text cannot be blank for TTS." }

        val validVoice = HindiVoicePresets.all.find { it.id == voicePresetName }?.id ?: "Kore"
        val ttsModel = Firebase.ai.generativeModel(
            modelName = "gemini-3.1-flash-tts-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(
                    voice = Voice(validVoice),
                    languageCode = "hi-IN"
                )
            },
            systemInstruction = content {
                text(
                    "You are a professional Hindi film dubbing voice actor. " +
                        "Deliver only the spoken Hindi dialogue naturally in a $emotion emotional tone. " +
                        "Pronounce Devanagari words authentically without adding extra words or commentary."
                )
            }
        )

        try {
            val response = withTimeout(45_000L) {
                ttsModel.generateContent(hindiText)
            }

            val audioPart = response.inlineDataParts.firstOrNull()
                ?: response.candidates.firstOrNull()?.content?.parts
                    ?.filterIsInstance<com.google.firebase.ai.type.InlineDataPart>()
                    ?.firstOrNull()
                ?: throw GeminiApiException("Gemini TTS returned no audio data for \"$hindiText\".")

            val mime = audioPart.mimeType.lowercase()
            val bytes = audioPart.inlineData
            require(bytes.isNotEmpty()) { "Gemini TTS returned empty audio payload." }

            outWavFile.parentFile?.mkdirs()

            if (mime.contains("l16") || mime.contains("pcm")) {
                val sampleRate = extractParamInt(mime, "rate", 24000)
                val channels = extractParamInt(mime, "channels", 1)
                val rawShorts = WavUtils.bytesToShortArray(bytes)
                val effectiveSpeed = computeEffectiveSpeed(
                    rawFrameCount = rawShorts.size / channels,
                    sampleRate = sampleRate,
                    requestedSpeed = speedMultiplier,
                    targetSlotMs = targetSlotDurationMs
                )
                val normalized = WavUtils.convertAndTimeScalePcm(
                    input = rawShorts,
                    srcRate = sampleRate,
                    srcChannels = channels,
                    dstRate = AudioChunkExtractor.MIX_SAMPLE_RATE,
                    dstChannels = AudioChunkExtractor.MIX_CHANNELS,
                    timeScale = effectiveSpeed
                )
                WavUtils.writePcmBytesAsWav(
                    pcmBytes = WavUtils.shortArrayToBytes(normalized),
                    sampleRate = AudioChunkExtractor.MIX_SAMPLE_RATE,
                    channels = AudioChunkExtractor.MIX_CHANNELS,
                    outFile = outWavFile
                )
            } else if (mime.contains("wav") || (bytes.size >= 4 && String(bytes, 0, 4) == "RIFF")) {
                val tempRawWav = File(tempCacheDir, "tts_raw_${System.nanoTime()}.wav")
                try {
                    tempRawWav.writeBytes(bytes)
                    val hdr = WavUtils.readHeader(tempRawWav)
                    val pcmBytes = tempRawWav.inputStream().use { ins ->
                        ins.skip(hdr.dataOffset)
                        ins.readBytes()
                    }
                    val rawShorts = WavUtils.bytesToShortArray(pcmBytes)
                    val effectiveSpeed = computeEffectiveSpeed(
                        rawFrameCount = rawShorts.size / hdr.channels,
                        sampleRate = hdr.sampleRate,
                        requestedSpeed = speedMultiplier,
                        targetSlotMs = targetSlotDurationMs
                    )
                    val normalized = WavUtils.convertAndTimeScalePcm(
                        input = rawShorts,
                        srcRate = hdr.sampleRate,
                        srcChannels = hdr.channels,
                        dstRate = AudioChunkExtractor.MIX_SAMPLE_RATE,
                        dstChannels = AudioChunkExtractor.MIX_CHANNELS,
                        timeScale = effectiveSpeed
                    )
                    WavUtils.writePcmBytesAsWav(
                        pcmBytes = WavUtils.shortArrayToBytes(normalized),
                        sampleRate = AudioChunkExtractor.MIX_SAMPLE_RATE,
                        channels = AudioChunkExtractor.MIX_CHANNELS,
                        outFile = outWavFile
                    )
                } finally {
                    tempRawWav.delete()
                }
            } else {
                // MP3 / AAC / OGG container: decode to raw WAV first, then apply uniform slot-fit time-scaling
                val ext = when {
                    mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
                    mime.contains("ogg") -> "ogg"
                    else -> "m4a"
                }
                val tempEncoded = File(tempCacheDir, "tts_enc_${System.nanoTime()}.$ext")
                val tempDecoded = File(tempCacheDir, "tts_dec_${System.nanoTime()}.wav")
                try {
                    tempEncoded.writeBytes(bytes)
                    AudioChunkExtractor.decodeEncodedAudioToMixWav(
                        encodedFile = tempEncoded,
                        outWavFile = tempDecoded,
                        timeScale = 1.0f
                    )
                    val hdr = WavUtils.readHeader(tempDecoded)
                    val pcmBytes = tempDecoded.inputStream().use { ins ->
                        ins.skip(hdr.dataOffset)
                        ins.readBytes()
                    }
                    val rawShorts = WavUtils.bytesToShortArray(pcmBytes)
                    val effectiveSpeed = computeEffectiveSpeed(
                        rawFrameCount = rawShorts.size / hdr.channels.coerceAtLeast(1),
                        sampleRate = hdr.sampleRate,
                        requestedSpeed = speedMultiplier,
                        targetSlotMs = targetSlotDurationMs
                    )
                    val normalized = WavUtils.convertAndTimeScalePcm(
                        input = rawShorts,
                        srcRate = hdr.sampleRate,
                        srcChannels = hdr.channels,
                        dstRate = AudioChunkExtractor.MIX_SAMPLE_RATE,
                        dstChannels = AudioChunkExtractor.MIX_CHANNELS,
                        timeScale = effectiveSpeed
                    )
                    WavUtils.writePcmBytesAsWav(
                        pcmBytes = WavUtils.shortArrayToBytes(normalized),
                        sampleRate = AudioChunkExtractor.MIX_SAMPLE_RATE,
                        channels = AudioChunkExtractor.MIX_CHANNELS,
                        outFile = outWavFile
                    )
                } finally {
                    tempEncoded.delete()
                    tempDecoded.delete()
                }
            }

            WavUtils.readHeader(outWavFile).durationMs
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "TTS timed out", e)
            throw GeminiApiException("Hindi TTS timed out after 45s.", cause = e)
        } catch (e: GeminiApiException) {
            throw e
        } catch (e: Exception) {
            throw mapException("Hindi TTS voice generation failed", e)
        }
    }

    internal fun computeEffectiveSpeed(
        rawFrameCount: Int,
        sampleRate: Int,
        requestedSpeed: Float,
        targetSlotMs: Long
    ): Float {
        if (rawFrameCount <= 0 || sampleRate <= 0 || targetSlotMs <= 0L) return requestedSpeed
        val baseDurationMs = (rawFrameCount * 1000.0) / sampleRate
        val requestedDurationMs = baseDurationMs / requestedSpeed.coerceIn(0.7f, 1.4f)
        // Speed up (up to 1.45x) when generated Hindi dialogue overflows the slot by >4% to prevent overlapping lines
        return if (requestedDurationMs > targetSlotMs * 1.04) {
            val fitScale = (baseDurationMs / (targetSlotMs * 1.01)).toFloat()
            fitScale.coerceIn(requestedSpeed, 1.45f)
        } else {
            requestedSpeed
        }
    }

    internal fun parseTranscriptionJson(
        jsonString: String,
        chunkDurationMs: Long
    ): ChunkTranscriptionResult {
        val root = JSONObject(jsonString)
        val speakersArr = root.optJSONArray("speakers") ?: JSONArray()
        val speakers = (0 until speakersArr.length()).mapNotNull { idx ->
            val obj = speakersArr.optJSONObject(idx) ?: return@mapNotNull null
            val label = obj.optString("speakerId").trim().ifBlank { "SPEAKER_${idx + 1}" }
            val gender = obj.optString("inferredGender", "NEUTRAL").uppercase()
            val traits = obj.optString("traits", "")
            TranscribedSpeaker(label, gender, traits)
        }

        val segArr = root.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until segArr.length()).mapNotNull { idx ->
            val obj = segArr.optJSONObject(idx) ?: return@mapNotNull null
            val start = obj.optLong("startOffsetMs", 0L).coerceIn(0L, chunkDurationMs)
            val end = obj.optLong("endOffsetMs", start + 500L).coerceIn(start, chunkDurationMs)
            val speakerId = obj.optString("speakerId", "SPEAKER_1").trim().ifBlank { "SPEAKER_1" }
            val text = obj.optString("englishText", "").trim()
            val emotion = obj.optString("emotion", "neutral").lowercase()
            val overlapping = obj.optBoolean("isOverlapping", false)
            val audioType = obj.optString("audioType", "SPEECH").uppercase()
            TranscribedSegment(start, end, speakerId, text, emotion, overlapping, audioType)
        }.sortedBy { it.startOffsetMs }

        return ChunkTranscriptionResult(speakers, segments)
    }

    internal fun parseTranslationJson(jsonString: String): List<TranslatedLine> {
        val root = JSONObject(jsonString)
        val arr = root.optJSONArray("translations") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { idx ->
            val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
            val segId = obj.optLong("segmentId", -1L)
            if (segId < 0L) return@mapNotNull null
            val hindi = obj.optString("hindiText", "").trim()
            val emotion = obj.optString("emotion", "neutral").lowercase()
            TranslatedLine(segId, hindi, emotion)
        }
    }

    private fun extractParamInt(mime: String, key: String, fallback: Int): Int {
        val match = Regex("""$key\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(mime)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallback
    }

    internal fun mapException(prefix: String, e: Exception): GeminiApiException {
        Log.e(TAG, prefix, e)
        val msg = e.localizedMessage ?: e.javaClass.simpleName
        val lower = msg.lowercase()
        val isQuota = lower.contains("429") || lower.contains("quota") ||
            lower.contains("rate limit") || lower.contains("resource_exhausted")
        val isAppCheck = lower.contains("403") || lower.contains("app check") || lower.contains("attestation")
        val friendly = when {
            isAppCheck -> "$prefix: Firebase App Check attestation error (HTTP 403). Ensure debug token / App Check is configured."
            isQuota -> "$prefix: Gemini API quota or rate limit reached (429). Pause and resume later or upgrade Firebase Blaze plan."
            else -> "$prefix: $msg"
        }
        return GeminiApiException(friendly, isRateLimitOrQuota = isQuota, isAppCheckOrAuth = isAppCheck, cause = e)
    }
}
