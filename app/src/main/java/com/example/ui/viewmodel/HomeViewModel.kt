package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DubbingRepository
import com.example.data.local.MovieProjectEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.StudioPreferences
import com.example.media.MediaInspector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DubbingRepository(application)
    private val preferencesRepository = PreferencesRepository(application)

    val projects: StateFlow<List<MovieProjectEntity>> = repository.observeAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<StudioPreferences> = preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudioPreferences())

    private val _availableStorageBytes = MutableStateFlow(MediaInspector.getAvailableStorageBytes(application))
    val availableStorageBytes: StateFlow<Long> = _availableStorageBytes.asStateFlow()

    private val _tempCacheBytes = MutableStateFlow(0L)
    val tempCacheBytes: StateFlow<Long> = _tempCacheBytes.asStateFlow()

    init {
        refreshStorageInfo()
    }

    fun refreshStorageInfo() {
        viewModelScope.launch {
            _availableStorageBytes.value = MediaInspector.getAvailableStorageBytes(getApplication())
            _tempCacheBytes.value = repository.getTempWorkspaceSizeBytes()
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            refreshStorageInfo()
        }
    }
}
