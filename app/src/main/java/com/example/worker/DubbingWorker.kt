package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.ai.GeminiApiException
import com.example.ai.GeminiDubbingService
import com.example.ai.HindiVoicePresets
import com.example.ai.OnDeviceDubbingEngine
import com.example.ai.SlangDictionaryManager
import com.example.ai.VocalSfxClassifier
import com.example.data.local.ChunkEntity
import com.example.data.local.ChunkStatus
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DubbingRepository
import com.example.data.local.DubbingSpeedMode
import com.example.data.local.EngineMode
import com.example.data.local.PreferencesRepository
import com.example.data.local.ProcessingStage
import com.example.data.local.ProjectStatus
import com.example.data.local.SegmentStatus
import com.example.data.local.SpeakerEntity
import com.example.media.AudioChunkExtractor
import com.example.media.AudioMixer
import com.example.media.AudioSeparator
import com.example.media.SrtSubtitleParser
import com.example.media.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "DubbingWorker"
private const val CHANNEL_ID = "dubbing_pipeline_channel"
private const val NOTIFICATION_BASE_ID = 4100

class DubbingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository = DubbingRepository(appContext)
    private val aiService = GeminiDubbingService()
    private val onDeviceEngine = OnDeviceDubbingEngine(appContext)

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId <= 0L) return Result.failure()

        val initialProject = repository.getProject(projectId) ?: return Result.failure()
        val actionMode = inputData.getString(KEY_ACTION_MODE) ?: MODE_FULL_PIPELINE
        val initialUseMediaProcessingType = actionMode == MODE_REMIX_ONLY ||
            initialProject.engineMode == EngineMode.ON_DEVICE_FREE ||
            initialProject.engineMode == EngineMode.ON_DEVICE_LLM

        safeSetForeground(
            createForegroundInfo(
                projectId = projectId,
                movieTitle = initialProject.title,
                stage = "Preparing dubbing pipeline...",
                progress = 0,
                useMediaProcessingType = initialUseMediaProcessingType
            )
        )

        return try {
            when (actionMode) {
                MODE_REMIX_ONLY -> runRemixOnly(projectId)
                MODE_RETRY_FAILED -> runFullPipeline(projectId, retryFailedOnly = true)
                else -> runFullPipeline(projectId, retryFailedOnly = false)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "DubbingWorker cancelled/paused for project $projectId")
            val latest = repository.getProject(projectId)
            if (latest != null && latest.status == ProjectStatus.PROCESSING) {
                repository.updateProjectProgress(
                    projectId = projectId,
                    status = ProjectStatus.PAUSED,
                    stage = latest.currentStage,
                    progress = latest.progressPercent,
                    etaMs = null,
                    error = null
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "DubbingWorker failed for project $projectId", e)
            val latest = repository.getProject(projectId)
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.ERROR,
                stage = latest?.currentStage ?: ProcessingStage.IDLE,
                progress = latest?.progressPercent ?: 0,
                etaMs = null,
                error = e.localizedMessage ?: "Processing failed"
            )
            Result.failure()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "DubbingWorker fatal/memory error for project $projectId", t)
            val latest = repository.getProject(projectId)
            val friendlyMsg = if (t is OutOfMemoryError) {
                "Phone ran out of RAM loading the local LLM. Switch to Qwen 2.5 0.5B Lite or close background apps."
            } else {
                t.localizedMessage ?: "Unexpected native processing error"
            }
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.ERROR,
                stage = latest?.currentStage ?: ProcessingStage.IDLE,
                progress = latest?.progressPercent ?: 0,
                etaMs = null,
                error = friendlyMsg
            )
            Result.failure()
        } finally {
            onDeviceEngine.release()
        }
    }

    private suspend fun runFullPipeline(projectId: Long, retryFailedOnly: Boolean): Result {
        var project = repository.getProject(projectId) ?: return Result.failure()
        val sourceUri = Uri.parse(project.sourceUriString)

        // 1. Initialize chunks in Room if not yet created
        var chunks = repository.getChunks(projectId)
        if (chunks.isEmpty()) {
            val chunkMs = (project.chunkDurationSec.coerceIn(120, 300)) * 1000L
            val created = mutableListOf<ChunkEntity>()
            var start = 0L
            var idx = 0
            while (start < project.durationMs) {
                val end = minOf(start + chunkMs, project.durationMs)
                created.add(
                    ChunkEntity(
                        projectId = projectId,
                        chunkIndex = idx,
                        startMs = start,
                        endMs = end,
                        status = ChunkStatus.PENDING
                    )
                )
                start = end
                idx++
            }
            repository.insertChunks(created)
            chunks = repository.getChunks(projectId)
        }

        val useLocalLlm = project.engineMode == EngineMode.ON_DEVICE_LLM
        val useOnDevice = project.engineMode == EngineMode.ON_DEVICE_FREE || useLocalLlm
        val studioPrefs = PreferencesRepository(applicationContext).preferencesFlow.first()
        val hybridTurbo = studioPrefs.dubbingSpeedMode == DubbingSpeedMode.HYBRID_TURBO
        val sttEngineMode = studioPrefs.sttEngineMode
        val vocalIsolationEnabled = studioPrefs.sttVocalIsolationEnabled

        val parsedSubtitleCues: List<SubtitleCue> = project.subtitlePath
            ?.let { path -> File(path) }
            ?.takeIf { it.exists() && it.isFile }
            ?.let { subFile -> runCatching { SrtSubtitleParser.parse(subFile.readText()) }.getOrNull() }
            ?: emptyList()
        val hasSubtitleFile = parsedSubtitleCues.isNotEmpty()

        var activeGgufFile: File? = null
        if (useOnDevice) {
            var lastReported = ""
            val reportStatus: (String) -> Unit = { statusMsg ->
                if (statusMsg != lastReported) {
                    lastReported = statusMsg
                    runCatching {
                        setForegroundAsync(
                            createForegroundInfo(
                                projectId = projectId,
                                movieTitle = project.title,
                                stage = statusMsg,
                                progress = 1,
                                useMediaProcessingType = false
                            )
                        )
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.updateProjectProgress(
                            projectId = projectId,
                            status = ProjectStatus.PROCESSING,
                            stage = statusMsg,
                            progress = 1,
                            etaMs = null,
                            error = null
                        )
                    }
                }
            }
            if (useLocalLlm) {
                activeGgufFile = onDeviceEngine.ensureReadyForLocalLlm(
                    preferredGgufFileName = studioPrefs.activeGgufModelFileName,
                    sttEngineMode = sttEngineMode,
                    skipSttDownload = hasSubtitleFile,
                    onStatus = reportStatus
                )
            } else {
                onDeviceEngine.ensureReady(
                    sttEngineMode = sttEngineMode,
                    skipSttDownload = hasSubtitleFile,
                    onStatus = reportStatus
                )
            }
        }

        val totalChunks = chunks.size.coerceAtLeast(1)
        val chunkWorkProgress = FloatArray(totalChunks)
        val completedChunkTimes = mutableListOf<Long>()
        val pendingChunks = mutableListOf<ChunkEntity>()

        for (chunk in chunks) {
            if (chunk.status == ChunkStatus.MIXED && !retryFailedOnly) {
                if (chunk.processingTimeMs > 0L) completedChunkTimes.add(chunk.processingTimeMs)
                chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)] = 1.0f
            } else {
                pendingChunks.add(chunk)
            }
        }

        val chunksDir = repository.chunksDir(projectId)
        val ttsDir = repository.ttsDir(projectId)
        val chunkAccumMs = mutableMapOf<Int, Long>().withDefault { 0L }

        // Execute in 4-chunk waves so heavy native models (Vosk STT, GGUF LLM) load ONCE per wave
        // instead of thrashing memory/disk between every single chunk.
        for (wave in pendingChunks.chunked(4)) {
            val waveStates = mutableMapOf<Int, ChunkEntity>()

            // Wave Phase 1: Audio Extraction + Vosk English Speech Recognition
            for (chunk in wave) {
                if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)
                val p1Start = System.currentTimeMillis()
                val origWav = File(chunksDir, "chunk_${chunk.chunkIndex}_orig.wav")
                val sttWav = File(chunksDir, "chunk_${chunk.chunkIndex}_stt.wav")
                var currentChunk = chunk

                if (!origWav.exists() || !sttWav.exists() || currentChunk.status == ChunkStatus.PENDING) {
                    updateStage(
                        projectId = projectId,
                        title = project.title,
                        stage = "${ProcessingStage.EXTRACTING} (${chunk.chunkIndex + 1}/$totalChunks)",
                        chunkIndex = chunk.chunkIndex,
                        subStepFraction = 0.10f,
                        chunkWorkProgress = chunkWorkProgress,
                        completedTimes = completedChunkTimes,
                        useMediaProcessingType = useOnDevice
                    )
                    AudioChunkExtractor.extractChunk(
                        context = applicationContext,
                        sourceUri = sourceUri,
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        origWavFile = origWav,
                        sttWavFile = sttWav,
                        enableVocalIsolationForStt = vocalIsolationEnabled
                    )
                    currentChunk = currentChunk.copy(
                        origWavPath = origWav.absolutePath,
                        sttWavPath = sttWav.absolutePath,
                        status = if (currentChunk.status == ChunkStatus.PENDING) ChunkStatus.EXTRACTED else currentChunk.status,
                        errorMessage = null
                    )
                    repository.updateChunk(currentChunk)
                } else {
                    chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)] =
                        maxOf(chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)], 0.10f)
                }

                if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)

                val existingSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                if (currentChunk.status == ChunkStatus.EXTRACTED || existingSegments.isEmpty()) {
                    updateStage(
                        projectId = projectId,
                        title = project.title,
                        stage = "${ProcessingStage.TRANSCRIBING} (${chunk.chunkIndex + 1}/$totalChunks)",
                        chunkIndex = chunk.chunkIndex,
                        subStepFraction = 0.25f,
                        chunkWorkProgress = chunkWorkProgress,
                        completedTimes = completedChunkTimes,
                        useMediaProcessingType = useOnDevice
                    )

                    val knownSpeakers = repository.getSpeakers(projectId)
                    val chunkDurationMs = (chunk.endMs - chunk.startMs).coerceAtLeast(1000L)
                    val sttResult = when {
                        hasSubtitleFile -> {
                            val chunkPhrases = SrtSubtitleParser.slicesForChunk(
                                cues = parsedSubtitleCues,
                                chunkStartMs = chunk.startMs,
                                chunkEndMs = chunk.endMs
                            )
                            onDeviceEngine.transcribeWithSubtitleCues(
                                sttWavFile = sttWav,
                                phrases = chunkPhrases,
                                chunkDurationMs = chunkDurationMs
                            )
                        }
                        useOnDevice -> onDeviceEngine.transcribeAudioChunk(
                            sttWavFile = sttWav,
                            chunkDurationMs = chunkDurationMs,
                            sttEngineMode = sttEngineMode
                        )
                        else -> callWithRateLimitRetry {
                            aiService.transcribeAudioChunk(
                                sttWavFile = sttWav,
                                chunkDurationMs = chunkDurationMs,
                                knownSpeakers = knownSpeakers
                            )
                        }
                    }

                    val existingCount = knownSpeakers.size
                    val preferredPreset = HindiVoicePresets.all.find { it.id == project.defaultVoicePreset }
                    val newSpeakers = sttResult.speakers.mapIndexed { idx, sp ->
                        val preset = if (
                            idx == 0 &&
                            existingCount == 0 &&
                            preferredPreset != null &&
                            sp.inferredGender.equals(preferredPreset.gender, ignoreCase = true)
                        ) {
                            preferredPreset
                        } else {
                            HindiVoicePresets.defaultForGender(sp.inferredGender, existingCount + idx)
                        }
                        SpeakerEntity(
                            projectId = projectId,
                            speakerLabel = sp.speakerLabel,
                            displayName = sp.speakerLabel.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            gender = sp.inferredGender,
                            voicePresetName = preset.id,
                            speedMultiplier = project.defaultSpeechSpeed,
                            defaultEmotion = project.defaultEmotionBias,
                            traits = sp.traits
                        )
                    }
                    repository.insertSpeakersIgnore(newSpeakers)

                    repository.deleteSegmentsForChunk(projectId, chunk.chunkIndex)
                    val entities = sttResult.segments.mapIndexed { orderIdx, seg ->
                        val isVocalMusic = seg.audioType == "SPEECH" &&
                            VocalSfxClassifier.isNonLexicalVocalSound(seg.englishText)
                        DialogueSegmentEntity(
                            projectId = projectId,
                            chunkIndex = chunk.chunkIndex,
                            orderIndex = orderIdx,
                            startMs = chunk.startMs + seg.startOffsetMs,
                            endMs = chunk.startMs + seg.endOffsetMs,
                            speakerLabel = if (isVocalMusic) "AMBIENT" else seg.speakerLabel,
                            englishText = seg.englishText,
                            emotion = if (isVocalMusic) "neutral" else seg.emotion,
                            isOverlapping = seg.isOverlapping,
                            audioType = if (isVocalMusic) "VOCAL_SFX" else seg.audioType,
                            speechSpeed = project.defaultSpeechSpeed,
                            status = SegmentStatus.TRANSCRIBED
                        )
                    }
                    repository.insertSegments(entities)
                    currentChunk = currentChunk.copy(status = ChunkStatus.TRANSCRIBED, errorMessage = null)
                    repository.updateChunk(currentChunk)
                } else {
                    chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)] =
                        maxOf(chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)], 0.25f)
                }

                chunkAccumMs[chunk.chunkIndex] =
                    chunkAccumMs.getValue(chunk.chunkIndex) + (System.currentTimeMillis() - p1Start).coerceAtLeast(0L)
                waveStates[chunk.chunkIndex] = currentChunk
            }

            // Wave Phase 2: English -> Hindi Translation (Local GGUF stays mapped across entire wave!)
            for (chunk in wave) {
                if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)
                val p2Start = System.currentTimeMillis()
                var currentChunk = waveStates[chunk.chunkIndex] ?: chunk
                val rawChunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                for (seg in rawChunkSegments) {
                    if (seg.audioType == "SPEECH" && VocalSfxClassifier.isNonLexicalVocalSound(seg.englishText)) {
                        repository.updateSegment(
                            seg.copy(
                                audioType = "VOCAL_SFX",
                                speakerLabel = "AMBIENT",
                                hindiText = "",
                                ttsAudioPath = null,
                                ttsDurationMs = 0L,
                                status = SegmentStatus.TRANSCRIBED,
                                errorMessage = null
                            )
                        )
                    }
                }
                val chunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                val untranslated = chunkSegments.filter {
                    it.audioType == "SPEECH" && it.englishText.isNotBlank() && it.hindiText.isBlank()
                }
                if (untranslated.isNotEmpty() || currentChunk.status == ChunkStatus.TRANSCRIBED) {
                    updateStage(
                        projectId = projectId,
                        title = project.title,
                        stage = "${ProcessingStage.TRANSLATING} (${chunk.chunkIndex + 1}/$totalChunks)",
                        chunkIndex = chunk.chunkIndex,
                        subStepFraction = 0.45f,
                        chunkWorkProgress = chunkWorkProgress,
                        completedTimes = completedChunkTimes,
                        useMediaProcessingType = useOnDevice
                    )
                    project = repository.getProject(projectId) ?: project
                    val speakers = repository.getSpeakers(projectId)
                    if (untranslated.isNotEmpty()) {
                        val untranslatedById = untranslated.associateBy { it.id }
                        val alreadySavedIds = mutableSetOf<Long>()
                        val translations = when {
                            useLocalLlm && activeGgufFile != null -> {
                                val totalToTranslate = untranslated.size.coerceAtLeast(1)
                                val modeTag = if (hybridTurbo) "Hybrid Turbo" else "Local GGUF"
                                updateStage(
                                    projectId = projectId,
                                    title = project.title,
                                    stage = "$modeTag Translating (0/$totalToTranslate • Chunk ${chunk.chunkIndex + 1}/$totalChunks)",
                                    chunkIndex = chunk.chunkIndex,
                                    subStepFraction = 0.34f,
                                    chunkWorkProgress = chunkWorkProgress,
                                    completedTimes = completedChunkTimes,
                                    useMediaProcessingType = true
                                )
                                onDeviceEngine.translateToHindiWithLocalLlm(
                                    segments = untranslated,
                                    speakers = speakers,
                                    dialogueStyle = project.dialogueStyle,
                                    modelFile = activeGgufFile,
                                    hybridTurbo = hybridTurbo
                                ) { trLine, idx, totalLines ->
                                    val origSeg = untranslatedById[trLine.segmentId]
                                    if (origSeg != null && trLine.hindiText.isNotBlank()) {
                                        repository.updateSegment(
                                            origSeg.copy(
                                                hindiText = trLine.hindiText,
                                                emotion = trLine.emotion.ifBlank { origSeg.emotion },
                                                status = SegmentStatus.TRANSLATED,
                                                errorMessage = null
                                            )
                                        )
                                        alreadySavedIds.add(origSeg.id)
                                    }
                                    val doneCount = (idx + 1).coerceAtMost(totalLines)
                                    val stepFrac = 0.34f + (0.20f * (doneCount.toFloat() / totalLines.coerceAtLeast(1)))
                                    updateStage(
                                        projectId = projectId,
                                        title = project.title,
                                        stage = "$modeTag Translating ($doneCount/$totalLines • Chunk ${chunk.chunkIndex + 1}/$totalChunks)",
                                        chunkIndex = chunk.chunkIndex,
                                        subStepFraction = stepFrac,
                                        chunkWorkProgress = chunkWorkProgress,
                                        completedTimes = completedChunkTimes,
                                        useMediaProcessingType = true
                                    )
                                    if (shouldStopOrPause(projectId)) {
                                        throw CancellationException("Paused during Local GGUF translation")
                                    }
                                }
                            }
                            useOnDevice -> onDeviceEngine.translateToHindi(
                                segments = untranslated,
                                dialogueStyle = project.dialogueStyle
                            )
                            else -> callWithRateLimitRetry {
                                aiService.translateToHindi(
                                    segments = untranslated,
                                    speakers = speakers,
                                    dialogueStyle = project.dialogueStyle,
                                    dictionaryConfig = SlangDictionaryManager.loadConfig(applicationContext)
                                )
                            }
                        }
                        val translatedById = translations.associateBy { it.segmentId }
                        for (seg in untranslated) {
                            if (seg.id in alreadySavedIds) continue
                            val tr = translatedById[seg.id]
                            if (tr != null && tr.hindiText.isNotBlank()) {
                                repository.updateSegment(
                                    seg.copy(
                                        hindiText = tr.hindiText,
                                        emotion = tr.emotion.ifBlank { seg.emotion },
                                        status = SegmentStatus.TRANSLATED,
                                        errorMessage = null
                                    )
                                )
                            } else {
                                repository.updateSegment(
                                    seg.copy(
                                        status = SegmentStatus.FAILED,
                                        errorMessage = "Hindi translation omitted for this segment."
                                    )
                                )
                            }
                        }
                    }
                    currentChunk = currentChunk.copy(status = ChunkStatus.TRANSLATED)
                    repository.updateChunk(currentChunk)
                } else {
                    chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)] =
                        maxOf(chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)], 0.54f)
                }

                chunkAccumMs[chunk.chunkIndex] =
                    chunkAccumMs.getValue(chunk.chunkIndex) + (System.currentTimeMillis() - p2Start).coerceAtLeast(0L)
                waveStates[chunk.chunkIndex] = currentChunk
            }

            // Free GGUF weights after the wave's translation phase so TTS + Mixing + next wave Vosk have full RAM
            if (useLocalLlm) {
                onDeviceEngine.releaseLocalLlm()
            }

            // Wave Phase 3: Hindi Voice Synthesis + O(1) BGM Separation + Audio Mixing
            for (chunk in wave) {
                if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)
                val p3Start = System.currentTimeMillis()
                val origWav = File(chunksDir, "chunk_${chunk.chunkIndex}_orig.wav")
                val bgmWav = File(chunksDir, "chunk_${chunk.chunkIndex}_bgm.wav")
                val mixedWav = File(chunksDir, "chunk_${chunk.chunkIndex}_mixed.wav")
                var currentChunk = waveStates[chunk.chunkIndex] ?: chunk

                var chunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                val speakersMap = repository.getSpeakers(projectId).associateBy { it.speakerLabel }
                val speechSegments = chunkSegments.filter {
                    it.audioType == "SPEECH" && it.hindiText.isNotBlank()
                }
                val segmentsToSynthesize = speechSegments.filter { seg ->
                    val ttsExists = seg.ttsAudioPath?.let { File(it).exists() } == true
                    if (retryFailedOnly) {
                        seg.status == SegmentStatus.FAILED || !ttsExists
                    } else {
                        seg.status != SegmentStatus.TTS_DONE || !ttsExists
                    }
                }

                if (segmentsToSynthesize.isNotEmpty()) {
                    for ((sIdx, seg) in segmentsToSynthesize.withIndex()) {
                        if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)

                        val frac = 0.55f + (0.27f * (sIdx.toFloat() / segmentsToSynthesize.size.coerceAtLeast(1)))
                        updateStage(
                            projectId = projectId,
                            title = project.title,
                            stage = "${ProcessingStage.GENERATING_TTS} (${sIdx + 1}/${segmentsToSynthesize.size} in chunk ${chunk.chunkIndex + 1})",
                            chunkIndex = chunk.chunkIndex,
                            subStepFraction = frac,
                            chunkWorkProgress = chunkWorkProgress,
                            completedTimes = completedChunkTimes,
                            useMediaProcessingType = useOnDevice
                        )

                        val speaker = speakersMap[seg.speakerLabel]
                        val voicePreset = speaker?.voicePresetName ?: project.defaultVoicePreset.ifBlank { "Kore" }
                        val speed = (speaker?.speedMultiplier ?: seg.speechSpeed).coerceIn(0.75f, 1.35f)
                        val ttsOut = File(ttsDir, "seg_${seg.id}_tts.wav")

                        try {
                            val ttsDurationMs = if (useOnDevice) {
                                onDeviceEngine.generateHindiTts(
                                    hindiText = seg.hindiText,
                                    voicePresetName = voicePreset,
                                    emotion = seg.emotion,
                                    speedMultiplier = speed,
                                    targetSlotDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(400L),
                                    outWavFile = ttsOut,
                                    tempCacheDir = applicationContext.cacheDir
                                )
                            } else {
                                callWithRateLimitRetry {
                                    aiService.generateHindiTts(
                                        hindiText = seg.hindiText,
                                        voicePresetName = voicePreset,
                                        emotion = seg.emotion,
                                        speedMultiplier = speed,
                                        targetSlotDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(400L),
                                        outWavFile = ttsOut,
                                        tempCacheDir = applicationContext.cacheDir
                                    )
                                }
                            }
                            repository.updateSegment(
                                seg.copy(
                                    ttsAudioPath = ttsOut.absolutePath,
                                    ttsDurationMs = ttsDurationMs,
                                    status = SegmentStatus.TTS_DONE,
                                    errorMessage = null
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Segment ${seg.id} TTS failed", e)
                            repository.updateSegment(
                                seg.copy(
                                    status = SegmentStatus.FAILED,
                                    errorMessage = e.localizedMessage ?: "TTS generation failed"
                                )
                            )
                            if (e is GeminiApiException && (e.isAppCheckOrAuth || e.isRateLimitOrQuota)) {
                                repository.updateChunk(
                                    currentChunk.copy(status = ChunkStatus.FAILED, errorMessage = e.message)
                                )
                                throw e
                            }
                        }
                    }
                }

                if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)

                project = repository.getProject(projectId) ?: project
                updateStage(
                    projectId = projectId,
                    title = project.title,
                    stage = "${ProcessingStage.SEPARATING_BGM} (${chunk.chunkIndex + 1}/$totalChunks)",
                    chunkIndex = chunk.chunkIndex,
                    subStepFraction = 0.88f,
                    chunkWorkProgress = chunkWorkProgress,
                    completedTimes = completedChunkTimes,
                    useMediaProcessingType = true
                )

                chunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                AudioSeparator.separateBgmAndSfx(
                    origWavFile = origWav,
                    bgmWavFile = bgmWav,
                    segments = chunkSegments,
                    chunkStartMs = chunk.startMs,
                    sourceChannels = project.audioChannels,
                    preserveBgm = project.preserveBgm,
                    removeEnglishDialogue = project.removeEnglishDialogue
                )

                updateStage(
                    projectId = projectId,
                    title = project.title,
                    stage = "${ProcessingStage.MIXING} (${chunk.chunkIndex + 1}/$totalChunks)",
                    chunkIndex = chunk.chunkIndex,
                    subStepFraction = 0.95f,
                    chunkWorkProgress = chunkWorkProgress,
                    completedTimes = completedChunkTimes,
                    useMediaProcessingType = true
                )

                val positionedTts = chunkSegments
                    .filter { it.status == SegmentStatus.TTS_DONE && it.ttsAudioPath != null }
                    .map { AudioMixer.PositionedTts(it, File(it.ttsAudioPath!!)) }

                AudioMixer.mixChunk(
                    origWavFile = origWav,
                    bgmWavFile = bgmWav,
                    ttsSegments = positionedTts,
                    chunkStartMs = chunk.startMs,
                    hindiVolume = project.hindiVolume,
                    bgmVolume = project.bgmVolume,
                    originalVolume = project.originalVolume,
                    outputMixedWav = mixedWav
                )

                val elapsed = (chunkAccumMs.getValue(chunk.chunkIndex) + (System.currentTimeMillis() - p3Start))
                    .coerceAtLeast(100L)
                completedChunkTimes.add(elapsed)
                chunkWorkProgress[chunk.chunkIndex.coerceIn(0, totalChunks - 1)] = 1.0f

                val hasFailedSegs = chunkSegments.any { it.status == SegmentStatus.FAILED }
                currentChunk = currentChunk.copy(
                    bgmWavPath = bgmWav.absolutePath,
                    mixedWavPath = mixedWav.absolutePath,
                    status = if (hasFailedSegs) ChunkStatus.FAILED else ChunkStatus.MIXED,
                    processingTimeMs = elapsed,
                    errorMessage = if (hasFailedSegs) "Some dialogue segments failed TTS" else null
                )
                repository.updateChunk(currentChunk)
            }
        }

        val failedCount = repository.getFailedSegments(projectId).size
        val finalError = if (failedCount > 0) {
            "$failedCount segment(s) failed voice synthesis. Tap Retry Failed Segments or edit them in Review."
        } else null

        repository.updateProjectProgress(
            projectId = projectId,
            status = ProjectStatus.READY_FOR_REVIEW,
            stage = ProcessingStage.READY,
            progress = 100,
            etaMs = 0L,
            error = finalError
        )
        return Result.success()
    }

    private suspend fun runRemixOnly(projectId: Long): Result {
        val project = repository.getProject(projectId) ?: return Result.failure()
        val chunks = repository.getChunks(projectId)
        val total = chunks.size.coerceAtLeast(1)

        for ((idx, chunk) in chunks.withIndex()) {
            if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)
            val origPath = chunk.origWavPath ?: continue
            val origWav = File(origPath)
            if (!origWav.exists()) continue

            val bgmWav = File(repository.chunksDir(projectId), "chunk_${chunk.chunkIndex}_bgm.wav")
            val mixedWav = File(repository.chunksDir(projectId), "chunk_${chunk.chunkIndex}_mixed.wav")
            val segs = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)

            val pct = ((idx * 100) / total).coerceIn(0, 99)
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.PROCESSING,
                stage = "Updating Audio Mix (${idx + 1}/$total)",
                progress = pct,
                etaMs = null,
                error = null
            )
            safeSetForeground(
                createForegroundInfo(
                    projectId = projectId,
                    movieTitle = project.title,
                    stage = "Updating Audio Mix (${idx + 1}/$total)",
                    progress = pct,
                    useMediaProcessingType = true
                )
            )

            AudioSeparator.separateBgmAndSfx(
                origWavFile = origWav,
                bgmWavFile = bgmWav,
                segments = segs,
                chunkStartMs = chunk.startMs,
                sourceChannels = project.audioChannels,
                preserveBgm = project.preserveBgm,
                removeEnglishDialogue = project.removeEnglishDialogue
            )

            val positioned = segs
                .filter { it.status == SegmentStatus.TTS_DONE && it.ttsAudioPath != null }
                .map { AudioMixer.PositionedTts(it, File(it.ttsAudioPath!!)) }

            AudioMixer.mixChunk(
                origWavFile = origWav,
                bgmWavFile = bgmWav,
                ttsSegments = positioned,
                chunkStartMs = chunk.startMs,
                hindiVolume = project.hindiVolume,
                bgmVolume = project.bgmVolume,
                originalVolume = project.originalVolume,
                outputMixedWav = mixedWav
            )

            repository.updateChunk(
                chunk.copy(
                    bgmWavPath = bgmWav.absolutePath,
                    mixedWavPath = mixedWav.absolutePath,
                    status = ChunkStatus.MIXED
                )
            )
        }

        repository.updateProjectProgress(
            projectId = projectId,
            status = ProjectStatus.READY_FOR_REVIEW,
            stage = ProcessingStage.READY,
            progress = 100,
            etaMs = 0L,
            error = null
        )
        return Result.success()
    }

    private suspend fun <T> callWithRateLimitRetry(block: suspend () -> T): T {
        var attempt = 0
        var backoffMs = 2500L
        while (true) {
            try {
                return block()
            } catch (e: GeminiApiException) {
                attempt++
                if (e.isRateLimitOrQuota && attempt < 3) {
                    Log.w(TAG, "Rate limit hit, backing off ${backoffMs}ms (attempt $attempt)")
                    delay(backoffMs)
                    backoffMs *= 2
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun shouldStopOrPause(projectId: Long): Boolean {
        if (isStopped) return true
        val p = repository.getProject(projectId) ?: return true
        return p.status == ProjectStatus.PAUSED
    }

    private suspend fun markPausedAndReturn(projectId: Long): Result {
        val p = repository.getProject(projectId)
        if (p != null && p.status != ProjectStatus.READY_FOR_REVIEW && p.status != ProjectStatus.COMPLETED) {
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.PAUSED,
                stage = p.currentStage,
                progress = p.progressPercent,
                etaMs = null,
                error = null
            )
        }
        return Result.success()
    }

    private suspend fun updateStage(
        projectId: Long,
        title: String,
        stage: String,
        chunkIndex: Int,
        subStepFraction: Float,
        chunkWorkProgress: FloatArray,
        completedTimes: List<Long>,
        useMediaProcessingType: Boolean
    ) {
        val idx = chunkIndex.coerceIn(0, chunkWorkProgress.lastIndex.coerceAtLeast(0))
        val clampedStep = subStepFraction.coerceIn(0f, 1f)
        if (clampedStep > chunkWorkProgress[idx]) {
            chunkWorkProgress[idx] = clampedStep
        }
        val totalChunks = chunkWorkProgress.size.coerceAtLeast(1)
        val doneSum = chunkWorkProgress.sum()
        val overallProgress = ((doneSum / totalChunks.toFloat()) * 100f)
            .toInt().coerceIn(1, 99)
        // Only compute ETA when >= 1 full chunk has completed with real measured timing
        val etaMs = if (completedTimes.isNotEmpty()) {
            val avgPerChunk = completedTimes.average().toLong()
            val remainingUnits = (totalChunks.toFloat() - doneSum).coerceAtLeast(0.05f)
            (avgPerChunk * remainingUnits).toLong().coerceAtLeast(1000L)
        } else null

        repository.updateProjectProgress(
            projectId = projectId,
            status = ProjectStatus.PROCESSING,
            stage = stage,
            progress = overallProgress,
            etaMs = etaMs,
            error = null
        )
        safeSetForeground(
            createForegroundInfo(
                projectId = projectId,
                movieTitle = title,
                stage = stage,
                progress = overallProgress,
                useMediaProcessingType = useMediaProcessingType
            )
        )
    }

    private suspend fun safeSetForeground(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (e: IllegalStateException) {
            // Android 12+ (API 31+) ForegroundServiceStartNotAllowedException or Android 15+ FGS timeout
            Log.w(TAG, "Unable to promote worker to foreground service; continuing in background", e)
        }
    }

    private fun createForegroundInfo(
        projectId: Long,
        movieTitle: String,
        stage: String,
        progress: Int,
        useMediaProcessingType: Boolean
    ): ForegroundInfo {
        ensureNotificationChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Dubbing: $movieTitle")
            .setContentText("$stage ($progress%)")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                applicationContext.getString(R.string.cancel_dubbing),
                cancelIntent
            )
            .build()

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                val fgsType = if (useMediaProcessingType) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                ForegroundInfo(
                    NOTIFICATION_BASE_ID + (projectId % 1000).toInt(),
                    notification,
                    fgsType
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                ForegroundInfo(
                    NOTIFICATION_BASE_ID + (projectId % 1000).toInt(),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                ForegroundInfo(
                    NOTIFICATION_BASE_ID + (projectId % 1000).toInt(),
                    notification
                )
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = applicationContext.getString(R.string.notification_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ACTION_MODE = "action_mode"
        const val MODE_FULL_PIPELINE = "full_pipeline"
        const val MODE_RETRY_FAILED = "retry_failed"
        const val MODE_REMIX_ONLY = "remix_only"
    }
}
