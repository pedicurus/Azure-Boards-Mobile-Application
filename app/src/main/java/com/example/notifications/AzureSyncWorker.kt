package com.example.notifications

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.example.data.local.EncryptedTokenStore
import com.example.data.repository.AzureDevOpsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background synchronization coordinator for Azure Boards notifications and work item snapshots.
 *
 * Works with [AzureNotificationJobService] for periodic OS-scheduled background checks (every 15 minutes)
 * and reads saved organization credentials decrypted through [EncryptedTokenStore].
 */
object AzureSyncWorker {

    const val BACKGROUND_SYNC_JOB_ID = 44019
    private const val PERIODIC_INTERVAL_MS = 15L * 60L * 1000L // 15 minutes

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
     * Schedules periodic 15-minute background sync with network constraint via [JobScheduler].
     */
    fun schedulePeriodicSync(context: Context) {
        runCatching {
            val appContext = context.applicationContext ?: context
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            val existing = scheduler.allPendingJobs.any { it.id == BACKGROUND_SYNC_JOB_ID }
            if (existing) return

            val component = ComponentName(appContext, AzureNotificationJobService::class.java)
            val jobInfo = JobInfo.Builder(BACKGROUND_SYNC_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIODIC_INTERVAL_MS)
                .build()
            scheduler.schedule(jobInfo)
        }
    }
}
