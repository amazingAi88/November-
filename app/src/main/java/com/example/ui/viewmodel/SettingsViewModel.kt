package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.OnDeviceModelManager
import com.example.ai.OnDeviceModelsStatus
import com.example.data.local.DubbingRepository
import com.example.data.local.PreferencesRepository
import com.example.data.local.StudioPreferences
import com.example.media.WavUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = PreferencesRepository(application)
    private val dubbingRepository = DubbingRepository(application)

    val preferences: StateFlow<StudioPreferences> = preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudioPreferences())

    private val _tempCacheSizeBytes = MutableStateFlow(0L)
    val tempCacheSizeBytes: StateFlow<Long> = _tempCacheSizeBytes.asStateFlow()

    private val _modelsStatus = MutableStateFlow<OnDeviceModelsStatus?>(null)
    val modelsStatus: StateFlow<OnDeviceModelsStatus?> = _modelsStatus.asStateFlow()

    private val _isManagingModels = MutableStateFlow(false)
    val isManagingModels: StateFlow<Boolean> = _isManagingModels.asStateFlow()

    private val _modelProgressText = MutableStateFlow<String?>(null)
    val modelProgressText: StateFlow<String?> = _modelProgressText.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshTempSize()
        refreshModelsStatus()
    }

    fun refreshTempSize() {
        viewModelScope.launch {
            _tempCacheSizeBytes.value = dubbingRepository.getTempWorkspaceSizeBytes()
        }
    }

    fun refreshModelsStatus() {
        viewModelScope.launch {
            _modelsStatus.value = OnDeviceModelManager.inspectStatus(getApplication())
        }
    }

    fun downloadOnDeviceModels() {
        if (_isManagingModels.value) return
        viewModelScope.launch {
            _isManagingModels.value = true
            _modelProgressText.value = "Preparing download..."
            try {
                OnDeviceModelManager.ensureAllModelsReady(getApplication()) { status ->
                    _modelProgressText.value = status
                }
                refreshModelsStatus()
                _message.value = "On-device English STT, Hindi Translator & Android TTS are ready."
            } catch (e: Exception) {
                refreshModelsStatus()
                _message.value = e.localizedMessage ?: "Model setup failed."
            } finally {
                _isManagingModels.value = false
                _modelProgressText.value = null
            }
        }
    }

    fun deleteOnDeviceModels() {
        if (_isManagingModels.value) return
        viewModelScope.launch {
            _isManagingModels.value = true
            try {
                val freed = OnDeviceModelManager.deleteOnDeviceModels(getApplication())
                refreshModelsStatus()
                _message.value = "Removed downloaded offline models (${WavUtils.formatFileSize(freed)} freed)."
            } finally {
                _isManagingModels.value = false
            }
        }
    }

    fun setEngineMode(mode: String) = viewModelScope.launch { preferencesRepository.setEngineMode(mode) }
    fun setChunkDurationSec(sec: Int) = viewModelScope.launch { preferencesRepository.setChunkDurationSec(sec) }
    fun setDialogueStyle(style: String) = viewModelScope.launch { preferencesRepository.setDialogueStyle(style) }
    fun setSpeechSpeed(speed: Float) = viewModelScope.launch { preferencesRepository.setSpeechSpeed(speed) }
    fun setPreserveBgm(preserve: Boolean) = viewModelScope.launch { preferencesRepository.setPreserveBgm(preserve) }
    fun setExportQuality(quality: String) = viewModelScope.launch { preferencesRepository.setExportQuality(quality) }
    fun setAutoCleanup(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setAutoCleanup(enabled) }

    fun cleanAllTempFiles() {
        viewModelScope.launch {
            val freed = dubbingRepository.cleanAllTempFiles()
            refreshTempSize()
            _message.value = "Freed ${WavUtils.formatFileSize(freed)} of temporary audio chunks."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
