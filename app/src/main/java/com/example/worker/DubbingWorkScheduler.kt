package com.example.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow

class DubbingWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun startOrResumeDubbing(
        projectId: Long,
        retryFailedOnly: Boolean = false,
        requiresNetwork: Boolean = true
    ) {
        val mode = if (retryFailedOnly) {
            DubbingWorker.MODE_RETRY_FAILED
        } else {
            DubbingWorker.MODE_FULL_PIPELINE
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()

        val request = OneTimeWorkRequestBuilder<DubbingWorker>()
            .setConstraints(constraints)
            .setInputData(
                Data.Builder()
                    .putLong(DubbingWorker.KEY_PROJECT_ID, projectId)
                    .putString(DubbingWorker.KEY_ACTION_MODE, mode)
                    .build()
            )
            .addTag(projectTag(projectId))
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(projectId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun remixLocalAudio(projectId: Long) {
        val request = OneTimeWorkRequestBuilder<DubbingWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(DubbingWorker.KEY_PROJECT_ID, projectId)
                    .putString(DubbingWorker.KEY_ACTION_MODE, DubbingWorker.MODE_REMIX_ONLY)
                    .build()
            )
            .addTag(projectTag(projectId))
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(projectId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelWork(projectId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(projectId))
    }

    fun observeWork(projectId: Long): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(projectId))

    private fun uniqueWorkName(projectId: Long) = "dubbing_project_$projectId"
    private fun projectTag(projectId: Long) = "project_$projectId"
}
