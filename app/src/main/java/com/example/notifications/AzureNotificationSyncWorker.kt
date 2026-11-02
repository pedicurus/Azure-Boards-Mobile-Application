package com.example.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.di.AppContainer

/**
 * AndroidX WorkManager [CoroutineWorker] that executes 15-minute background synchronization
 * of Azure Boards notifications (assigned work items and @mentions) using saved encrypted PAT credentials.
 */
class AzureNotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val container = AppContainer.getInstance(applicationContext)
            AzureSyncWorker.executeBackgroundSync(
                context = applicationContext,
                repository = container.repository
            )
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
