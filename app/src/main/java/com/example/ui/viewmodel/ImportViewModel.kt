package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DubbingRepository
import com.example.data.local.MovieProjectEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.SeparationMode
import com.example.data.local.SubtitleLanguage
import android.provider.OpenableColumns
import com.example.media.InspectedMovie
import com.example.media.MediaAccelSettings
import com.example.media.MediaInspector
import com.example.media.SrtSubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Inspecting : ImportUiState
    data class Ready(
        val movie: InspectedMovie,
        val chunkDurationSec: Int,
        val subtitleFileName: String? = null,
        val subtitleCueCount: Int = 0,
        val subtitleRawContent: String? = null,
        val subtitleLanguage: String = SubtitleLanguage.ENGLISH
    ) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DubbingRepository(application)
    private val preferencesRepository = PreferencesRepository(application)

    val mediaAccelSettings: StateFlow<MediaAccelSettings> = preferencesRepository.preferencesFlow
        .map { MediaAccelSettings.fromPreferences(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MediaAccelSettings())

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val _availableStorageBytes = MutableStateFlow(MediaInspector.getAvailableStorageBytes(application))
    val availableStorageBytes: StateFlow<Long> = _availableStorageBytes.asStateFlow()

    private val _subtitleError = MutableStateFlow<String?>(null)
    val subtitleError: StateFlow<String?> = _subtitleError.asStateFlow()

    fun clearSubtitleError() {
        _subtitleError.value = null
    }

    fun inspectPickedVideo(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = ImportUiState.Inspecting
            _subtitleError.value = null
            try {
                val context = getApplication<Application>()
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some temporary providers do not support persistable URI grants
                }
                val inspected = MediaInspector.inspectMovie(context, uri)
                val prefs = preferencesRepository.preferencesFlow.first()
                _availableStorageBytes.value = inspected.availableStorageBytes
                _uiState.value = ImportUiState.Ready(
                    movie = inspected,
                    chunkDurationSec = prefs.defaultChunkDurationSec
                )
            } catch (e: Exception) {
                _uiState.value = ImportUiState.Error(
                    e.localizedMessage ?: "Failed to read the selected video file."
                )
            }
        }
    }

    fun attachSubtitleUri(uri: Uri) {
        val current = _uiState.value as? ImportUiState.Ready ?: return
        viewModelScope.launch {
            _subtitleError.value = null
            try {
                val context = getApplication<Application>()
                var displayName = "subtitles.srt"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val raw = c.getString(idx)?.trim()
                            if (!raw.isNullOrBlank()) displayName = raw
                        }
                    }
                }
                val rawText = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw IllegalStateException("Unable to read selected subtitle file.")
                val cues = SrtSubtitleParser.parse(rawText)
                if (cues.isEmpty()) {
                    _subtitleError.value = "No valid .srt or .vtt cues found in $displayName."
                    return@launch
                }
                val detectedLang = SrtSubtitleParser.detectLikelyLanguage(cues)
                _uiState.value = current.copy(
                    subtitleFileName = displayName,
                    subtitleCueCount = cues.size,
                    subtitleRawContent = rawText,
                    subtitleLanguage = detectedLang
                )
            } catch (e: Exception) {
                _subtitleError.value = e.localizedMessage ?: "Failed to parse subtitle file."
            }
        }
    }

    fun updateSubtitleLanguage(language: String) {
        val current = _uiState.value as? ImportUiState.Ready ?: return
        _uiState.value = current.copy(subtitleLanguage = language)
    }

    fun clearAttachedSubtitle() {
        val current = _uiState.value as? ImportUiState.Ready ?: return
        _subtitleError.value = null
        _uiState.value = current.copy(
            subtitleFileName = null,
            subtitleCueCount = 0,
            subtitleRawContent = null,
            subtitleLanguage = SubtitleLanguage.ENGLISH
        )
    }

    fun updateChunkDuration(seconds: Int) {
        val current = _uiState.value
        if (current is ImportUiState.Ready) {
            _uiState.value = current.copy(chunkDurationSec = seconds.coerceIn(120, 300))
        }
    }

    fun createProject(onProjectCreated: (Long) -> Unit) {
        val current = _uiState.value
        if (current !is ImportUiState.Ready) return

        viewModelScope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val movie = current.movie
            val sepMode = if (movie.audioChannels >= 2) {
                SeparationMode.STEREO_MID_SIDE
            } else {
                SeparationMode.MONO_BAND_DUCKING
            }

            val insertedId = repository.insertProject(
                MovieProjectEntity(
                    title = movie.title,
                    sourceUriString = movie.uri.toString(),
                    fileName = movie.fileName,
                    durationMs = movie.durationMs,
                    width = movie.width,
                    height = movie.height,
                    fileSizeBytes = movie.fileSizeBytes,
                    videoMime = movie.videoMime,
                    audioMime = movie.audioMime,
                    audioChannels = movie.audioChannels,
                    audioSampleRate = movie.audioSampleRate,
                    thumbnailPath = null,
                    chunkDurationSec = current.chunkDurationSec,
                    engineMode = prefs.defaultEngineMode,
                    dialogueStyle = prefs.defaultDialogueStyle,
                    defaultSpeechSpeed = prefs.defaultSpeechSpeed,
                    preserveBgm = prefs.defaultPreserveBgm,
                    separationMode = sepMode,
                    exportQuality = prefs.defaultExportQuality
                )
            )

            // Copy thumbnail & optional subtitle into project folder
            val tempThumb = movie.thumbnailTempFile
            var updatedProj = repository.getProject(insertedId)
            if (tempThumb != null && tempThumb.exists()) {
                val destThumb = File(repository.projectDir(insertedId), "thumbnail.jpg")
                tempThumb.copyTo(destThumb, overwrite = true)
                tempThumb.delete()
                updatedProj = updatedProj?.copy(thumbnailPath = destThumb.absolutePath)
            }
            val rawSub = current.subtitleRawContent
            val subName = current.subtitleFileName
            if (!rawSub.isNullOrBlank() && !subName.isNullOrBlank()) {
                val ext = if (subName.endsWith(".vtt", ignoreCase = true)) "vtt" else "srt"
                val destSub = File(repository.projectDir(insertedId), "imported_subtitles.$ext")
                kotlinx.coroutines.withContext(Dispatchers.IO) { destSub.writeText(rawSub) }
                updatedProj = updatedProj?.copy(
                    subtitlePath = destSub.absolutePath,
                    subtitleFileName = subName,
                    subtitleLanguage = current.subtitleLanguage
                )
            }
            if (updatedProj != null) {
                repository.updateProject(updatedProj)
            }

            onProjectCreated(insertedId)
        }
    }
}
