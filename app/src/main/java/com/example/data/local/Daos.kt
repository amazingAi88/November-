package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM movie_projects ORDER BY updatedAt DESC")
    fun observeAllProjects(): Flow<List<MovieProjectEntity>>

    @Query("SELECT * FROM movie_projects WHERE id = :projectId")
    fun observeProject(projectId: Long): Flow<MovieProjectEntity?>

    @Query("SELECT * FROM movie_projects WHERE id = :projectId")
    suspend fun getProject(projectId: Long): MovieProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: MovieProjectEntity): Long

    @Update
    suspend fun updateProject(project: MovieProjectEntity)

    @Query("DELETE FROM movie_projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Query(
        "UPDATE movie_projects SET status = :status, currentStage = :stage, " +
            "progressPercent = :progress, estimatedRemainingMs = :etaMs, " +
            "lastError = :error, updatedAt = :updatedAt WHERE id = :projectId"
    )
    suspend fun updateProgress(
        projectId: Long,
        status: String,
        stage: String,
        progress: Int,
        etaMs: Long?,
        error: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM audio_chunks WHERE projectId = :projectId ORDER BY chunkIndex ASC")
    fun observeChunks(projectId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM audio_chunks WHERE projectId = :projectId ORDER BY chunkIndex ASC")
    suspend fun getChunks(projectId: Long): List<ChunkEntity>

    @Query("SELECT * FROM audio_chunks WHERE projectId = :projectId AND chunkIndex = :chunkIndex LIMIT 1")
    suspend fun getChunk(projectId: Long, chunkIndex: Int): ChunkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Update
    suspend fun updateChunk(chunk: ChunkEntity)

    @Query("DELETE FROM audio_chunks WHERE projectId = :projectId")
    suspend fun deleteChunksForProject(projectId: Long)
}

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers WHERE projectId = :projectId ORDER BY speakerLabel ASC")
    fun observeSpeakers(projectId: Long): Flow<List<SpeakerEntity>>

    @Query("SELECT * FROM speakers WHERE projectId = :projectId ORDER BY speakerLabel ASC")
    suspend fun getSpeakers(projectId: Long): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE projectId = :projectId AND speakerLabel = :label LIMIT 1")
    suspend fun getSpeaker(projectId: Long, label: String): SpeakerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpeakersIgnore(speakers: List<SpeakerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpeaker(speaker: SpeakerEntity)

    @Update
    suspend fun updateSpeaker(speaker: SpeakerEntity)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM dialogue_segments WHERE projectId = :projectId ORDER BY startMs ASC, orderIndex ASC")
    fun observeSegments(projectId: Long): Flow<List<DialogueSegmentEntity>>

    @Query("SELECT * FROM dialogue_segments WHERE projectId = :projectId ORDER BY startMs ASC, orderIndex ASC")
    suspend fun getSegments(projectId: Long): List<DialogueSegmentEntity>

    @Query("SELECT * FROM dialogue_segments WHERE projectId = :projectId AND chunkIndex = :chunkIndex ORDER BY startMs ASC, orderIndex ASC")
    suspend fun getSegmentsForChunk(projectId: Long, chunkIndex: Int): List<DialogueSegmentEntity>

    @Query("SELECT * FROM dialogue_segments WHERE id = :segmentId")
    suspend fun getSegmentById(segmentId: Long): DialogueSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<DialogueSegmentEntity>)

    @Update
    suspend fun updateSegment(segment: DialogueSegmentEntity)

    @Query("DELETE FROM dialogue_segments WHERE projectId = :projectId AND chunkIndex = :chunkIndex")
    suspend fun deleteSegmentsForChunk(projectId: Long, chunkIndex: Int)

    @Query("SELECT * FROM dialogue_segments WHERE projectId = :projectId AND status = 'FAILED' ORDER BY startMs ASC")
    suspend fun getFailedSegments(projectId: Long): List<DialogueSegmentEntity>
}
