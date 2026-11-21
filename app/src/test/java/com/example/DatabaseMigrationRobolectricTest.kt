package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.MovieProjectEntity
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
    fun `MIGRATION_3_4 adds nullable subtitlePath and subtitleFileName columns cleanly`() = runTest {
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
            rawDb.execSQL("UPDATE movie_projects SET subtitlePath = '/tmp/sub.srt', subtitleFileName = 'sub.srt' WHERE id = 1")
            rawDb.query("SELECT subtitlePath, subtitleFileName FROM movie_projects WHERE id = 1").use { c ->
                assertEquals(true, c.moveToFirst())
                assertEquals("/tmp/sub.srt", c.getString(0))
                assertEquals("sub.srt", c.getString(1))
            }
        }
        helper.close()

        // Also validate in-memory v4 Room schema creation & subtitle updates
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
                subtitlePath = "/files/sub.srt",
                subtitleFileName = "movie_en.srt"
            )
        )
        val loaded = roomDb.projectDao().getProject(id)!!
        assertEquals("/files/sub.srt", loaded.subtitlePath)
        assertEquals("movie_en.srt", loaded.subtitleFileName)
        roomDb.projectDao().updateProject(loaded.copy(subtitlePath = null, subtitleFileName = null))
        assertNull(roomDb.projectDao().getProject(id)?.subtitlePath)
        roomDb.close()
        File(dbFile.absolutePath).delete()
    }
}
