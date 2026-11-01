package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.data.repository.AzureDevOpsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Periodic background JobService that checks saved Azure DevOps organizations for newly assigned
 * work items and new discussion @mentions, posting device system notifications when found.
 */
class AzureNotificationJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        activeJob = serviceScope.launch {
            try {
                val repository = AzureDevOpsRepository(appContext)
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
                    }
                }
            } catch (_: Exception) {
                // Ignore transient background errors
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
