package com.example.ai

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as AndroidTtsVoice
import android.util.Log
import com.example.ai.OnDeviceModelManager.awaitTask
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.SpeakerEntity
import com.example.media.AudioChunkExtractor
import com.example.media.WavUtils
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OnDeviceDubbingEngine"

/**
 * Zero-cost, on-device Hindi Movie Dubbing engine built for Android Go & mid-range phones:
 * 1. Speech Recognition: Vosk Offline English STT (word-level timestamps) + DSP pitch speaker detector
 * 2. Translation: Google ML Kit On-Device English -> Hindi Translator (no API key / no 429 limits)
 * 3. Voice Generation: Android built-in TextToSpeech (`hi-IN`) `synthesizeToFile` + pitch/rate per character
 */
class OnDeviceDubbingEngine(private val context: Context) {

    private var voskModel: Model? = null
    private var mlKitTranslator: Translator? = null
    private var ttsEngine: TextToSpeech? = null
    private var cachedHindiVoices: List<AndroidTtsVoice> = emptyList()
    private val ttsMutex = Mutex()
    private val localGgufTranslator = LocalGgufTranslator()

    suspend fun ensureReady(onStatus: (String) -> Unit) {
        OnDeviceModelManager.ensureAllModelsReady(context, onStatus)
    }

    suspend fun ensureReadyForLocalLlm(
        preferredGgufFileName: String?,
        onStatus: (String) -> Unit
    ): File {
        if (!OnDeviceModelManager.isVoskModelReady(context)) {
            OnDeviceModelManager.downloadAndExtractVoskModel(context, onStatus)
        }
        val (ttsReady, _, ttsNote) = OnDeviceModelManager.checkAndroidHindiTts(context)
        if (!ttsReady) {
            throw IllegalStateException(
                "Android Hindi Text-to-Speech voice data is not ready on this phone ($ttsNote). " +
                    "Tap 'Install Hindi TTS Voice' or enable Google Speech Services in Android Settings."
            )
        }
        onStatus("Verifying local open-source GGUF LLM...")
        return LocalLlmModelManager.resolveActiveModelFile(context, preferredGgufFileName)
            ?: throw IllegalStateException(
                "No Local GGUF LLM model installed yet. Open Studio Settings → Local Open-Source GGUF LLM to download Qwen 2.5 or import a .gguf file."
            )
    }

    /**
     * Transcribes 16kHz Mono PCM WAV chunk entirely on-device using Vosk + autocorrelation pitch analysis.
     */
    suspend fun transcribeAudioChunk(
        sttWavFile: File,
        chunkDurationMs: Long
    ): ChunkTranscriptionResult = withContext(Dispatchers.IO) {
        val model = getOrLoadVoskModel()
        val header = WavUtils.readHeader(sttWavFile)
        val sampleRate = header.sampleRate.toFloat().coerceAtLeast(16000.0f)

        val rawPhrases = mutableListOf<OnDeviceSpeakerAnalyzer.SpeechRawPhrase>()
        val recognizer = Recognizer(model, sampleRate)
        try {
            recognizer.setWords(true)
            val buffer = ByteArray(4096)
            RandomAccessFile(sttWavFile, "r").use { raf ->
                raf.seek(header.dataOffset)
                var remaining = header.dataSize
                while (remaining > 0L) {
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    remaining -= read
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        extractWordTimedPhrases(recognizer.result, chunkDurationMs, rawPhrases)
                    }
                }
            }
            extractWordTimedPhrases(recognizer.finalResult, chunkDurationMs, rawPhrases)
        } finally {
            runCatching { recognizer.close() }
        }

        OnDeviceSpeakerAnalyzer.analyzeSpeakersAndBuildTimeline(
            sttWavFile = sttWavFile,
            phrases = rawPhrases,
            chunkDurationMs = chunkDurationMs
        )
    }

    /**
     * Translates English segments into Hindi using Google ML Kit On-Device Translation (~30 MB model, ₹0 free).
     */
    suspend fun translateToHindi(
        segments: List<DialogueSegmentEntity>
    ): List<TranslatedLine> = withContext(Dispatchers.IO) {
        val speechSegments = segments.filter { it.audioType == "SPEECH" && it.englishText.isNotBlank() }
        if (speechSegments.isEmpty()) return@withContext emptyList()

        val translator = getOrCreateTranslator()
        val results = mutableListOf<TranslatedLine>()
        for (seg in speechSegments) {
            val cleanedEnglish = normalizeEnglishForTranslation(seg.englishText)
            val hindi = translator.translate(cleanedEnglish).awaitTask().trim()
            results.add(
                TranslatedLine(
                    segmentId = seg.id,
                    hindiText = hindi.ifBlank { cleanedEnglish },
                    emotion = seg.emotion
                )
            )
        }
        results
    }

    /**
     * Context-aware offline cinema translation via local open-source GGUF model (Qwen / Gemma / Llama GGUF).
     */
    suspend fun translateToHindiWithLocalLlm(
        segments: List<DialogueSegmentEntity>,
        speakers: List<SpeakerEntity>,
        dialogueStyle: String,
        modelFile: File
    ): List<TranslatedLine> = withContext(Dispatchers.IO) {
        // Free Vosk native buffers before mapping the GGUF weights on 4-6GB RAM phones
        runCatching { voskModel?.close() }
        voskModel = null

        localGgufTranslator.translateSegmentsWithContext(
            modelFile = modelFile,
            segments = segments,
            speakers = speakers,
            dialogueStyle = dialogueStyle
        )
    }

    /**
     * Synthesizes Hindi speech audio directly to a 44.1kHz Stereo PCM WAV file using Android's
     * built-in TextToSpeech (`hi-IN`) engine with speaker-specific voice & pitch shaping.
     */
    suspend fun generateHindiTts(
        hindiText: String,
        voicePresetName: String,
        emotion: String,
        speedMultiplier: Float,
        targetSlotDurationMs: Long,
        outWavFile: File,
        tempCacheDir: File
    ): Long = ttsMutex.withLock {
        require(hindiText.isNotBlank()) { "Hindi text cannot be blank for TTS." }

        val tts = getOrInitTts()
        val preset = HindiVoicePresets.all.find { it.id == voicePresetName }
            ?: HindiVoicePresets.all.first()

        // Pick distinct installed Hindi voice matching preset gender when multiple system voices exist
        val targetVoice = pickAndroidVoiceForPreset(preset)
        if (targetVoice != null) {
            runCatching { tts.voice = targetVoice }
        } else {
            runCatching { tts.language = Locale.forLanguageTag("hi-IN") }
        }

        val basePitch = when (preset.id) {
            "Charon" -> 0.82f
            "Fenrir" -> 0.89f
            "Orus" -> 0.95f
            "Puck" -> 1.04f
            "Kore" -> 1.15f
            "Aoede" -> 1.10f
            "Leda" -> 1.22f
            else -> 1.00f
        }
        val emotionPitchBias = when (emotion.lowercase()) {
            "excited", "energetic", "angry" -> 1.05f
            "sad", "whisper", "calm" -> 0.95f
            else -> 1.0f
        }
        tts.setPitch((basePitch * emotionPitchBias).coerceIn(0.65f, 1.45f))
        tts.setSpeechRate(speedMultiplier.coerceIn(0.75f, 1.35f))

        outWavFile.parentFile?.mkdirs()
        val rawTtsWav = File(tempCacheDir, "android_tts_${UUID.randomUUID()}.wav")
        try {
            synthesizeToFileSuspend(tts, hindiText, rawTtsWav)
            if (!rawTtsWav.exists() || rawTtsWav.length() <= 44L) {
                throw IllegalStateException("Android TTS produced an empty audio file for \"$hindiText\".")
            }

            // Read raw TTS WAV duration & compute gentle slot-fit time-scaling if slightly longer than scene gap
            val rawHdr = runCatching { WavUtils.readHeader(rawTtsWav) }.getOrNull()
            val fitScale = if (rawHdr != null && targetSlotDurationMs > 0L && rawHdr.durationMs > targetSlotDurationMs * 1.15) {
                (rawHdr.durationMs.toFloat() / (targetSlotDurationMs * 1.05f)).coerceIn(1.0f, 1.32f)
            } else 1.0f

            AudioChunkExtractor.decodeEncodedAudioToMixWav(
                encodedFile = rawTtsWav,
                outWavFile = outWavFile,
                timeScale = fitScale
            )
            WavUtils.readHeader(outWavFile).durationMs
        } finally {
            rawTtsWav.delete()
        }
    }

    fun release() {
        runCatching { voskModel?.close() }
        voskModel = null
        runCatching { mlKitTranslator?.close() }
        mlKitTranslator = null
        runCatching { ttsEngine?.shutdown() }
        ttsEngine = null
        localGgufTranslator.releaseBlocking()
    }

    @Synchronized
    private fun getOrLoadVoskModel(): Model {
        voskModel?.let { return it }
        val dir = OnDeviceModelManager.voskModelDir(context)
        require(OnDeviceModelManager.isVoskModelReady(context)) {
            "Vosk English model not found. Ensure phone internet is on for one-time ~40 MB download."
        }
        return Model(dir.absolutePath).also { voskModel = it }
    }

    @Synchronized
    private fun getOrCreateTranslator(): Translator {
        mlKitTranslator?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HINDI)
            .build()
        return Translation.getClient(options).also { mlKitTranslator = it }
    }

    private suspend fun getOrInitTts(): TextToSpeech {
        ttsEngine?.let { return it }
        return withTimeout(10_000L) {
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                created = TextToSpeech(context.applicationContext) { status ->
                    val engine = created
                    if (status != TextToSpeech.SUCCESS || engine == null) {
                        runCatching { engine?.shutdown() }
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("Android TextToSpeech engine failed to initialize (status=$status).")
                            )
                        }
                        return@TextToSpeech
                    }
                    val langRes = engine.setLanguage(Locale.forLanguageTag("hi-IN"))
                    if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                        runCatching { engine.shutdown() }
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(
                                    "Hindi (hi-IN) Text-to-Speech voice data is missing. Install Hindi TTS voice in Android Settings."
                                )
                            )
                        }
                        return@TextToSpeech
                    }
                    cachedHindiVoices = runCatching {
                        engine.voices
                            ?.filter { it.locale.language.equals("hi", ignoreCase = true) }
                            ?.sortedBy { it.name }
                            ?: emptyList()
                    }.getOrDefault(emptyList())
                    ttsEngine = engine
                    if (cont.isActive) cont.resume(engine)
                }
                cont.invokeOnCancellation {
                    if (ttsEngine == null) runCatching { created.shutdown() }
                }
            }
        }
    }

    private suspend fun synthesizeToFileSuspend(
        tts: TextToSpeech,
        text: String,
        outFile: File
    ) {
        val utteranceId = "dub_${UUID.randomUUID()}"
        withTimeout(35_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId && cont.isActive) {
                            cont.resume(Unit)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId && cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Android TTS synthesis error."))
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId && cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("Android TTS synthesis error (code $errorCode).")
                            )
                        }
                    }
                })
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val callResult = tts.synthesizeToFile(text, params, outFile, utteranceId)
                if (callResult != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("Android TTS failed to queue synthesis request (code $callResult).")
                    )
                }
            }
        }
    }

    private fun pickAndroidVoiceForPreset(preset: VoicePreset): AndroidTtsVoice? {
        if (cachedHindiVoices.isEmpty()) return null
        val localPreferred = cachedHindiVoices.sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
        val maleHints = localPreferred.filter {
            val n = it.name.lowercase()
            n.contains("-x-hid") || n.contains("-x-hie") || n.contains("male") || n.contains("-b-") || n.contains("-d-")
        }
        val femaleHints = localPreferred.filter {
            val n = it.name.lowercase()
            n.contains("-x-hia") || n.contains("-x-hic") || n.contains("female") || n.contains("-a-") || n.contains("-c-")
        }
        val idx = HindiVoicePresets.all.indexOfFirst { it.id == preset.id }.coerceAtLeast(0)
        return when (preset.gender) {
            "MALE" -> if (maleHints.isNotEmpty()) maleHints[idx % maleHints.size] else localPreferred[idx % localPreferred.size]
            "FEMALE" -> if (femaleHints.isNotEmpty()) femaleHints[idx % femaleHints.size] else localPreferred[idx % localPreferred.size]
            else -> localPreferred[idx % localPreferred.size]
        }
    }

    private fun extractWordTimedPhrases(
        jsonString: String?,
        chunkDurationMs: Long,
        out: MutableList<OnDeviceSpeakerAnalyzer.SpeechRawPhrase>
    ) {
        if (jsonString.isNullOrBlank()) return
        val root = runCatching { JSONObject(jsonString) }.getOrNull() ?: return
        val text = root.optString("text", "").trim()
        if (text.isBlank()) return

        val wordsArr = root.optJSONArray("result")
        if (wordsArr == null || wordsArr.length() == 0) return

        // Split long continuous Vosk blocks at natural pauses (>380ms) or every ~14 words
        // so dubbed segments remain tightly synced to screen action.
        var phraseStartMs = -1L
        var lastEndMs = 0L
        val currentWords = mutableListOf<String>()

        fun flushPhrase() {
            if (currentWords.isNotEmpty() && phraseStartMs >= 0L) {
                val s = phraseStartMs.coerceIn(0L, chunkDurationMs)
                val e = lastEndMs.coerceIn(s + 250L, chunkDurationMs)
                out.add(
                    OnDeviceSpeakerAnalyzer.SpeechRawPhrase(
                        startOffsetMs = s,
                        endOffsetMs = e,
                        text = currentWords.joinToString(" ")
                    )
                )
            }
            currentWords.clear()
            phraseStartMs = -1L
        }

        for (i in 0 until wordsArr.length()) {
            val wObj = wordsArr.optJSONObject(i) ?: continue
            val w = wObj.optString("word", "").trim()
            if (w.isBlank()) continue
            val wStartMs = (wObj.optDouble("start", 0.0) * 1000.0).toLong()
            val wEndMs = (wObj.optDouble("end", wStartMs / 1000.0 + 0.25) * 1000.0).toLong()

            if (phraseStartMs < 0L) {
                phraseStartMs = wStartMs
                lastEndMs = wEndMs
                currentWords.add(w)
            } else {
                val gapMs = wStartMs - lastEndMs
                if (gapMs > 380L || currentWords.size >= 14) {
                    flushPhrase()
                    phraseStartMs = wStartMs
                }
                currentWords.add(w)
                lastEndMs = maxOf(lastEndMs, wEndMs)
            }
        }
        flushPhrase()
    }

    private fun normalizeEnglishForTranslation(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        // Capitalize first letter so ML Kit produces cleaner Hindi sentence punctuation
        return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}
