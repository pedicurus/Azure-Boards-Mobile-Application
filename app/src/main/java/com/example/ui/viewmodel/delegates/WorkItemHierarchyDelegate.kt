package com.example.ui.viewmodel.delegates

import android.app.Application
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.data.repository.AzureDevOpsRepository
import com.example.di.SessionManager
import com.example.notifications.NotificationHelper
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findBacklogLevels
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findCategoryMap
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProcessHierarchy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Delegate managing detail selection, child work item relationships, discussion comments,
 * and hierarchy type resolution rules.
 */
class WorkItemHierarchyDelegate(
    private val application: Application,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val repository: AzureDevOpsRepository,
    private val allWorkItems: MutableStateFlow<List<WorkItem>>,
    private val filterDelegate: WorkItemFilterDelegate,
    private val processDelegate: WorkItemProcessDelegate,
    private val crudDelegate: WorkItemCrudDelegate,
    private val onPersistSnapshot: (String?) -> Unit
) {

    private val _selectedWorkItem = MutableStateFlow<WorkItem?>(null)
    val selectedWorkItem: StateFlow<WorkItem?> = _selectedWorkItem.asStateFlow()

    private val _selectedWorkItemComments = MutableStateFlow<List<WorkItemComment>>(emptyList())
    val selectedWorkItemComments: StateFlow<List<WorkItemComment>> = _selectedWorkItemComments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow(false)
    val isLoadingComments: StateFlow<Boolean> = _isLoadingComments.asStateFlow()

    private val commentsByWorkItemId = ConcurrentHashMap<Int, List<WorkItemComment>>()
    private val statesForTypeCache = ConcurrentHashMap<String, List<String>>()
    private var lastProcessMapSnapshot: Map<String, Map<String, List<String>>> = processDelegate.projectProcessMap.value
    private var activeCommentsJob: Job? = null

    init {
        scope.launch {
            processDelegate.projectProcessMap.collect { newMap ->
                lastProcessMapSnapshot = newMap
                statesForTypeCache.clear()
            }
        }
    }

    val selectedWorkItemChildren: StateFlow<List<WorkItem>> = combine(
        _selectedWorkItem,
        allWorkItems
    ) { selected, allItems ->
        if (selected == null) {
            emptyList()
        } else {
            val childIdSet = selected.childIds.toHashSet()
            allItems.filter { candidate ->
                candidate.id != selected.id &&
                    (candidate.parentId == selected.id || candidate.id in childIdSet)
            }.sortedWith(
                compareBy<WorkItem>(
                    { it.boardOrder ?: Double.MAX_VALUE },
                    { it.id }
                )
            )
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedWorkItemParent: StateFlow<WorkItem?> = combine(
        _selectedWorkItem,
        allWorkItems
    ) { selected, allItems ->
        val pId = selected?.parentId
        if (pId == null) {
            null
        } else {
            allItems.firstOrNull { it.id == pId } ?: WorkItem(
                id = pId,
                title = "Work Item #$pId",
                workItemType = "",
                state = "",
                teamProject = selected.teamProject
            )
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    private val _detailNavigationHistory = MutableStateFlow<List<WorkItem>>(emptyList())
    val detailNavigationHistory: StateFlow<List<WorkItem>> = _detailNavigationHistory.asStateFlow()

    fun setSelectedWorkItem(item: WorkItem?) {
        _selectedWorkItem.value = item
        if (item == null) {
            _detailNavigationHistory.value = emptyList()
            _selectedWorkItemComments.value = emptyList()
            _isLoadingComments.value = false
        }
    }

    fun syncSelectedIfMatches(updatedItem: WorkItem) {
        if (_selectedWorkItem.value?.id == updatedItem.id) {
            _selectedWorkItem.value = updatedItem
        }
        if (_detailNavigationHistory.value.any { it.id == updatedItem.id }) {
            _detailNavigationHistory.value = _detailNavigationHistory.value.map {
                if (it.id == updatedItem.id) updatedItem else it
            }
        }
    }

    fun selectWorkItem(item: WorkItem?) {
        if (item == null) {
            _detailNavigationHistory.value = emptyList()
            setSelectedWorkItem(null)
            activeCommentsJob?.cancel()
            return
        }
        _detailNavigationHistory.value = emptyList()
        activateWorkItemInDetail(item)
    }

    fun openWorkItemInDetailHierarchy(targetItem: WorkItem) {
        val current = _selectedWorkItem.value
        if (current != null && current.id != targetItem.id) {
            val latestCurrent = allWorkItems.value.firstOrNull { it.id == current.id } ?: current
            _detailNavigationHistory.value = _detailNavigationHistory.value + latestCurrent
        }
        val resolvedTarget = allWorkItems.value.firstOrNull {
            it.id == targetItem.id && it.workItemType.isNotBlank()
        } ?: targetItem
        activateWorkItemInDetail(resolvedTarget)
    }

    fun navigateBackInDetail() {
        val currentHistory = _detailNavigationHistory.value
        if (currentHistory.isNotEmpty()) {
            val previousSnapshot = currentHistory.last()
            _detailNavigationHistory.value = currentHistory.dropLast(1)
            val resolvedPrevious = allWorkItems.value.firstOrNull {
                it.id == previousSnapshot.id && it.workItemType.isNotBlank()
            } ?: previousSnapshot
            activateWorkItemInDetail(resolvedPrevious)
        } else {
            selectWorkItem(null)
        }
    }

    fun dismissDetail() {
        selectWorkItem(null)
    }

    private fun activateWorkItemInDetail(item: WorkItem) {
        if (filterDelegate.searchQuery.value.isNotEmpty()) {
            filterDelegate.setSearchQuery("")
        }
        setSelectedWorkItem(item)
        activeCommentsJob?.cancel()

        val cachedComments = commentsByWorkItemId[item.id].orEmpty()
        _selectedWorkItemComments.value = cachedComments

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        val project = item.teamProject ?: filterDelegate.selectedProject.value ?: ""

        if (org.isNotBlank()) {
            scope.launch {
                val updatedState = repository.markNotificationsReadForWorkItem(org, item.id)
                sessionManager.updateNotificationsState(updatedState)
            }
        }

        // If the selected item is a stub (e.g. unloaded parent) or its linked parent is missing from allWorkItems,
        // hydrate it on demand so all fields and parent/child cards are up to date.
        if (org.isNotBlank() && pat.isNotBlank()) {
            val loadedIds = allWorkItems.value.asSequence().filter { it.workItemType.isNotBlank() }.map { it.id }.toSet()
            val missingIdsToFetch = buildList {
                if (item.id !in loadedIds || item.workItemType.isBlank()) add(item.id)
                val pId = item.parentId
                if (pId != null && pId !in loadedIds) add(pId)
                item.childIds.forEach { cId ->
                    if (cId !in loadedIds) add(cId)
                }
            }.distinct()

            if (missingIdsToFetch.isNotEmpty()) {
                scope.launch {
                    missingIdsToFetch.forEach { missingId ->
                        val searchRes = repository.searchWorkItemsOnServer(
                            organization = org,
                            pat = pat,
                            query = "#$missingId",
                            project = project.takeIf { it.isNotBlank() }
                        )
                        searchRes.onSuccess { fetchedList ->
                            if (fetchedList.isNotEmpty()) {
                                sessionManager.onMergeHydratedWorkItemsRequested?.invoke(fetchedList)
                                val hydratedTarget = allWorkItems.value.firstOrNull { it.id == item.id }
                                    ?: fetchedList.firstOrNull { it.id == item.id }
                                if (hydratedTarget != null && _selectedWorkItem.value?.id == item.id) {
                                    _selectedWorkItem.value = hydratedTarget
                                }
                            }
                        }
                    }
                }
            }
        }

        if (org.isBlank() || pat.isBlank() || project.isBlank()) {
            _isLoadingComments.value = false
            return
        }

        activeCommentsJob = scope.launch {
            if (cachedComments.isEmpty()) {
                _isLoadingComments.value = true
            }
            val result = repository.fetchWorkItemComments(
                organization = org,
                pat = pat,
                project = project,
                workItemId = item.id
            )
            result.onSuccess { serverComments ->
                val pendingLocal = commentsByWorkItemId[item.id]
                    .orEmpty()
                    .filter { it.id < 0 && serverComments.none { s -> s.text == it.text } }
                val merged = (pendingLocal + serverComments)
                    .distinctBy { if (it.id > 0) "id_${it.id}" else "local_${it.id}_${it.text}" }
                val sorted = merged.sortedWith(
                    compareByDescending<WorkItemComment> { it.createdDate }
                        .thenByDescending { if (it.id < 0) Int.MAX_VALUE else it.id }
                )
                commentsByWorkItemId[item.id] = sorted
                if (_selectedWorkItem.value?.id == item.id) {
                    _selectedWorkItemComments.value = sorted
                }

                val mentionOutcome = repository.evaluateWorkItemCommentsForMentions(
                    organization = org,
                    workItem = item,
                    comments = serverComments,
                    pat = pat
                )
                if (mentionOutcome.newlyTriggeredNotifications.isNotEmpty()) {
                    val appContext = application.applicationContext
                    mentionOutcome.newlyTriggeredNotifications.forEach { notif ->
                        NotificationHelper.postSystemNotification(appContext, notif)
                    }
                }
                if (mentionOutcome.state.organization.isNotBlank()) {
                    val markedReadState = repository.markNotificationsReadForWorkItem(org, item.id)
                    sessionManager.updateNotificationsState(markedReadState)
                }
            }
            if (_selectedWorkItem.value?.id == item.id) {
                _isLoadingComments.value = false
            }
        }
    }

    fun addCommentToWorkItem(
        workItem: WorkItem,
        commentText: String,
        onResult: (Result<WorkItemComment>) -> Unit = {}
    ) {
        val trimmed = commentText.trim()
        if (trimmed.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Comment cannot be empty.")))
            return
        }

        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val formattedNow = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US).format(Date())
        val tempId = -((System.currentTimeMillis() % 1_000_000L).toInt().coerceAtLeast(1))

        val optimisticComment = WorkItemComment(
            id = tempId,
            workItemId = workItem.id,
            text = trimmed,
            authorName = "You",
            authorEmail = null,
            authorAvatar = null,
            createdDate = nowIso,
            formattedDate = formattedNow
        )

        val currentList = commentsByWorkItemId[workItem.id].orEmpty()
        val updatedOptimistic = listOf(optimisticComment) + currentList
        commentsByWorkItemId[workItem.id] = updatedOptimistic
        if (_selectedWorkItem.value?.id == workItem.id) {
            _selectedWorkItemComments.value = updatedOptimistic
        }

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        val project = workItem.teamProject ?: filterDelegate.selectedProject.value ?: ""

        if (org.isBlank() || pat.isBlank() || project.isBlank()) {
            onResult(Result.success(optimisticComment))
            return
        }

        scope.launch {
            val result = repository.addWorkItemCommentOnServer(
                organization = org,
                pat = pat,
                project = project,
                workItemId = workItem.id,
                commentText = trimmed
            )
            result.fold(
                onSuccess = { serverComment ->
                    repository.recordAuthenticatedUserIdentity(
                        organization = org,
                        userId = serverComment.authorId,
                        displayName = serverComment.authorName,
                        email = serverComment.authorEmail
                    )

                    val existing = commentsByWorkItemId[workItem.id].orEmpty()
                    val replaced = existing.map { c ->
                        if (c.id == tempId) serverComment else c
                    }.distinctBy { if (it.id > 0) "id_${it.id}" else "local_${it.id}" }
                    commentsByWorkItemId[workItem.id] = replaced
                    if (_selectedWorkItem.value?.id == workItem.id) {
                        _selectedWorkItemComments.value = replaced
                    }

                    val mentionOutcome = repository.evaluateWorkItemCommentsForMentions(
                        organization = org,
                        workItem = workItem,
                        comments = replaced,
                        allowSystemNotification = true,
                        pat = pat
                    )
                    if (mentionOutcome.newlyTriggeredNotifications.isNotEmpty()) {
                        val appContext = application.applicationContext
                        mentionOutcome.newlyTriggeredNotifications.forEach { notif ->
                            NotificationHelper.postSystemNotification(appContext, notif)
                        }
                    }
                    if (mentionOutcome.state.organization.isNotBlank()) {
                        sessionManager.updateNotificationsState(mentionOutcome.state)
                    }

                    onResult(Result.success(serverComment))
                },
                onFailure = { err ->
                    onResult(Result.failure(err))
                }
            )
        }
    }

    fun getStatesForWorkItem(item: WorkItem): List<String> {
        return getStatesForType(item.teamProject, item.workItemType, item.state)
    }

    fun getStatesForType(
        project: String?,
        workItemType: String,
        currentState: String? = null
    ): List<String> {
        val currentProcessMap = processDelegate.projectProcessMap.value
        if (currentProcessMap !== lastProcessMapSnapshot) {
            lastProcessMapSnapshot = currentProcessMap
            statesForTypeCache.clear()
        }

        val cacheKey = "${project.orEmpty().lowercase()}|${workItemType.trim().lowercase()}|${currentState.orEmpty().trim().lowercase()}"
        statesForTypeCache[cacheKey]?.let { return it }

        val resolvedProj = (project ?: filterDelegate.selectedProject.value).orEmpty()
        val activeOrg = sessionManager.getActiveOrganization()
        val allItems = allWorkItems.value
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProj, currentProcessMap)
        val fromProcess = processMapForProj?.entries
            ?.firstOrNull { it.key.equals(workItemType, ignoreCase = true) }?.value
        if (!fromProcess.isNullOrEmpty()) {
            val result = fromProcess.distinctBy { it.lowercase() }
            statesForTypeCache[cacheKey] = result
            return result
        }
        val fromItems = allItems.filter {
            it.teamProject?.equals(resolvedProj, ignoreCase = true) == true &&
                it.workItemType.equals(workItemType, ignoreCase = true)
        }.map { it.state }.distinct()
        val baseStates = if (fromItems.isNotEmpty()) fromItems else repository.getStandardProcessStates(workItemType)
        val result = if (!currentState.isNullOrBlank()) {
            (baseStates + currentState).distinctBy { it.lowercase() }
        } else {
            baseStates.distinctBy { it.lowercase() }
        }
        statesForTypeCache[cacheKey] = result
        return result
    }

    fun getAllowedChildTypesForWorkItem(item: WorkItem): List<String> {
        return getAllowedChildTypesForType(item.teamProject, item.workItemType)
    }

    fun getAllowedChildTypesForType(
        project: String?,
        workItemType: String
    ): List<String> {
        val cleanType = workItemType.trim()
        if (cleanType.isBlank()) return emptyList()

        val allItems = allWorkItems.value
        val resolvedProject = filterDelegate.resolveEffectiveProject(
            project ?: filterDelegate.selectedProject.value,
            allItems
        ).orEmpty()
        val activeOrg = sessionManager.getActiveOrganization()
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProject, processDelegate.projectProcessMap.value)
        val activeTypesFromProcess = processMapForProj?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
            .orEmpty()

        val projectItems = allItems.filter {
            resolvedProject.isBlank() || it.teamProject?.equals(resolvedProject, ignoreCase = true) == true
        }
        val activeTypesFromItems = projectItems.map { it.workItemType }
            .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            .distinct()

        val candidateActiveTypes = (activeTypesFromProcess + activeTypesFromItems).distinctBy { it.lowercase() }
        if (candidateActiveTypes.isEmpty()) return emptyList()

        val categoryMapForProj = findCategoryMap(activeOrg, resolvedProject, processDelegate.projectCategoryMap.value).orEmpty()
        val backlogLevelsForProj = findBacklogLevels(activeOrg, resolvedProject, processDelegate.projectBacklogLevelsMap.value).orEmpty()

        val orderedBacklogLevels = repository.resolveOrderedBacklogLevels(
            activeTypes = candidateActiveTypes,
            categoryMap = categoryMapForProj,
            explicitBacklogLevels = backlogLevelsForProj,
            projectWorkItems = projectItems
        )
        if (orderedBacklogLevels.size <= 1) return emptyList()

        val parentLevelIdx = orderedBacklogLevels.indexOfFirst { level ->
            level.any { it.equals(cleanType, ignoreCase = true) }
        }
        if (parentLevelIdx < 0 || parentLevelIdx >= orderedBacklogLevels.lastIndex) {
            return emptyList()
        }

        val nextLevelTypes = orderedBacklogLevels[parentLevelIdx + 1]
        return nextLevelTypes.filterNot { it.equals(cleanType, ignoreCase = true) }
    }

    fun getAllowedParentTypesForWorkItem(item: WorkItem): List<String> {
        return getAllowedParentTypesForType(item.teamProject, item.workItemType)
    }

    fun getAllowedParentTypesForType(
        project: String?,
        workItemType: String
    ): List<String> {
        val cleanType = workItemType.trim()
        if (cleanType.isBlank()) return emptyList()

        val allItems = allWorkItems.value
        val resolvedProject = filterDelegate.resolveEffectiveProject(
            project ?: filterDelegate.selectedProject.value,
            allItems
        ).orEmpty()
        val activeOrg = sessionManager.getActiveOrganization()
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProject, processDelegate.projectProcessMap.value)
        val activeTypesFromProcess = processMapForProj?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
            .orEmpty()

        val projectItems = allItems.filter {
            resolvedProject.isBlank() || it.teamProject?.equals(resolvedProject, ignoreCase = true) == true
        }
        val activeTypesFromItems = projectItems.map { it.workItemType }
            .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            .distinct()

        val candidateActiveTypes = (activeTypesFromProcess + activeTypesFromItems).distinctBy { it.lowercase() }
        if (candidateActiveTypes.isEmpty()) return emptyList()

        val categoryMapForProj = findCategoryMap(activeOrg, resolvedProject, processDelegate.projectCategoryMap.value).orEmpty()
        val backlogLevelsForProj = findBacklogLevels(activeOrg, resolvedProject, processDelegate.projectBacklogLevelsMap.value).orEmpty()

        val orderedBacklogLevels = repository.resolveOrderedBacklogLevels(
            activeTypes = candidateActiveTypes,
            categoryMap = categoryMapForProj,
            explicitBacklogLevels = backlogLevelsForProj,
            projectWorkItems = projectItems
        )
        if (orderedBacklogLevels.isEmpty()) return emptyList()

        val currentLevelIdx = orderedBacklogLevels.indexOfFirst { level ->
            level.any { it.equals(cleanType, ignoreCase = true) }
        }
        if (currentLevelIdx <= 0) {
            return emptyList()
        }

        val parentLevelTypes = orderedBacklogLevels[currentLevelIdx - 1]
        return parentLevelTypes.filterNot { it.equals(cleanType, ignoreCase = true) }
    }

    fun canWorkItemHaveParent(item: WorkItem): Boolean {
        return getAllowedParentTypesForWorkItem(item).isNotEmpty()
    }

    fun createParentWorkItem(
        child: WorkItem,
        title: String,
        parentType: String,
        initialState: String
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return

        val allowedParentTypes = getAllowedParentTypesForWorkItem(child)
        if (allowedParentTypes.isEmpty()) return

        val project = child.teamProject ?: filterDelegate.selectedProject.value ?: ""
        val resolvedType = parentType.takeIf { candidate ->
            candidate.isNotBlank() && allowedParentTypes.any { it.equals(candidate, ignoreCase = true) }
        } ?: allowedParentTypes.first()
        val resolvedState = initialState.ifBlank {
            getStatesForType(project, resolvedType).firstOrNull() ?: "New"
        }

        val tempId = (allWorkItems.value.maxOfOrNull { it.id } ?: 1000) + 1
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        val optimisticParent = WorkItem(
            id = tempId,
            title = trimmedTitle,
            workItemType = resolvedType,
            state = resolvedState,
            teamProject = project,
            createdDate = nowIso,
            changedDate = nowIso,
            areaPath = child.areaPath ?: project,
            iterationPath = child.iterationPath ?: project,
            childIds = listOf(child.id)
        )

        val updatedChild = child.copy(parentId = tempId)
        val oldParentId = child.parentId

        allWorkItems.value = (allWorkItems.value.map { item ->
            when (item.id) {
                child.id -> updatedChild
                oldParentId -> item.copy(childIds = item.childIds.filterNot { it == child.id })
                else -> item
            }
        } + optimisticParent)
        onPersistSnapshot(null)

        if (_selectedWorkItem.value?.id == child.id) {
            setSelectedWorkItem(updatedChild)
        }

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        scope.launch {
            val result = repository.createParentWorkItemOnServer(
                organization = org,
                pat = pat,
                project = project,
                childId = child.id,
                title = trimmedTitle,
                parentType = resolvedType,
                desiredState = resolvedState
            )
            result.onSuccess { createdServerParent ->
                val finalParent = createdServerParent.copy(
                    childIds = (createdServerParent.childIds + child.id).distinct()
                )
                crudDelegate.clearPendingDirty(tempId)
                crudDelegate.recordServerSyncedItem(finalParent)

                allWorkItems.value = allWorkItems.value.map { item ->
                    when (item.id) {
                        tempId -> finalParent
                        child.id -> {
                            val newChild = item.copy(parentId = finalParent.id)
                            crudDelegate.recordServerSyncedItem(newChild)
                            newChild
                        }
                        else -> item
                    }
                }
                onPersistSnapshot(org)
                if (_selectedWorkItem.value?.id == child.id) {
                    setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == child.id })
                }
            }
        }
    }

    fun linkExistingParentWorkItem(
        child: WorkItem,
        parentId: Int,
        onResult: (Result<WorkItem>) -> Unit = {}
    ) {
        val allowedParentTypes = getAllowedParentTypesForWorkItem(child)
        if (allowedParentTypes.isEmpty()) {
            onResult(
                Result.failure(
                    IllegalArgumentException("${child.workItemType} is at the highest backlog level and cannot have a parent.")
                )
            )
            return
        }
        if (parentId <= 0) {
            onResult(Result.failure(IllegalArgumentException("Please enter a valid work item ID.")))
            return
        }
        if (parentId == child.id) {
            onResult(Result.failure(IllegalArgumentException("A work item cannot be its own parent.")))
            return
        }
        if (parentId in child.childIds) {
            onResult(Result.failure(IllegalArgumentException("Work item #$parentId is a child of this work item.")))
            return
        }
        if (parentId == child.parentId) {
            onResult(Result.failure(IllegalArgumentException("Work item #$parentId is already the parent.")))
            return
        }

        val byId = allWorkItems.value.associateBy { it.id }
        var cursorId: Int? = parentId
        val visited = mutableSetOf<Int>()
        while (cursorId != null && visited.add(cursorId)) {
            if (cursorId == child.id) {
                onResult(
                    Result.failure(
                        IllegalArgumentException("Work item #$parentId is a descendant of #${child.id} and cannot be set as its parent.")
                    )
                )
                return
            }
            cursorId = byId[cursorId]?.parentId
        }

        val existingInMemory = byId[parentId]
        val oldParentId = child.parentId
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()

        if (existingInMemory != null) {
            val updatedChild = child.copy(parentId = parentId)
            allWorkItems.value = allWorkItems.value.map { item ->
                when (item.id) {
                    child.id -> updatedChild
                    parentId -> item.copy(childIds = (item.childIds + child.id).distinct())
                    oldParentId -> item.copy(childIds = item.childIds.filterNot { it == child.id })
                    else -> item
                }
            }
            if (_selectedWorkItem.value?.id == child.id) {
                setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == child.id })
            }
            onPersistSnapshot(org)

            if (org.isBlank() || pat.isBlank()) {
                onResult(Result.success(existingInMemory))
                return
            }
        } else if (org.isBlank() || pat.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Work item #$parentId was not found.")))
            return
        }

        scope.launch {
            val result = repository.linkExistingParentWorkItemOnServer(
                organization = org,
                pat = pat,
                childId = child.id,
                parentId = parentId
            )
            result.fold(
                onSuccess = { serverParent ->
                    val finalParent = serverParent.copy(
                        childIds = (serverParent.childIds + child.id).distinct()
                    )
                    crudDelegate.recordServerSyncedItem(finalParent)

                    val currentItems = allWorkItems.value
                    val hadParentInList = currentItems.any { it.id == finalParent.id }
                    val updatedList = if (hadParentInList) {
                        currentItems.map { item ->
                            when {
                                item.id == finalParent.id -> finalParent
                                item.id == child.id -> {
                                    val newChild = item.copy(parentId = finalParent.id)
                                    crudDelegate.recordServerSyncedItem(newChild)
                                    newChild
                                }
                                oldParentId != null && item.id == oldParentId -> {
                                    item.copy(childIds = item.childIds.filterNot { it == child.id })
                                }
                                else -> item
                            }
                        }
                    } else {
                        currentItems.map { item ->
                            when {
                                item.id == child.id -> {
                                    val newChild = item.copy(parentId = finalParent.id)
                                    crudDelegate.recordServerSyncedItem(newChild)
                                    newChild
                                }
                                oldParentId != null && item.id == oldParentId -> {
                                    item.copy(childIds = item.childIds.filterNot { it == child.id })
                                }
                                else -> item
                            }
                        } + finalParent
                    }

                    allWorkItems.value = updatedList
                    if (_selectedWorkItem.value?.id == child.id) {
                        setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == child.id })
                    }
                    onPersistSnapshot(org)
                    onResult(Result.success(finalParent))
                },
                onFailure = { err ->
                    if (existingInMemory != null) {
                        onResult(Result.success(existingInMemory))
                    } else {
                        onResult(Result.failure(err))
                    }
                }
            )
        }
    }

    fun unlinkParentWorkItem(
        child: WorkItem,
        parentId: Int
    ) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()

        allWorkItems.value = allWorkItems.value.map { item ->
            when {
                item.id == child.id && item.parentId == parentId -> {
                    val updatedChild = item.copy(parentId = null)
                    crudDelegate.recordServerSyncedItem(updatedChild)
                    updatedChild
                }
                item.id == parentId -> {
                    val updatedParent = item.copy(childIds = item.childIds.filterNot { it == child.id })
                    crudDelegate.recordServerSyncedItem(updatedParent)
                    updatedParent
                }
                else -> item
            }
        }

        if (_selectedWorkItem.value?.id == child.id) {
            setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == child.id })
        }
        onPersistSnapshot(org)

        if (org.isBlank() || pat.isBlank()) return

        scope.launch {
            repository.unlinkChildWorkItemOnServer(
                organization = org,
                pat = pat,
                parentId = parentId,
                childId = child.id
            )
        }
    }

    fun addChildWorkItem(
        parent: WorkItem,
        title: String,
        childType: String,
        initialState: String
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return

        val allowedChildTypes = getAllowedChildTypesForWorkItem(parent)
        if (allowedChildTypes.isEmpty()) return

        val project = parent.teamProject ?: filterDelegate.selectedProject.value ?: ""
        val resolvedType = childType.takeIf { candidate ->
            candidate.isNotBlank() && allowedChildTypes.any { it.equals(candidate, ignoreCase = true) }
        } ?: allowedChildTypes.first()
        val resolvedState = initialState.ifBlank {
            getStatesForType(project, resolvedType).firstOrNull() ?: "New"
        }

        val tempId = (allWorkItems.value.maxOfOrNull { it.id } ?: 1000) + 1
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val existingChildren = allWorkItems.value.filter {
            it.parentId == parent.id || it.id in parent.childIds
        }
        val nextBoardOrder = (existingChildren.mapNotNull { it.boardOrder }.maxOrNull() ?: 1000.0) + 10.0

        val optimisticChild = WorkItem(
            id = tempId,
            title = trimmedTitle,
            workItemType = resolvedType,
            state = resolvedState,
            teamProject = project,
            createdDate = nowIso,
            changedDate = nowIso,
            areaPath = parent.areaPath ?: project,
            iterationPath = parent.iterationPath ?: project,
            boardOrder = nextBoardOrder,
            parentId = parent.id
        )

        val updatedParent = parent.copy(
            childIds = (parent.childIds + tempId).distinct()
        )

        allWorkItems.value = (allWorkItems.value.map {
            if (it.id == parent.id) updatedParent else it
        } + optimisticChild)
        onPersistSnapshot(null)

        if (_selectedWorkItem.value?.id == parent.id) {
            setSelectedWorkItem(updatedParent)
        }

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        scope.launch {
            val result = repository.createChildWorkItemOnServer(
                organization = org,
                pat = pat,
                project = project,
                parentId = parent.id,
                title = trimmedTitle,
                childType = resolvedType,
                desiredState = resolvedState
            )
            result.onSuccess { createdServerChild ->
                val finalChild = createdServerChild.copy(
                    boardOrder = createdServerChild.boardOrder ?: nextBoardOrder,
                    parentId = parent.id
                )
                crudDelegate.clearPendingDirty(tempId)
                crudDelegate.recordServerSyncedItem(finalChild)

                allWorkItems.value = allWorkItems.value.map { item ->
                    when (item.id) {
                        tempId -> finalChild
                        parent.id -> {
                            val newParent = item.copy(
                                childIds = (item.childIds.filterNot { it == tempId } + finalChild.id).distinct()
                            )
                            crudDelegate.recordServerSyncedItem(newParent)
                            newParent
                        }
                        else -> item
                    }
                }
                onPersistSnapshot(org)
                if (_selectedWorkItem.value?.id == parent.id) {
                    setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == parent.id })
                }
            }
        }
    }

    fun addExistingChildWorkItem(
        parent: WorkItem,
        childId: Int,
        onResult: (Result<WorkItem>) -> Unit = {}
    ) {
        val allowedChildTypes = getAllowedChildTypesForWorkItem(parent)
        if (allowedChildTypes.isEmpty()) {
            onResult(
                Result.failure(
                    IllegalArgumentException("${parent.workItemType} is at the last hierarchy level and cannot have children.")
                )
            )
            return
        }
        if (childId <= 0) {
            onResult(Result.failure(IllegalArgumentException("Please enter a valid work item ID.")))
            return
        }
        if (childId == parent.id) {
            onResult(Result.failure(IllegalArgumentException("A work item cannot be added as a child of itself.")))
            return
        }
        if (childId == parent.parentId) {
            onResult(Result.failure(IllegalArgumentException("Work item #$childId is the parent of this work item.")))
            return
        }
        val alreadyChild = childId in parent.childIds ||
            allWorkItems.value.any { it.id == childId && it.parentId == parent.id }
        if (alreadyChild) {
            onResult(Result.failure(IllegalArgumentException("Work item #$childId is already a child of this item.")))
            return
        }

        val byId = allWorkItems.value.associateBy { it.id }
        var cursorParentId = parent.parentId
        val visited = mutableSetOf<Int>()
        while (cursorParentId != null && visited.add(cursorParentId)) {
            if (cursorParentId == childId) {
                onResult(
                    Result.failure(
                        IllegalArgumentException("Work item #$childId is an ancestor of #${parent.id} and cannot be added as its child.")
                    )
                )
                return
            }
            cursorParentId = byId[cursorParentId]?.parentId
        }

        val existingInMemory = byId[childId]
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()

        if (existingInMemory != null) {
            val previousParentId = existingInMemory.parentId
            val updatedChild = existingInMemory.copy(parentId = parent.id)
            allWorkItems.value = allWorkItems.value.map { item ->
                when (item.id) {
                    childId -> updatedChild
                    parent.id -> item.copy(childIds = (item.childIds + childId).distinct())
                    previousParentId -> item.copy(childIds = item.childIds.filterNot { it == childId })
                    else -> item
                }
            }
            if (_selectedWorkItem.value?.id == parent.id) {
                setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == parent.id })
            }
            onPersistSnapshot(org)

            if (org.isBlank() || pat.isBlank()) {
                onResult(Result.success(updatedChild))
                return
            }
        } else if (org.isBlank() || pat.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Work item #$childId was not found.")))
            return
        }

        scope.launch {
            val result = repository.linkExistingChildWorkItemOnServer(
                organization = org,
                pat = pat,
                parentId = parent.id,
                childId = childId
            )
            result.fold(
                onSuccess = { serverLinkedChild ->
                    val finalChild = serverLinkedChild.copy(parentId = parent.id)
                    crudDelegate.recordServerSyncedItem(finalChild)

                    val currentItems = allWorkItems.value
                    val hadChildInList = currentItems.any { it.id == finalChild.id }
                    val updatedList = if (hadChildInList) {
                        currentItems.map { item ->
                            when {
                                item.id == finalChild.id -> finalChild
                                item.id == parent.id -> {
                                    val newParent = item.copy(childIds = (item.childIds + finalChild.id).distinct())
                                    crudDelegate.recordServerSyncedItem(newParent)
                                    newParent
                                }
                                item.id != parent.id && finalChild.id in item.childIds -> {
                                    item.copy(childIds = item.childIds.filterNot { it == finalChild.id })
                                }
                                else -> item
                            }
                        }
                    } else {
                        currentItems.map { item ->
                            if (item.id == parent.id) {
                                val newParent = item.copy(childIds = (item.childIds + finalChild.id).distinct())
                                crudDelegate.recordServerSyncedItem(newParent)
                                newParent
                            } else {
                                item
                            }
                        } + finalChild
                    }

                    allWorkItems.value = updatedList
                    if (_selectedWorkItem.value?.id == parent.id) {
                        setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == parent.id })
                    }
                    onPersistSnapshot(org)
                    onResult(Result.success(finalChild))
                },
                onFailure = { err ->
                    if (existingInMemory != null) {
                        onResult(Result.success(existingInMemory.copy(parentId = parent.id)))
                    } else {
                        onResult(Result.failure(err))
                    }
                }
            )
        }
    }

    fun removeChildWorkItem(
        parent: WorkItem,
        childId: Int
    ) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()

        allWorkItems.value = allWorkItems.value.map { item ->
            when {
                item.id == parent.id -> {
                    val updatedParent = item.copy(childIds = item.childIds.filterNot { it == childId })
                    crudDelegate.recordServerSyncedItem(updatedParent)
                    updatedParent
                }
                item.id == childId && item.parentId == parent.id -> {
                    val updatedChild = item.copy(parentId = null)
                    crudDelegate.recordServerSyncedItem(updatedChild)
                    updatedChild
                }
                else -> item
            }
        }

        if (_selectedWorkItem.value?.id == parent.id) {
            setSelectedWorkItem(allWorkItems.value.firstOrNull { it.id == parent.id })
        }
        onPersistSnapshot(org)

        if (org.isBlank() || pat.isBlank()) return

        scope.launch {
            repository.unlinkChildWorkItemOnServer(
                organization = org,
                pat = pat,
                parentId = parent.id,
                childId = childId
            )
        }
    }

    fun reset() {
        activeCommentsJob?.cancel()
        commentsByWorkItemId.clear()
        statesForTypeCache.clear()
        _selectedWorkItem.value = null
        _selectedWorkItemComments.value = emptyList()
        _isLoadingComments.value = false
    }
}
