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
import com.example.media.InspectedMovie
import com.example.media.MediaAccelSettings
import com.example.media.MediaInspector
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
        val chunkDurationSec: Int
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

    fun inspectPickedVideo(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = ImportUiState.Inspecting
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

            // Copy thumbnail into project folder
            val tempThumb = movie.thumbnailTempFile
            if (tempThumb != null && tempThumb.exists()) {
                val destThumb = File(repository.projectDir(insertedId), "thumbnail.jpg")
                tempThumb.copyTo(destThumb, overwrite = true)
                tempThumb.delete()
                repository.getProject(insertedId)?.let { proj ->
                    repository.updateProject(proj.copy(thumbnailPath = destThumb.absolutePath))
                }
            }

            onProjectCreated(insertedId)
        }
    }
}
