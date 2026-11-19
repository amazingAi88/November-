package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ChunkEntity
import com.example.data.local.ChunkStatus
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.MovieProjectEntity
import com.example.data.local.ProjectStatus
import com.example.data.local.SegmentStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DubbingRepositoryRobolectricTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `long 120 minute movie splits into 40 resumable 3-minute chunks and persists segments`() = runTest {
        val duration120MinMs = 120L * 60L * 1000L
        val projectId = db.projectDao().insertProject(
            MovieProjectEntity(
                title = "Feature Film Test",
                sourceUriString = "content://test/movie.mp4",
                fileName = "movie.mp4",
                durationMs = duration120MinMs,
                width = 1920,
                height = 1080,
                fileSizeBytes = 2_400_000_000L,
                videoMime = "video/avc",
                audioMime = "audio/mp4a-latm",
                audioChannels = 2,
                audioSampleRate = 48000,
                thumbnailPath = null,
                chunkDurationSec = 180
            )
        )

        val chunkDurationMs = 180_000L
        val chunks = mutableListOf<ChunkEntity>()
        var start = 0L
        var idx = 0
        while (start < duration120MinMs) {
            val end = minOf(start + chunkDurationMs, duration120MinMs)
            chunks.add(
                ChunkEntity(
                    projectId = projectId,
                    chunkIndex = idx,
                    startMs = start,
                    endMs = end,
                    status = if (idx == 0) ChunkStatus.MIXED else ChunkStatus.PENDING
                )
            )
            start = end
            idx++
        }
        db.chunkDao().insertChunks(chunks)

        val savedChunks = db.chunkDao().getChunks(projectId)
        assertEquals(40, savedChunks.size)
        assertEquals(0L, savedChunks.first().startMs)
        assertEquals(duration120MinMs, savedChunks.last().endMs)
        assertEquals(ChunkStatus.MIXED, savedChunks.first().status)

        db.segmentDao().insertSegments(
            listOf(
                DialogueSegmentEntity(
                    projectId = projectId,
                    chunkIndex = 1,
                    orderIndex = 0,
                    startMs = 185_000L,
                    endMs = 188_500L,
                    speakerLabel = "SPEAKER_1",
                    englishText = "We need to leave before sunrise.",
                    hindiText = "हमें सूरज उगने से पहले निकलना होगा।",
                    status = SegmentStatus.FAILED,
                    errorMessage = "HTTP 429 rate limit"
                )
            )
        )

        db.projectDao().updateProgress(
            projectId = projectId,
            status = ProjectStatus.PAUSED,
            stage = "Paused at chunk 2",
            progress = 3,
            etaMs = null,
            error = null
        )

        val reloaded = db.projectDao().getProject(projectId)
        assertNotNull(reloaded)
        assertEquals(ProjectStatus.PAUSED, reloaded?.status)
        assertEquals(1, db.segmentDao().getFailedSegments(projectId).size)
    }

    @Test
    fun `project persists selected Hindi voice model preset before applying to dubbing`() = runTest {
        val projectId = db.projectDao().insertProject(
            MovieProjectEntity(
                title = "Voice Model Test",
                sourceUriString = "content://test/sample.mkv",
                fileName = "sample.mkv",
                durationMs = 120_000L,
                width = 1280,
                height = 720,
                fileSizeBytes = 25_000_000L,
                videoMime = "video/avc",
                audioMime = "audio/mp4a-latm",
                audioChannels = 2,
                audioSampleRate = 48000,
                thumbnailPath = null
            )
        )
        val initial = db.projectDao().getProject(projectId)!!
        assertEquals("Charon", initial.defaultVoicePreset)

        db.projectDao().updateProject(
            initial.copy(
                defaultVoiceGender = "FEMALE",
                defaultVoicePreset = "Aoede"
            )
        )

        val updated = db.projectDao().getProject(projectId)!!
        assertEquals("FEMALE", updated.defaultVoiceGender)
        assertEquals("Aoede", updated.defaultVoicePreset)
    }
}
