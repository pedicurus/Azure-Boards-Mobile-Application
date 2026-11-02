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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature ViewModel responsible for Azure Boards work items, filtering, progressive background
 * streaming, delta sync, process metadata & hierarchy rules, optimistic CRUD, and item discussions.
 */
class WorkItemsViewModel(
    application: Application,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    private val repository = sessionManager.repository

    private val _allWorkItems = MutableStateFlow<List<WorkItem>>(emptyList())
    val allWorkItems: StateFlow<List<WorkItem>> = _allWorkItems.asStateFlow()

    /**
     * Tracks which organization the items currently in [_allWorkItems] belong to.
     */
    private val _workItemsOrg = MutableStateFlow<String?>(null)

    /**
     * Tracks the last server-synchronized state of each work item so we can compute JSON Patch
     * operations accurately when syncing edits back to Azure DevOps.
     */
    private val serverSyncedItems = ConcurrentHashMap<Int, WorkItem>()

    /**
     * Tracks work items that have local unsaved/in-flight edits so a refresh never overwrites them
     * before they finish syncing to Azure DevOps.
     */
    private val pendingDirtyItems = ConcurrentHashMap<Int, WorkItem>()

    /**
     * Active debounce jobs per work item ID for syncing edits to Azure DevOps.
     */
    private val updateJobs = ConcurrentHashMap<Int, Job>()

    private val _projectProcessMap = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    private val _projectCategoryMap = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    private val _projectBacklogLevelsMap = MutableStateFlow<Map<String, List<List<String>>>>(emptyMap())
    private val _projectAreasMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val _projectIterationsMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    private val _stateFilter = MutableStateFlow<String?>(null)
    val stateFilter: StateFlow<String?> = _stateFilter.asStateFlow()

    private val _assigneeFilter = MutableStateFlow<String?>(null)
    val assigneeFilter: StateFlow<String?> = _assigneeFilter.asStateFlow()

    private val _areaFilter = MutableStateFlow<String?>(null)
    val areaFilter: StateFlow<String?> = _areaFilter.asStateFlow()

    private val _iterationFilter = MutableStateFlow<String?>(null)
    val iterationFilter: StateFlow<String?> = _iterationFilter.asStateFlow()

    private var userManuallySelectedProject = false
    private var userManuallySelectedType = false

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isSearchingServer = MutableStateFlow(false)
    val isSearchingServer: StateFlow<Boolean> = _isSearchingServer.asStateFlow()

    private val _isBackgroundSyncing = MutableStateFlow(false)
    val isBackgroundSyncing: StateFlow<Boolean> = _isBackgroundSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<Float?>(null)
    val syncProgress: StateFlow<Float?> = _syncProgress.asStateFlow()

    private val _isPullRefreshing = MutableStateFlow(false)
    val isPullRefreshing: StateFlow<Boolean> = _isPullRefreshing.asStateFlow()

    private val _syncStatusText = MutableStateFlow<String?>(null)
    val syncStatusText: StateFlow<String?> = _syncStatusText.asStateFlow()

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

    private val _selectedWorkItem = MutableStateFlow<WorkItem?>(null)
    val selectedWorkItem: StateFlow<WorkItem?> = _selectedWorkItem.asStateFlow()

    private val _selectedWorkItemComments = MutableStateFlow<List<WorkItemComment>>(emptyList())
    val selectedWorkItemComments: StateFlow<List<WorkItemComment>> = _selectedWorkItemComments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow(false)
    val isLoadingComments: StateFlow<Boolean> = _isLoadingComments.asStateFlow()

    private val commentsByWorkItemId = ConcurrentHashMap<Int, List<WorkItemComment>>()
    private var activeCommentsJob: Job? = null

    val selectedWorkItemChildren: StateFlow<List<WorkItem>> = combine(
        _selectedWorkItem,
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
            if (fallback != null && !_selectedProject.value.equals(fallback, ignoreCase = true)) {
                _selectedProject.value = fallback
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
        _selectedProject,
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
                effectiveProject = _selectedProject.value,
                effectiveProjectLower = _selectedProject.value?.trim()?.lowercase().orEmpty(),
                projectItems = emptyList(),
                allItems = emptyList()
            )
        )

    private val projectAndTypeScopedSnapshotFlow: StateFlow<ProjectAndTypeScopedSnapshot> = combine(
        projectScopedSnapshotFlow,
        _typeFilter
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
                effectiveProject = _selectedProject.value,
                effectiveProjectLower = _selectedProject.value?.trim()?.lowercase().orEmpty(),
                selectedType = _typeFilter.value,
                selectedTypeLower = _typeFilter.value?.trim()?.lowercase().orEmpty(),
                projectAndTypeItems = emptyList(),
                projectItems = emptyList()
            )
        )

    val availableTypes: StateFlow<List<String>> = combine(
        projectScopedSnapshotFlow,
        _projectProcessMap,
        _projectCategoryMap,
        _projectBacklogLevelsMap
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
        _projectProcessMap
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
        _projectAreasMap
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
        _projectIterationsMap
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
        combine(_stateFilter, _proposedDisplayLimit, _stateDisplayLimits) { state, propLimit, stateLimits ->
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
        combine(projectScopedSnapshotFlow, _searchQuery) { projSnap, query ->
            projSnap to query
        }.distinctUntilChanged(),
        combine(_typeFilter, _stateFilter, _assigneeFilter) { type, state, assignee ->
            Triple(type, state, assignee)
        }.distinctUntilChanged(),
        combine(_areaFilter, _iterationFilter) { area, iteration ->
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
        _searchQuery,
        combine(_typeFilter, _stateFilter, _assigneeFilter, _areaFilter, _iterationFilter) { t, s, a, ar, it ->
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
        _searchQuery,
        _typeFilter,
        _stateFilter,
        _selectedProject,
        _assigneeFilter,
        _areaFilter,
        _iterationFilter,
        availableTypes,
        availableStates,
        availableProjects,
        projectAssignees,
        projectFrequentTags,
        projectAreas,
        projectIterations,
        _isLoading,
        _isLoadingMore,
        _isSearchingServer,
        _isBackgroundSyncing,
        _syncProgress,
        _isPullRefreshing,
        _syncStatusText,
        hasMoreItems,
        _selectedWorkItem,
        selectedWorkItemChildren,
        _selectedWorkItemComments,
        _isLoadingComments
    ) { values ->
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
            val isDifferentProject = !_selectedProject.value.equals(trimmedProj, ignoreCase = true)
            userManuallySelectedProject = false
            userManuallySelectedType = false
            if (isDifferentProject) {
                _areaFilter.value = null
                _iterationFilter.value = null
            }
            _selectedProject.value = trimmedProj
            ensureProjectProcessLoaded(trimmedProj)
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
            _selectedProject.value = initialDefProj
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
                val currentProj = _selectedProject.value
                val isCurrentInList = currentProj != null && projects.any { it.equals(currentProj, ignoreCase = true) }
                if (!userManuallySelectedProject || !isCurrentInList) {
                    val chosen = resolvedDefProj ?: projects.first()
                    if (!_selectedProject.value.equals(chosen, ignoreCase = true)) {
                        _selectedProject.value = chosen
                    }
                    ensureProjectProcessLoaded(chosen)
                }
            }
        }

        viewModelScope.launch {
            availableTypes.collectLatest { types ->
                if (types.isEmpty()) return@collectLatest
                val currentType = _typeFilter.value
                val isCurrentInList = currentType != null && types.any { it.equals(currentType, ignoreCase = true) }
                if (!userManuallySelectedType || !isCurrentInList) {
                    val biggestType = types.first()
                    if (_typeFilter.value != biggestType) {
                        _typeFilter.value = biggestType
                        _stateFilter.value = "All"
                    }
                }
            }
        }

        viewModelScope.launch {
            availableStates.collectLatest { states ->
                if (states.isEmpty()) return@collectLatest
                val currentState = _stateFilter.value
                val isCurrentInList = currentState != null && states.any { it.equals(currentState, ignoreCase = true) }
                if (currentState == null || !isCurrentInList) {
                    _stateFilter.value = "All"
                }
            }
        }
    }

    private fun observeOnDemandServerSearch() {
        viewModelScope.launch {
            _searchQuery.collectLatest { rawQuery ->
                val cleanQuery = rawQuery.trim()
                if (cleanQuery.length < 2) {
                    _isSearchingServer.value = false
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

                _isSearchingServer.value = true
                val searchResult = repository.searchWorkItemsOnServer(
                    organization = org,
                    pat = pat,
                    query = cleanQuery,
                    project = _selectedProject.value,
                    alreadyLoadedIds = existingIds
                )
                searchResult.onSuccess { fetchedSearchItems ->
                    if (fetchedSearchItems.isNotEmpty()) {
                        mergeHydratedWorkItemsSuspend(fetchedSearchItems)
                    }
                }
                _isSearchingServer.value = false
            }
        }
    }

    private fun restoreOrganizationFromLocalCache(organization: String): CachedOrganizationSnapshot? {
        val normOrg = repository.extractOrganization(organization)
        if (normOrg.isBlank()) return null

        val cachedMeta = repository.getCachedOrganizationMetadataSync(normOrg)
        if (cachedMeta != null) {
            if (cachedMeta.processHierarchyByProject.isNotEmpty()) {
                _projectProcessMap.value = _projectProcessMap.value + cachedMeta.processHierarchyByProject
            }
            if (cachedMeta.categoryMapByProject.isNotEmpty()) {
                _projectCategoryMap.value = _projectCategoryMap.value + cachedMeta.categoryMapByProject
            }
            if (cachedMeta.backlogLevelsByProject.isNotEmpty()) {
                _projectBacklogLevelsMap.value = _projectBacklogLevelsMap.value + cachedMeta.backlogLevelsByProject
            }
            if (cachedMeta.areasByProject.isNotEmpty()) {
                _projectAreasMap.value = _projectAreasMap.value + cachedMeta.areasByProject
            }
            if (cachedMeta.iterationsByProject.isNotEmpty()) {
                _projectIterationsMap.value = _projectIterationsMap.value + cachedMeta.iterationsByProject
            }
        }

        val cachedNotifs = repository.getCachedNotificationsStateSync(normOrg)
        sessionManager.updateNotificationsState(cachedNotifs)

        val snapshot = repository.getCachedOrganizationSnapshotSync(normOrg)
        if (snapshot != null && snapshot.workItems.isNotEmpty()) {
            serverSyncedItems.clear()
            snapshot.workItems.forEach { serverSyncedItems[it.id] = it }
            _workItemsOrg.value = normOrg
            _allWorkItems.value = snapshot.workItems
            _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, snapshot.workItems.size)
            _stateDisplayLimits.value = emptyMap()
            _hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)
            _isLoading.value = false
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
                _isBackgroundSyncing.value = false
                _syncProgress.value = null
                _syncStatusText.value = null
                return@launch
            }

            _isBackgroundSyncing.value = true
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
                _syncProgress.value = (loadedCount.toFloat() / totalEstimate.toFloat()).coerceIn(0.05f, 0.99f)
                _syncStatusText.value = "Syncing $loadedCount / $totalEstimate"

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
            _isBackgroundSyncing.value = false
            _syncProgress.value = null
            _syncStatusText.value = null
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

    private suspend fun fetchAndStoreProjectMetadata(
        organization: String,
        project: String,
        pat: String
    ) {
        val normOrg = repository.extractOrganization(organization)
        val cleanProject = project.trim()
        if (normOrg.isBlank() || cleanProject.isBlank() || pat.isBlank()) return

        val scopedKey = projectScopedKey(normOrg, cleanProject)
        coroutineScope {
            val processDeferred = async { repository.fetchProjectProcessHierarchy(normOrg, cleanProject, pat) }
            val classificationDeferred = async {
                repository.fetchProjectClassificationPaths(normOrg, cleanProject, pat)
            }

            val processHierarchy = processDeferred.await()
            val activeTypes = processHierarchy.keys
                .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
                .toList()
            val backlogMeta = repository.fetchProjectBacklogMetadata(
                organization = normOrg,
                project = cleanProject,
                pat = pat,
                activeTypesHint = activeTypes
            )
            val (areas, iterations) = classificationDeferred.await()

            val categoryMap = backlogMeta.categoryMap
            val backlogLevels = backlogMeta.orderedBacklogLevels

            if (processHierarchy.isNotEmpty()) {
                _projectProcessMap.value = _projectProcessMap.value + (scopedKey to processHierarchy)
            }
            if (categoryMap.isNotEmpty()) {
                _projectCategoryMap.value = _projectCategoryMap.value + (scopedKey to categoryMap)
            }
            if (backlogLevels.isNotEmpty()) {
                _projectBacklogLevelsMap.value = _projectBacklogLevelsMap.value + (scopedKey to backlogLevels)
            }
            if (areas.isNotEmpty()) {
                _projectAreasMap.value = _projectAreasMap.value + (scopedKey to areas)
            }
            if (iterations.isNotEmpty()) {
                _projectIterationsMap.value = _projectIterationsMap.value + (scopedKey to iterations)
            }

            repository.saveCachedOrganizationMetadata(
                organization = normOrg,
                processHierarchyByProject = _projectProcessMap.value,
                categoryMapByProject = _projectCategoryMap.value,
                backlogLevelsByProject = _projectBacklogLevelsMap.value,
                areasByProject = _projectAreasMap.value,
                iterationsByProject = _projectIterationsMap.value
            )
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
                _isLoading.value = true
                if (sessionManager.organizations.value.isEmpty()) {
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connecting)
                }
            } else {
                _isLoading.value = false
                sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                _isBackgroundSyncing.value = true
                _syncProgress.value = null
                _syncStatusText.value = "Checking for updates..."
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
                            fetchAndStoreProjectMetadata(normalizedOrg, preResolvedProj, cleanPat)
                        }
                    }
                    metaDef.await()
                    deltaDef.await() to projsDef.await()
                }

                deltaResult.fold(
                    onSuccess = { delta ->
                        saveAccountIntoMemoryAndPrefs(normalizedOrg, cleanPat, rememberCredentials)

                        if (delta.deletedItemIds.isNotEmpty()) {
                            delta.deletedItemIds.forEach { serverSyncedItems.remove(it) }
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
                        val validSelectedProj = _selectedProject.value?.takeIf { current ->
                            mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                        } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                        if (validSelectedProj != null && !_selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                            userManuallySelectedProject = false
                            userManuallySelectedType = false
                            _typeFilter.value = null
                            _stateFilter.value = null
                            _selectedProject.value = validSelectedProj
                        }

                        val otherProjects = mergedProjects.filterNot { it.equals(preResolvedProj, ignoreCase = true) }
                        if (otherProjects.isNotEmpty()) {
                            launch {
                                otherProjects.map { proj ->
                                    async { fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat) }
                                }.awaitAll()
                            }
                        }

                        _isLoading.value = false
                        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, cleanPat)
                        } else {
                            _isBackgroundSyncing.value = false
                            _syncProgress.value = null
                            _syncStatusText.value = null
                        }
                        sessionManager.onSyncNotificationsRequested?.invoke(true)
                    },
                    onFailure = {
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
                        _isLoading.value = false
                    }
                )
                return@launch
            }

            val (result, fetchedProjs) = coroutineScope {
                val itemsDeferred = async { repository.fetchWorkItems(normalizedOrg, cleanPat) }
                val projectsDeferred = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                val preMetaDeferred = async {
                    if (!preResolvedProj.isNullOrBlank()) {
                        fetchAndStoreProjectMetadata(normalizedOrg, preResolvedProj, cleanPat)
                    }
                }
                preMetaDeferred.await()
                itemsDeferred.await() to projectsDeferred.await()
            }

            result.fold(
                onSuccess = { items ->
                    saveAccountIntoMemoryAndPrefs(normalizedOrg, cleanPat, rememberCredentials)

                    val fromItemsProjects = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
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
                    val targetProject = resolvedDefProj ?: mergedProjects.firstOrNull()

                    userManuallySelectedProject = false
                    userManuallySelectedType = false
                    _searchQuery.value = ""
                    _typeFilter.value = null
                    _stateFilter.value = null
                    _assigneeFilter.value = null
                    _areaFilter.value = null
                    _iterationFilter.value = null
                    _selectedWorkItem.value = null
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()

                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    _workItemsOrg.value = normalizedOrg
                    _selectedProject.value = targetProject

                    serverSyncedItems.clear()
                    items.forEach { serverSyncedItems[it.id] = it }
                    _allWorkItems.value = items
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                    _isLoading.value = false

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    if (!targetProject.isNullOrBlank() && !targetProject.equals(preResolvedProj, ignoreCase = true)) {
                        launch {
                            fetchAndStoreProjectMetadata(normalizedOrg, targetProject, cleanPat)
                        }
                    }

                    val otherProjects = mergedProjects.filterNot {
                        it.equals(targetProject, ignoreCase = true) || it.equals(preResolvedProj, ignoreCase = true)
                    }
                    if (otherProjects.isNotEmpty()) {
                        launch {
                            otherProjects.map { proj ->
                                async {
                                    fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                }
                            }.awaitAll()
                        }
                    }

                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)
                },
                onFailure = { error ->
                    val message = error.message ?: "Failed to connect to Azure DevOps."
                    _isLoading.value = false
                    _isBackgroundSyncing.value = false
                    _syncProgress.value = null
                    _syncStatusText.value = null
                    sessionManager.updateErrorMessage(message)
                    if (sessionManager.organizations.value.isEmpty()) {
                        sessionManager.updateConnectionStatus(ConnectionStatus.Error(message))
                    }
                }
            )
        }
    }

    private fun saveAccountIntoMemoryAndPrefs(
        normalizedOrg: String,
        cleanPat: String,
        rememberCredentials: Boolean
    ) {
        val account = OrganizationAccount(
            organization = normalizedOrg,
            pat = cleanPat,
            isRemembered = rememberCredentials
        )
        val updatedOrgs = sessionManager.organizations.value.toMutableList()
        val idx = updatedOrgs.indexOfFirst { it.organization.equals(normalizedOrg, ignoreCase = true) }
        if (idx >= 0) {
            updatedOrgs[idx] = account
        } else {
            updatedOrgs.add(account)
        }
        sessionManager.updateOrganizations(updatedOrgs)
        repository.saveOrganizationAccount(account)

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
        _isBackgroundSyncing.value = false
        _syncProgress.value = null
        _syncStatusText.value = null

        userManuallySelectedProject = false
        userManuallySelectedType = false
        _searchQuery.value = ""
        _typeFilter.value = null
        _stateFilter.value = null
        _assigneeFilter.value = null
        _areaFilter.value = null
        _iterationFilter.value = null
        _selectedWorkItem.value = null
        _workItemsOrg.value = account.organization
        _allWorkItems.value = emptyList()

        val knownProjectsForOrg = (sessionManager.projectsByOrg.value.entries
            .firstOrNull { it.key.equals(account.organization, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(account.organization))
            .distinctBy { it.lowercase() }
            .sorted()

        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(account.organization))
        _selectedProject.value = sessionManager.resolveAndPersistDefaultProjectForOrg(
            account.organization,
            knownProjectsForOrg
        ) ?: knownProjectsForOrg.firstOrNull()

        val restored = restoreOrganizationFromLocalCache(account.organization)
        _isLoading.value = restored == null || restored.workItems.isEmpty()

        viewModelScope.launch {
            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            val dirtyList = pendingDirtyItems.values.toList()
            coroutineScope {
                dirtyList.map { dirtyItem ->
                    async { syncWorkItemToServer(dirtyItem) }
                }.awaitAll()
            }
            pendingDirtyItems.clear()

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
                    _searchQuery.value = ""
                    _typeFilter.value = null
                    _stateFilter.value = null
                    _assigneeFilter.value = null
                    _areaFilter.value = null
                    _iterationFilter.value = null
                    _selectedWorkItem.value = null
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()

                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    _workItemsOrg.value = normalizedOrg
                    _selectedProject.value = chosenProj

                    serverSyncedItems.clear()
                    items.forEach { serverSyncedItems[it.id] = it }
                    _allWorkItems.value = items
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    if (chosenProj != null) {
                        launch {
                            fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, cleanPat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                }
                            }.awaitAll()
                        }
                    }

                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
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

        val baseline = serverSyncedItems[itemToSync.id]
        val result = repository.updateWorkItemOnServer(
            organization = org,
            pat = pat,
            previousItem = baseline,
            updatedItem = itemToSync,
            resolvedAssigneeIdentity = itemToSync.assignedToEmail
        )
        if (result.isSuccess) {
            serverSyncedItems[itemToSync.id] = itemToSync
            pendingDirtyItems.remove(itemToSync.id, itemToSync)
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
            _isPullRefreshing.value = true
            if (!hasExisting) {
                _isLoading.value = true
            } else {
                _isBackgroundSyncing.value = true
                _syncProgress.value = null
                _syncStatusText.value = "Refreshing..."
            }
            sessionManager.updateErrorMessage(null)

            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            val dirtyList = pendingDirtyItems.values.toList()
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
                            delta.deletedItemIds.forEach { serverSyncedItems.remove(it) }
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
                        val validSelectedProj = _selectedProject.value?.takeIf { current ->
                            mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                        } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                        if (validSelectedProj != null && !_selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                            userManuallySelectedProject = false
                            userManuallySelectedType = false
                            _typeFilter.value = null
                            _stateFilter.value = null
                            _selectedProject.value = validSelectedProj
                        }

                        val targetProj = _selectedProject.value ?: validSelectedProj
                        if (!targetProj.isNullOrBlank()) {
                            launch {
                                fetchAndStoreProjectMetadata(normalizedOrg, targetProj, pat)
                            }
                        }
                        _isLoading.value = false
                        _isPullRefreshing.value = false
                        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, pat)
                        } else {
                            _isBackgroundSyncing.value = false
                            _syncProgress.value = null
                            _syncStatusText.value = null
                        }
                        sessionManager.onSyncNotificationsRequested?.invoke(true)
                    },
                    onFailure = { error ->
                        _isLoading.value = false
                        _isPullRefreshing.value = false
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
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
                    val resolvedItems = items.map { fetched ->
                        pendingDirtyItems[fetched.id] ?: fetched
                    }
                    resolvedItems.forEach { item ->
                        if (!pendingDirtyItems.containsKey(item.id)) {
                            serverSyncedItems[item.id] = item
                        }
                    }

                    val fromItemsProjects = resolvedItems.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
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
                    val validSelectedProj = _selectedProject.value?.takeIf { current ->
                        mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                    } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    _workItemsOrg.value = normalizedOrg
                    if (validSelectedProj != null && !_selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                        userManuallySelectedProject = false
                        userManuallySelectedType = false
                        _typeFilter.value = null
                        _stateFilter.value = null
                        _selectedProject.value = validSelectedProj
                    }

                    _allWorkItems.value = resolvedItems
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, resolvedItems.size)
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                    repository.saveCachedOrganizationSnapshot(normalizedOrg, resolvedItems)

                    _selectedWorkItem.value?.let { currentSelected ->
                        _selectedWorkItem.value = resolvedItems.firstOrNull { it.id == currentSelected.id }
                    }
                    val targetProj = _selectedProject.value ?: validSelectedProj
                    if (!targetProj.isNullOrBlank()) {
                        launch {
                            fetchAndStoreProjectMetadata(normalizedOrg, targetProj, pat)
                        }
                    }
                    _isLoading.value = false
                    _isPullRefreshing.value = false

                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, pat)
                    } else {
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)
                },
                onFailure = { error ->
                    _isLoading.value = false
                    _isPullRefreshing.value = false
                    _isBackgroundSyncing.value = false
                    _syncProgress.value = null
                    _syncStatusText.value = null
                    sessionManager.updateErrorMessage(error.message ?: "Failed to refresh work items.")
                }
            )
        }
    }

    suspend fun mergeHydratedWorkItemsSuspend(newItems: List<WorkItem>) {
        if (newItems.isEmpty()) return
        val mergedList = withContext(Dispatchers.Default) {
            val resolvedNew = newItems.map { fetched ->
                pendingDirtyItems[fetched.id] ?: fetched
            }
            resolvedNew.forEach { item ->
                if (!pendingDirtyItems.containsKey(item.id)) {
                    serverSyncedItems[item.id] = item
                }
            }

            val combinedMap = LinkedHashMap<Int, WorkItem>()
            for (existing in _allWorkItems.value) {
                combinedMap[existing.id] = existing
            }
            for (incoming in resolvedNew) {
                combinedMap[incoming.id] = incoming
            }

            val parentToChildren = mutableMapOf<Int, MutableSet<Int>>()
            val childToParent = mutableMapOf<Int, Int>()
            for (item in combinedMap.values) {
                if (item.parentId != null) {
                    childToParent[item.id] = item.parentId
                    parentToChildren.getOrPut(item.parentId) { mutableSetOf() }.add(item.id)
                }
                for (cId in item.childIds) {
                    parentToChildren.getOrPut(item.id) { mutableSetOf() }.add(cId)
                    if (!childToParent.containsKey(cId)) {
                        childToParent[cId] = item.id
                    }
                }
            }

            combinedMap.values.map { item ->
                item.copy(
                    parentId = item.parentId ?: childToParent[item.id],
                    childIds = parentToChildren[item.id]?.toList() ?: item.childIds
                )
            }
        }
        _allWorkItems.value = mergedList
        _selectedWorkItem.value?.let { currentSelected ->
            _selectedWorkItem.value = mergedList.firstOrNull { it.id == currentSelected.id }
        }
    }

    fun loadMoreWorkItems() {
        val currentState = _stateFilter.value
        val currentProject = _selectedProject.value
        val currentType = _typeFilter.value

        if (currentState == null || currentState.equals("All", ignoreCase = true)) {
            _proposedDisplayLimit.value = _proposedDisplayLimit.value + PROPOSED_PAGE_STEP
        } else {
            val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${currentState.trim().lowercase()}"
            val updated = _stateDisplayLimits.value.toMutableMap()
            val prevLimit = updated[stateKey] ?: STATE_INITIAL_BATCH_SIZE
            updated[stateKey] = prevLimit + STATE_PAGE_STEP
            _stateDisplayLimits.value = updated
        }

        if (_isLoadingMore.value) return
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
            _isLoadingMore.value = true
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
            _isLoadingMore.value = false
        }
    }

    fun disconnect() {
        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()
        _isBackgroundSyncing.value = false
        _syncProgress.value = null
        _syncStatusText.value = null
        repository.clearCredentials()
        sessionManager.updateOrganizations(emptyList())
        sessionManager.updateDefaultOrganization(null)
        _workItemsOrg.value = null
        serverSyncedItems.clear()
        pendingDirtyItems.clear()
        updateJobs.values.forEach { it.cancel() }
        updateJobs.clear()
        _allWorkItems.value = emptyList()
        _selectedProject.value = null
        _searchQuery.value = ""
        _typeFilter.value = null
        _stateFilter.value = null
        _assigneeFilter.value = null
        _areaFilter.value = null
        _iterationFilter.value = null
        _selectedWorkItem.value = null
        activeCommentsJob?.cancel()
        commentsByWorkItemId.clear()
        _selectedWorkItemComments.value = emptyList()
        _isLoadingComments.value = false
        sessionManager.updateErrorMessage(null)
        sessionManager.updateConnectionStatus(ConnectionStatus.Disconnected)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProject(project: String?) {
        val trimmed = project?.trim()
        if (trimmed.isNullOrBlank()) return
        val isDifferentProject = !_selectedProject.value.equals(trimmed, ignoreCase = true)
        userManuallySelectedProject = true
        userManuallySelectedType = false
        if (isDifferentProject) {
            _areaFilter.value = null
            _iterationFilter.value = null
        }
        _selectedProject.value = trimmed
        ensureProjectProcessLoaded(trimmed)
    }

    private fun ensureProjectProcessLoaded(project: String) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            viewModelScope.launch {
                fetchAndStoreProjectMetadata(org, project, pat)
            }
        }
    }

    fun setTypeFilter(type: String?) {
        if (type.isNullOrBlank()) return
        userManuallySelectedType = true
        _typeFilter.value = type
        _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, _allWorkItems.value.size)
    }

    fun setStateFilter(state: String?) {
        if (state.isNullOrBlank()) return
        val trimmedState = state.trim()
        _stateFilter.value = trimmedState

        if (!trimmedState.equals("All", ignoreCase = true)) {
            val currentProject = _selectedProject.value
            val currentType = _typeFilter.value
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
                if (inMemoryForState < targetLimit && !_isLoadingMore.value) {
                    val acc = sessionManager.getActiveAccount()
                    val org = acc?.organization ?: repository.getConfiguredUrl()
                    val pat = acc?.pat ?: repository.getConfiguredPat()
                    if (org.isNotBlank() && pat.isNotBlank()) {
                        viewModelScope.launch {
                            _isLoadingMore.value = true
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
                            _isLoadingMore.value = false
                        }
                    }
                }
            }
        }
    }

    fun setAssigneeFilter(assignee: String?) {
        _assigneeFilter.value = if (assignee.isNullOrBlank() || assignee.equals("All", ignoreCase = true)) {
            null
        } else {
            assignee
        }
    }

    fun setAreaFilter(areaPath: String?) {
        _areaFilter.value = if (areaPath.isNullOrBlank() || areaPath.equals("All", ignoreCase = true)) {
            null
        } else {
            normalizeClassificationPath(areaPath).ifBlank { null }
        }
    }

    fun setIterationFilter(iterationPath: String?) {
        _iterationFilter.value = if (iterationPath.isNullOrBlank() || iterationPath.equals("All", ignoreCase = true)) {
            null
        } else {
            normalizeClassificationPath(iterationPath).ifBlank { null }
        }
    }

    fun getAreasForProject(project: String?): List<String> {
        val currentSnap = projectScopedSnapshotFlow.value
        if (project.isNullOrBlank() || project.equals(currentSnap.effectiveProject, ignoreCase = true)) {
            val cached = projectAreas.value
            if (cached.isNotEmpty()) return cached
        }
        val resolvedProj = resolveEffectiveProject(project ?: _selectedProject.value, _allWorkItems.value)
            ?: project
            ?: _selectedProject.value
            ?: ""
        if (resolvedProj.isBlank()) return emptyList()
        val resolvedProjLower = resolvedProj.trim().lowercase()
        val activeOrg = sessionManager.getActiveOrganization()
        val fromApi = findProjectAreas(activeOrg, resolvedProj, _projectAreasMap.value).orEmpty()
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
        val resolvedProj = resolveEffectiveProject(project ?: _selectedProject.value, _allWorkItems.value)
            ?: project
            ?: _selectedProject.value
            ?: ""
        if (resolvedProj.isBlank()) return emptyList()
        val resolvedProjLower = resolvedProj.trim().lowercase()
        val activeOrg = sessionManager.getActiveOrganization()
        val fromApi = findProjectIterations(activeOrg, resolvedProj, _projectIterationsMap.value).orEmpty()
        val fromItems = _allWorkItems.value
            .filter { it.normTeamProjectLower == resolvedProjLower }
            .mapNotNull { it.normIterationPath.takeIf { p -> p.isNotEmpty() } }
        return expandAndSortHierarchyPaths(resolvedProj, fromApi + fromItems)
    }

    fun selectWorkItem(item: WorkItem?) {
        if (item != null && _searchQuery.value.isNotEmpty()) {
            _searchQuery.value = ""
        }
        _selectedWorkItem.value = item
        activeCommentsJob?.cancel()

        if (item == null) {
            _selectedWorkItemComments.value = emptyList()
            _isLoadingComments.value = false
            return
        }

        val cachedComments = commentsByWorkItemId[item.id].orEmpty()
        _selectedWorkItemComments.value = cachedComments

        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        val project = item.teamProject ?: _selectedProject.value ?: ""

        if (org.isNotBlank()) {
            viewModelScope.launch {
                val updatedState = repository.markNotificationsReadForWorkItem(org, item.id)
                sessionManager.updateNotificationsState(updatedState)
            }
        }

        if (org.isBlank() || pat.isBlank() || project.isBlank()) {
            _isLoadingComments.value = false
            return
        }

        activeCommentsJob = viewModelScope.launch {
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
                    .sortedWith(
                        compareByDescending<WorkItemComment> { it.createdDate }
                            .thenByDescending { if (it.id < 0) Int.MAX_VALUE else it.id }
                    )
                commentsByWorkItemId[item.id] = merged
                if (_selectedWorkItem.value?.id == item.id) {
                    _selectedWorkItemComments.value = merged
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
        val project = workItem.teamProject ?: _selectedProject.value ?: ""

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
                    if (_selectedWorkItem.value?.id == workItem.id) {
                        _selectedWorkItemComments.value = replaced
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
        if (_selectedWorkItem.value?.id == normalizedUpdated.id) {
            _selectedWorkItem.value = normalizedUpdated
        }

        pendingDirtyItems[normalizedUpdated.id] = normalizedUpdated
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

        updateJobs[normalizedUpdated.id]?.cancel()
        updateJobs[normalizedUpdated.id] = viewModelScope.launch {
            if (onlyTextChanged) {
                delay(400L)
            }
            syncWorkItemToServer(normalizedUpdated)
        }
    }

    fun getStatesForWorkItem(item: WorkItem): List<String> {
        return getStatesForType(item.teamProject, item.workItemType, item.state)
    }

    fun getStatesForType(project: String?, workItemType: String, currentState: String? = null): List<String> {
        val resolvedProj = project ?: _selectedProject.value ?: ""
        val activeOrg = sessionManager.getActiveOrganization()
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProj, _projectProcessMap.value)
        val fromProcess = processMapForProj?.entries
            ?.firstOrNull { it.key.equals(workItemType, ignoreCase = true) }?.value
        val baseStates = if (!fromProcess.isNullOrEmpty()) {
            fromProcess
        } else {
            val fromItems = _allWorkItems.value.filter {
                it.teamProject?.equals(resolvedProj, ignoreCase = true) == true &&
                    it.workItemType.equals(workItemType, ignoreCase = true)
            }.map { it.state }.distinct()
            if (fromItems.isNotEmpty()) fromItems else repository.getStandardProcessStates(workItemType)
        }
        return if (!currentState.isNullOrBlank()) {
            (baseStates + currentState).distinctBy { it.lowercase() }
        } else {
            baseStates.distinctBy { it.lowercase() }
        }
    }

    fun getAllowedChildTypesForWorkItem(item: WorkItem): List<String> {
        return getAllowedChildTypesForType(item.teamProject, item.workItemType)
    }

    fun getAllowedChildTypesForType(project: String?, workItemType: String): List<String> {
        val cleanType = workItemType.trim()
        if (cleanType.isBlank()) return emptyList()

        val resolvedProject = resolveEffectiveProject(project ?: _selectedProject.value, _allWorkItems.value)
            ?: project
            ?: _selectedProject.value
            ?: ""
        val activeOrg = sessionManager.getActiveOrganization()
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProject, _projectProcessMap.value)
        val activeTypesFromProcess = processMapForProj?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
            .orEmpty()

        val projectItems = _allWorkItems.value.filter {
            resolvedProject.isBlank() || it.teamProject?.equals(resolvedProject, ignoreCase = true) == true
        }
        val activeTypesFromItems = projectItems.map { it.workItemType }
            .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            .distinct()

        val activeTypes = when {
            activeTypesFromProcess.isNotEmpty() -> activeTypesFromProcess
            activeTypesFromItems.isNotEmpty() -> activeTypesFromItems
            availableTypes.value.isNotEmpty() -> availableTypes.value
            else -> repository.getKnownProjectProcessHierarchy(resolvedProject).keys.toList()
        }

        val catMap = findCategoryMap(activeOrg, resolvedProject, _projectCategoryMap.value).orEmpty()
        val explicitBacklogLevels = findBacklogLevels(activeOrg, resolvedProject, _projectBacklogLevelsMap.value).orEmpty()

        val backlogLevels = repository.resolveOrderedBacklogLevels(
            activeTypes = activeTypes,
            categoryMap = catMap,
            explicitBacklogLevels = explicitBacklogLevels,
            projectWorkItems = projectItems
        )
        if (backlogLevels.size <= 1) return emptyList()

        val levelIdx = backlogLevels.indexOfFirst { levelTypes ->
            levelTypes.any { it.equals(cleanType, ignoreCase = true) }
        }
        if (levelIdx < 0 || levelIdx >= backlogLevels.lastIndex) {
            return emptyList()
        }

        return backlogLevels[levelIdx + 1]
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
            ?: _selectedProject.value?.takeIf { it.isNotBlank() }
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
            serverSyncedItems[localCreated.id] = localCreated
            _allWorkItems.value = _allWorkItems.value + localCreated
            persistCurrentItemsInBackground(org)
            userManuallySelectedType = true
            _typeFilter.value = localCreated.workItemType
            if (_stateFilter.value != null &&
                !_stateFilter.value.equals("All", ignoreCase = true) &&
                !_stateFilter.value.equals(localCreated.state, ignoreCase = true)
            ) {
                _stateFilter.value = "All"
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
                    serverSyncedItems[finalCreated.id] = finalCreated
                    _allWorkItems.value = (_allWorkItems.value.filterNot { it.id == finalCreated.id } + finalCreated)
                    persistCurrentItemsInBackground(org)
                    userManuallySelectedType = true
                    _typeFilter.value = finalCreated.workItemType
                    if (_stateFilter.value != null &&
                        !_stateFilter.value.equals("All", ignoreCase = true) &&
                        !_stateFilter.value.equals(finalCreated.state, ignoreCase = true)
                    ) {
                        _stateFilter.value = "All"
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

        val project = parent.teamProject ?: _selectedProject.value ?: ""
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

        if (_selectedWorkItem.value?.id == parent.id) {
            _selectedWorkItem.value = updatedParent
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
                serverSyncedItems.remove(tempId)
                serverSyncedItems[finalChild.id] = finalChild

                _allWorkItems.value = _allWorkItems.value.map { item ->
                    when (item.id) {
                        tempId -> finalChild
                        parent.id -> {
                            val newParent = item.copy(
                                childIds = (item.childIds.filterNot { it == tempId } + finalChild.id).distinct()
                            )
                            serverSyncedItems[newParent.id] = newParent
                            newParent
                        }
                        else -> item
                    }
                }
                persistCurrentItemsInBackground(org)
                if (_selectedWorkItem.value?.id == parent.id) {
                    _selectedWorkItem.value = _allWorkItems.value.firstOrNull { it.id == parent.id }
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
            if (_selectedWorkItem.value?.id == parent.id) {
                _selectedWorkItem.value = _allWorkItems.value.firstOrNull { it.id == parent.id }
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
                    serverSyncedItems[finalChild.id] = finalChild

                    val currentItems = _allWorkItems.value
                    val hadChildInList = currentItems.any { it.id == finalChild.id }
                    val updatedList = if (hadChildInList) {
                        currentItems.map { item ->
                            when {
                                item.id == finalChild.id -> finalChild
                                item.id == parent.id -> {
                                    val newParent = item.copy(childIds = (item.childIds + finalChild.id).distinct())
                                    serverSyncedItems[newParent.id] = newParent
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
                                serverSyncedItems[newParent.id] = newParent
                                newParent
                            } else {
                                item
                            }
                        } + finalChild
                    }

                    _allWorkItems.value = updatedList
                    if (_selectedWorkItem.value?.id == parent.id) {
                        _selectedWorkItem.value = _allWorkItems.value.firstOrNull { it.id == parent.id }
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
                    serverSyncedItems[updatedParent.id] = updatedParent
                    updatedParent
                }
                item.id == childId && item.parentId == parent.id -> {
                    val updatedChild = item.copy(parentId = null)
                    serverSyncedItems[updatedChild.id] = updatedChild
                    updatedChild
                }
                else -> item
            }
        }

        if (_selectedWorkItem.value?.id == parent.id) {
            _selectedWorkItem.value = _allWorkItems.value.firstOrNull { it.id == parent.id }
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
