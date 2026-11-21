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

object MediaAccelMode {
    const val HARDWARE_PRIORITY = "HARDWARE_PRIORITY"
    const val BALANCED_AUTO = "BALANCED_AUTO"
    const val SOFTWARE_SAFE = "SOFTWARE_SAFE"

    val all = listOf(HARDWARE_PRIORITY, BALANCED_AUTO, SOFTWARE_SAFE)

    fun displayLabel(mode: String): String = when (mode) {
        HARDWARE_PRIORITY -> "Hardware Priority"
        BALANCED_AUTO -> "Balanced Auto"
        SOFTWARE_SAFE -> "Software Safe"
        else -> "Hardware Priority"
    }
}

data class StudioPreferences(
    val defaultChunkDurationSec: Int = 180,
    val defaultEngineMode: String = EngineMode.ON_DEVICE_FREE,
    val defaultDialogueStyle: String = DialogueStyle.CONVERSATIONAL,
    val defaultSpeechSpeed: Float = 1.0f,
    val defaultPreserveBgm: Boolean = true,
    val defaultExportQuality: String = ExportQuality.ORIGINAL,
    val autoCleanupAfterExport: Boolean = false,
    val activeGgufModelFileName: String = "",
    val mediaAccelMode: String = MediaAccelMode.HARDWARE_PRIORITY,
    val mediaAsyncCodecQueueing: Boolean = true,
    val mediaHighOperatingRate: Boolean = true,
    val mediaAllowCodecFallback: Boolean = true,
    val mediaTransformerTimeoutSec: Int = 30
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
        val MEDIA_ACCEL_MODE = stringPreferencesKey("media_accel_mode")
        val MEDIA_ASYNC_CODEC_QUEUEING = booleanPreferencesKey("media_async_codec_queueing")
        val MEDIA_HIGH_OPERATING_RATE = booleanPreferencesKey("media_high_operating_rate")
        val MEDIA_ALLOW_CODEC_FALLBACK = booleanPreferencesKey("media_allow_codec_fallback")
        val MEDIA_TRANSFORMER_TIMEOUT_SEC = intPreferencesKey("media_transformer_timeout_sec")
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
            activeGgufModelFileName = prefs[Keys.ACTIVE_GGUF_MODEL] ?: "",
            mediaAccelMode = prefs[Keys.MEDIA_ACCEL_MODE] ?: MediaAccelMode.HARDWARE_PRIORITY,
            mediaAsyncCodecQueueing = prefs[Keys.MEDIA_ASYNC_CODEC_QUEUEING] ?: true,
            mediaHighOperatingRate = prefs[Keys.MEDIA_HIGH_OPERATING_RATE] ?: true,
            mediaAllowCodecFallback = prefs[Keys.MEDIA_ALLOW_CODEC_FALLBACK] ?: true,
            mediaTransformerTimeoutSec = prefs[Keys.MEDIA_TRANSFORMER_TIMEOUT_SEC] ?: 30
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

    suspend fun setMediaAccelMode(mode: String) {
        val valid = if (mode in MediaAccelMode.all) mode else MediaAccelMode.HARDWARE_PRIORITY
        context.dataStore.edit { it[Keys.MEDIA_ACCEL_MODE] = valid }
    }

    suspend fun setMediaAsyncCodecQueueing(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEDIA_ASYNC_CODEC_QUEUEING] = enabled }
    }

    suspend fun setMediaHighOperatingRate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEDIA_HIGH_OPERATING_RATE] = enabled }
    }

    suspend fun setMediaAllowCodecFallback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEDIA_ALLOW_CODEC_FALLBACK] = enabled }
    }

    suspend fun setMediaTransformerTimeoutSec(seconds: Int) {
        val normalized = when {
            seconds <= 0 -> 0
            else -> seconds.coerceIn(10, 300)
        }
        context.dataStore.edit { it[Keys.MEDIA_TRANSFORMER_TIMEOUT_SEC] = normalized }
    }
}
