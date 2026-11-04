package com.example.sync

import android.content.Context

/**
 * Façade delegating background synchronization lifecycle controls to [com.example.notifications.AzureSyncWorker].
 */
object AzureSyncWorker {

    const val WORK_NAME_PERIODIC_SYNC = com.example.notifications.AzureSyncWorker.WORK_NAME_PERIODIC_SYNC
    const val WORK_NAME_ONE_TIME_SYNC = com.example.notifications.AzureSyncWorker.WORK_NAME_ONE_TIME_SYNC

    fun schedulePeriodicSync(context: Context) {
        com.example.notifications.AzureSyncWorker.schedulePeriodicSync(context)
    }

    fun triggerImmediateSync(context: Context) {
        com.example.notifications.AzureSyncWorker.triggerImmediateSync(context)
    }

    fun cancelPeriodicSync(context: Context) {
        com.example.notifications.AzureSyncWorker.cancelPeriodicSync(context)
    }
}
