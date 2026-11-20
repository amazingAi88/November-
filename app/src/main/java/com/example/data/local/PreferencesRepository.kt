package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "studio_settings")

data class StudioPreferences(
    val defaultChunkDurationSec: Int = 180,
    val defaultEngineMode: String = EngineMode.ON_DEVICE_FREE,
    val defaultDialogueStyle: String = DialogueStyle.CONVERSATIONAL,
    val defaultSpeechSpeed: Float = 1.0f,
    val defaultPreserveBgm: Boolean = true,
    val defaultExportQuality: String = ExportQuality.ORIGINAL,
    val autoCleanupAfterExport: Boolean = false,
    val activeGgufModelFileName: String = ""
)

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val CHUNK_DURATION_SEC = intPreferencesKey("chunk_duration_sec")
        val ENGINE_MODE = stringPreferencesKey("engine_mode")
        val DIALOGUE_STYLE = stringPreferencesKey("dialogue_style")
        val SPEECH_SPEED = floatPreferencesKey("speech_speed")
        val PRESERVE_BGM = booleanPreferencesKey("preserve_bgm")
        val EXPORT_QUALITY = stringPreferencesKey("export_quality")
        val AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup_after_export")
        val ACTIVE_GGUF_MODEL = stringPreferencesKey("active_gguf_model")
    }

    val preferencesFlow: Flow<StudioPreferences> = context.dataStore.data.map { prefs ->
        StudioPreferences(
            defaultChunkDurationSec = prefs[Keys.CHUNK_DURATION_SEC] ?: 180,
            defaultEngineMode = prefs[Keys.ENGINE_MODE] ?: EngineMode.ON_DEVICE_FREE,
            defaultDialogueStyle = prefs[Keys.DIALOGUE_STYLE] ?: DialogueStyle.CONVERSATIONAL,
            defaultSpeechSpeed = prefs[Keys.SPEECH_SPEED] ?: 1.0f,
            defaultPreserveBgm = prefs[Keys.PRESERVE_BGM] ?: true,
            defaultExportQuality = prefs[Keys.EXPORT_QUALITY] ?: ExportQuality.ORIGINAL,
            autoCleanupAfterExport = prefs[Keys.AUTO_CLEANUP] ?: false,
            activeGgufModelFileName = prefs[Keys.ACTIVE_GGUF_MODEL] ?: ""
        )
    }

    suspend fun setChunkDurationSec(seconds: Int) {
        context.dataStore.edit { it[Keys.CHUNK_DURATION_SEC] = seconds.coerceIn(120, 300) }
    }

    suspend fun setEngineMode(mode: String) {
        context.dataStore.edit { it[Keys.ENGINE_MODE] = mode }
    }

    suspend fun setDialogueStyle(style: String) {
        context.dataStore.edit { it[Keys.DIALOGUE_STYLE] = style }
    }

    suspend fun setSpeechSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.SPEECH_SPEED] = speed.coerceIn(0.75f, 1.35f) }
    }

    suspend fun setPreserveBgm(preserve: Boolean) {
        context.dataStore.edit { it[Keys.PRESERVE_BGM] = preserve }
    }

    suspend fun setExportQuality(quality: String) {
        context.dataStore.edit { it[Keys.EXPORT_QUALITY] = quality }
    }

    suspend fun setAutoCleanup(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CLEANUP] = enabled }
    }

    suspend fun setActiveGgufModelFileName(fileName: String) {
        context.dataStore.edit { it[Keys.ACTIVE_GGUF_MODEL] = fileName }
    }
}
