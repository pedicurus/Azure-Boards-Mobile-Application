package com.example.ui.viewmodel.delegates

import com.example.data.model.WorkItem
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Delegate managing dirty work item states, debounced update jobs, and baseline tracking
 * for JSON Patch synchronization with Azure DevOps.
 */
class WorkItemCrudDelegate {

    private val serverSyncedItems = ConcurrentHashMap<Int, WorkItem>()
    private val pendingDirtyItems = ConcurrentHashMap<Int, WorkItem>()
    private val updateJobs = ConcurrentHashMap<Int, Job>()

    fun recordServerSyncedItem(item: WorkItem) {
        serverSyncedItems[item.id] = item
    }

    fun recordServerSyncedItems(items: Collection<WorkItem>) {
        items.forEach { serverSyncedItems[it.id] = it }
    }

    fun getPreviousSyncedItem(id: Int): WorkItem? = serverSyncedItems[id]

    fun recordPendingDirty(item: WorkItem) {
        pendingDirtyItems[item.id] = item
    }

    fun clearPendingDirty(id: Int) {
        pendingDirtyItems.remove(id)
    }

    fun isPendingDirty(id: Int): Boolean = pendingDirtyItems.containsKey(id)

    fun getPendingDirtyItems(): Map<Int, WorkItem> = pendingDirtyItems.toMap()

    fun recordUpdateJob(id: Int, job: Job) {
        updateJobs[id]?.cancel()
        updateJobs[id] = job
    }

    fun cancelUpdateJob(id: Int) {
        updateJobs.remove(id)?.cancel()
    }

    fun clearAll() {
        updateJobs.values.forEach { it.cancel() }
        updateJobs.clear()
        pendingDirtyItems.clear()
        serverSyncedItems.clear()
    }
}
