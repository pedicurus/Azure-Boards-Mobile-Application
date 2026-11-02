package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Periodic background JobService that delegates to [AzureSyncWorker] to check saved
 * Azure DevOps organizations (using encrypted PAT credentials via [AppContainer]) for newly
 * assigned work items and new discussion `@mentions`.
 */
class AzureNotificationJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        activeJob = serviceScope.launch {
            try {
                val container = AppContainer.getInstance(appContext)
                AzureSyncWorker.executeBackgroundSync(
                    context = appContext,
                    repository = container.repository
                )
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
