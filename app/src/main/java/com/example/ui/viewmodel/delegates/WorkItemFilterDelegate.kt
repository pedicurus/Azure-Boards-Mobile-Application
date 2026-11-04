package com.example.ui.viewmodel.delegates

import com.example.data.model.WorkItem
import com.example.data.repository.AzureDevOpsRepository
import com.example.di.SessionManager
import com.example.ui.ConnectionStatus
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.expandAndSortHierarchyPaths
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findBacklogLevels
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findCategoryMap
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProcessHierarchy
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectAreas
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectIterations
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.insertBoundedTopK
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.matchesClassificationHierarchyFast
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.normalizeClassificationPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.Collections

/**
 * Delegate managing search queries, project selection, attribute filters
 * (Work Item Type, State, Assignee, Area Path, Iteration Path), pagination limits,
 * and reactive facet/filtered work item pipelines for WorkItemsViewModel.
 */
class WorkItemFilterDelegate(
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val repository: AzureDevOpsRepository,
    private val processDelegate: WorkItemProcessDelegate,
    private val allWorkItems: StateFlow<List<WorkItem>>,
    private val workItemsOrg: StateFlow<String?>,
    private val onEnsureProjectProcessLoaded: (String) -> Unit
) {

    companion object {
        const val PROPOSED_INITIAL_BATCH_SIZE = 500
        const val PROPOSED_PAGE_STEP = 100
        const val STATE_INITIAL_BATCH_SIZE = 200
        const val STATE_PAGE_STEP = 100
        const val RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE = 5
    }

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

    var userManuallySelectedProject: Boolean = false
    var userManuallySelectedType: Boolean = false

    val proposedDisplayLimit = MutableStateFlow(PROPOSED_INITIAL_BATCH_SIZE)
    val stateDisplayLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    val hasMoreServerItems = MutableStateFlow(false)
    val exhaustedStateQueries: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    data class ProjectScopedWorkItemsSnapshot(
        val activeOrg: String,
        val effectiveProject: String?,
        val effectiveProjectLower: String,
        val projectItems: List<WorkItem>,
        val allItems: List<WorkItem>
    )

    data class ProjectAndTypeScopedSnapshot(
        val activeOrg: String,
        val effectiveProject: String?,
        val effectiveProjectLower: String,
        val selectedType: String?,
        val selectedTypeLower: String,
        val projectAndTypeItems: List<WorkItem>,
        val projectItems: List<WorkItem>
    )

    val availableProjects: StateFlow<List<String>> = combine(
        sessionManager.connectionStatus,
        sessionManager.defaultOrganization,
        sessionManager.organizations,
        sessionManager.projectsByOrg,
        combine(allWorkItems, workItemsOrg) { items, itemsOrg -> items to itemsOrg }
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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun resolveEffectiveProject(project: String?, items: List<WorkItem>): String? {
        val activeOrg = sessionManager.getActiveOrganization()
        val fetchedForOrg = sessionManager.projectsByOrg.value.entries
            .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItems = if (workItemsOrg.value.equals(activeOrg, ignoreCase = true)) {
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
                setProject(fallback)
            }
            return fallback
        }
        return project
    }

    val projectScopedSnapshotFlow: StateFlow<ProjectScopedWorkItemsSnapshot> = combine(
        sessionManager.connectionStatus,
        allWorkItems,
        _selectedProject,
        sessionManager.projectsByOrg,
        workItemsOrg
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
            scope,
            SharingStarted.WhileSubscribed(5000),
            ProjectScopedWorkItemsSnapshot(
                activeOrg = sessionManager.defaultOrganization.value.orEmpty(),
                effectiveProject = _selectedProject.value,
                effectiveProjectLower = _selectedProject.value?.trim()?.lowercase().orEmpty(),
                projectItems = emptyList(),
                allItems = emptyList()
            )
        )

    val projectAndTypeScopedSnapshotFlow: StateFlow<ProjectAndTypeScopedSnapshot> = combine(
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
            scope,
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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasMoreItems: StateFlow<Boolean> = combine(
        projectAndTypeScopedSnapshotFlow,
        processDelegate.projectProcessMap,
        combine(_stateFilter, proposedDisplayLimit, stateDisplayLimits) { state, propLimit, stateLimits ->
            Triple(state, propLimit, stateLimits)
        }.distinctUntilChanged(),
        hasMoreServerItems
    ) { typeSnap, processMap, (state, propLimit, stateLimits), hasMoreServer ->
        val project = typeSnap.effectiveProject
        val matchedProjMap = if (!project.isNullOrBlank()) {
            findProcessHierarchy(typeSnap.activeOrg, project, processMap)
        } else {
            null
        }
        val enabledStatesByTypeLower: Map<String, Set<String>> = matchedProjMap?.entries
            ?.associate { (k, v) ->
                k.trim().lowercase() to v.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            }
            .orEmpty()
        val matchingProjectAndType = typeSnap.projectAndTypeItems.filter { item ->
            val enabledForType = enabledStatesByTypeLower[item.normTypeLower]
            enabledForType.isNullOrEmpty() || item.normStateLower in enabledForType
        }
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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    val filteredWorkItems: StateFlow<List<WorkItem>> = combine(
        combine(projectScopedSnapshotFlow, _searchQuery, processDelegate.projectProcessMap) { projSnap, query, processMap ->
            Triple(projSnap, query, processMap)
        }.distinctUntilChanged(),
        combine(_typeFilter, _stateFilter, _assigneeFilter) { type, state, assignee ->
            Triple(type, state, assignee)
        }.distinctUntilChanged(),
        combine(_areaFilter, _iterationFilter) { area, iteration ->
            area to iteration
        }.distinctUntilChanged(),
        combine(proposedDisplayLimit, stateDisplayLimits) { propLimit, stateLimits ->
            propLimit to stateLimits
        }.distinctUntilChanged()
    ) { (projSnap, query, processMap), (type, state, assignee), (area, iteration), (propLimit, stateLimits) ->
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

        val matchedProjMap = if (!project.isNullOrBlank()) {
            findProcessHierarchy(projSnap.activeOrg, project, processMap)
        } else {
            null
        }
        val enabledStatesByTypeLower: Map<String, Set<String>> = matchedProjMap?.entries
            ?.associate { (k, v) ->
                k.trim().lowercase() to v.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            }
            .orEmpty()

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
            val enabledStatesForType = enabledStatesByTypeLower[item.normTypeLower]
            val isStateEnabledForType = enabledStatesForType.isNullOrEmpty() || item.normStateLower in enabledStatesForType
            isStateEnabledForType &&
                (typeLower.isEmpty() || item.normTypeLower == typeLower) &&
                (stateLower.isEmpty() || item.normStateLower == stateLower) &&
                (isAssigneeAll || (if (isAssigneeUnassigned) item.isUnassigned else item.normAssigneeLower == assigneeLower)) &&
                (normAreaLower.isEmpty() || matchesClassificationHierarchyFast(item.normAreaPathLower, normAreaLower, normAreaPrefixLower)) &&
                (normIterationLower.isEmpty() || matchesClassificationHierarchyFast(item.normIterationPathLower, normIterationLower, normIterationPrefixLower))
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
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalWorkItemsCount: StateFlow<Int> = combine(
        projectScopedSnapshotFlow,
        processDelegate.projectProcessMap,
        filteredWorkItems,
        _searchQuery,
        combine(_typeFilter, _stateFilter, _assigneeFilter, _areaFilter, _iterationFilter) { t, s, a, ar, it ->
            listOf(t, s, a, ar, it)
        }.distinctUntilChanged()
    ) { projSnap, processMap, filtered, query, filters ->
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
            val project = projSnap.effectiveProject
            val matchedProjMap = if (!project.isNullOrBlank()) {
                findProcessHierarchy(projSnap.activeOrg, project, processMap)
            } else {
                null
            }
            val enabledStatesByTypeLower: Map<String, Set<String>> = matchedProjMap?.entries
                ?.associate { (k, v) ->
                    k.trim().lowercase() to v.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                }
                .orEmpty()
            val activeProjectItems = projSnap.projectItems.filter { item ->
                val enabledForType = enabledStatesByTypeLower[item.normTypeLower]
                enabledForType.isNullOrEmpty() || item.normStateLower in enabledForType
            }
            val type = filters[0]
            val typeLower = type?.trim()?.lowercase().orEmpty()
            if (typeLower.isEmpty()) {
                activeProjectItems.size
            } else {
                activeProjectItems.count { it.normTypeLower == typeLower }
            }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)

    fun observeProjectAndTypeSelection() {
        scope.launch {
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
                        setProject(chosen)
                    }
                    onEnsureProjectProcessLoaded(chosen)
                }
            }
        }

        scope.launch {
            availableTypes.collectLatest { types ->
                if (types.isEmpty()) return@collectLatest
                val currentType = _typeFilter.value
                val isCurrentInList = currentType != null && types.any { it.equals(currentType, ignoreCase = true) }
                if (!userManuallySelectedType || !isCurrentInList) {
                    val biggestType = types.first()
                    if (_typeFilter.value != biggestType) {
                        setTypeFilter(biggestType)
                        setStateFilter("All")
                    }
                }
            }
        }

        scope.launch {
            availableStates.collectLatest { states ->
                if (states.isEmpty()) return@collectLatest
                val currentState = _stateFilter.value
                val isCurrentInList = currentState != null && states.any { it.equals(currentState, ignoreCase = true) }
                if (currentState == null || !isCurrentInList) {
                    setStateFilter("All")
                }
            }
        }
    }

    fun getAreasForProject(project: String?): List<String> {
        val currentSnap = projectScopedSnapshotFlow.value
        if (project.isNullOrBlank() || project.equals(currentSnap.effectiveProject, ignoreCase = true)) {
            val cached = projectAreas.value
            if (cached.isNotEmpty()) return cached
        }
        val resolvedProj = resolveEffectiveProject(project ?: _selectedProject.value, allWorkItems.value)
            ?: project
            ?: _selectedProject.value
            ?: ""
        if (resolvedProj.isBlank()) return emptyList()
        val resolvedProjLower = resolvedProj.trim().lowercase()
        val activeOrg = sessionManager.getActiveOrganization()
        val fromApi = findProjectAreas(activeOrg, resolvedProj, processDelegate.projectAreasMap.value).orEmpty()
        val fromItems = allWorkItems.value
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
        val resolvedProj = resolveEffectiveProject(project ?: _selectedProject.value, allWorkItems.value)
            ?: project
            ?: _selectedProject.value
            ?: ""
        if (resolvedProj.isBlank()) return emptyList()
        val resolvedProjLower = resolvedProj.trim().lowercase()
        val activeOrg = sessionManager.getActiveOrganization()
        val fromApi = findProjectIterations(activeOrg, resolvedProj, processDelegate.projectIterationsMap.value).orEmpty()
        val fromItems = allWorkItems.value
            .filter { it.normTeamProjectLower == resolvedProjLower }
            .mapNotNull { it.normIterationPath.takeIf { p -> p.isNotEmpty() } }
        return expandAndSortHierarchyPaths(resolvedProj, fromApi + fromItems)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProject(project: String?) {
        val trimmed = project?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        if (_selectedProject.value != trimmed) {
            _selectedProject.value = trimmed
            _areaFilter.value = null
            _iterationFilter.value = null
        }
    }

    fun setTypeFilter(type: String?) {
        val trimmed = type?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _typeFilter.value = trimmed
    }

    fun setStateFilter(state: String?) {
        val trimmed = state?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _stateFilter.value = trimmed
    }

    fun setAssigneeFilter(assignee: String?) {
        val trimmed = assignee?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _assigneeFilter.value = trimmed
    }

    fun setAreaFilter(areaPath: String?) {
        val trimmed = areaPath?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _areaFilter.value = trimmed
    }

    fun setIterationFilter(iterationPath: String?) {
        val trimmed = iterationPath?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _iterationFilter.value = trimmed
    }

    fun resetFilters() {
        _searchQuery.value = ""
        _typeFilter.value = null
        _stateFilter.value = null
        _assigneeFilter.value = null
        _areaFilter.value = null
        _iterationFilter.value = null
    }

    fun clearAll() {
        resetFilters()
        _selectedProject.value = null
        userManuallySelectedProject = false
        userManuallySelectedType = false
        proposedDisplayLimit.value = PROPOSED_INITIAL_BATCH_SIZE
        stateDisplayLimits.value = emptyMap()
        hasMoreServerItems.value = false
        exhaustedStateQueries.clear()
    }
}
