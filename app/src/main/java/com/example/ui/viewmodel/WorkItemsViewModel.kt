package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.data.repository.AzureDevOpsRepository
import com.example.di.SessionManager
import com.example.notifications.NotificationHelper
import com.example.ui.ConnectionStatus
import com.example.ui.state.WorkItemsUiState
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.expandAndSortHierarchyPaths
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findBacklogLevels
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findCategoryMap
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProcessHierarchy
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectAreas
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectIterations
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.insertBoundedTopK
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.matchesClassificationHierarchyFast
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.normalizeClassificationPath
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.projectScopedKey
import com.example.ui.viewmodel.delegates.WorkItemCrudDelegate
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate
import com.example.ui.viewmodel.delegates.WorkItemHierarchyDelegate
import com.example.ui.viewmodel.delegates.WorkItemProcessDelegate
import com.example.ui.viewmodel.delegates.WorkItemStreamDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature ViewModel responsible for Azure Boards work items, filtering, progressive background
 * streaming, delta sync, process metadata & hierarchy rules, optimistic CRUD, and item discussions.
 *
 * Decomposed with specialized feature delegates:
 * - [WorkItemFilterDelegate]: Search and facet query filters
 * - [WorkItemStreamDelegate]: Progressive sync and streaming state indicators
 * - [WorkItemHierarchyDelegate]: Hierarchy, allowed child types, and discussion comments
 * - [WorkItemCrudDelegate]: Dirty tracking, debounced jobs, and baseline tracking
 * - [WorkItemProcessDelegate]: Classification trees and backlog metadata
 */
class WorkItemsViewModel(
    application: Application,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    private val repository = sessionManager.repository

    // Feature Delegates
    private val filterDelegate = WorkItemFilterDelegate()
    private val streamDelegate = WorkItemStreamDelegate()
    private val hierarchyDelegate = WorkItemHierarchyDelegate()
    private val crudDelegate = WorkItemCrudDelegate()
    private val processDelegate = WorkItemProcessDelegate(repository)

    private val _allWorkItems = MutableStateFlow<List<WorkItem>>(emptyList())
    val allWorkItems: StateFlow<List<WorkItem>> = _allWorkItems.asStateFlow()

    private val _workItemsOrg = MutableStateFlow<String?>(null)

    // Filter delegate flows
    val searchQuery: StateFlow<String> = filterDelegate.searchQuery
    val selectedProject: StateFlow<String?> = filterDelegate.selectedProject
    val typeFilter: StateFlow<String?> = filterDelegate.typeFilter
    val stateFilter: StateFlow<String?> = filterDelegate.stateFilter
    val assigneeFilter: StateFlow<String?> = filterDelegate.assigneeFilter
    val areaFilter: StateFlow<String?> = filterDelegate.areaFilter
    val iterationFilter: StateFlow<String?> = filterDelegate.iterationFilter

    private var userManuallySelectedProject = false
    private var userManuallySelectedType = false

    // Stream delegate flows
    val isLoading: StateFlow<Boolean> = streamDelegate.isLoading
    val isLoadingMore: StateFlow<Boolean> = streamDelegate.isLoadingMore
    val isSearchingServer: StateFlow<Boolean> = streamDelegate.isSearchingServer
    val isBackgroundSyncing: StateFlow<Boolean> = streamDelegate.isBackgroundSyncing
    val syncProgress: StateFlow<Float?> = streamDelegate.syncProgress
    val isPullRefreshing: StateFlow<Boolean> = streamDelegate.isPullRefreshing
    val syncStatusText: StateFlow<String?> = streamDelegate.syncStatusText

    private var backgroundStreamJob: Job? = null
    private var activeConnectJob: Job? = null
    private var snapshotPersistJob: Job? = null

    private val _proposedDisplayLimit = MutableStateFlow(PROPOSED_INITIAL_BATCH_SIZE)
    private val _stateDisplayLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _hasMoreServerItems = MutableStateFlow(false)
    private val exhaustedStateQueries = Collections.synchronizedSet(mutableSetOf<String>())

    companion object {
        private const val PROPOSED_INITIAL_BATCH_SIZE = 500
        private const val PROPOSED_PAGE_STEP = 100
        private const val STATE_INITIAL_BATCH_SIZE = 200
        private const val STATE_PAGE_STEP = 100
        private const val RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE = 5
        private const val BACKGROUND_STREAM_UI_THROTTLE_MS = 250L
    }

    // Hierarchy delegate flows
    val selectedWorkItem: StateFlow<WorkItem?> = hierarchyDelegate.selectedWorkItem
    val selectedWorkItemComments: StateFlow<List<WorkItemComment>> = hierarchyDelegate.selectedWorkItemComments
    val isLoadingComments: StateFlow<Boolean> = hierarchyDelegate.isLoadingComments

    private val commentsByWorkItemId = ConcurrentHashMap<Int, List<WorkItemComment>>()
    private var activeCommentsJob: Job? = null

    val selectedWorkItemChildren: StateFlow<List<WorkItem>> = combine(
        selectedWorkItem,
        _allWorkItems
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableProjects: StateFlow<List<String>> = combine(
        sessionManager.connectionStatus,
        sessionManager.defaultOrganization,
        sessionManager.organizations,
        sessionManager.projectsByOrg,
        combine(_allWorkItems, _workItemsOrg) { items, itemsOrg -> items to itemsOrg }
    ) { status, defOrg, orgs, byOrg, (items, itemsOrg) ->
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization
            ?: defOrg
            ?: orgs.firstOrNull()?.organization
            ?: ""
        if (activeOrg.isBlank()) return@combine emptyList()
        val fetchedForOrg = byOrg.entries
            .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItems = if (itemsOrg.equals(activeOrg, ignoreCase = true)) {
            items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val cachedForOrg = repository.getCachedProjectsForOrg(activeOrg)
        (fetchedForOrg + fromItems + cachedForOrg)
            .distinctBy { it.lowercase() }
            .sorted()
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun resolveEffectiveProject(project: String?, items: List<WorkItem>): String? {
        val activeOrg = sessionManager.getActiveOrganization()
        val fetchedForOrg = sessionManager.projectsByOrg.value.entries
            .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItems = if (_workItemsOrg.value.equals(activeOrg, ignoreCase = true)) {
            items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val knownOrgProjects = (fetchedForOrg + fromItems).distinctBy { it.lowercase() }
        if (project.isNullOrBlank()) {
            return knownOrgProjects.firstOrNull()
        }
        if (knownOrgProjects.isNotEmpty() && knownOrgProjects.none { it.equals(project, ignoreCase = true) }) {
            val fallback = sessionManager.defaultProjectsByOrg.value.entries
                .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value
                ?.takeIf { def -> knownOrgProjects.any { it.equals(def, ignoreCase = true) } }
                ?: knownOrgProjects.firstOrNull()
            if (fallback != null && !selectedProject.value.equals(fallback, ignoreCase = true)) {
                filterDelegate.setProject(fallback)
            }
            return fallback
        }
        return project
    }

    private data class ProjectScopedWorkItemsSnapshot(
        val activeOrg: String,
        val effectiveProject: String?,
        val effectiveProjectLower: String,
        val projectItems: List<WorkItem>,
        val allItems: List<WorkItem>
    )

    private data class ProjectAndTypeScopedSnapshot(
        val activeOrg: String,
        val effectiveProject: String?,
        val effectiveProjectLower: String,
        val selectedType: String?,
        val selectedTypeLower: String,
        val projectAndTypeItems: List<WorkItem>,
        val projectItems: List<WorkItem>
    )

    private val projectScopedSnapshotFlow: StateFlow<ProjectScopedWorkItemsSnapshot> = combine(
        sessionManager.connectionStatus,
        _allWorkItems,
        selectedProject,
        sessionManager.projectsByOrg,
        _workItemsOrg
    ) { status, items, rawProject, _, _ ->
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization
            ?: sessionManager.defaultOrganization.value
            ?: sessionManager.organizations.value.firstOrNull()?.organization
            ?: ""
        val project = resolveEffectiveProject(rawProject, items)
        val projectLower = project?.trim()?.lowercase().orEmpty()
        val projItems = if (projectLower.isEmpty()) {
            items
        } else {
            items.filter { it.normTeamProjectLower == projectLower }
        }
        ProjectScopedWorkItemsSnapshot(
            activeOrg = activeOrg,
            effectiveProject = project,
            effectiveProjectLower = projectLower,
            projectItems = projItems,
            allItems = items
        )
    }
        .conflate()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ProjectScopedWorkItemsSnapshot(
                activeOrg = sessionManager.defaultOrganization.value.orEmpty(),
                effectiveProject = selectedProject.value,
                effectiveProjectLower = selectedProject.value?.trim()?.lowercase().orEmpty(),
                projectItems = emptyList(),
                allItems = emptyList()
            )
        )

    private val projectAndTypeScopedSnapshotFlow: StateFlow<ProjectAndTypeScopedSnapshot> = combine(
        projectScopedSnapshotFlow,
        typeFilter
    ) { projSnap, type ->
        val typeLower = type?.trim()?.lowercase().orEmpty()
        val typedItems = if (typeLower.isEmpty()) {
            projSnap.projectItems
        } else {
            projSnap.projectItems.filter { it.normTypeLower == typeLower }
        }
        ProjectAndTypeScopedSnapshot(
            activeOrg = projSnap.activeOrg,
            effectiveProject = projSnap.effectiveProject,
            effectiveProjectLower = projSnap.effectiveProjectLower,
            selectedType = type,
            selectedTypeLower = typeLower,
            projectAndTypeItems = typedItems,
            projectItems = projSnap.projectItems
        )
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ProjectAndTypeScopedSnapshot(
                activeOrg = sessionManager.defaultOrganization.value.orEmpty(),
                effectiveProject = selectedProject.value,
                effectiveProjectLower = selectedProject.value?.trim()?.lowercase().orEmpty(),
                selectedType = typeFilter.value,
                selectedTypeLower = typeFilter.value?.trim()?.lowercase().orEmpty(),
                projectAndTypeItems = emptyList(),
                projectItems = emptyList()
            )
        )

    val availableTypes: StateFlow<List<String>> = combine(
        projectScopedSnapshotFlow,
        processDelegate.projectProcessMap,
        processDelegate.projectCategoryMap,
        processDelegate.projectBacklogLevelsMap
    ) { projSnap, processMap, categoryMap, backlogLevelsMap ->
        val project = projSnap.effectiveProject
        if (project.isNullOrBlank()) return@combine emptyList()
        val activeOrg = projSnap.activeOrg
        val matchedProcessMap = findProcessHierarchy(activeOrg, project, processMap)
        val matchedCategoryMap = findCategoryMap(activeOrg, project, categoryMap).orEmpty()
        val matchedBacklogLevels = findBacklogLevels(activeOrg, project, backlogLevelsMap).orEmpty()
        val projectItems = projSnap.projectItems
        val fromApi = matchedProcessMap?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
        val rawTypes = if (!fromApi.isNullOrEmpty()) {
            fromApi
        } else {
            projectItems
                .map { it.workItemType }
                .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
                .distinct()
        }
        repository.orderWorkItemTypesBiggestToSmallest(
            activeTypes = rawTypes,
            categoryMap = matchedCategoryMap,
            projectWorkItems = projectItems,
            explicitBacklogLevels = matchedBacklogLevels
        )
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableStates: StateFlow<List<String>> = combine(
        projectAndTypeScopedSnapshotFlow,
        processDelegate.projectProcessMap
    ) { typeSnap, processMap ->
        val project = typeSnap.effectiveProject
        val type = typeSnap.selectedType
        if (project.isNullOrBlank() || type.isNullOrBlank()) return@combine emptyList()
        val activeOrg = typeSnap.activeOrg
        val matchedProjMap = findProcessHierarchy(activeOrg, project, processMap)
        val fromApi = matchedProjMap?.entries?.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value
        val typeStates = if (!fromApi.isNullOrEmpty()) {
            fromApi
        } else {
            val fromItems = typeSnap.projectAndTypeItems.map { it.state }.distinct()
            if (fromItems.isNotEmpty()) {
                fromItems
            } else {
                repository.getStandardProcessStates(type)
            }
        }
        if (typeStates.isEmpty()) {
            emptyList()
        } else {
            listOf("All") + typeStates.filterNot { it.equals("All", ignoreCase = true) }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projectAssignees: StateFlow<List<String>> = projectScopedSnapshotFlow
        .map { projSnap ->
            val seenLower = HashSet<String>()
            val result = ArrayList<String>()
            for (item in projSnap.projectItems) {
                if (!item.isUnassigned) {
                    val trimmed = item.assignedToName?.trim().orEmpty()
                    if (trimmed.isNotEmpty() && seenLower.add(item.normAssigneeLower)) {
                        result.add(trimmed)
                    }
                }
            }
            for (item in projSnap.allItems) {
                if (!item.isUnassigned) {
                    val trimmed = item.assignedToName?.trim().orEmpty()
                    if (trimmed.isNotEmpty() && seenLower.add(item.normAssigneeLower)) {
                        result.add(trimmed)
                    }
                }
            }
            result
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projectFrequentTags: StateFlow<List<String>> = projectScopedSnapshotFlow
        .map { projSnap ->
            val counts = LinkedHashMap<String, Int>()
            for (item in projSnap.projectItems) {
                for (rawTag in item.tags) {
                    val clean = rawTag.trim()
                    if (clean.isNotEmpty()) {
                        counts[clean] = (counts[clean] ?: 0) + 1
                    }
                }
            }
            counts.entries
                .sortedByDescending { it.value }
                .map { it.key }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projectAreas: StateFlow<List<String>> = combine(
        projectScopedSnapshotFlow,
        processDelegate.projectAreasMap
    ) { projSnap, areasMap ->
        val project = projSnap.effectiveProject
        if (project.isNullOrBlank()) return@combine emptyList()
        val fromApi = findProjectAreas(projSnap.activeOrg, project, areasMap).orEmpty()
        val fromItems = projSnap.projectItems
            .mapNotNull { it.normAreaPath.takeIf { p -> p.isNotEmpty() } }
        expandAndSortHierarchyPaths(project, fromApi + fromItems)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projectIterations: StateFlow<List<String>> = combine(
        projectScopedSnapshotFlow,
        processDelegate.projectIterationsMap
    ) { projSnap, iterationsMap ->
        val project = projSnap.effectiveProject
        if (project.isNullOrBlank()) return@combine emptyList()
        val fromApi = findProjectIterations(projSnap.activeOrg, project, iterationsMap).orEmpty()
        val fromItems = projSnap.projectItems
            .mapNotNull { it.normIterationPath.takeIf { p -> p.isNotEmpty() } }
        expandAndSortHierarchyPaths(project, fromApi + fromItems)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasMoreItems: StateFlow<Boolean> = combine(
        projectAndTypeScopedSnapshotFlow,
        combine(stateFilter, _proposedDisplayLimit, _stateDisplayLimits) { state, propLimit, stateLimits ->
            Triple(state, propLimit, stateLimits)
        }.distinctUntilChanged(),
        _hasMoreServerItems
    ) { typeSnap, (state, propLimit, stateLimits), hasMoreServer ->
        val matchingProjectAndType = typeSnap.projectAndTypeItems
        val stateLower = state?.trim()?.lowercase().orEmpty()
        if (stateLower.isEmpty() || stateLower == "all") {
            val totalProposedInMemory = matchingProjectAndType.count { item ->
                repository.resolveStateCategoryForWorkItem(item) ==
                    AzureDevOpsRepository.CATEGORY_PROPOSED
            }
            totalProposedInMemory > propLimit || hasMoreServer
        } else {
            val stateKey = "${typeSnap.effectiveProjectLower}|${typeSnap.selectedTypeLower}|$stateLower"
            val currentLimit = stateLimits[stateKey] ?: STATE_INITIAL_BATCH_SIZE
            val totalStateInMemory = matchingProjectAndType.count { item ->
                item.normStateLower == stateLower
            }
            val isStateExhaustedOnServer = stateKey in exhaustedStateQueries
            totalStateInMemory > currentLimit || hasMoreServer || !isStateExhaustedOnServer
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val filteredWorkItems: StateFlow<List<WorkItem>> = combine(
        combine(projectScopedSnapshotFlow, searchQuery) { projSnap, query ->
            projSnap to query
        }.distinctUntilChanged(),
        combine(typeFilter, stateFilter, assigneeFilter) { type, state, assignee ->
            Triple(type, state, assignee)
        }.distinctUntilChanged(),
        combine(areaFilter, iterationFilter) { area, iteration ->
            area to iteration
        }.distinctUntilChanged(),
        combine(_proposedDisplayLimit, _stateDisplayLimits) { propLimit, stateLimits ->
            propLimit to stateLimits
        }.distinctUntilChanged()
    ) { (projSnap, query), (type, state, assignee), (area, iteration), (propLimit, stateLimits) ->
        val project = projSnap.effectiveProject
        val projectLower = projSnap.effectiveProjectLower
        val cleanQuery = query.trim()

        if (cleanQuery.isNotEmpty()) {
            val queryLower = cleanQuery.lowercase()
            val numericQuery = cleanQuery.removePrefix("#").trim()
            val allQueryMatches = projSnap.allItems.filter { item ->
                item.titleLower.contains(queryLower) ||
                    (numericQuery.isNotEmpty() && item.idString.contains(numericQuery)) ||
                    item.descriptionLower.contains(queryLower) ||
                    item.normAssigneeLower.contains(queryLower) ||
                    item.normAreaPathLower.contains(queryLower) ||
                    item.normIterationPathLower.contains(queryLower) ||
                    item.normTypeLower.contains(queryLower) ||
                    item.normStateLower.contains(queryLower) ||
                    item.tagsLower.any { it.contains(queryLower) }
            }

            val projectScopedMatches = if (projectLower.isEmpty()) {
                allQueryMatches
            } else {
                val inProject = allQueryMatches.filter { item ->
                    item.normTeamProjectLower.isEmpty() ||
                        item.normTeamProjectLower == projectLower ||
                        (numericQuery.isNotEmpty() && item.idString == numericQuery)
                }
                inProject.ifEmpty { allQueryMatches }
            }

            return@combine projectScopedMatches.sortedWith(
                compareBy<WorkItem>(
                    { item ->
                        when {
                            numericQuery.isNotEmpty() && item.idString == numericQuery -> 0
                            item.titleLower == queryLower -> 1
                            item.titleLower.startsWith(queryLower) -> 2
                            item.titleLower.contains(queryLower) -> 3
                            item.tagsLower.any { it.contains(queryLower) } -> 4
                            numericQuery.isNotEmpty() && item.idString.contains(numericQuery) -> 5
                            else -> 6
                        }
                    },
                    { repository.getStatePriorityTier(it.state, it.teamProject, it.workItemType) },
                    { it.boardOrder ?: Double.MAX_VALUE },
                    { it.id }
                )
            )
        }

        val typeLower = type?.trim()?.lowercase().orEmpty()
        val stateLower = if (state == null || state.equals("All", ignoreCase = true)) {
            ""
        } else {
            state.trim().lowercase()
        }
        val assigneeLower = assignee?.trim()?.lowercase().orEmpty()
        val isAssigneeAll = assigneeLower.isEmpty() || assigneeLower == "all"
        val isAssigneeUnassigned = assigneeLower == "unassigned"

        val normAreaLower = if (area.isNullOrBlank() || area.equals("All", ignoreCase = true)) {
            ""
        } else {
            normalizeClassificationPath(area).lowercase()
        }
        val normAreaPrefixLower = if (normAreaLower.isEmpty()) "" else "$normAreaLower\\"

        val normIterationLower = if (iteration.isNullOrBlank() || iteration.equals("All", ignoreCase = true)) {
            ""
        } else {
            normalizeClassificationPath(iteration).lowercase()
        }
        val normIterationPrefixLower = if (normIterationLower.isEmpty()) "" else "$normIterationLower\\"

        val baseMatched = projSnap.projectItems.filter { item ->
            (typeLower.isEmpty() || item.normTypeLower == typeLower) &&
                (stateLower.isEmpty() || item.normStateLower == stateLower) &&
                (isAssigneeAll || (if (isAssigneeUnassigned) item.isUnassigned else item.normAssigneeLower == assigneeLower)) &&
                matchesClassificationHierarchyFast(item.normAreaPathLower, normAreaLower, normAreaPrefixLower) &&
                matchesClassificationHierarchyFast(item.normIterationPathLower, normIterationLower, normIterationPrefixLower)
        }

        val boardOrderComparator = compareBy<WorkItem>(
            { it.boardOrder ?: Double.MAX_VALUE },
            { it.id }
        )
        val recentChangedComparator = compareByDescending<WorkItem> { it.changedDate ?: "" }
            .thenBy { it.boardOrder ?: Double.MAX_VALUE }
            .thenByDescending { it.id }

        if (stateLower.isEmpty()) {
            val inProgressItems = ArrayList<WorkItem>()
            val proposedItems = ArrayList<WorkItem>()
            val terminalTopKBuckets = HashMap<Pair<String, String>, MutableList<WorkItem>>()

            for (item in baseMatched) {
                when (repository.resolveStateCategoryForWorkItem(item)) {
                    AzureDevOpsRepository.CATEGORY_IN_PROGRESS -> inProgressItems.add(item)
                    AzureDevOpsRepository.CATEGORY_PROPOSED -> proposedItems.add(item)
                    else -> {
                        val bucketKey = item.normTypeLower to item.normStateLower
                        val bucket = terminalTopKBuckets.getOrPut(bucketKey) {
                            ArrayList(RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE + 1)
                        }
                        insertBoundedTopK(
                            bucket = bucket,
                            candidate = item,
                            maxSize = RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE,
                            comparator = recentChangedComparator
                        )
                    }
                }
            }

            val sortedInProgress = inProgressItems.sortedWith(boardOrderComparator)
            val sortedProposed = proposedItems.sortedWith(boardOrderComparator).take(propLimit)
            val cappedRecentTerminal = terminalTopKBuckets.values
                .flatten()
                .sortedWith(recentChangedComparator)

            return@combine sortedInProgress + sortedProposed + cappedRecentTerminal
        }

        val stateKey = "$projectLower|$typeLower|$stateLower"
        val stateLimit = stateLimits[stateKey] ?: STATE_INITIAL_BATCH_SIZE
        val selectedCategory = repository.resolveStateCategory(state, project, type)

        when (selectedCategory) {
            AzureDevOpsRepository.CATEGORY_IN_PROGRESS -> baseMatched.sortedWith(boardOrderComparator)
            AzureDevOpsRepository.CATEGORY_PROPOSED -> baseMatched.sortedWith(boardOrderComparator).take(stateLimit)
            else -> baseMatched.sortedWith(recentChangedComparator).take(stateLimit)
        }
    }
        .conflate()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalWorkItemsCount: StateFlow<Int> = combine(
        projectScopedSnapshotFlow,
        filteredWorkItems,
        searchQuery,
        combine(typeFilter, stateFilter, assigneeFilter, areaFilter, iterationFilter) { t, s, a, ar, it ->
            listOf(t, s, a, ar, it)
        }.distinctUntilChanged()
    ) { projSnap, filtered, query, filters ->
        val state = filters[1]
        val assignee = filters[2]
        val area = filters[3]
        val iteration = filters[4]
        val hasNarrowFilter = query.isNotBlank() ||
            (!state.isNullOrBlank() && !state.equals("All", ignoreCase = true)) ||
            !assignee.isNullOrBlank() ||
            !area.isNullOrBlank() ||
            !iteration.isNullOrBlank()

        if (hasNarrowFilter) {
            filtered.size
        } else {
            val type = filters[0]
            val typeLower = type?.trim()?.lowercase().orEmpty()
            if (typeLower.isEmpty()) {
                projSnap.projectItems.size
            } else {
                projSnap.projectItems.count { it.normTypeLower == typeLower }
            }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val uiState: StateFlow<WorkItemsUiState> = combine(
        sessionManager.connectionStatus,
        sessionManager.defaultOrganization,
        sessionManager.organizations,
        filteredWorkItems,
        _allWorkItems,
        totalWorkItemsCount,
        searchQuery,
        typeFilter,
        stateFilter,
        selectedProject,
        assigneeFilter,
        areaFilter,
        iterationFilter,
        availableTypes,
        availableStates,
        availableProjects,
        projectAssignees,
        projectFrequentTags,
        projectAreas,
        projectIterations,
        isLoading,
        isLoadingMore,
        isSearchingServer,
        isBackgroundSyncing,
        syncProgress,
        isPullRefreshing,
        syncStatusText,
        hasMoreItems,
        selectedWorkItem,
        selectedWorkItemChildren,
        selectedWorkItemComments,
        isLoadingComments
    ) { values: Array<Any?> ->
        val status = values[0] as ConnectionStatus
        val defOrg = values[1] as String?
        @Suppress("UNCHECKED_CAST")
        val orgs = values[2] as List<OrganizationAccount>
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization
            ?: defOrg
            ?: orgs.firstOrNull()?.organization
            ?: ""

        @Suppress("UNCHECKED_CAST")
        WorkItemsUiState(
            organization = activeOrg,
            workItems = values[3] as List<WorkItem>,
            allWorkItems = values[4] as List<WorkItem>,
            totalCount = values[5] as Int,
            searchQuery = values[6] as String,
            selectedType = values[7] as String?,
            selectedState = values[8] as String?,
            selectedProject = values[9] as String?,
            selectedAssignee = values[10] as String?,
            selectedArea = values[11] as String?,
            selectedIteration = values[12] as String?,
            availableTypes = values[13] as List<String>,
            availableStates = values[14] as List<String>,
            availableProjects = values[15] as List<String>,
            availableAssignees = values[16] as List<String>,
            availableTags = values[17] as List<String>,
            availableAreas = values[18] as List<String>,
            availableIterations = values[19] as List<String>,
            isLoading = values[20] as Boolean,
            isLoadingMore = values[21] as Boolean,
            isSearchingServer = values[22] as Boolean,
            isBackgroundSyncing = values[23] as Boolean,
            syncProgress = values[24] as Float?,
            isPullRefreshing = values[25] as Boolean,
            syncStatusText = values[26] as String?,
            hasMoreItems = values[27] as Boolean,
            selectedWorkItem = values[28] as WorkItem?,
            childWorkItems = values[29] as List<WorkItem>,
            selectedWorkItemComments = values[30] as List<WorkItemComment>,
            isLoadingComments = values[31] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkItemsUiState())

    init {
        registerSessionCallbacks()
        initializeSavedAccountsAndAutoConnect()
        observeProjectAndTypeSelection()
        observeOnDemandServerSearch()
    }

    private fun registerSessionCallbacks() {
        sessionManager.onConnectRequested = { org, pat, remember ->
            connect(org, pat, remember)
        }
        sessionManager.onSwitchOrganizationRequested = { orgName ->
            switchOrganization(orgName)
        }
        sessionManager.onSaveOrganizationFromSettingsRequested = { prevOrg, orgIn, patIn, rem, onRes ->
            saveOrganizationFromSettings(prevOrg, orgIn, patIn, rem, onRes)
        }
        sessionManager.onDisconnectRequested = {
            disconnect()
        }
        sessionManager.onDefaultProjectChangedForActiveOrg = { trimmedProj ->
            val isDifferentProject = !selectedProject.value.equals(trimmedProj, ignoreCase = true)
            userManuallySelectedProject = false
            userManuallySelectedType = false
            if (isDifferentProject) {
                filterDelegate.setAreaFilter(null)
                filterDelegate.setIterationFilter(null)
            }
            filterDelegate.setProject(trimmedProj)
            if (!trimmedProj.isNullOrBlank()) {
                ensureProjectProcessLoaded(trimmedProj)
            }
        }
        sessionManager.onSelectWorkItemRequested = { item ->
            selectWorkItem(item)
        }
        sessionManager.onMergeHydratedWorkItemsRequested = { items ->
            mergeHydratedWorkItemsSuspend(items)
        }
        sessionManager.currentWorkItemsProvider = {
            _workItemsOrg.value to _allWorkItems.value
        }
    }

    private fun initializeSavedAccountsAndAutoConnect() {
        val savedAccounts = repository.getSavedOrganizations()
        sessionManager.updateOrganizations(savedAccounts)
        val savedDefaultOrg = repository.getDefaultOrganization() ?: savedAccounts.firstOrNull()?.organization
        sessionManager.updateDefaultOrganization(savedDefaultOrg)

        val initialProjectsMap = mutableMapOf<String, List<String>>()
        val initialDefaultsMap = mutableMapOf<String, String>()
        savedAccounts.forEach { acc ->
            val cachedProjs = repository.getCachedProjectsForOrg(acc.organization)
            if (cachedProjs.isNotEmpty()) {
                initialProjectsMap[acc.organization] = cachedProjs
            }
            val savedDefProj = repository.getDefaultProject(acc.organization)
                ?.takeIf { def -> cachedProjs.isEmpty() || cachedProjs.any { it.equals(def, ignoreCase = true) } }
                ?: cachedProjs.firstOrNull()
            if (savedDefProj != null) {
                initialDefaultsMap[acc.organization] = savedDefProj
                repository.saveDefaultProject(savedDefProj, acc.organization)
            }
        }
        if (initialProjectsMap.isNotEmpty()) {
            sessionManager.updateProjectsByOrg(initialProjectsMap)
        }
        if (initialDefaultsMap.isNotEmpty()) {
            sessionManager.updateDefaultProjectsByOrg(initialDefaultsMap)
        }

        val initialDefProj = savedDefaultOrg?.let { defOrg ->
            initialDefaultsMap.entries.firstOrNull { it.key.equals(defOrg, ignoreCase = true) }?.value
                ?: repository.getDefaultProject(defOrg)
        } ?: repository.getDefaultProject()
        sessionManager.updateDefaultProject(initialDefProj)
        if (!initialDefProj.isNullOrBlank()) {
            filterDelegate.setProject(initialDefProj)
        }

        if (!savedDefaultOrg.isNullOrBlank()) {
            restoreOrganizationFromLocalCache(savedDefaultOrg)
        }

        val activeAccount = sessionManager.getActiveAccount()
        if (activeAccount != null && activeAccount.organization.isNotBlank() && activeAccount.pat.isNotBlank()) {
            connect(
                urlOrOrg = activeAccount.organization,
                pat = activeAccount.pat,
                rememberCredentials = activeAccount.isRemembered
            )
        } else {
            val savedUrl = repository.getConfiguredUrl()
            val savedPat = repository.getConfiguredPat()
            if (savedUrl.isNotBlank() && savedPat.isNotBlank()) {
                connect(savedUrl, savedPat, rememberCredentials = true)
            }
        }
    }

    private fun observeProjectAndTypeSelection() {
        viewModelScope.launch {
            availableProjects.collectLatest { projects ->
                if (projects.isEmpty()) return@collectLatest
                val activeOrg = sessionManager.getActiveOrganization()
                val resolvedDefProj = if (activeOrg.isNotBlank()) {
                    sessionManager.resolveAndPersistDefaultProjectForOrg(activeOrg, projects)
                } else {
                    null
                }
                val currentProj = selectedProject.value
                val isCurrentInList = currentProj != null && projects.any { it.equals(currentProj, ignoreCase = true) }
                if (!userManuallySelectedProject || !isCurrentInList) {
                    val chosen = resolvedDefProj ?: projects.first()
                    if (!selectedProject.value.equals(chosen, ignoreCase = true)) {
                        filterDelegate.setProject(chosen)
                    }
                    ensureProjectProcessLoaded(chosen)
                }
            }
        }

        viewModelScope.launch {
            availableTypes.collectLatest { types ->
                if (types.isEmpty()) return@collectLatest
                val currentType = typeFilter.value
                val isCurrentInList = currentType != null && types.any { it.equals(currentType, ignoreCase = true) }
                if (!userManuallySelectedType || !isCurrentInList) {
                    val biggestType = types.first()
                    if (typeFilter.value != biggestType) {
                        filterDelegate.setTypeFilter(biggestType)
                        filterDelegate.setStateFilter("All")
                    }
                }
            }
        }

        viewModelScope.launch {
            availableStates.collectLatest { states ->
                if (states.isEmpty()) return@collectLatest
                val currentState = stateFilter.value
                val isCurrentInList = currentState != null && states.any { it.equals(currentState, ignoreCase = true) }
                if (currentState == null || !isCurrentInList) {
                    filterDelegate.setStateFilter("All")
                }
            }
        }
    }

    private fun observeOnDemandServerSearch() {
        viewModelScope.launch {
            searchQuery.collectLatest { rawQuery ->
                val cleanQuery = rawQuery.trim()
                if (cleanQuery.length < 2) {
                    streamDelegate.setSearchingServer(false)
                    return@collectLatest
                }
                delay(280L)
                val acc = sessionManager.getActiveAccount()
                val org = acc?.organization ?: repository.getConfiguredUrl()
                val pat = acc?.pat ?: repository.getConfiguredPat()
                if (org.isBlank() || pat.isBlank()) return@collectLatest

                val existingIds = _allWorkItems.value.map { it.id }.toSet()
                val numericId = cleanQuery.removePrefix("#").trim().toIntOrNull()
                if (numericId != null && numericId in existingIds) {
                    return@collectLatest
                }

                streamDelegate.setSearchingServer(true)
                val searchResult = repository.searchWorkItemsOnServer(
                    organization = org,
                    pat = pat,
                    query = cleanQuery,
                    project = selectedProject.value,
                    alreadyLoadedIds = existingIds
                )
                searchResult.onSuccess { fetchedSearchItems ->
                    if (fetchedSearchItems.isNotEmpty()) {
                        mergeHydratedWorkItemsSuspend(fetchedSearchItems)
                    }
                }
                streamDelegate.setSearchingServer(false)
            }
        }
    }

    private fun restoreOrganizationFromLocalCache(organization: String): CachedOrganizationSnapshot? {
        val normOrg = repository.extractOrganization(organization)
        if (normOrg.isBlank()) return null

        val cachedMeta = repository.getCachedOrganizationMetadataSync(normOrg)
        if (cachedMeta != null) {
            processDelegate.updateMaps(
                processMap = processDelegate.projectProcessMap.value + cachedMeta.processHierarchyByProject,
                categoryMap = processDelegate.projectCategoryMap.value + cachedMeta.categoryMapByProject,
                backlogLevelsMap = processDelegate.projectBacklogLevelsMap.value + cachedMeta.backlogLevelsByProject,
                areasMap = processDelegate.projectAreasMap.value + cachedMeta.areasByProject,
                iterationsMap = processDelegate.projectIterationsMap.value + cachedMeta.iterationsByProject
            )
        }

        val cachedNotifs = repository.getCachedNotificationsStateSync(normOrg)
        sessionManager.updateNotificationsState(cachedNotifs)

        val snapshot = repository.getCachedOrganizationSnapshotSync(normOrg)
        if (snapshot != null && snapshot.workItems.isNotEmpty()) {
            crudDelegate.clearAll()
            crudDelegate.recordServerSyncedItems(snapshot.workItems)
            _workItemsOrg.value = normOrg
            _allWorkItems.value = snapshot.workItems
            _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, snapshot.workItems.size)
            _stateDisplayLimits.value = emptyMap()
            _hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)
            streamDelegate.setLoading(false)
            sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normOrg))
        }
        return snapshot
    }

    private fun startIterativeBackgroundStream(organization: String, pat: String) {
        val normOrg = repository.extractOrganization(organization)
        val cleanPat = pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) return

        backgroundStreamJob?.cancel()
        backgroundStreamJob = viewModelScope.launch {
            val initialUnloaded = repository.getRemainingUnloadedCount(normOrg)
            if (initialUnloaded <= 0) {
                _hasMoreServerItems.value = false
                streamDelegate.setBackgroundSyncing(false)
                streamDelegate.setSyncProgress(null)
                streamDelegate.setSyncStatusText(null)
                return@launch
            }

            streamDelegate.setBackgroundSyncing(true)
            _hasMoreServerItems.value = true

            val pendingStreamBuffer = ArrayList<WorkItem>()
            var lastUiEmitMs = 0L

            suspend fun flushBufferedStreamItems(forcePersist: Boolean) {
                if (pendingStreamBuffer.isNotEmpty()) {
                    val toMerge = ArrayList(pendingStreamBuffer)
                    pendingStreamBuffer.clear()
                    mergeHydratedWorkItemsSuspend(toMerge)
                    _proposedDisplayLimit.value = maxOf(_proposedDisplayLimit.value, _allWorkItems.value.size)
                    lastUiEmitMs = System.currentTimeMillis()
                }
                if (forcePersist) {
                    repository.saveCachedOrganizationSnapshot(normOrg, _allWorkItems.value)
                }
            }

            while (repository.hasUnloadedServerItems(normOrg)) {
                val activeOrg = (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
                    ?: sessionManager.defaultOrganization.value
                if (activeOrg != null && !activeOrg.equals(normOrg, ignoreCase = true)) {
                    break
                }

                val loadedCount = _allWorkItems.value.size + pendingStreamBuffer.size
                val remainingCount = repository.getRemainingUnloadedCount(normOrg)
                val totalEstimate = (loadedCount + remainingCount).coerceAtLeast(1)
                streamDelegate.setSyncProgress((loadedCount.toFloat() / totalEstimate.toFloat()).coerceIn(0.05f, 0.99f))
                streamDelegate.setSyncStatusText("Syncing $loadedCount / $totalEstimate")

                val alreadyIds = _allWorkItems.value.map { it.id }.toHashSet().apply {
                    pendingStreamBuffer.forEach { add(it.id) }
                }
                val batchResult = repository.fetchNextWorkItemBatch(
                    organization = normOrg,
                    pat = cleanPat,
                    alreadyLoadedIds = alreadyIds,
                    batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
                )

                val fetchedBatch = batchResult.getOrNull()
                if (fetchedBatch.isNullOrEmpty()) {
                    if (batchResult.isFailure) {
                        break
                    }
                    if (!repository.hasUnloadedServerItems(normOrg)) {
                        break
                    }
                } else {
                    pendingStreamBuffer.addAll(fetchedBatch)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastUiEmitMs >= BACKGROUND_STREAM_UI_THROTTLE_MS) {
                        flushBufferedStreamItems(forcePersist = false)
                    }
                }
                delay(45L)
            }

            flushBufferedStreamItems(forcePersist = true)
            _hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)
            streamDelegate.setBackgroundSyncing(false)
            streamDelegate.setSyncProgress(null)
            streamDelegate.setSyncStatusText(null)
        }
    }

    private fun persistCurrentItemsInBackground(organization: String? = null) {
        val targetOrg = organization
            ?: _workItemsOrg.value
            ?: (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: sessionManager.defaultOrganization.value
            ?: return
        if (targetOrg.isBlank()) return
        val itemsSnapshot = _allWorkItems.value
        if (itemsSnapshot.isEmpty()) return

        snapshotPersistJob?.cancel()
        snapshotPersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(200L)
            repository.saveCachedOrganizationSnapshot(targetOrg, itemsSnapshot)
        }
    }

    fun connect(
        urlOrOrg: String,
        pat: String,
        rememberCredentials: Boolean = true
    ) {
        val normalizedOrg = repository.extractOrganization(urlOrOrg)
        val cleanPat = pat.trim()

        if (normalizedOrg.isBlank()) {
            sessionManager.updateErrorMessage("Please enter a valid Azure DevOps organization name or URL.")
            sessionManager.updateConnectionStatus(ConnectionStatus.Error("Invalid organization"))
            return
        }
        if (cleanPat.isBlank()) {
            sessionManager.updateErrorMessage("Please enter your Personal Access Token (PAT).")
            sessionManager.updateConnectionStatus(ConnectionStatus.Error("Missing PAT"))
            return
        }

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = viewModelScope.launch {
            sessionManager.updateErrorMessage(null)

            val currentItemsBelongToTargetOrg = _workItemsOrg.value.equals(normalizedOrg, ignoreCase = true)
            if (!currentItemsBelongToTargetOrg) {
                _allWorkItems.value = emptyList()
                restoreOrganizationFromLocalCache(normalizedOrg)
            }

            val hasCachedItemsForOrg = _workItemsOrg.value.equals(normalizedOrg, ignoreCase = true) &&
                _allWorkItems.value.isNotEmpty()

            if (!hasCachedItemsForOrg) {
                streamDelegate.setLoading(true)
                if (sessionManager.organizations.value.isEmpty()) {
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connecting)
                }
            } else {
                streamDelegate.setLoading(false)
                sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                streamDelegate.setBackgroundSyncing(true)
                streamDelegate.setSyncProgress(null)
                streamDelegate.setSyncStatusText("Checking for updates...")
            }

            val knownCachedProjs = repository.getCachedProjectsForOrg(normalizedOrg)
            val preResolvedProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, knownCachedProjs)

            if (hasCachedItemsForOrg) {
                val (deltaResult, fetchedProjs) = coroutineScope {
                    val deltaDef = async {
                        repository.syncDeltaAndQueueRemaining(
                            organization = normalizedOrg,
                            pat = cleanPat,
                            cachedItems = _allWorkItems.value
                        )
                    }
                    val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                    val metaDef = async {
                        if (!preResolvedProj.isNullOrBlank()) {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, preResolvedProj, cleanPat)
                        }
                    }
                    metaDef.await()
                    deltaDef.await() to projsDef.await()
                }

                deltaResult.fold(
                    onSuccess = { delta ->
                        saveAccountIntoMemoryAndPrefs(normalizedOrg, cleanPat, rememberCredentials)

                        if (delta.deletedItemIds.isNotEmpty()) {
                            delta.deletedItemIds.forEach { crudDelegate.clearPendingDirty(it) }
                            _allWorkItems.value = _allWorkItems.value.filterNot { it.id in delta.deletedItemIds }
                        }
                        if (delta.updatedOrNewItems.isNotEmpty()) {
                            mergeHydratedWorkItemsSuspend(delta.updatedOrNewItems)
                        }
                        _workItemsOrg.value = normalizedOrg
                        _proposedDisplayLimit.value = maxOf(_proposedDisplayLimit.value, _allWorkItems.value.size)
                        _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                        repository.saveCachedOrganizationSnapshot(normalizedOrg, _allWorkItems.value)

                        val fromItemsProjects = _allWorkItems.value
                            .mapNotNull { it.teamProject?.trim() }
                            .filter { it.isNotBlank() }
                        val mergedProjects = (fetchedProjs + fromItemsProjects + knownCachedProjs)
                            .distinctBy { it.lowercase() }
                            .sorted()
                        if (mergedProjects.isNotEmpty()) {
                            repository.saveCachedProjectsForOrg(normalizedOrg, mergedProjects)
                            sessionManager.putProjectsForOrg(normalizedOrg, mergedProjects)
                        }

                        val resolvedDefProj = sessionManager.resolveAndPersistDefaultProjectForOrg(
                            normalizedOrg,
                            mergedProjects
                        )
                        val validSelectedProj = selectedProject.value?.takeIf { current ->
                            mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                        } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                        if (validSelectedProj != null && !selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                            userManuallySelectedProject = false
                            userManuallySelectedType = false
                            filterDelegate.setTypeFilter(null)
                            filterDelegate.setStateFilter(null)
                            filterDelegate.setProject(validSelectedProj)
                        }

                        val targetProj = selectedProject.value ?: validSelectedProj
                        if (!targetProj.isNullOrBlank()) {
                            launch {
                                processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, targetProj, cleanPat)
                            }
                        }

                        val otherProjs = mergedProjects.filterNot { it.equals(targetProj, ignoreCase = true) }
                        if (otherProjs.isNotEmpty()) {
                            launch {
                                otherProjs.map { proj ->
                                    async {
                                        processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                    }
                                }.awaitAll()
                            }
                        }

                        streamDelegate.setLoading(false)
                        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, cleanPat)
                        } else {
                            streamDelegate.setBackgroundSyncing(false)
                            streamDelegate.setSyncProgress(null)
                            streamDelegate.setSyncStatusText(null)
                        }
                        sessionManager.onSyncNotificationsRequested?.invoke(true)
                    },
                    onFailure = { error ->
                        streamDelegate.setLoading(false)
                        streamDelegate.setBackgroundSyncing(false)
                        streamDelegate.setSyncProgress(null)
                        streamDelegate.setSyncStatusText(null)
                        sessionManager.updateErrorMessage(error.message ?: "Failed to connect to Azure DevOps.")
                    }
                )
                return@launch
            }

            val (itemsResult, fetchedProjs) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, cleanPat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                itemsDef.await() to projsDef.await()
            }

            itemsResult.fold(
                onSuccess = { items ->
                    saveAccountIntoMemoryAndPrefs(normalizedOrg, cleanPat, rememberCredentials)

                    crudDelegate.clearAll()
                    crudDelegate.recordServerSyncedItems(items)
                    _allWorkItems.value = items
                    _workItemsOrg.value = normalizedOrg
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val orgProjects = (fetchedProjs + fromItemsProjs + knownCachedProjs)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (orgProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, orgProjects)
                        sessionManager.putProjectsForOrg(normalizedOrg, orgProjects)
                    }

                    val chosenProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    userManuallySelectedProject = false
                    userManuallySelectedType = false
                    filterDelegate.resetFilters()
                    hierarchyDelegate.setSelectedWorkItem(null)
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    filterDelegate.setProject(chosenProj)

                    if (chosenProj != null) {
                        launch {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, cleanPat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                }
                            }.awaitAll()
                        }
                    }

                    streamDelegate.setLoading(false)
                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        streamDelegate.setBackgroundSyncing(false)
                        streamDelegate.setSyncProgress(null)
                        streamDelegate.setSyncStatusText(null)
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)
                },
                onFailure = { error ->
                    streamDelegate.setLoading(false)
                    streamDelegate.setBackgroundSyncing(false)
                    streamDelegate.setSyncProgress(null)
                    streamDelegate.setSyncStatusText(null)
                    sessionManager.updateErrorMessage(error.message ?: "Failed to connect to Azure DevOps.")
                    sessionManager.updateConnectionStatus(ConnectionStatus.Error(error.message ?: "Connection failed"))
                }
            )
        }
    }

    private fun saveAccountIntoMemoryAndPrefs(
        normalizedOrg: String,
        cleanPat: String,
        rememberCredentials: Boolean
    ) {
        val newAccount = OrganizationAccount(
            organization = normalizedOrg,
            pat = cleanPat,
            isRemembered = rememberCredentials
        )
        val currentAccounts = sessionManager.organizations.value.toMutableList()
        val existingIndex = currentAccounts.indexOfFirst { it.organization.equals(normalizedOrg, ignoreCase = true) }
        if (existingIndex >= 0) {
            currentAccounts[existingIndex] = newAccount
        } else {
            currentAccounts.add(newAccount)
        }
        val updatedOrgs = currentAccounts.distinctBy { it.organization.lowercase() }
        sessionManager.updateOrganizations(updatedOrgs)
        repository.saveOrganizationAccount(newAccount)

        if (sessionManager.defaultOrganization.value.isNullOrBlank() || updatedOrgs.size == 1) {
            sessionManager.updateDefaultOrganization(normalizedOrg)
            if (rememberCredentials) {
                repository.saveDefaultOrganization(normalizedOrg)
            }
        }
    }

    fun switchOrganization(organizationName: String) {
        val normOrg = repository.extractOrganization(organizationName)
        val account = sessionManager.organizations.value.firstOrNull {
            it.organization.equals(normOrg, ignoreCase = true)
        } ?: return

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()
        streamDelegate.setBackgroundSyncing(false)
        streamDelegate.setSyncProgress(null)
        streamDelegate.setSyncStatusText(null)

        userManuallySelectedProject = false
        userManuallySelectedType = false
        filterDelegate.resetFilters()
        hierarchyDelegate.setSelectedWorkItem(null)
        _workItemsOrg.value = account.organization
        _allWorkItems.value = emptyList()

        val knownProjectsForOrg = (sessionManager.projectsByOrg.value.entries
            .firstOrNull { it.key.equals(account.organization, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(account.organization))
            .distinctBy { it.lowercase() }
            .sorted()

        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(account.organization))
        filterDelegate.setProject(
            sessionManager.resolveAndPersistDefaultProjectForOrg(
                account.organization,
                knownProjectsForOrg
            ) ?: knownProjectsForOrg.firstOrNull()
        )

        val restored = restoreOrganizationFromLocalCache(account.organization)
        streamDelegate.setLoading(restored == null || restored.workItems.isEmpty())

        viewModelScope.launch {
            val dirtyList = crudDelegate.getPendingDirtyItems().values.toList()
            crudDelegate.clearAll()
            coroutineScope {
                dirtyList.map { dirtyItem ->
                    async { syncWorkItemToServer(dirtyItem) }
                }.awaitAll()
            }

            connect(account.organization, account.pat, rememberCredentials = account.isRemembered)
        }
    }

    fun saveOrganizationFromSettings(
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        val normalizedOrg = repository.extractOrganization(orgInput)
        val cleanPat = patInput.trim()
        if (normalizedOrg.isBlank()) {
            onResult(false, "Please enter a valid organization name or URL.")
            return
        }
        if (cleanPat.isBlank()) {
            onResult(false, "Please enter a valid Personal Access Token (PAT).")
            return
        }

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = viewModelScope.launch {
            val (workItemsResult, fetchedProjs) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, cleanPat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                itemsDef.await() to projsDef.await()
            }
            workItemsResult.fold(
                onSuccess = { items ->
                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                    val orgProjects = (fetchedProjs + fromItemsProjs + cachedForOrg)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (orgProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, orgProjects)
                        sessionManager.putProjectsForOrg(normalizedOrg, orgProjects)
                    }

                    val newAccount = OrganizationAccount(
                        organization = normalizedOrg,
                        pat = cleanPat,
                        isRemembered = isRemembered
                    )

                    val currentList = sessionManager.organizations.value.toMutableList()
                    if (!previousOrgName.isNullOrBlank() && !previousOrgName.equals(normalizedOrg, ignoreCase = true)) {
                        repository.removeOrganizationAccount(previousOrgName)
                        val prevIdx = currentList.indexOfFirst { it.organization.equals(previousOrgName, ignoreCase = true) }
                        if (prevIdx >= 0) {
                            currentList[prevIdx] = newAccount
                        } else {
                            currentList.add(newAccount)
                        }
                    } else {
                        val existingIdx = currentList.indexOfFirst { it.organization.equals(normalizedOrg, ignoreCase = true) }
                        if (existingIdx >= 0) {
                            currentList[existingIdx] = newAccount
                        } else {
                            currentList.add(newAccount)
                        }
                    }
                    val distinctList = currentList.distinctBy { it.organization.lowercase() }
                    sessionManager.updateOrganizations(distinctList)
                    repository.saveOrganizationAccount(newAccount)

                    val wasPreviousDefault = !previousOrgName.isNullOrBlank() &&
                        previousOrgName.equals(sessionManager.defaultOrganization.value, ignoreCase = true)
                    if (distinctList.size == 1 || sessionManager.defaultOrganization.value.isNullOrBlank() || wasPreviousDefault) {
                        sessionManager.updateDefaultOrganization(normalizedOrg)
                        if (isRemembered) {
                            repository.saveDefaultOrganization(normalizedOrg)
                        }
                    }

                    val chosenProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    userManuallySelectedProject = false
                    userManuallySelectedType = false
                    filterDelegate.resetFilters()
                    hierarchyDelegate.setSelectedWorkItem(null)
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()

                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    _workItemsOrg.value = normalizedOrg
                    filterDelegate.setProject(chosenProj)

                    crudDelegate.clearAll()
                    crudDelegate.recordServerSyncedItems(items)
                    _allWorkItems.value = items
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    if (chosenProj != null) {
                        launch {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, cleanPat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                }
                            }.awaitAll()
                        }
                    }

                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        streamDelegate.setBackgroundSyncing(false)
                        streamDelegate.setSyncProgress(null)
                        streamDelegate.setSyncStatusText(null)
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)

                    onResult(true, null)
                },
                onFailure = { err ->
                    onResult(false, err.message ?: "Failed to verify organization and PAT.")
                }
            )
        }
    }

    private suspend fun syncWorkItemToServer(itemToSync: WorkItem) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val baseline = crudDelegate.getPreviousSyncedItem(itemToSync.id)
        val result = repository.updateWorkItemOnServer(
            organization = org,
            pat = pat,
            previousItem = baseline,
            updatedItem = itemToSync,
            resolvedAssigneeIdentity = itemToSync.assignedToEmail
        )
        if (result.isSuccess) {
            crudDelegate.recordServerSyncedItem(itemToSync)
            crudDelegate.clearPendingDirty(itemToSync.id)
            repository.saveCachedOrganizationSnapshot(org, _allWorkItems.value)
        }
    }

    fun refreshWorkItems() {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val normalizedOrg = repository.extractOrganization(org)
        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = viewModelScope.launch {
            val itemsBelongToTargetOrg = _workItemsOrg.value.equals(normalizedOrg, ignoreCase = true)
            val hasExisting = itemsBelongToTargetOrg && _allWorkItems.value.isNotEmpty()
            streamDelegate.setPullRefreshing(true)
            if (!hasExisting) {
                streamDelegate.setLoading(true)
            } else {
                streamDelegate.setBackgroundSyncing(true)
                streamDelegate.setSyncProgress(null)
                streamDelegate.setSyncStatusText("Refreshing...")
            }
            sessionManager.updateErrorMessage(null)

            val dirtyList = crudDelegate.getPendingDirtyItems().values.toList()
            crudDelegate.clearAll()
            coroutineScope {
                dirtyList.map { dirtyItem ->
                    async { syncWorkItemToServer(dirtyItem) }
                }.awaitAll()
            }

            if (hasExisting) {
                val (deltaRes, fetchedProjs) = coroutineScope {
                    val deltaDef = async {
                        repository.syncDeltaAndQueueRemaining(
                            organization = normalizedOrg,
                            pat = pat,
                            cachedItems = _allWorkItems.value
                        )
                    }
                    val projsDef = async { repository.fetchProjects(normalizedOrg, pat) }
                    deltaDef.await() to projsDef.await()
                }
                deltaRes.fold(
                    onSuccess = { delta ->
                        if (delta.deletedItemIds.isNotEmpty()) {
                            delta.deletedItemIds.forEach { crudDelegate.clearPendingDirty(it) }
                            _allWorkItems.value = _allWorkItems.value.filterNot { it.id in delta.deletedItemIds }
                        }
                        if (delta.updatedOrNewItems.isNotEmpty()) {
                            mergeHydratedWorkItemsSuspend(delta.updatedOrNewItems)
                        }
                        _workItemsOrg.value = normalizedOrg
                        _proposedDisplayLimit.value = maxOf(_proposedDisplayLimit.value, _allWorkItems.value.size)
                        _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                        repository.saveCachedOrganizationSnapshot(normalizedOrg, _allWorkItems.value)

                        val fromItemsProjects = _allWorkItems.value.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                        val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                        val mergedProjects = (fetchedProjs + fromItemsProjects + cachedForOrg)
                            .distinctBy { it.lowercase() }
                            .sorted()
                        if (mergedProjects.isNotEmpty()) {
                            repository.saveCachedProjectsForOrg(normalizedOrg, mergedProjects)
                            sessionManager.putProjectsForOrg(normalizedOrg, mergedProjects)
                        }

                        val resolvedDefProj = sessionManager.resolveAndPersistDefaultProjectForOrg(
                            normalizedOrg,
                            mergedProjects
                        )
                        val validSelectedProj = selectedProject.value?.takeIf { current ->
                            mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                        } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                        if (validSelectedProj != null && !selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                            userManuallySelectedProject = false
                            userManuallySelectedType = false
                            filterDelegate.setTypeFilter(null)
                            filterDelegate.setStateFilter(null)
                            filterDelegate.setProject(validSelectedProj)
                        }

                        val targetProj = selectedProject.value ?: validSelectedProj
                        if (!targetProj.isNullOrBlank()) {
                            launch {
                                processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, targetProj, pat)
                            }
                        }
                        streamDelegate.setLoading(false)
                        streamDelegate.setPullRefreshing(false)
                        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, pat)
                        } else {
                            streamDelegate.setBackgroundSyncing(false)
                            streamDelegate.setSyncProgress(null)
                            streamDelegate.setSyncStatusText(null)
                        }
                        sessionManager.onSyncNotificationsRequested?.invoke(true)
                    },
                    onFailure = { error ->
                        streamDelegate.setLoading(false)
                        streamDelegate.setPullRefreshing(false)
                        streamDelegate.setBackgroundSyncing(false)
                        streamDelegate.setSyncProgress(null)
                        streamDelegate.setSyncStatusText(null)
                        sessionManager.updateErrorMessage(error.message ?: "Failed to refresh work items.")
                    }
                )
                return@launch
            }

            val (result, fetchedProjs) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, pat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, pat) }
                itemsDef.await() to projsDef.await()
            }

            result.fold(
                onSuccess = { items ->
                    crudDelegate.clearAll()
                    crudDelegate.recordServerSyncedItems(items)
                    _allWorkItems.value = items
                    _workItemsOrg.value = normalizedOrg
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                    val orgProjects = (fetchedProjs + fromItemsProjs + cachedForOrg)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (orgProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, orgProjects)
                        sessionManager.putProjectsForOrg(normalizedOrg, orgProjects)
                    }

                    val chosenProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    userManuallySelectedProject = false
                    userManuallySelectedType = false
                    filterDelegate.resetFilters()
                    hierarchyDelegate.setSelectedWorkItem(null)
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    filterDelegate.setProject(chosenProj)

                    if (chosenProj != null) {
                        launch {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, pat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, pat)
                                }
                            }.awaitAll()
                        }
                    }

                    streamDelegate.setLoading(false)
                    streamDelegate.setPullRefreshing(false)
                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, pat)
                    } else {
                        streamDelegate.setBackgroundSyncing(false)
                        streamDelegate.setSyncProgress(null)
                        streamDelegate.setSyncStatusText(null)
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)
                },
                onFailure = { error ->
                    streamDelegate.setLoading(false)
                    streamDelegate.setPullRefreshing(false)
                    streamDelegate.setBackgroundSyncing(false)
                    streamDelegate.setSyncProgress(null)
                    streamDelegate.setSyncStatusText(null)
                    sessionManager.updateErrorMessage(error.message ?: "Failed to refresh work items.")
                }
            )
        }
    }

    private suspend fun mergeHydratedWorkItemsSuspend(newItems: List<WorkItem>) {
        if (newItems.isEmpty()) return
        val current = _allWorkItems.value
        val pendingDirty = crudDelegate.getPendingDirtyItems()
        val map = current.associateBy { it.id }.toMutableMap()
        for (item in newItems) {
            crudDelegate.recordServerSyncedItem(item)
            if (!pendingDirty.containsKey(item.id)) {
                map[item.id] = item
            }
        }
        _allWorkItems.value = map.values.toList()
        selectedWorkItem.value?.let { currentSelected ->
            if (map.containsKey(currentSelected.id) && !pendingDirty.containsKey(currentSelected.id)) {
                hierarchyDelegate.setSelectedWorkItem(map[currentSelected.id])
            }
        }
    }

    fun loadMoreWorkItems() {
        val currentState = stateFilter.value
        val currentProject = selectedProject.value
        val currentType = typeFilter.value

        if (currentState == null || currentState.equals("All", ignoreCase = true)) {
            _proposedDisplayLimit.value = _proposedDisplayLimit.value + PROPOSED_PAGE_STEP
        } else {
            val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${currentState.trim().lowercase()}"
            val updated = _stateDisplayLimits.value.toMutableMap()
            val prevLimit = updated[stateKey] ?: STATE_INITIAL_BATCH_SIZE
            updated[stateKey] = prevLimit + STATE_PAGE_STEP
            _stateDisplayLimits.value = updated
        }

        if (isLoadingMore.value) return
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val isSpecificState = !currentState.isNullOrBlank() && !currentState.equals("All", ignoreCase = true)
        val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${currentState.orEmpty().trim().lowercase()}"
        val shouldQueryServer = if (isSpecificState) {
            stateKey !in exhaustedStateQueries || repository.hasUnloadedServerItems(org)
        } else {
            repository.hasUnloadedServerItems(org)
        }

        if (!shouldQueryServer) return

        viewModelScope.launch {
            streamDelegate.setLoadingMore(true)
            val existingIds = _allWorkItems.value.map { it.id }.toSet()
            val batchResult = repository.fetchNextWorkItemBatch(
                organization = org,
                pat = pat,
                project = currentProject,
                workItemType = currentType,
                state = if (isSpecificState) currentState else null,
                alreadyLoadedIds = existingIds,
                batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
            )
            batchResult.onSuccess { fetchedBatch ->
                if (fetchedBatch.isNotEmpty()) {
                    mergeHydratedWorkItemsSuspend(fetchedBatch)
                    persistCurrentItemsInBackground(org)
                } else if (isSpecificState) {
                    exhaustedStateQueries.add(stateKey)
                }
                _hasMoreServerItems.value = repository.hasUnloadedServerItems(org)
            }
            streamDelegate.setLoadingMore(false)
        }
    }

    fun disconnect() {
        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()
        streamDelegate.reset()
        repository.clearCredentials()
        sessionManager.updateOrganizations(emptyList())
        sessionManager.updateDefaultOrganization(null)
        _workItemsOrg.value = null
        crudDelegate.clearAll()
        _allWorkItems.value = emptyList()
        filterDelegate.clearAll()
        hierarchyDelegate.reset()
        activeCommentsJob?.cancel()
        commentsByWorkItemId.clear()
        sessionManager.updateErrorMessage(null)
        sessionManager.updateConnectionStatus(ConnectionStatus.Disconnected)
    }

    fun setSearchQuery(query: String) {
        filterDelegate.setSearchQuery(query)
    }

    fun setProject(project: String?) {
        val trimmed = project?.trim()
        if (trimmed.isNullOrBlank()) return
        userManuallySelectedProject = true
        userManuallySelectedType = false
        filterDelegate.setProject(trimmed)
        ensureProjectProcessLoaded(trimmed)
    }

    private fun ensureProjectProcessLoaded(project: String) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            viewModelScope.launch {
                processDelegate.fetchAndStoreProjectMetadata(org, project, pat)
            }
        }
    }

    fun setTypeFilter(type: String?) {
        if (type.isNullOrBlank()) return
        userManuallySelectedType = true
        filterDelegate.setTypeFilter(type)
        _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, _allWorkItems.value.size)
    }

    fun setStateFilter(state: String?) {
        if (state.isNullOrBlank()) return
        val trimmedState = state.trim()
        filterDelegate.setStateFilter(trimmedState)

        if (!trimmedState.equals("All", ignoreCase = true)) {
            val currentProject = selectedProject.value
            val currentType = typeFilter.value
            val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${trimmedState.lowercase()}"
            if (!_stateDisplayLimits.value.containsKey(stateKey)) {
                val updated = _stateDisplayLimits.value.toMutableMap()
                updated[stateKey] = maxOf(STATE_INITIAL_BATCH_SIZE, _allWorkItems.value.size)
                _stateDisplayLimits.value = updated
            }

            val cat = repository.resolveStateCategory(trimmedState, currentProject, currentType)
            if (cat != AzureDevOpsRepository.CATEGORY_IN_PROGRESS && stateKey !in exhaustedStateQueries) {
                val inMemoryForState = _allWorkItems.value.count { item ->
                    (currentProject == null || item.teamProject?.equals(currentProject, ignoreCase = true) == true) &&
                        (currentType == null || item.workItemType.equals(currentType, ignoreCase = true)) &&
                        item.state.equals(trimmedState, ignoreCase = true)
                }
                val targetLimit = _stateDisplayLimits.value[stateKey] ?: STATE_INITIAL_BATCH_SIZE
                if (inMemoryForState < targetLimit && !isLoadingMore.value) {
                    val acc = sessionManager.getActiveAccount()
                    val org = acc?.organization ?: repository.getConfiguredUrl()
                    val pat = acc?.pat ?: repository.getConfiguredPat()
                    if (org.isNotBlank() && pat.isNotBlank()) {
                        viewModelScope.launch {
                            streamDelegate.setLoadingMore(true)
                            val existingIds = _allWorkItems.value.map { it.id }.toSet()
                            val batchResult = repository.fetchNextWorkItemBatch(
                                organization = org,
                                pat = pat,
                                project = currentProject,
                                workItemType = currentType,
                                state = trimmedState,
                                alreadyLoadedIds = existingIds,
                                batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
                            )
                            batchResult.onSuccess { fetchedBatch ->
                                if (fetchedBatch.isNotEmpty()) {
                                    mergeHydratedWorkItemsSuspend(fetchedBatch)
                                    persistCurrentItemsInBackground(org)
                                } else {
                                    exhaustedStateQueries.add(stateKey)
                                }
                                _hasMoreServerItems.value = repository.hasUnloadedServerItems(org)
                            }
                            streamDelegate.setLoadingMore(false)
                        }
                    }
                }
            }
        }
    }

    fun setAssigneeFilter(assignee: String?) {
        filterDelegate.setAssigneeFilter(assignee)
    }

    fun setAreaFilter(areaPath: String?) {
        val norm = if (areaPath.isNullOrBlank() || areaPath.equals("All", ignoreCase = true)) {
            null
        } else {
            normalizeClassificationPath(areaPath).ifBlank { null }
        }
        filterDelegate.setAreaFilter(norm)
    }

    fun setIterationFilter(iterationPath: String?) {
        val norm = if (iterationPath.isNullOrBlank() || iterationPath.equals("All", ignoreCase = true)) {
            null
        } else {
            normalizeClassificationPath(iterationPath).ifBlank { null }
        }
        filterDelegate.setIterationFilter(norm)
    }

    fun getAreasForProject(project: String?): List<String> {
        val currentSnap = projectScopedSnapshotFlow.value
        if (project.isNullOrBlank() || project.equals(currentSnap.effectiveProject, ignoreCase = true)) {
            val cached = projectAreas.value
            if (cached.isNotEmpty()) return cached
        }
        val resolvedProj = resolveEffectiveProject(project ?: selectedProject.value, _allWorkItems.value)
            ?: project
            ?: selectedProject.value
            ?: ""
        if (resolvedProj.isBlank()) return emptyList()
        val resolvedProjLower = resolvedProj.trim().lowercase()
        val activeOrg = sessionManager.getActiveOrganization()
        val fromApi = findProjectAreas(activeOrg, resolvedProj, processDelegate.projectAreasMap.value).orEmpty()
        val fromItems = _allWorkItems.value
            .filter { it.normTeamProjectLower == resolvedProjLower }
            .mapNotNull { it.normAreaPath.takeIf { p -> p.isNotEmpty() } }
        return expandAndSortHierarchyPaths(resolvedProj, fromApi + fromItems)
    }

    fun getIterationsForProject(project: String?): List<String> {
        val currentSnap = projectScopedSnapshotFlow.value
        if (project.isNullOrBlank() || project.equals(currentSnap.effectiveProject, ignoreCase = true)) {
            val cached = projectIterations.value
            if (cached.isNotEmpty()) return cached
        }
        val resolvedProj = resolveEffectiveProject(project ?: selectedProject.value, _allWorkItems.value)
            ?: project
            ?: selectedProject.value
            ?: ""
        if (resolvedProj.isBlank()) return emptyList()
        val resolvedProjLower = resolvedProj.trim().lowercase()
        val activeOrg = sessionManager.getActiveOrganization()
        val fromApi = findProjectIterations(activeOrg, resolvedProj, processDelegate.projectIterationsMap.value).orEmpty()
        val fromItems = _allWorkItems.value
            .filter { it.normTeamProjectLower == resolvedProjLower }
            .mapNotNull { it.normIterationPath.takeIf { p -> p.isNotEmpty() } }
        return expandAndSortHierarchyPaths(resolvedProj, fromApi + fromItems)
    }

    fun selectWorkItem(item: WorkItem?) {
        if (item != null && searchQuery.value.isNotEmpty()) {
            filterDelegate.setSearchQuery("")
        }
        hierarchyDelegate.setSelectedWorkItem(item)
        activeCommentsJob?.cancel()

        if (item == null) {
            return
        }

        val cachedComments = commentsByWorkItemId[item.id].orEmpty()
        hierarchyDelegate.setSelectedWorkItemComments(cachedComments)

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        val project = item.teamProject ?: selectedProject.value ?: ""

        if (org.isNotBlank()) {
            viewModelScope.launch {
                val updatedState = repository.markNotificationsReadForWorkItem(org, item.id)
                sessionManager.updateNotificationsState(updatedState)
            }
        }

        if (org.isBlank() || pat.isBlank() || project.isBlank()) {
            hierarchyDelegate.setLoadingComments(false)
            return
        }

        activeCommentsJob = viewModelScope.launch {
            if (cachedComments.isEmpty()) {
                hierarchyDelegate.setLoadingComments(true)
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
                if (selectedWorkItem.value?.id == item.id) {
                    hierarchyDelegate.setSelectedWorkItemComments(sorted)
                }

                val mentionOutcome = repository.evaluateWorkItemCommentsForMentions(
                    organization = org,
                    workItem = item,
                    comments = serverComments
                )
                if (mentionOutcome.newlyTriggeredNotifications.isNotEmpty()) {
                    val appContext = getApplication<Application>().applicationContext
                    mentionOutcome.newlyTriggeredNotifications.forEach { notif ->
                        NotificationHelper.postSystemNotification(appContext, notif)
                    }
                }
                if (mentionOutcome.state.organization.isNotBlank()) {
                    val markedReadState = repository.markNotificationsReadForWorkItem(org, item.id)
                    sessionManager.updateNotificationsState(markedReadState)
                }
            }
            if (selectedWorkItem.value?.id == item.id) {
                hierarchyDelegate.setLoadingComments(false)
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
        if (selectedWorkItem.value?.id == workItem.id) {
            hierarchyDelegate.setSelectedWorkItemComments(updatedOptimistic)
        }

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        val project = workItem.teamProject ?: selectedProject.value ?: ""

        if (org.isBlank() || pat.isBlank() || project.isBlank()) {
            onResult(Result.success(optimisticComment))
            return
        }

        viewModelScope.launch {
            val result = repository.addWorkItemCommentOnServer(
                organization = org,
                pat = pat,
                project = project,
                workItemId = workItem.id,
                commentText = trimmed
            )
            result.fold(
                onSuccess = { serverComment ->
                    val existing = commentsByWorkItemId[workItem.id].orEmpty()
                    val replaced = existing.map { c ->
                        if (c.id == tempId) serverComment else c
                    }.distinctBy { if (it.id > 0) "id_${it.id}" else "local_${it.id}" }
                    commentsByWorkItemId[workItem.id] = replaced
                    if (selectedWorkItem.value?.id == workItem.id) {
                        hierarchyDelegate.setSelectedWorkItemComments(replaced)
                    }

                    val mentionOutcome = repository.evaluateWorkItemCommentsForMentions(
                        organization = org,
                        workItem = workItem,
                        comments = listOf(serverComment),
                        allowSystemNotification = true
                    )
                    if (mentionOutcome.newlyTriggeredNotifications.isNotEmpty()) {
                        val appContext = getApplication<Application>().applicationContext
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

    fun updateWorkItem(updatedItem: WorkItem) {
        val currentInMemory = _allWorkItems.value.firstOrNull { it.id == updatedItem.id }

        val resolvedEmail = if (updatedItem.assignedToName.isNullOrBlank() ||
            updatedItem.assignedToName.equals("Unassigned", ignoreCase = true)
        ) {
            null
        } else {
            _allWorkItems.value.firstOrNull {
                it.assignedToName?.trim()?.equals(updatedItem.assignedToName.trim(), ignoreCase = true) == true &&
                    !it.assignedToEmail.isNullOrBlank()
            }?.assignedToEmail ?: updatedItem.assignedToEmail
        }

        val normalizedUpdated = updatedItem.copy(assignedToEmail = resolvedEmail)

        _allWorkItems.value = _allWorkItems.value.map {
            if (it.id == normalizedUpdated.id) normalizedUpdated else it
        }
        if (selectedWorkItem.value?.id == normalizedUpdated.id) {
            hierarchyDelegate.setSelectedWorkItem(normalizedUpdated)
        }

        crudDelegate.recordPendingDirty(normalizedUpdated)
        persistCurrentItemsInBackground()

        val currentNotifState = sessionManager.notificationsState.value
        val effectiveName = currentNotifState.manualIdentityOverride?.takeIf { it.isNotBlank() }
            ?: currentNotifState.detectedDisplayName
        val detectedEmail = if (currentNotifState.manualIdentityOverride.isNullOrBlank()) {
            currentNotifState.detectedEmail
        } else null
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
                viewModelScope.launch {
                    val updatedNotifState = repository.removeAssignedNotificationForWorkItem(org, normalizedUpdated.id)
                    sessionManager.updateNotificationsState(updatedNotifState)
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

        val job = viewModelScope.launch {
            if (onlyTextChanged) {
                delay(400L)
            }
            syncWorkItemToServer(normalizedUpdated)
        }
        crudDelegate.recordUpdateJob(normalizedUpdated.id, job)
    }

    fun getStatesForWorkItem(item: WorkItem): List<String> {
        return getStatesForType(item.teamProject, item.workItemType, item.state)
    }

    fun getStatesForType(project: String?, workItemType: String, currentState: String? = null): List<String> {
        return hierarchyDelegate.getStatesForType(
            project = project ?: selectedProject.value,
            workItemType = workItemType,
            currentState = currentState,
            activeOrg = sessionManager.getActiveOrganization(),
            allItems = _allWorkItems.value,
            projectProcessMap = processDelegate.projectProcessMap.value,
            repository = repository
        )
    }

    fun getAllowedChildTypesForWorkItem(item: WorkItem): List<String> {
        return getAllowedChildTypesForType(item.teamProject, item.workItemType)
    }

    fun getAllowedChildTypesForType(project: String?, workItemType: String): List<String> {
        return hierarchyDelegate.getAllowedChildTypesForType(
            project = resolveEffectiveProject(project ?: selectedProject.value, _allWorkItems.value),
            workItemType = workItemType,
            activeOrg = sessionManager.getActiveOrganization(),
            allItems = _allWorkItems.value,
            projectProcessMap = processDelegate.projectProcessMap.value,
            projectCategoryMap = processDelegate.projectCategoryMap.value,
            projectBacklogLevelsMap = processDelegate.projectBacklogLevelsMap.value,
            repository = repository
        )
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
        onResult: (Result<WorkItem>) -> Unit = {}
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Please enter a title.")))
            return
        }
        val resolvedProject = project?.takeIf { it.isNotBlank() }
            ?: selectedProject.value?.takeIf { it.isNotBlank() }
            ?: availableProjects.value.firstOrNull()
            ?: ""
        val resolvedType = workItemType.trim().ifBlank {
            availableTypes.value.firstOrNull() ?: "Task"
        }
        val resolvedState = state.trim().takeUnless {
            it.isBlank() || it.equals("All", ignoreCase = true)
        } ?: getStatesForType(resolvedProject, resolvedType).firstOrNull() ?: "New"

        val cleanAssignee = assignedToName?.trim()?.takeUnless {
            it.isEmpty() || it.equals("Unassigned", ignoreCase = true)
        }
        val resolvedEmail = if (cleanAssignee == null) {
            null
        } else {
            _allWorkItems.value.firstOrNull {
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

        val nextBoardOrder = (_allWorkItems.value.mapNotNull { it.boardOrder }.maxOrNull() ?: 1000.0) + 10.0
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        if (org.isBlank() || pat.isBlank() || resolvedProject.isBlank()) {
            val localId = (_allWorkItems.value.maxOfOrNull { it.id } ?: 1000) + 1
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
            crudDelegate.recordServerSyncedItem(localCreated)
            _allWorkItems.value = _allWorkItems.value + localCreated
            persistCurrentItemsInBackground(org)
            userManuallySelectedType = true
            filterDelegate.setTypeFilter(localCreated.workItemType)
            if (stateFilter.value != null &&
                !stateFilter.value.equals("All", ignoreCase = true) &&
                !stateFilter.value.equals(localCreated.state, ignoreCase = true)
            ) {
                filterDelegate.setStateFilter("All")
            }
            onResult(Result.success(localCreated))
            return
        }

        viewModelScope.launch {
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
                    crudDelegate.recordServerSyncedItem(finalCreated)
                    _allWorkItems.value = (_allWorkItems.value.filterNot { it.id == finalCreated.id } + finalCreated)
                    persistCurrentItemsInBackground(org)
                    userManuallySelectedType = true
                    filterDelegate.setTypeFilter(finalCreated.workItemType)
                    if (stateFilter.value != null &&
                        !stateFilter.value.equals("All", ignoreCase = true) &&
                        !stateFilter.value.equals(finalCreated.state, ignoreCase = true)
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

        val project = parent.teamProject ?: selectedProject.value ?: ""
        val resolvedType = childType.takeIf { candidate ->
            candidate.isNotBlank() && allowedChildTypes.any { it.equals(candidate, ignoreCase = true) }
        } ?: allowedChildTypes.first()
        val resolvedState = initialState.ifBlank {
            getStatesForType(project, resolvedType).firstOrNull() ?: "New"
        }

        val tempId = (_allWorkItems.value.maxOfOrNull { it.id } ?: 1000) + 1
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val existingChildren = _allWorkItems.value.filter {
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

        _allWorkItems.value = (_allWorkItems.value.map {
            if (it.id == parent.id) updatedParent else it
        } + optimisticChild)
        persistCurrentItemsInBackground()

        if (selectedWorkItem.value?.id == parent.id) {
            hierarchyDelegate.setSelectedWorkItem(updatedParent)
        }

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        viewModelScope.launch {
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

                _allWorkItems.value = _allWorkItems.value.map { item ->
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
                persistCurrentItemsInBackground(org)
                if (selectedWorkItem.value?.id == parent.id) {
                    hierarchyDelegate.setSelectedWorkItem(_allWorkItems.value.firstOrNull { it.id == parent.id })
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
            _allWorkItems.value.any { it.id == childId && it.parentId == parent.id }
        if (alreadyChild) {
            onResult(Result.failure(IllegalArgumentException("Work item #$childId is already a child of this item.")))
            return
        }

        val byId = _allWorkItems.value.associateBy { it.id }
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
            _allWorkItems.value = _allWorkItems.value.map { item ->
                when (item.id) {
                    childId -> updatedChild
                    parent.id -> item.copy(childIds = (item.childIds + childId).distinct())
                    previousParentId -> item.copy(childIds = item.childIds.filterNot { it == childId })
                    else -> item
                }
            }
            if (selectedWorkItem.value?.id == parent.id) {
                hierarchyDelegate.setSelectedWorkItem(_allWorkItems.value.firstOrNull { it.id == parent.id })
            }
            persistCurrentItemsInBackground(org)

            if (org.isBlank() || pat.isBlank()) {
                onResult(Result.success(updatedChild))
                return
            }
        } else if (org.isBlank() || pat.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Work item #$childId was not found.")))
            return
        }

        viewModelScope.launch {
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

                    val currentItems = _allWorkItems.value
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

                    _allWorkItems.value = updatedList
                    if (selectedWorkItem.value?.id == parent.id) {
                        hierarchyDelegate.setSelectedWorkItem(_allWorkItems.value.firstOrNull { it.id == parent.id })
                    }
                    persistCurrentItemsInBackground(org)
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

        _allWorkItems.value = _allWorkItems.value.map { item ->
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

        if (selectedWorkItem.value?.id == parent.id) {
            hierarchyDelegate.setSelectedWorkItem(_allWorkItems.value.firstOrNull { it.id == parent.id })
        }
        persistCurrentItemsInBackground(org)

        if (org.isBlank() || pat.isBlank()) return

        viewModelScope.launch {
            repository.unlinkChildWorkItemOnServer(
                organization = org,
                pat = pat,
                parentId = parent.id,
                childId = childId
            )
        }
    }
}
