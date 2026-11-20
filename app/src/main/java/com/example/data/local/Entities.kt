package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ProjectStatus {
    const val CONFIGURING = "CONFIGURING"
    const val PROCESSING = "PROCESSING"
    const val PAUSED = "PAUSED"
    const val READY_FOR_REVIEW = "READY_FOR_REVIEW"
    const val EXPORTING = "EXPORTING"
    const val COMPLETED = "COMPLETED"
    const val ERROR = "ERROR"
}

object ProcessingStage {
    const val IDLE = "Idle"
    const val PREPARING_MODELS = "Preparing On-Device AI Models"
    const val EXTRACTING = "Extracting Audio Chunks"
    const val TRANSCRIBING = "Transcribing English Dialogue"
    const val TRANSLATING = "Translating to Natural Hindi"
    const val GENERATING_TTS = "Generating Hindi Voices"
    const val SEPARATING_BGM = "Preserving Background Music & SFX"
    const val MIXING = "Mixing Hindi Dialogue & BGM"
    const val READY = "Ready for Review & Export"
}

object ChunkStatus {
    const val PENDING = "PENDING"
    const val EXTRACTED = "EXTRACTED"
    const val TRANSCRIBED = "TRANSCRIBED"
    const val TRANSLATED = "TRANSLATED"
    const val TTS_DONE = "TTS_DONE"
    const val MIXED = "MIXED"
    const val FAILED = "FAILED"
}

object SegmentStatus {
    const val TRANSCRIBED = "TRANSCRIBED"
    const val TRANSLATED = "TRANSLATED"
    const val TTS_DONE = "TTS_DONE"
    const val FAILED = "FAILED"
}

object DialogueStyle {
    const val CONVERSATIONAL = "Natural Conversational Hindi"
    const val CINEMATIC = "Cinematic Dramatic Hindi"
    const val CASUAL = "Casual Everyday Hindustani"
    const val RAW_UNCENSORED = "Raw Uncensored / Adult Cinema"

    val all = listOf(CONVERSATIONAL, CINEMATIC, CASUAL, RAW_UNCENSORED)
}

object SeparationMode {
    const val STEREO_MID_SIDE = "STEREO_MID_SIDE"
    const val MONO_BAND_DUCKING = "MONO_BAND_DUCKING"
    const val ORIGINAL_UNSEPARATED = "ORIGINAL_UNSEPARATED"
}

object ExportQuality {
    const val ORIGINAL = "ORIGINAL"
    const val HD_1080P = "1080P"
    const val HD_720P = "720P"
    const val SD_480P = "480P"

    val all = listOf(ORIGINAL, HD_1080P, HD_720P, SD_480P)
}

object EngineMode {
    const val ON_DEVICE_FREE = "ON_DEVICE_FREE"
    const val ON_DEVICE_LLM = "ON_DEVICE_LLM"
    const val GEMINI_CLOUD = "GEMINI_CLOUD"

    val all = listOf(ON_DEVICE_FREE, ON_DEVICE_LLM, GEMINI_CLOUD)
}

@Entity(tableName = "movie_projects")
data class MovieProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUriString: String,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val videoMime: String,
    val audioMime: String,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val thumbnailPath: String?,
    val chunkDurationSec: Int = 180,
    val engineMode: String = EngineMode.ON_DEVICE_FREE,
    val dialogueStyle: String = DialogueStyle.CONVERSATIONAL,
    val defaultVoiceGender: String = "MALE",
    val defaultVoicePreset: String = "Charon",
    val defaultSpeechSpeed: Float = 1.0f,
    val defaultEmotionBias: String = "natural",
    val preserveBgm: Boolean = true,
    val removeEnglishDialogue: Boolean = true,
    val separationMode: String = SeparationMode.STEREO_MID_SIDE,
    val hindiVolume: Float = 1.0f,
    val bgmVolume: Float = 0.85f,
    val originalVolume: Float = 0.0f,
    val status: String = ProjectStatus.CONFIGURING,
    val currentStage: String = ProcessingStage.IDLE,
    val progressPercent: Int = 0,
    val estimatedRemainingMs: Long? = null,
    val lastError: String? = null,
    val exportQuality: String = ExportQuality.ORIGINAL,
    val exportPath: String? = null,
    val exportSizeBytes: Long = 0L,
    val tempFilesCleaned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "audio_chunks",
    foreignKeys = [
        ForeignKey(
            entity = MovieProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index(value = ["projectId", "chunkIndex"], unique = true)]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chunkIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val sttWavPath: String? = null,
    val origWavPath: String? = null,
    val bgmWavPath: String? = null,
    val mixedWavPath: String? = null,
    val status: String = ChunkStatus.PENDING,
    val processingTimeMs: Long = 0L,
    val errorMessage: String? = null
)

@Entity(
    tableName = "speakers",
    foreignKeys = [
        ForeignKey(
            entity = MovieProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index(value = ["projectId", "speakerLabel"], unique = true)]
)
data class SpeakerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val speakerLabel: String,
    val displayName: String,
    val gender: String = "NEUTRAL", // MALE, FEMALE, NEUTRAL
    val voicePresetName: String = "Kore",
    val speedMultiplier: Float = 1.0f,
    val defaultEmotion: String = "natural",
    val traits: String = ""
)

@Entity(
    tableName = "dialogue_segments",
    foreignKeys = [
        ForeignKey(
            entity = MovieProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("chunkIndex")]
)
data class DialogueSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chunkIndex: Int,
    val orderIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String,
    val englishText: String,
    val hindiText: String = "",
    val emotion: String = "neutral",
    val isOverlapping: Boolean = false,
    val audioType: String = "SPEECH", // SPEECH, MUSIC, SFX, SILENCE
    val speechSpeed: Float = 1.0f,
    val ttsAudioPath: String? = null,
    val ttsDurationMs: Long = 0L,
    val status: String = SegmentStatus.TRANSCRIBED,
    val errorMessage: String? = null
)
