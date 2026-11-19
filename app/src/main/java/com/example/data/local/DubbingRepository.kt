package com.example.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class DubbingRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context)
) {
    private val projectDao = database.projectDao()
    private val chunkDao = database.chunkDao()
    private val speakerDao = database.speakerDao()
    private val segmentDao = database.segmentDao()

    fun observeAllProjects(): Flow<List<MovieProjectEntity>> = projectDao.observeAllProjects()

    fun observeProject(projectId: Long): Flow<MovieProjectEntity?> = projectDao.observeProject(projectId)

    fun observeChunks(projectId: Long): Flow<List<ChunkEntity>> = chunkDao.observeChunks(projectId)

    fun observeSpeakers(projectId: Long): Flow<List<SpeakerEntity>> = speakerDao.observeSpeakers(projectId)

    fun observeSegments(projectId: Long): Flow<List<DialogueSegmentEntity>> = segmentDao.observeSegments(projectId)

    suspend fun getProject(projectId: Long): MovieProjectEntity? = projectDao.getProject(projectId)

    suspend fun insertProject(project: MovieProjectEntity): Long = projectDao.insertProject(project)

    suspend fun updateProject(project: MovieProjectEntity) =
        projectDao.updateProject(project.copy(updatedAt = System.currentTimeMillis()))

    suspend fun updateProjectProgress(
        projectId: Long,
        status: String,
        stage: String,
        progress: Int,
        etaMs: Long? = null,
        error: String? = null
    ) = projectDao.updateProgress(projectId, status, stage, progress, etaMs, error)

    suspend fun deleteProject(projectId: Long) {
        withContext(Dispatchers.IO) {
            projectDir(projectId).deleteRecursively()
        }
        projectDao.deleteProject(projectId)
    }

    suspend fun getChunks(projectId: Long): List<ChunkEntity> = chunkDao.getChunks(projectId)

    suspend fun getChunk(projectId: Long, chunkIndex: Int): ChunkEntity? =
        chunkDao.getChunk(projectId, chunkIndex)

    suspend fun insertChunks(chunks: List<ChunkEntity>) = chunkDao.insertChunks(chunks)

    suspend fun updateChunk(chunk: ChunkEntity) = chunkDao.updateChunk(chunk)

    suspend fun deleteChunksForProject(projectId: Long) = chunkDao.deleteChunksForProject(projectId)

    suspend fun getSpeakers(projectId: Long): List<SpeakerEntity> = speakerDao.getSpeakers(projectId)

    suspend fun getSpeaker(projectId: Long, label: String): SpeakerEntity? =
        speakerDao.getSpeaker(projectId, label)

    suspend fun insertSpeakersIgnore(speakers: List<SpeakerEntity>) =
        speakerDao.insertSpeakersIgnore(speakers)

    suspend fun updateSpeaker(speaker: SpeakerEntity) = speakerDao.updateSpeaker(speaker)

    suspend fun getSegments(projectId: Long): List<DialogueSegmentEntity> = segmentDao.getSegments(projectId)

    suspend fun getSegmentsForChunk(projectId: Long, chunkIndex: Int): List<DialogueSegmentEntity> =
        segmentDao.getSegmentsForChunk(projectId, chunkIndex)

    suspend fun getSegmentById(segmentId: Long): DialogueSegmentEntity? =
        segmentDao.getSegmentById(segmentId)

    suspend fun insertSegments(segments: List<DialogueSegmentEntity>) =
        segmentDao.insertSegments(segments)

    suspend fun updateSegment(segment: DialogueSegmentEntity) = segmentDao.updateSegment(segment)

    suspend fun deleteSegmentsForChunk(projectId: Long, chunkIndex: Int) =
        segmentDao.deleteSegmentsForChunk(projectId, chunkIndex)

    suspend fun getFailedSegments(projectId: Long): List<DialogueSegmentEntity> =
        segmentDao.getFailedSegments(projectId)

    fun projectDir(projectId: Long): File =
        File(context.filesDir, "projects/$projectId").apply { mkdirs() }

    fun chunksDir(projectId: Long): File =
        File(projectDir(projectId), "chunks").apply { mkdirs() }

    fun ttsDir(projectId: Long): File =
        File(projectDir(projectId), "tts").apply { mkdirs() }

    fun exportsDir(): File =
        File(context.filesDir, "exports").apply { mkdirs() }

    suspend fun getTempWorkspaceSizeBytes(projectId: Long? = null): Long = withContext(Dispatchers.IO) {
        val root = if (projectId != null) projectDir(projectId) else File(context.filesDir, "projects")
        if (!root.exists()) return@withContext 0L
        root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".wav") || it.name.endsWith(".m4a")) }
            .sumOf { it.length() }
    }

    suspend fun cleanTempFilesForProject(projectId: Long): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        val chunks = chunksDir(projectId)
        val tts = ttsDir(projectId)
        listOf(chunks, tts).forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    freed += f.length()
                    f.delete()
                }
            }
        }
        val project = projectDao.getProject(projectId)
        if (project != null) {
            projectDao.updateProject(
                project.copy(tempFilesCleaned = true, updatedAt = System.currentTimeMillis())
            )
        }
        freed
    }

    suspend fun cleanAllTempFiles(): Long = withContext(Dispatchers.IO) {
        val projectsRoot = File(context.filesDir, "projects")
        if (!projectsRoot.exists()) return@withContext 0L
        var freed = 0L
        projectsRoot.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".wav") || it.name.endsWith(".m4a")) }
            .forEach { file ->
                freed += file.length()
                file.delete()
            }
        freed
    }
}
