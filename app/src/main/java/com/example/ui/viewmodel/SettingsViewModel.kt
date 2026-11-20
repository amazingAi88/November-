package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GgufModelPreset
import com.example.ai.InstalledGgufModel
import com.example.ai.LocalGgufTranslator
import com.example.ai.LocalLlmModelManager
import com.example.ai.LocalLlmSampleResult
import com.example.ai.OnDeviceModelManager
import com.example.ai.OnDeviceModelsStatus
import com.example.data.local.DubbingRepository
import com.example.data.local.PreferencesRepository
import com.example.data.local.StudioPreferences
import com.example.media.WavUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = PreferencesRepository(application)
    private val dubbingRepository = DubbingRepository(application)
    private val localGgufTranslator = LocalGgufTranslator()

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

    private val _installedGgufModels = MutableStateFlow<List<InstalledGgufModel>>(emptyList())
    val installedGgufModels: StateFlow<List<InstalledGgufModel>> = _installedGgufModels.asStateFlow()

    private val _deviceRamGb = MutableStateFlow(LocalLlmModelManager.getDeviceTotalRamGb(application))
    val deviceRamGb: StateFlow<Float> = _deviceRamGb.asStateFlow()

    private val _isManagingGguf = MutableStateFlow(false)
    val isManagingGguf: StateFlow<Boolean> = _isManagingGguf.asStateFlow()

    private val _ggufProgress = MutableStateFlow<Float?>(null)
    val ggufProgress: StateFlow<Float?> = _ggufProgress.asStateFlow()

    private val _ggufProgressText = MutableStateFlow<String?>(null)
    val ggufProgressText: StateFlow<String?> = _ggufProgressText.asStateFlow()

    private val _sampleTestOutput = MutableStateFlow<LocalLlmSampleResult?>(null)
    val sampleTestOutput: StateFlow<LocalLlmSampleResult?> = _sampleTestOutput.asStateFlow()

    private val _isTestingSample = MutableStateFlow(false)
    val isTestingSample: StateFlow<Boolean> = _isTestingSample.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshTempSize()
        refreshModelsStatus()
        refreshGgufModels()
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

    fun refreshGgufModels() {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) {
                LocalLlmModelManager.listInstalledModels(getApplication())
            }
            _installedGgufModels.value = models
            _deviceRamGb.value = LocalLlmModelManager.getDeviceTotalRamGb(getApplication())
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

    fun downloadGgufPreset(preset: GgufModelPreset) {
        if (_isManagingGguf.value) return
        viewModelScope.launch {
            _isManagingGguf.value = true
            _ggufProgress.value = 0f
            _ggufProgressText.value = "Connecting to download ${preset.displayName}..."
            try {
                val file = LocalLlmModelManager.downloadPresetModel(
                    context = getApplication(),
                    preset = preset
                ) { downloadedMb, totalMb, fraction ->
                    _ggufProgress.value = fraction
                    val pct = (fraction * 100f).toInt().coerceIn(0, 100)
                    _ggufProgressText.value = String.format(
                        Locale.US,
                        "Downloading %s • %.0f / %.0f MB (%d%%)",
                        preset.fileName,
                        downloadedMb,
                        totalMb,
                        pct
                    )
                }
                preferencesRepository.setActiveGgufModelFileName(file.name)
                refreshGgufModels()
                _message.value = "${preset.displayName} is installed & set as active offline LLM."
            } catch (e: Exception) {
                refreshGgufModels()
                _message.value = e.localizedMessage ?: "GGUF model download failed."
            } finally {
                _isManagingGguf.value = false
                _ggufProgress.value = null
                _ggufProgressText.value = null
            }
        }
    }

    fun importCustomGguf(uri: Uri) {
        if (_isManagingGguf.value) return
        viewModelScope.launch {
            _isManagingGguf.value = true
            _ggufProgress.value = 0f
            _ggufProgressText.value = "Validating & copying custom .gguf model..."
            try {
                val file = LocalLlmModelManager.importGgufFromUri(
                    context = getApplication(),
                    sourceUri = uri
                ) { copiedMb, totalMb, fraction ->
                    _ggufProgress.value = fraction
                    val pct = (fraction * 100f).toInt().coerceIn(0, 100)
                    _ggufProgressText.value = if (totalMb > 0f) {
                        String.format(Locale.US, "Importing GGUF • %.0f / %.0f MB (%d%%)", copiedMb, totalMb, pct)
                    } else {
                        String.format(Locale.US, "Importing GGUF • %.0f MB copied...", copiedMb)
                    }
                }
                preferencesRepository.setActiveGgufModelFileName(file.name)
                refreshGgufModels()
                _message.value = "Imported ${file.name} and selected it as active offline LLM."
            } catch (e: Exception) {
                refreshGgufModels()
                _message.value = e.localizedMessage ?: "Unable to import .gguf file."
            } finally {
                _isManagingGguf.value = false
                _ggufProgress.value = null
                _ggufProgressText.value = null
            }
        }
    }

    fun selectActiveGguf(fileName: String) {
        viewModelScope.launch {
            preferencesRepository.setActiveGgufModelFileName(fileName)
            _sampleTestOutput.value = null
            _message.value = "Active offline LLM set to $fileName."
        }
    }

    fun deleteGgufModel(fileName: String) {
        if (_isManagingGguf.value) return
        viewModelScope.launch {
            _isManagingGguf.value = true
            try {
                localGgufTranslator.release()
                val freed = withContext(Dispatchers.IO) {
                    LocalLlmModelManager.deleteModel(getApplication(), fileName)
                }
                val remaining = withContext(Dispatchers.IO) {
                    LocalLlmModelManager.listInstalledModels(getApplication()).filter { it.isValidGguf }
                }
                _installedGgufModels.value = withContext(Dispatchers.IO) {
                    LocalLlmModelManager.listInstalledModels(getApplication())
                }
                if (preferences.value.activeGgufModelFileName == fileName) {
                    preferencesRepository.setActiveGgufModelFileName(remaining.firstOrNull()?.fileName ?: "")
                }
                _sampleTestOutput.value = null
                _message.value = "Deleted $fileName (${WavUtils.formatFileSize(freed)} freed)."
            } finally {
                _isManagingGguf.value = false
            }
        }
    }

    fun testGgufSample(englishLine: String, dialogueStyle: String) {
        val cleanInput = englishLine.trim()
        if (cleanInput.isBlank() || _isTestingSample.value) return
        viewModelScope.launch {
            _isTestingSample.value = true
            try {
                val activeFile = withContext(Dispatchers.IO) {
                    LocalLlmModelManager.resolveActiveModelFile(
                        getApplication(),
                        preferences.value.activeGgufModelFileName
                    )
                } ?: throw IllegalStateException("Please download or import a .gguf model first.")

                val result = localGgufTranslator.translateSingleSample(
                    modelFile = activeFile,
                    englishLine = cleanInput,
                    dialogueStyle = dialogueStyle
                )
                _sampleTestOutput.value = result
            } catch (e: Exception) {
                _message.value = e.localizedMessage ?: "Sample GGUF translation failed."
            } finally {
                _isTestingSample.value = false
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

    override fun onCleared() {
        super.onCleared()
        localGgufTranslator.releaseBlocking()
    }
}
