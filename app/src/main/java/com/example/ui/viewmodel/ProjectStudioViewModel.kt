package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiDubbingService
import com.example.ai.HindiVoicePresets
import com.example.ai.LocalLlmModelManager
import com.example.ai.OnDeviceDubbingEngine
import com.example.ai.OnDeviceModelManager
import com.example.data.local.ChunkEntity
import com.example.data.local.ChunkStatus
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DubbingRepository
import com.example.data.local.DubbingSpeedMode
import com.example.data.local.EngineMode
import com.example.data.local.MovieProjectEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.ProcessingStage
import com.example.data.local.ProjectStatus
import com.example.data.local.SegmentStatus
import com.example.data.local.SpeakerEntity
import com.example.media.AudioChunkExtractor
import com.example.media.AudioMixer
import com.example.media.AudioPlaybackState
import com.example.media.AudioSeparator
import com.example.media.MediaAccelSettings
import com.example.media.PcmPreviewPlayer
import com.example.media.VideoExporter
import com.example.worker.DubbingWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "ProjectStudioViewModel"

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Exporting(val progress: Int, val stage: String) : ExportUiState
    data class Success(val file: File, val fileSizeBytes: Long) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

sealed interface PreviewPrepState {
    data object Idle : PreviewPrepState
    data object Preparing : PreviewPrepState
    data class Error(val message: String) : PreviewPrepState
}

sealed interface VoicePreviewState {
    data object Idle : VoicePreviewState
    data class Loading(val presetId: String) : VoicePreviewState
    data class Error(val presetId: String, val message: String) : VoicePreviewState
}

class ProjectStudioViewModel(
    application: Application,
    val projectId: Long
) : AndroidViewModel(application) {

    private val repository = DubbingRepository(application)
    private val preferencesRepository = PreferencesRepository(application)
    private val scheduler = DubbingWorkScheduler(application)
    private val aiService = GeminiDubbingService()
    private val onDeviceEngine = OnDeviceDubbingEngine(application)
    private val previewPlayer = PcmPreviewPlayer(viewModelScope)

    val project: StateFlow<MovieProjectEntity?> = repository.observeProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chunks: StateFlow<List<ChunkEntity>> = repository.observeChunks(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val speakers: StateFlow<List<SpeakerEntity>> = repository.observeSpeakers(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val segments: StateFlow<List<DialogueSegmentEntity>> = repository.observeSegments(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaAccelSettings: StateFlow<MediaAccelSettings> = preferencesRepository.preferencesFlow
        .map { MediaAccelSettings.fromPreferences(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MediaAccelSettings())

    val dubbingSpeedMode: StateFlow<String> = preferencesRepository.preferencesFlow
        .map { it.dubbingSpeedMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DubbingSpeedMode.HYBRID_TURBO)

    val playbackState: StateFlow<AudioPlaybackState> = previewPlayer.state

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _previewPrepState = MutableStateFlow<PreviewPrepState>(PreviewPrepState.Idle)
    val previewPrepState: StateFlow<PreviewPrepState> = _previewPrepState.asStateFlow()

    private val _voicePreviewState = MutableStateFlow<VoicePreviewState>(VoicePreviewState.Idle)
    val voicePreviewState: StateFlow<VoicePreviewState> = _voicePreviewState.asStateFlow()

    private val _regeneratingSegmentId = MutableStateFlow<Long?>(null)
    val regeneratingSegmentId: StateFlow<Long?> = _regeneratingSegmentId.asStateFlow()

    private val _activeGgufModelName = MutableStateFlow<String?>(null)
    val activeGgufModelName: StateFlow<String?> = _activeGgufModelName.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _tempBytes = MutableStateFlow(0L)
    val tempBytes: StateFlow<Long> = _tempBytes.asStateFlow()

    init {
        refreshTempSize()
        refreshActiveGgufModel()
        viewModelScope.launch(Dispatchers.IO) {
            onDeviceEngine.warmUpTts()
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun refreshTempSize() {
        viewModelScope.launch {
            _tempBytes.value = repository.getTempWorkspaceSizeBytes(projectId)
        }
    }

    fun refreshActiveGgufModel() {
        viewModelScope.launch {
            val preferred = preferencesRepository.preferencesFlow.first().activeGgufModelFileName
            val resolved = withContext(Dispatchers.IO) {
                LocalLlmModelManager.resolveActiveModelFile(getApplication(), preferred)?.name
            }
            _activeGgufModelName.value = resolved
        }
    }

    // --- Dubbing Settings ---
    fun updateDubbingSettings(
        engineMode: String? = null,
        dialogueStyle: String? = null,
        defaultVoiceGender: String? = null,
        defaultVoicePreset: String? = null,
        defaultSpeechSpeed: Float? = null,
        defaultEmotionBias: String? = null
    ) {
        viewModelScope.launch {
            val current = repository.getProject(projectId) ?: return@launch
            val resolvedGender = defaultVoiceGender ?: current.defaultVoiceGender
            val resolvedPreset = defaultVoicePreset ?: if (defaultVoiceGender != null &&
                HindiVoicePresets.all.find { it.id == current.defaultVoicePreset }?.gender != resolvedGender
            ) {
                HindiVoicePresets.defaultForGender(resolvedGender).id
            } else {
                current.defaultVoicePreset
            }
            repository.updateProject(
                current.copy(
                    engineMode = engineMode ?: current.engineMode,
                    dialogueStyle = dialogueStyle ?: current.dialogueStyle,
                    defaultVoiceGender = resolvedGender,
                    defaultVoicePreset = resolvedPreset,
                    defaultSpeechSpeed = (defaultSpeechSpeed ?: current.defaultSpeechSpeed).coerceIn(0.75f, 1.35f),
                    defaultEmotionBias = defaultEmotionBias ?: current.defaultEmotionBias
                )
            )
        }
    }

    fun updateDubbingSpeedMode(mode: String) {
        viewModelScope.launch {
            preferencesRepository.setDubbingSpeedMode(mode)
        }
    }

    fun applyVoicePresetToProject(presetId: String) {
        viewModelScope.launch {
            val preset = HindiVoicePresets.all.find { it.id == presetId } ?: return@launch
            val current = repository.getProject(projectId) ?: return@launch
            repository.updateProject(
                current.copy(
                    defaultVoiceGender = preset.gender,
                    defaultVoicePreset = preset.id
                )
            )
            // Also update matching unassigned/primary speakers if present so UI stays consistent
            val existingSpeakers = repository.getSpeakers(projectId)
            val primarySpeaker = existingSpeakers.firstOrNull { it.speakerLabel == "SPEAKER_1" }
                ?: existingSpeakers.firstOrNull { it.gender.equals(preset.gender, ignoreCase = true) }
            if (primarySpeaker != null) {
                repository.updateSpeaker(
                    primarySpeaker.copy(
                        gender = preset.gender,
                        voicePresetName = preset.id
                    )
                )
            }
            _statusBanner.value = "Applied ${preset.label} as default voice for this movie project."
        }
    }

    fun previewHindiVoicePreset(
        presetId: String,
        sampleHindiText: String? = null,
        speedOverride: Float? = null,
        tagPrefix: String = "voice_preview"
    ) {
        val tag = "${tagPrefix}_$presetId"
        if (playbackState.value.activeTag == tag && playbackState.value.isPlaying) {
            stopVoicePreview()
            return
        }
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            val preset = HindiVoicePresets.all.find { it.id == presetId } ?: return@launch
            stopVoicePreview()
            _voicePreviewState.value = VoicePreviewState.Loading(presetId)
            try {
                val speed = (speedOverride ?: proj.defaultSpeechSpeed).coerceIn(0.75f, 1.35f)
                val emotion = proj.defaultEmotionBias
                val line = sampleHindiText?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultSampleLineForPreset(preset.id)
                val textHash = line.hashCode().toUInt().toString(16)
                val speedKey = (speed * 100).toInt()
                val cacheDir = getApplication<Application>().cacheDir
                val previewWav = File(
                    cacheDir,
                    "vp_${proj.engineMode}_${preset.id}_${speedKey}_${emotion}_$textHash.wav"
                )

                // Fast path 1: Cached WAV plays in <10ms
                if (previewWav.exists() && previewWav.length() > 44L) {
                    _voicePreviewState.value = VoicePreviewState.Idle
                    previewPlayer.playWav(previewWav, tag)
                    return@launch
                }

                // Fast path 2: Cloud mode gets a strict 950ms budget before instant local voice playback
                if (proj.engineMode == EngineMode.GEMINI_CLOUD) {
                    val cloudOk = withTimeoutOrNull(950L) {
                        runCatching {
                            aiService.generateHindiTts(
                                hindiText = line,
                                voicePresetName = preset.id,
                                emotion = emotion,
                                speedMultiplier = speed,
                                targetSlotDurationMs = 5_000L,
                                outWavFile = previewWav,
                                tempCacheDir = cacheDir
                            )
                            true
                        }.getOrDefault(false)
                    } == true
                    if (cloudOk && previewWav.exists() && previewWav.length() > 44L) {
                        _voicePreviewState.value = VoicePreviewState.Idle
                        previewPlayer.playWav(previewWav, tag)
                        return@launch
                    }
                }

                // Fast path 3: Direct streaming Android TTS (audible in ~100-250ms, <= 1s guarantee)
                onDeviceEngine.speakPreviewInstant(
                    hindiText = line,
                    voicePresetName = preset.id,
                    emotion = emotion,
                    speedMultiplier = speed,
                    onStarted = { estDurationMs ->
                        _voicePreviewState.value = VoicePreviewState.Idle
                        previewPlayer.startExternalPlayback(
                            tag = tag,
                            estimatedDurationMs = estDurationMs,
                            onStop = { onDeviceEngine.stopPreviewSpeech() }
                        )
                    },
                    onCompleted = {
                        _voicePreviewState.value = VoicePreviewState.Idle
                        previewPlayer.finishExternalPlayback(tag)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Voice model preview failed for $presetId", e)
                _voicePreviewState.value = VoicePreviewState.Error(
                    presetId = presetId,
                    message = e.localizedMessage ?: "Unable to preview ${preset.label}"
                )
            }
        }
    }

    fun stopVoicePreview() {
        previewPlayer.stop()
        onDeviceEngine.stopPreviewSpeech()
        _voicePreviewState.value = VoicePreviewState.Idle
    }

    private fun defaultSampleLineForPreset(presetId: String): String = when (presetId) {
        "Charon" -> "वक़्त कम है, जल्दी निकलो!"
        "Fenrir" -> "रास्ते से हटो, वरना बुरा होगा!"
        "Orus" -> "चिंता मत करो, सब ठीक होगा।"
        "Puck" -> "अरे वाह! कमाल कर दिया!"
        "Kore" -> "मुझे आज हर सच जानना है।"
        "Aoede" -> "जब तक हम साथ हैं, डरना मत।"
        "Leda" -> "इस ख़ामोशी में एक राज़ है।"
        else -> "नमस्ते, यह हिंदी डबिंग आवाज़ है।"
    }

    fun updateSpeaker(
        speaker: SpeakerEntity,
        gender: String = speaker.gender,
        voicePresetName: String = speaker.voicePresetName,
        speedMultiplier: Float = speaker.speedMultiplier,
        defaultEmotion: String = speaker.defaultEmotion
    ) {
        viewModelScope.launch {
            repository.updateSpeaker(
                speaker.copy(
                    gender = gender,
                    voicePresetName = voicePresetName,
                    speedMultiplier = speedMultiplier.coerceIn(0.75f, 1.35f),
                    defaultEmotion = defaultEmotion
                )
            )
        }
    }

    fun addManualSpeakerPreset(gender: String) {
        viewModelScope.launch {
            val existing = repository.getSpeakers(projectId)
            val nextIdx = existing.size + 1
            val proj = repository.getProject(projectId)
            val preset = if (existing.isEmpty() && proj != null) {
                HindiVoicePresets.all.find { it.id == proj.defaultVoicePreset }
                    ?: HindiVoicePresets.defaultForGender(gender, 0)
            } else {
                HindiVoicePresets.defaultForGender(gender, nextIdx - 1)
            }
            repository.insertSpeakersIgnore(
                listOf(
                    SpeakerEntity(
                        projectId = projectId,
                        speakerLabel = "SPEAKER_$nextIdx",
                        displayName = "Speaker $nextIdx",
                        gender = preset.gender,
                        voicePresetName = preset.id,
                        speedMultiplier = proj?.defaultSpeechSpeed ?: 1.0f,
                        defaultEmotion = proj?.defaultEmotionBias ?: "natural"
                    )
                )
            )
        }
    }

    // --- Audio & BGM Settings ---
    fun updateAudioSettings(
        preserveBgm: Boolean? = null,
        removeEnglishDialogue: Boolean? = null,
        hindiVolume: Float? = null,
        bgmVolume: Float? = null,
        originalVolume: Float? = null,
        chunkDurationSec: Int? = null
    ) {
        viewModelScope.launch {
            val current = repository.getProject(projectId) ?: return@launch
            repository.updateProject(
                current.copy(
                    preserveBgm = preserveBgm ?: current.preserveBgm,
                    removeEnglishDialogue = removeEnglishDialogue ?: current.removeEnglishDialogue,
                    hindiVolume = (hindiVolume ?: current.hindiVolume).coerceIn(0f, 2f),
                    bgmVolume = (bgmVolume ?: current.bgmVolume).coerceIn(0f, 2f),
                    originalVolume = (originalVolume ?: current.originalVolume).coerceIn(0f, 1.5f),
                    chunkDurationSec = (chunkDurationSec ?: current.chunkDurationSec).coerceIn(120, 300)
                )
            )
        }
    }

    fun previewOriginalAudio() {
        val tag = "orig_preview"
        if (playbackState.value.activeTag == tag && playbackState.value.isPlaying) {
            previewPlayer.stop()
            return
        }
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            _previewPrepState.value = PreviewPrepState.Preparing
            try {
                val existingOrig = repository.getChunks(projectId)
                    .mapNotNull { it.origWavPath }
                    .map { File(it) }
                    .firstOrNull { it.exists() }

                val fileToPlay = existingOrig ?: extractPreviewSampleWav(proj).first
                _previewPrepState.value = PreviewPrepState.Idle
                previewPlayer.playWav(fileToPlay, tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Original preview failed", e)
                _previewPrepState.value = PreviewPrepState.Error(e.localizedMessage ?: "Preview failed")
            }
        }
    }

    fun previewProcessedAudio() {
        val tag = "final_preview"
        if (playbackState.value.activeTag == tag && playbackState.value.isPlaying) {
            previewPlayer.stop()
            return
        }
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            _previewPrepState.value = PreviewPrepState.Preparing
            try {
                val chunk0 = repository.getChunks(projectId).firstOrNull()
                val chunk0OrigPath = chunk0?.origWavPath
                val mixedExisting = chunk0?.mixedWavPath?.let { File(it) }?.takeIf { it.exists() }
                val fileToPlay = if (mixedExisting != null && chunk0OrigPath != null) {
                    // Re-mix chunk 0 locally on the fly with current sliders so preview reflects live sliders!
                    val origFile = File(chunk0OrigPath)
                    val liveMixed = File(getApplication<Application>().cacheDir, "live_mix_$projectId.wav")
                    val segs = repository.getSegmentsForChunk(projectId, chunk0.chunkIndex)
                    val bgmFile = File(getApplication<Application>().cacheDir, "live_bgm_$projectId.wav")
                    AudioSeparator.separateBgmAndSfx(
                        origWavFile = origFile,
                        bgmWavFile = bgmFile,
                        segments = segs,
                        chunkStartMs = chunk0.startMs,
                        sourceChannels = proj.audioChannels,
                        preserveBgm = proj.preserveBgm,
                        removeEnglishDialogue = proj.removeEnglishDialogue
                    )
                    val ttsList = segs
                        .filter { it.status == SegmentStatus.TTS_DONE && it.ttsAudioPath != null }
                        .map { AudioMixer.PositionedTts(it, File(it.ttsAudioPath!!)) }
                    AudioMixer.mixChunk(
                        origWavFile = origFile,
                        bgmWavFile = bgmFile,
                        ttsSegments = ttsList,
                        chunkStartMs = chunk0.startMs,
                        hindiVolume = proj.hindiVolume,
                        bgmVolume = proj.bgmVolume,
                        originalVolume = proj.originalVolume,
                        outputMixedWav = liveMixed
                    )
                    liveMixed
                } else {
                    // Preview first 15s of separated BGM + volume mix directly from movie source
                    val (sampleOrig, _) = extractPreviewSampleWav(proj)
                    val sampleBgm = File(getApplication<Application>().cacheDir, "sample_bgm_$projectId.wav")
                    val sampleMixed = File(getApplication<Application>().cacheDir, "sample_mixed_$projectId.wav")
                    // Apply DSP separation across sample to let user hear BGM preservation behavior
                    val dummyFullInterval = listOf(
                        DialogueSegmentEntity(
                            projectId = projectId,
                            chunkIndex = 0,
                            orderIndex = 0,
                            startMs = 0L,
                            endMs = 15_000L,
                            speakerLabel = "SPEAKER_1",
                            englishText = "",
                            audioType = "SPEECH"
                        )
                    )
                    AudioSeparator.separateBgmAndSfx(
                        origWavFile = sampleOrig,
                        bgmWavFile = sampleBgm,
                        segments = dummyFullInterval,
                        chunkStartMs = 0L,
                        sourceChannels = proj.audioChannels,
                        preserveBgm = proj.preserveBgm,
                        removeEnglishDialogue = proj.removeEnglishDialogue
                    )
                    AudioMixer.mixChunk(
                        origWavFile = sampleOrig,
                        bgmWavFile = sampleBgm,
                        ttsSegments = emptyList(),
                        chunkStartMs = 0L,
                        hindiVolume = proj.hindiVolume,
                        bgmVolume = proj.bgmVolume,
                        originalVolume = proj.originalVolume,
                        outputMixedWav = sampleMixed
                    )
                    sampleMixed
                }
                _previewPrepState.value = PreviewPrepState.Idle
                previewPlayer.playWav(fileToPlay, tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Processed preview failed", e)
                _previewPrepState.value = PreviewPrepState.Error(e.localizedMessage ?: "Preview failed")
            }
        }
    }

    private suspend fun extractPreviewSampleWav(proj: MovieProjectEntity): Pair<File, File> {
        val cacheDir = getApplication<Application>().cacheDir
        val orig = File(cacheDir, "sample_orig_$projectId.wav")
        val stt = File(cacheDir, "sample_stt_$projectId.wav")
        val endMs = minOf(15_000L, proj.durationMs)
        AudioChunkExtractor.extractChunk(
            context = getApplication(),
            sourceUri = Uri.parse(proj.sourceUriString),
            startMs = 0L,
            endMs = endMs,
            origWavFile = orig,
            sttWavFile = stt
        )
        return orig to stt
    }

    fun stopAudioPreview() = previewPlayer.stop()

    // --- Processing Controls ---
    fun startOrResumeDubbing() {
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            val app = getApplication<Application>()
            val preferredGguf = preferencesRepository.preferencesFlow.first().activeGgufModelFileName
            val needsNetwork = when (proj.engineMode) {
                EngineMode.ON_DEVICE_FREE -> !OnDeviceModelManager.isOfflineModelsDownloaded(app)
                EngineMode.ON_DEVICE_LLM -> !OnDeviceModelManager.isVoskModelReady(app) ||
                    !withContext(Dispatchers.IO) { LocalLlmModelManager.hasReadyModel(app, preferredGguf) }
                else -> true
            }
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.PROCESSING,
                stage = if (proj.currentStage == ProcessingStage.IDLE) ProcessingStage.EXTRACTING else proj.currentStage,
                progress = proj.progressPercent,
                etaMs = null,
                error = null
            )
            scheduler.startOrResumeDubbing(
                projectId = projectId,
                retryFailedOnly = false,
                requiresNetwork = needsNetwork
            )
        }
    }

    fun pauseDubbing() {
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.PAUSED,
                stage = proj.currentStage,
                progress = proj.progressPercent,
                etaMs = null,
                error = null
            )
            scheduler.cancelWork(projectId)
        }
    }

    fun cancelDubbing() {
        viewModelScope.launch {
            scheduler.cancelWork(projectId)
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.CONFIGURING,
                stage = ProcessingStage.IDLE,
                progress = 0,
                etaMs = null,
                error = null
            )
        }
    }

    fun retryFailedSegments() {
        viewModelScope.launch {
            val proj = repository.getProject(projectId)
            val app = getApplication<Application>()
            val preferredGguf = preferencesRepository.preferencesFlow.first().activeGgufModelFileName
            val needsNetwork = when (proj?.engineMode) {
                EngineMode.ON_DEVICE_FREE -> !OnDeviceModelManager.isOfflineModelsDownloaded(app)
                EngineMode.ON_DEVICE_LLM -> !OnDeviceModelManager.isVoskModelReady(app) ||
                    !withContext(Dispatchers.IO) { LocalLlmModelManager.hasReadyModel(app, preferredGguf) }
                else -> true
            }
            repository.updateProjectProgress(
                projectId = projectId,
                status = ProjectStatus.PROCESSING,
                stage = "Retrying failed Hindi voice segments...",
                progress = project.value?.progressPercent ?: 50,
                etaMs = null,
                error = null
            )
            scheduler.startOrResumeDubbing(
                projectId = projectId,
                retryFailedOnly = true,
                requiresNetwork = needsNetwork
            )
        }
    }

    fun applyMixChangesToProcessedChunks() {
        viewModelScope.launch {
            val chunksList = repository.getChunks(projectId)
            if (chunksList.any { it.origWavPath != null }) {
                scheduler.remixLocalAudio(projectId)
                _statusBanner.value = "Re-mixing audio stems with updated volume settings..."
            }
        }
    }

    // --- Review & Manual Editing ---
    fun updateSegment(
        segment: DialogueSegmentEntity,
        newHindiText: String,
        newSpeakerLabel: String,
        newEmotion: String,
        newSpeed: Float
    ) {
        viewModelScope.launch {
            val textChanged = newHindiText.trim() != segment.hindiText.trim()
            val updated = segment.copy(
                hindiText = newHindiText.trim(),
                speakerLabel = newSpeakerLabel,
                emotion = newEmotion,
                speechSpeed = newSpeed.coerceIn(0.75f, 1.35f),
                status = if (textChanged && segment.status == SegmentStatus.TTS_DONE) {
                    SegmentStatus.TRANSLATED
                } else segment.status
            )
            repository.updateSegment(updated)
            if (textChanged || newSpeakerLabel != segment.speakerLabel || newSpeed != segment.speechSpeed) {
                regenerateSegmentTts(updated)
            }
        }
    }

    fun regenerateSegmentTts(segment: DialogueSegmentEntity) {
        viewModelScope.launch {
            _regeneratingSegmentId.value = segment.id
            try {
                val proj = repository.getProject(projectId) ?: return@launch
                val speaker = repository.getSpeaker(projectId, segment.speakerLabel)
                val voice = speaker?.voicePresetName ?: proj.defaultVoicePreset.ifBlank { "Kore" }
                val speed = (speaker?.speedMultiplier ?: segment.speechSpeed).coerceIn(0.75f, 1.35f)
                val outWav = File(repository.ttsDir(projectId), "seg_${segment.id}_tts.wav")

                val durationMs = if (proj.engineMode == EngineMode.ON_DEVICE_FREE || proj.engineMode == EngineMode.ON_DEVICE_LLM) {
                    onDeviceEngine.generateHindiTts(
                        hindiText = segment.hindiText,
                        voicePresetName = voice,
                        emotion = segment.emotion,
                        speedMultiplier = speed,
                        targetSlotDurationMs = (segment.endMs - segment.startMs).coerceAtLeast(400L),
                        outWavFile = outWav,
                        tempCacheDir = getApplication<Application>().cacheDir
                    )
                } else {
                    aiService.generateHindiTts(
                        hindiText = segment.hindiText,
                        voicePresetName = voice,
                        emotion = segment.emotion,
                        speedMultiplier = speed,
                        targetSlotDurationMs = (segment.endMs - segment.startMs).coerceAtLeast(400L),
                        outWavFile = outWav,
                        tempCacheDir = getApplication<Application>().cacheDir
                    )
                }
                val savedSeg = segment.copy(
                    ttsAudioPath = outWav.absolutePath,
                    ttsDurationMs = durationMs,
                    status = SegmentStatus.TTS_DONE,
                    errorMessage = null
                )
                repository.updateSegment(savedSeg)

                // Re-mix that chunk immediately so export reflects edits
                val chunk = repository.getChunk(projectId, segment.chunkIndex)
                if (chunk?.origWavPath != null && File(chunk.origWavPath).exists()) {
                    val segs = repository.getSegmentsForChunk(projectId, chunk.chunkIndex)
                    val bgmWav = File(repository.chunksDir(projectId), "chunk_${chunk.chunkIndex}_bgm.wav")
                    val mixedWav = File(repository.chunksDir(projectId), "chunk_${chunk.chunkIndex}_mixed.wav")
                    AudioSeparator.separateBgmAndSfx(
                        origWavFile = File(chunk.origWavPath),
                        bgmWavFile = bgmWav,
                        segments = segs,
                        chunkStartMs = chunk.startMs,
                        sourceChannels = proj.audioChannels,
                        preserveBgm = proj.preserveBgm,
                        removeEnglishDialogue = proj.removeEnglishDialogue
                    )
                    val positioned = segs
                        .filter { it.status == SegmentStatus.TTS_DONE && it.ttsAudioPath != null }
                        .map { AudioMixer.PositionedTts(it, File(it.ttsAudioPath!!)) }
                    AudioMixer.mixChunk(
                        origWavFile = File(chunk.origWavPath),
                        bgmWavFile = bgmWav,
                        ttsSegments = positioned,
                        chunkStartMs = chunk.startMs,
                        hindiVolume = proj.hindiVolume,
                        bgmVolume = proj.bgmVolume,
                        originalVolume = proj.originalVolume,
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
                _statusBanner.value = "Updated Hindi voice for segment."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to regenerate TTS", e)
                repository.updateSegment(
                    segment.copy(
                        status = SegmentStatus.FAILED,
                        errorMessage = e.localizedMessage ?: "TTS regeneration failed"
                    )
                )
                _statusBanner.value = e.localizedMessage ?: "TTS regeneration failed."
            } finally {
                _regeneratingSegmentId.value = null
            }
        }
    }

    fun playSegmentHindi(segment: DialogueSegmentEntity) {
        val path = segment.ttsAudioPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _statusBanner.value = "Generated Hindi audio file is missing. Tap Regenerate."
            return
        }
        previewPlayer.playWav(file, "seg_tts_${segment.id}")
    }

    // --- Export & Media3 Hardware Acceleration ---
    fun updateMediaAccelMode(mode: String) {
        viewModelScope.launch {
            preferencesRepository.setMediaAccelMode(mode)
        }
    }

    fun updateMediaAsyncQueueing(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMediaAsyncCodecQueueing(enabled)
        }
    }

    fun updateExportQuality(quality: String) {
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            repository.updateProject(proj.copy(exportQuality = quality))
        }
    }

    fun exportFinalMp4() {
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            val chunksList = repository.getChunks(projectId).sortedBy { it.chunkIndex }
            val mixedWavs = chunksList.mapNotNull { ch ->
                ch.mixedWavPath?.let { File(it) }?.takeIf { it.exists() }
            }
            if (mixedWavs.isEmpty() || mixedWavs.size != chunksList.size) {
                _exportState.value = ExportUiState.Error(
                    "Not all audio chunks are mixed yet. Complete processing before exporting."
                )
                return@launch
            }

            val safeTitle = proj.title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(36)
            val outMp4 = File(repository.exportsDir(), "${safeTitle}_HindiDub_${proj.id}.mp4")
            val accelSettings = MediaAccelSettings.fromPreferences(preferencesRepository.preferencesFlow.first())
            _exportState.value = ExportUiState.Exporting(2, "Starting MP4 export...")

            try {
                val resultFile = VideoExporter.exportFinalMp4(
                    context = getApplication(),
                    sourceUri = Uri.parse(proj.sourceUriString),
                    mixedChunkWavs = mixedWavs,
                    durationMs = proj.durationMs,
                    quality = proj.exportQuality,
                    outputMp4File = outMp4,
                    mediaAccelSettings = accelSettings
                ) { pct, stage ->
                    _exportState.value = ExportUiState.Exporting(pct, stage)
                }

                val size = resultFile.length()
                repository.updateProject(
                    proj.copy(
                        exportPath = resultFile.absolutePath,
                        exportSizeBytes = size,
                        status = ProjectStatus.COMPLETED,
                        currentStage = "Exported (${proj.exportQuality})",
                        progressPercent = 100
                    )
                )
                _exportState.value = ExportUiState.Success(resultFile, size)
                refreshTempSize()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _exportState.value = ExportUiState.Error(
                    e.localizedMessage ?: "Failed to export dubbed MP4."
                )
            }
        }
    }

    fun saveExportToDownloads(onNeedSafFallback: (String) -> Unit) {
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            val path = proj.exportPath ?: return@launch
            val file = File(path)
            if (!file.exists()) {
                _statusBanner.value = "Exported MP4 file not found. Export again first."
                return@launch
            }
            try {
                val savedUri = VideoExporter.saveToDownloads(getApplication(), file, file.name)
                if (savedUri != null) {
                    _statusBanner.value = "Saved to Downloads/HindiDubStudio/${file.name}"
                } else {
                    onNeedSafFallback(file.name)
                }
            } catch (e: Exception) {
                _statusBanner.value = "Save failed: ${e.localizedMessage}"
            }
        }
    }

    fun saveExportToSafUri(targetUri: Uri) {
        viewModelScope.launch {
            val path = repository.getProject(projectId)?.exportPath ?: return@launch
            val file = File(path)
            if (!file.exists()) return@launch
            try {
                VideoExporter.copyToUri(getApplication(), file, targetUri)
                _statusBanner.value = "Saved Hindi dubbed MP4 successfully."
            } catch (e: Exception) {
                _statusBanner.value = "Save failed: ${e.localizedMessage}"
            }
        }
    }

    fun buildShareIntent(): Intent? {
        val path = project.value?.exportPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val uri = VideoExporter.getShareUri(getApplication(), file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${project.value?.title ?: "Movie"} - Hindi Dubbed")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun cleanTempFiles() {
        viewModelScope.launch {
            val freed = repository.cleanTempFilesForProject(projectId)
            refreshTempSize()
            _statusBanner.value = if (freed > 0L) {
                "Cleaned ${com.example.media.WavUtils.formatFileSize(freed)} of temporary audio chunks."
            } else {
                "No temporary chunk files left to remove."
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewPlayer.stop()
        onDeviceEngine.release()
    }

    companion object {
        fun factory(application: Application, projectId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ProjectStudioViewModel(application, projectId) as T
                }
            }
    }
}
