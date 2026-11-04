package com.example.ui.viewmodel.delegates

import com.example.data.model.WorkItem
import com.example.data.repository.AzureDevOpsRepository
import com.example.di.SessionManager
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.normalizeClassificationPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Delegate managing dirty work item states, debounced update jobs, baseline tracking,
 * and optimistic CRUD synchronization with Azure DevOps.
 */
class WorkItemCrudDelegate(
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val repository: AzureDevOpsRepository,
    private val allWorkItems: MutableStateFlow<List<WorkItem>>,
    private val filterDelegate: WorkItemFilterDelegate,
    private val onSyncSelectedWorkItem: (WorkItem) -> Unit,
    private val onPersistSnapshot: (String?) -> Unit
) {

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

    suspend fun syncWorkItemToServer(itemToSync: WorkItem) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val baseline = getPreviousSyncedItem(itemToSync.id)
        val result = repository.updateWorkItemOnServer(
            organization = org,
            pat = pat,
            previousItem = baseline,
            updatedItem = itemToSync,
            resolvedAssigneeIdentity = itemToSync.assignedToEmail
        )
        if (result.isSuccess) {
            recordServerSyncedItem(itemToSync)
            clearPendingDirty(itemToSync.id)
            repository.saveCachedOrganizationSnapshot(org, allWorkItems.value)
        }
    }

    fun updateWorkItem(updatedItem: WorkItem) {
        val currentList = allWorkItems.value
        val currentInMemory = currentList.firstOrNull { it.id == updatedItem.id }
        if (currentInMemory == updatedItem) return

        val resolvedEmail = when {
            updatedItem.assignedToName.isNullOrBlank() ||
                updatedItem.assignedToName.equals("Unassigned", ignoreCase = true) -> null
            currentInMemory != null &&
                currentInMemory.assignedToName == updatedItem.assignedToName &&
                !currentInMemory.assignedToEmail.isNullOrBlank() -> currentInMemory.assignedToEmail
            !updatedItem.assignedToEmail.isNullOrBlank() -> updatedItem.assignedToEmail
            else -> {
                val targetAssignee = updatedItem.assignedToName.trim()
                currentList.firstOrNull {
                    it.assignedToName?.trim()?.equals(targetAssignee, ignoreCase = true) == true &&
                        !it.assignedToEmail.isNullOrBlank()
                }?.assignedToEmail
            }
        }

        val normalizedUpdated = if (resolvedEmail == updatedItem.assignedToEmail) {
            updatedItem
        } else {
            updatedItem.copy(assignedToEmail = resolvedEmail)
        }
        if (currentInMemory == normalizedUpdated) return

        allWorkItems.value = currentList.map {
            if (it.id == normalizedUpdated.id) normalizedUpdated else it
        }
        onSyncSelectedWorkItem(normalizedUpdated)
        recordPendingDirty(normalizedUpdated)

        val assigneeChanged = currentInMemory == null ||
            currentInMemory.assignedToName != normalizedUpdated.assignedToName
        if (assigneeChanged) {
            val currentNotifState = sessionManager.notificationsState.value
            val effectiveName = currentNotifState.detectedDisplayName
            val detectedEmail = currentNotifState.detectedEmail
            val isStillAssignedToMe = !normalizedUpdated.isUnassigned &&
                (!effectiveName.isNullOrBlank() || !detectedEmail.isNullOrBlank()) &&
                repository.matchesUserIdentity(
                    candidateName = normalizedUpdated.assignedToName,
                    candidateEmail = normalizedUpdated.assignedToEmail,
                    effectiveDisplayName = effectiveName,
                    detectedEmail = detectedEmail
                )
            if (!isStillAssignedToMe) {
                val acc = sessionManager.getActiveAccount()
                val org = acc?.organization ?: sessionManager.defaultOrganization.value ?: repository.getConfiguredUrl()
                if (org.isNotBlank()) {
                    scope.launch {
                        val updatedNotifState = repository.removeAssignedNotificationForWorkItem(org, normalizedUpdated.id)
                        sessionManager.updateNotificationsState(updatedNotifState)
                    }
                }
            }
        }

        val onlyTextChanged = currentInMemory != null &&
            currentInMemory.state == normalizedUpdated.state &&
            currentInMemory.assignedToName == normalizedUpdated.assignedToName &&
            currentInMemory.areaPath == normalizedUpdated.areaPath &&
            currentInMemory.iterationPath == normalizedUpdated.iterationPath &&
            currentInMemory.tags == normalizedUpdated.tags &&
            (currentInMemory.description != normalizedUpdated.description ||
                currentInMemory.title != normalizedUpdated.title)

        if (!onlyTextChanged) {
            onPersistSnapshot(null)
        }

        val job = scope.launch {
            if (onlyTextChanged) {
                delay(400L)
                onPersistSnapshot(null)
            }
            syncWorkItemToServer(normalizedUpdated)
        }
        recordUpdateJob(normalizedUpdated.id, job)
    }

    fun createWorkItem(
        project: String?,
        workItemType: String,
        title: String,
        state: String,
        assignedToName: String?,
        description: String?,
        tags: List<String>,
        areaPath: String? = null,
        iterationPath: String? = null,
        resolveDefaultState: (String, String) -> String,
        onResult: (Result<WorkItem>) -> Unit = {}
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Please enter a title.")))
            return
        }
        val resolvedProject = project?.takeIf { it.isNotBlank() }
            ?: filterDelegate.selectedProject.value?.takeIf { it.isNotBlank() }
            ?: filterDelegate.availableProjects.value.firstOrNull()
            ?: ""
        val resolvedType = workItemType.trim().ifBlank {
            filterDelegate.availableTypes.value.firstOrNull() ?: "Task"
        }
        val resolvedState = state.trim().takeUnless {
            it.isBlank() || it.equals("All", ignoreCase = true)
        } ?: resolveDefaultState(resolvedProject, resolvedType)

        val cleanAssignee = assignedToName?.trim()?.takeUnless {
            it.isEmpty() || it.equals("Unassigned", ignoreCase = true)
        }
        val resolvedEmail = if (cleanAssignee == null) {
            null
        } else {
            allWorkItems.value.firstOrNull {
                it.assignedToName?.trim()?.equals(cleanAssignee, ignoreCase = true) == true &&
                    !it.assignedToEmail.isNullOrBlank()
            }?.assignedToEmail
        }
        val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val cleanDesc = description?.trim()?.takeIf { it.isNotBlank() }
        val resolvedArea = normalizeClassificationPath(areaPath).ifBlank {
            resolvedProject.ifBlank { "" }
        }.ifBlank { null }
        val resolvedIteration = normalizeClassificationPath(iterationPath).ifBlank {
            resolvedProject.ifBlank { "" }
        }.ifBlank { null }

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()

        val nextBoardOrder = (allWorkItems.value.mapNotNull { it.boardOrder }.maxOrNull() ?: 1000.0) + 10.0
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        if (org.isBlank() || pat.isBlank() || resolvedProject.isBlank()) {
            val localId = (allWorkItems.value.maxOfOrNull { it.id } ?: 1000) + 1
            val localCreated = WorkItem(
                id = localId,
                title = trimmedTitle,
                workItemType = resolvedType,
                state = resolvedState,
                teamProject = resolvedProject.ifBlank { null },
                assignedToName = cleanAssignee,
                assignedToEmail = resolvedEmail,
                createdDate = nowIso,
                changedDate = nowIso,
                description = cleanDesc,
                areaPath = resolvedArea,
                iterationPath = resolvedIteration,
                boardOrder = nextBoardOrder,
                tags = cleanTags
            )
            recordServerSyncedItem(localCreated)
            allWorkItems.value = allWorkItems.value + localCreated
            onPersistSnapshot(org)
            filterDelegate.userManuallySelectedType = true
            filterDelegate.setTypeFilter(localCreated.workItemType)
            val currentStateFilter = filterDelegate.stateFilter.value
            if (currentStateFilter != null &&
                !currentStateFilter.equals("All", ignoreCase = true) &&
                !currentStateFilter.equals(localCreated.state, ignoreCase = true)
            ) {
                filterDelegate.setStateFilter("All")
            }
            onResult(Result.success(localCreated))
            return
        }

        scope.launch {
            val result = repository.createWorkItemOnServer(
                organization = org,
                pat = pat,
                project = resolvedProject,
                workItemType = resolvedType,
                title = trimmedTitle,
                desiredState = resolvedState,
                assignedToName = cleanAssignee,
                resolvedAssigneeIdentity = resolvedEmail,
                description = cleanDesc,
                tags = cleanTags,
                areaPath = resolvedArea,
                iterationPath = resolvedIteration
            )
            result.fold(
                onSuccess = { serverCreated ->
                    val finalCreated = serverCreated.copy(
                        areaPath = serverCreated.areaPath ?: resolvedArea,
                        iterationPath = serverCreated.iterationPath ?: resolvedIteration,
                        boardOrder = serverCreated.boardOrder ?: nextBoardOrder
                    )
                    recordServerSyncedItem(finalCreated)
                    allWorkItems.value = (allWorkItems.value.filterNot { it.id == finalCreated.id } + finalCreated)
                    onPersistSnapshot(org)
                    filterDelegate.userManuallySelectedType = true
                    filterDelegate.setTypeFilter(finalCreated.workItemType)
                    val currentStateFilter = filterDelegate.stateFilter.value
                    if (currentStateFilter != null &&
                        !currentStateFilter.equals("All", ignoreCase = true) &&
                        !currentStateFilter.equals(finalCreated.state, ignoreCase = true)
                    ) {
                        filterDelegate.setStateFilter("All")
                    }
                    onResult(Result.success(finalCreated))
                },
                onFailure = { err ->
                    onResult(Result.failure(err))
                }
            )
        }
    }
}
