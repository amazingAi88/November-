package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.MovieProjectEntity
import com.example.data.local.SubtitleLanguage
import com.example.data.local.TranslationSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationRobolectricTest {

    @Test
    fun `MIGRATION_3_4 and MIGRATION_4_5 add subtitlePath, subtitleFileName, and subtitleLanguage cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_${System.currentTimeMillis()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Create a v3 schema table manually
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `movie_projects` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL,
                                `sourceUriString` TEXT NOT NULL,
                                `fileName` TEXT NOT NULL,
                                `durationMs` INTEGER NOT NULL,
                                `width` INTEGER NOT NULL,
                                `height` INTEGER NOT NULL,
                                `fileSizeBytes` INTEGER NOT NULL,
                                `videoMime` TEXT NOT NULL,
                                `audioMime` TEXT NOT NULL,
                                `audioChannels` INTEGER NOT NULL,
                                `audioSampleRate` INTEGER NOT NULL,
                                `thumbnailPath` TEXT,
                                `chunkDurationSec` INTEGER NOT NULL,
                                `engineMode` TEXT NOT NULL,
                                `dialogueStyle` TEXT NOT NULL,
                                `defaultVoiceGender` TEXT NOT NULL,
                                `defaultVoicePreset` TEXT NOT NULL,
                                `defaultSpeechSpeed` REAL NOT NULL,
                                `defaultEmotionBias` TEXT NOT NULL,
                                `preserveBgm` INTEGER NOT NULL,
                                `removeEnglishDialogue` INTEGER NOT NULL,
                                `separationMode` TEXT NOT NULL,
                                `hindiVolume` REAL NOT NULL,
                                `bgmVolume` REAL NOT NULL,
                                `originalVolume` REAL NOT NULL,
                                `status` TEXT NOT NULL,
                                `currentStage` TEXT NOT NULL,
                                `progressPercent` INTEGER NOT NULL,
                                `estimatedRemainingMs` INTEGER,
                                `lastError` TEXT,
                                `exportQuality` TEXT NOT NULL,
                                `exportPath` TEXT,
                                `exportSizeBytes` INTEGER NOT NULL,
                                `tempFilesCleaned` INTEGER NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        helper.writableDatabase.use { rawDb ->
            rawDb.execSQL(
                """
                INSERT INTO movie_projects (
                    id, title, sourceUriString, fileName, durationMs, width, height, fileSizeBytes,
                    videoMime, audioMime, audioChannels, audioSampleRate, thumbnailPath, chunkDurationSec,
                    engineMode, dialogueStyle, defaultVoiceGender, defaultVoicePreset, defaultSpeechSpeed,
                    defaultEmotionBias, preserveBgm, removeEnglishDialogue, separationMode,
                    hindiVolume, bgmVolume, originalVolume, status, currentStage, progressPercent,
                    estimatedRemainingMs, lastError, exportQuality, exportPath, exportSizeBytes,
                    tempFilesCleaned, createdAt, updatedAt
                ) VALUES (
                    1, 'Legacy Project', 'content://video/1', 'legacy.mp4', 120000, 1280, 720, 10000000,
                    'video/avc', 'audio/mp4a-latm', 2, 48000, NULL, 300,
                    'ON_DEVICE_FREE', 'Natural Conversational Hindi', 'MALE', 'Charon', 1.0,
                    'natural', 1, 1, 'STEREO_MID_SIDE',
                    1.0, 0.85, 0.0, 'CONFIGURING', 'Idle', 0,
                    NULL, NULL, 'ORIGINAL', NULL, 0,
                    0, 1000, 1000
                )
                """.trimIndent()
            )
            AppDatabase.MIGRATION_3_4.migrate(rawDb)
            AppDatabase.MIGRATION_4_5.migrate(rawDb)
            rawDb.execSQL("UPDATE movie_projects SET subtitlePath = '/tmp/sub.srt', subtitleFileName = 'sub.srt' WHERE id = 1")
            rawDb.query("SELECT subtitlePath, subtitleFileName, subtitleLanguage FROM movie_projects WHERE id = 1").use { c ->
                assertEquals(true, c.moveToFirst())
                assertEquals("/tmp/sub.srt", c.getString(0))
                assertEquals("sub.srt", c.getString(1))
                assertEquals(SubtitleLanguage.ENGLISH, c.getString(2))
            }
            rawDb.execSQL("UPDATE movie_projects SET subtitleLanguage = 'HI' WHERE id = 1")
            rawDb.query("SELECT subtitleLanguage FROM movie_projects WHERE id = 1").use { c ->
                assertEquals(true, c.moveToFirst())
                assertEquals(SubtitleLanguage.HINDI, c.getString(0))
            }
        }
        helper.close()

        // Validate in-memory v5 Room schema creation & Hindi subtitle language updates
        val roomDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val id = roomDb.projectDao().insertProject(
            MovieProjectEntity(
                title = "Sub Test",
                sourceUriString = "content://v/2",
                fileName = "v2.mp4",
                durationMs = 60_000L,
                width = 1920,
                height = 1080,
                fileSizeBytes = 1_000_000L,
                videoMime = "video/avc",
                audioMime = "audio/mp4a-latm",
                audioChannels = 2,
                audioSampleRate = 48000,
                thumbnailPath = null,
                subtitlePath = "/files/sub_hi.srt",
                subtitleFileName = "movie_hi.srt",
                subtitleLanguage = SubtitleLanguage.HINDI
            )
        )
        val loaded = roomDb.projectDao().getProject(id)!!
        assertEquals("/files/sub_hi.srt", loaded.subtitlePath)
        assertEquals("movie_hi.srt", loaded.subtitleFileName)
        assertEquals(SubtitleLanguage.HINDI, loaded.subtitleLanguage)
        roomDb.projectDao().updateProject(
            loaded.copy(subtitlePath = null, subtitleFileName = null, subtitleLanguage = SubtitleLanguage.ENGLISH)
        )
        assertNull(roomDb.projectDao().getProject(id)?.subtitlePath)
        assertEquals(SubtitleLanguage.ENGLISH, roomDb.projectDao().getProject(id)?.subtitleLanguage)
        roomDb.close()
        File(dbFile.absolutePath).delete()
    }

    @Test
    fun `MIGRATION_5_6 adds translationSource column and v6 Room persists engine badge`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_5_6_${System.currentTimeMillis()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `dialogue_segments` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `projectId` INTEGER NOT NULL,
                                `chunkIndex` INTEGER NOT NULL,
                                `orderIndex` INTEGER NOT NULL,
                                `startMs` INTEGER NOT NULL,
                                `endMs` INTEGER NOT NULL,
                                `speakerLabel` TEXT NOT NULL,
                                `englishText` TEXT NOT NULL,
                                `hindiText` TEXT NOT NULL,
                                `emotion` TEXT NOT NULL,
                                `isOverlapping` INTEGER NOT NULL,
                                `audioType` TEXT NOT NULL,
                                `speechSpeed` REAL NOT NULL,
                                `ttsAudioPath` TEXT,
                                `ttsDurationMs` INTEGER NOT NULL,
                                `status` TEXT NOT NULL,
                                `errorMessage` TEXT
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        helper.writableDatabase.use { rawDb ->
            rawDb.execSQL(
                """
                INSERT INTO dialogue_segments (
                    id, projectId, chunkIndex, orderIndex, startMs, endMs, speakerLabel,
                    englishText, hindiText, emotion, isOverlapping, audioType,
                    speechSpeed, ttsAudioPath, ttsDurationMs, status, errorMessage
                ) VALUES (
                    1, 10, 0, 0, 0, 1500, 'SPEAKER_1',
                    'Hello', 'नमस्ते', 'neutral', 0, 'SPEECH',
                    1.0, NULL, 0, 'TRANSLATED', NULL
                )
                """.trimIndent()
            )
            AppDatabase.MIGRATION_5_6.migrate(rawDb)
            rawDb.query("SELECT translationSource FROM dialogue_segments WHERE id = 1").use { c ->
                assertEquals(true, c.moveToFirst())
                assertEquals("", c.getString(0))
            }
        }
        helper.close()

        val roomDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val pid = roomDb.projectDao().insertProject(
            MovieProjectEntity(
                title = "Source Test",
                sourceUriString = "content://v/3",
                fileName = "v3.mp4",
                durationMs = 30_000L,
                width = 1280,
                height = 720,
                fileSizeBytes = 500_000L,
                videoMime = "video/avc",
                audioMime = "audio/mp4a-latm",
                audioChannels = 2,
                audioSampleRate = 48000,
                thumbnailPath = null
            )
        )
        roomDb.segmentDao().insertSegments(
            listOf(
                DialogueSegmentEntity(
                    projectId = pid,
                    chunkIndex = 0,
                    orderIndex = 0,
                    startMs = 0L,
                    endMs = 1500L,
                    speakerLabel = "SPEAKER_1",
                    englishText = "Watch out!",
                    hindiText = "संभल के!",
                    translationSource = TranslationSource.LOCAL_GGUF
                )
            )
        )
        val saved = roomDb.segmentDao().getSegments(pid).first()
        assertEquals(TranslationSource.LOCAL_GGUF, saved.translationSource)
        roomDb.close()
        File(dbFile.absolutePath).delete()
    }
}
