package com.example.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.EncryptedTokenStore
import com.example.data.repository.AzureDevOpsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background synchronization coordinator for Azure Boards notifications and work item snapshots
 * backed by AndroidX WorkManager.
 */
object AzureSyncWorker {

    const val WORK_NAME_PERIODIC_SYNC = "azure_boards_periodic_notification_sync"
    const val WORK_NAME_ONE_TIME_SYNC = "azure_boards_immediate_notification_sync"
    private const val PERIODIC_INTERVAL_MINUTES = 15L

    /**
     * Executes a full background notification check across all remembered organizations,
     * posting Android system notifications for any newly assigned work items or `@mentions`.
     */
    suspend fun executeBackgroundSync(
        context: Context,
        repository: AzureDevOpsRepository = AzureDevOpsRepository(
            context.applicationContext ?: context,
            EncryptedTokenStore(context.applicationContext ?: context)
        )
    ): Int = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        var totalTriggered = 0
        val accounts = repository.getSavedOrganizations()
        for (account in accounts) {
            if (account.organization.isBlank() || account.pat.isBlank()) continue
            val cachedSnapshot = repository.getCachedOrganizationSnapshotSync(account.organization)
            val knownItems = cachedSnapshot?.workItems.orEmpty()
            val outcome = repository.syncOrganizationNotifications(
                organization = account.organization,
                pat = account.pat,
                knownWorkItems = knownItems
            )
            outcome.newlyTriggeredNotifications.forEach { notif ->
                NotificationHelper.postSystemNotification(appContext, notif)
                totalTriggered++
            }
        }
        totalTriggered
    }

    /**
     * Schedules periodic 15-minute background sync with network constraint via WorkManager.
     */
    fun schedulePeriodicSync(context: Context) {
        runCatching {
            val appContext = context.applicationContext ?: context
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<AzureNotificationSyncWorker>(
                repeatInterval = PERIODIC_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }

    /**
     * Triggers an immediate one-time sync via WorkManager when network is available.
     */
    fun triggerImmediateSync(context: Context) {
        runCatching {
            val appContext = context.applicationContext ?: context
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<AzureNotificationSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_NAME_ONE_TIME_SYNC,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
        }
    }

    fun cancelPeriodicSync(context: Context) {
        runCatching {
            val appContext = context.applicationContext ?: context
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME_PERIODIC_SYNC)
        }
    }
}
