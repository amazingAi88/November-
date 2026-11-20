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
import com.example.data.local.ChunkEntity
import com.example.data.local.ChunkStatus
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DubbingRepository
import com.example.data.local.EngineMode
import com.example.data.local.PreferencesRepository
import com.example.data.local.ProcessingStage
import com.example.data.local.ProjectStatus
import com.example.data.local.SegmentStatus
import com.example.data.local.SpeakerEntity
import com.example.media.AudioChunkExtractor
import com.example.media.AudioMixer
import com.example.media.AudioSeparator
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
                val preferredGguf = PreferencesRepository(applicationContext).preferencesFlow.first().activeGgufModelFileName
                activeGgufFile = onDeviceEngine.ensureReadyForLocalLlm(preferredGguf, reportStatus)
            } else {
                onDeviceEngine.ensureReady(reportStatus)
            }
        }

        val totalChunks = chunks.size.coerceAtLeast(1)
        val completedChunkTimes = mutableListOf<Long>()

        for (chunk in chunks) {
            if (shouldStopOrPause(projectId)) {
                return markPausedAndReturn(projectId)
            }

            if (chunk.status == ChunkStatus.MIXED && !retryFailedOnly) {
                if (chunk.processingTimeMs > 0L) completedChunkTimes.add(chunk.processingTimeMs)
                continue
            }

            val chunkStartWall = System.currentTimeMillis()
            val chunksDir = repository.chunksDir(projectId)
            val ttsDir = repository.ttsDir(projectId)
            val origWav = File(chunksDir, "chunk_${chunk.chunkIndex}_orig.wav")
            val sttWav = File(chunksDir, "chunk_${chunk.chunkIndex}_stt.wav")
            val bgmWav = File(chunksDir, "chunk_${chunk.chunkIndex}_bgm.wav")
            val mixedWav = File(chunksDir, "chunk_${chunk.chunkIndex}_mixed.wav")

            var currentChunk = chunk

            // Stage A: Audio Extraction (if needed)
            if (!origWav.exists() || !sttWav.exists() || currentChunk.status == ChunkStatus.PENDING) {
                updateStage(
                    projectId = projectId,
                    title = project.title,
                    stage = "${ProcessingStage.EXTRACTING} (${chunk.chunkIndex + 1}/$totalChunks)",
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = totalChunks,
                    subStepFraction = 0.1f,
                    completedTimes = completedChunkTimes,
                    useMediaProcessingType = useOnDevice
                )
                AudioChunkExtractor.extractChunk(
                    context = applicationContext,
                    sourceUri = sourceUri,
                    startMs = chunk.startMs,
                    endMs = chunk.endMs,
                    origWavFile = origWav,
                    sttWavFile = sttWav
                )
                currentChunk = currentChunk.copy(
                    origWavPath = origWav.absolutePath,
                    sttWavPath = sttWav.absolutePath,
                    status = if (currentChunk.status == ChunkStatus.PENDING) ChunkStatus.EXTRACTED else currentChunk.status,
                    errorMessage = null
                )
                repository.updateChunk(currentChunk)
            }

            if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)

            // Stage B: English Speech Recognition + Speaker Diarization (if not already transcribed)
            var chunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
            if (currentChunk.status == ChunkStatus.EXTRACTED || chunkSegments.isEmpty()) {
                updateStage(
                    projectId = projectId,
                    title = project.title,
                    stage = "${ProcessingStage.TRANSCRIBING} (${chunk.chunkIndex + 1}/$totalChunks)",
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = totalChunks,
                    subStepFraction = 0.25f,
                    completedTimes = completedChunkTimes,
                    useMediaProcessingType = useOnDevice
                )

                val knownSpeakers = repository.getSpeakers(projectId)
                val sttResult = if (useOnDevice) {
                    onDeviceEngine.transcribeAudioChunk(
                        sttWavFile = sttWav,
                        chunkDurationMs = (chunk.endMs - chunk.startMs).coerceAtLeast(1000L)
                    )
                } else {
                    callWithRateLimitRetry {
                        aiService.transcribeAudioChunk(
                            sttWavFile = sttWav,
                            chunkDurationMs = (chunk.endMs - chunk.startMs).coerceAtLeast(1000L),
                            knownSpeakers = knownSpeakers
                        )
                    }
                }

                // Register newly discovered speakers in Room
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

                // Store segments with exact movie-level timestamps
                repository.deleteSegmentsForChunk(projectId, chunk.chunkIndex)
                val entities = sttResult.segments.mapIndexed { orderIdx, seg ->
                    DialogueSegmentEntity(
                        projectId = projectId,
                        chunkIndex = chunk.chunkIndex,
                        orderIndex = orderIdx,
                        startMs = chunk.startMs + seg.startOffsetMs,
                        endMs = chunk.startMs + seg.endOffsetMs,
                        speakerLabel = seg.speakerLabel,
                        englishText = seg.englishText,
                        emotion = seg.emotion,
                        isOverlapping = seg.isOverlapping,
                        audioType = seg.audioType,
                        speechSpeed = project.defaultSpeechSpeed,
                        status = SegmentStatus.TRANSCRIBED
                    )
                }
                repository.insertSegments(entities)
                chunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)

                currentChunk = currentChunk.copy(status = ChunkStatus.TRANSCRIBED, errorMessage = null)
                repository.updateChunk(currentChunk)
            }

            if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)

            // Stage C: English -> Hindi Translation (only segments without hindiText)
            val untranslated = chunkSegments.filter {
                it.audioType == "SPEECH" && it.englishText.isNotBlank() && it.hindiText.isBlank()
            }
            if (untranslated.isNotEmpty() || currentChunk.status == ChunkStatus.TRANSCRIBED) {
                updateStage(
                    projectId = projectId,
                    title = project.title,
                    stage = "${ProcessingStage.TRANSLATING} (${chunk.chunkIndex + 1}/$totalChunks)",
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = totalChunks,
                    subStepFraction = 0.45f,
                    completedTimes = completedChunkTimes,
                    useMediaProcessingType = useOnDevice
                )
                project = repository.getProject(projectId) ?: project
                val speakers = repository.getSpeakers(projectId)
                if (untranslated.isNotEmpty()) {
                    val translations = when {
                        useLocalLlm && activeGgufFile != null -> {
                            updateStage(
                                projectId = projectId,
                                title = project.title,
                                stage = "Local GGUF LLM Translating (${chunk.chunkIndex + 1}/$totalChunks)",
                                chunkIndex = chunk.chunkIndex,
                                totalChunks = totalChunks,
                                subStepFraction = 0.48f,
                                completedTimes = completedChunkTimes,
                                useMediaProcessingType = true
                            )
                            onDeviceEngine.translateToHindiWithLocalLlm(
                                segments = untranslated,
                                speakers = speakers,
                                dialogueStyle = project.dialogueStyle,
                                modelFile = activeGgufFile
                            )
                        }
                        useOnDevice -> onDeviceEngine.translateToHindi(untranslated)
                        else -> callWithRateLimitRetry {
                            aiService.translateToHindi(
                                segments = untranslated,
                                speakers = speakers,
                                dialogueStyle = project.dialogueStyle
                            )
                        }
                    }
                    val translatedById = translations.associateBy { it.segmentId }
                    for (seg in untranslated) {
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
                    chunkSegments = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                }
                currentChunk = currentChunk.copy(status = ChunkStatus.TRANSLATED)
                repository.updateChunk(currentChunk)
            }

            if (shouldStopOrPause(projectId)) return markPausedAndReturn(projectId)

            // Stage D: Hindi AI Voice Generation (segment by segment, skipping already-completed!)
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

                    val frac = 0.50f + (0.32f * (sIdx.toFloat() / segmentsToSynthesize.size.coerceAtLeast(1)))
                    updateStage(
                        projectId = projectId,
                        title = project.title,
                        stage = "${ProcessingStage.GENERATING_TTS} (${sIdx + 1}/${segmentsToSynthesize.size} in chunk ${chunk.chunkIndex + 1})",
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = totalChunks,
                        subStepFraction = frac,
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
                        // If App Check or fatal quota hit, abort cleanly so user sees the real error immediately
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

            // Stage E: Background Music & SFX Preservation + Mixing
            project = repository.getProject(projectId) ?: project
            updateStage(
                projectId = projectId,
                title = project.title,
                stage = "${ProcessingStage.SEPARATING_BGM} (${chunk.chunkIndex + 1}/$totalChunks)",
                chunkIndex = chunk.chunkIndex,
                totalChunks = totalChunks,
                subStepFraction = 0.86f,
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
                totalChunks = totalChunks,
                subStepFraction = 0.94f,
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

            val elapsed = (System.currentTimeMillis() - chunkStartWall).coerceAtLeast(100L)
            completedChunkTimes.add(elapsed)

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
        totalChunks: Int,
        subStepFraction: Float,
        completedTimes: List<Long>,
        useMediaProcessingType: Boolean
    ) {
        val overallProgress = (((chunkIndex + subStepFraction) / totalChunks.toFloat()) * 100f)
            .toInt().coerceIn(1, 99)
        // Only compute ETA when >= 1 full chunk has completed with real measured timing
        val etaMs = if (completedTimes.isNotEmpty()) {
            val avgPerChunk = completedTimes.average().toLong()
            val remainingChunks = (totalChunks - chunkIndex).coerceAtLeast(1)
            (avgPerChunk * remainingChunks * (1f - subStepFraction)).toLong().coerceAtLeast(1000L)
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
