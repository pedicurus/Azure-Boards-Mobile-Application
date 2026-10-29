package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.repository.AzureDevOpsRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface ConnectionStatus {
    object Disconnected : ConnectionStatus
    object Connecting : ConnectionStatus
    data class Connected(val organization: String) : ConnectionStatus
}

class AzureDevOpsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AzureDevOpsRepository(application.applicationContext)

    private val serverSyncedItems = ConcurrentHashMap<Int, WorkItem>()
    private val pendingDirtyItems = ConcurrentHashMap<Int, WorkItem>()
    private val updateJobs = ConcurrentHashMap<Int, Job>()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _allWorkItems = MutableStateFlow<List<WorkItem>>(emptyList())
    val allWorkItems: StateFlow<List<WorkItem>> = _allWorkItems.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    private val _stateFilter = MutableStateFlow<String?>(null)
    val stateFilter: StateFlow<String?> = _stateFilter.asStateFlow()

    private val _assigneeFilter = MutableStateFlow<String?>(null)
    val assigneeFilter: StateFlow<String?> = _assigneeFilter.asStateFlow()

    private val _organizations = MutableStateFlow<List<OrganizationAccount>>(repository.getSavedOrganizations())
    val organizations: StateFlow<List<OrganizationAccount>> = _organizations.asStateFlow()

    private val _defaultOrganization = MutableStateFlow<String?>(
        repository.getDefaultOrganization() ?: repository.getSavedOrganizations().firstOrNull()?.organization
    )
    val defaultOrganization: StateFlow<String?> = _defaultOrganization.asStateFlow()

    private val _defaultProject = MutableStateFlow<String?>(
        repository.getDefaultProject(_defaultOrganization.value)
    )
    val defaultProject: StateFlow<String?> = _defaultProject.asStateFlow()

    private val _selectedProject = MutableStateFlow<String?>(
        repository.getDefaultProject(_defaultOrganization.value)
    )
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private var userManuallySelectedProject: Boolean = false
    private var userManuallySelectedType: Boolean = false

    private val _projectsByOrg = MutableStateFlow<Map<String, List<String>>>(
        repository.getSavedOrganizations().associate { acc ->
            acc.organization to repository.getCachedProjectsForOrg(acc.organization)
        }
    )
    val projectsByOrg: StateFlow<Map<String, List<String>>> = _projectsByOrg.asStateFlow()

    private val _defaultProjectsByOrg = MutableStateFlow<Map<String, String>>(
        buildMap {
            repository.getSavedOrganizations().forEach { acc ->
                val cachedProjs = repository.getCachedProjectsForOrg(acc.organization)
                val savedDef = repository.getDefaultProject(acc.organization)
                val resolved = savedDef?.let { def ->
                    if (cachedProjs.isNotEmpty()) {
                        cachedProjs.firstOrNull { it.equals(def, ignoreCase = true) }
                    } else {
                        def
                    }
                } ?: cachedProjs.firstOrNull()
                if (!resolved.isNullOrBlank()) {
                    put(acc.organization, resolved)
                }
            }
        }
    )
    val defaultProjectsByOrg: StateFlow<Map<String, String>> = _defaultProjectsByOrg.asStateFlow()

    private val _projectProcessMap = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    private val _projectCategoryMap = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    private fun processKey(org: String, project: String): String =
        "${org.trim().lowercase()}|${project.trim().lowercase()}"

    private fun findProcessHierarchy(
        org: String?,
        project: String?,
        map: Map<String, Map<String, List<String>>> = _projectProcessMap.value
    ): Map<String, List<String>>? {
        if (project.isNullOrBlank()) return null
        val pTrim = project.trim()
        if (!org.isNullOrBlank()) {
            val key = processKey(org, pTrim)
            map[key]?.let { return it }
            map.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { return it }
        }
        return map.entries.firstOrNull {
            it.key.equals(pTrim, ignoreCase = true) ||
                it.key.endsWith("|${pTrim.lowercase()}", ignoreCase = true)
        }?.value
    }

    private fun findCategoryMap(
        org: String?,
        project: String?,
        map: Map<String, Map<String, String>> = _projectCategoryMap.value
    ): Map<String, String>? {
        if (project.isNullOrBlank()) return null
        val pTrim = project.trim()
        if (!org.isNullOrBlank()) {
            val key = processKey(org, pTrim)
            map[key]?.let { return it }
            map.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { return it }
        }
        return map.entries.firstOrNull {
            it.key.equals(pTrim, ignoreCase = true) ||
                it.key.endsWith("|${pTrim.lowercase()}", ignoreCase = true)
        }?.value
    }

    private fun getDefaultProjectForOrgInternal(organization: String?): String? {
        if (organization.isNullOrBlank()) return null
        val normOrg = repository.extractOrganization(organization)
        return _defaultProjectsByOrg.value.entries
            .firstOrNull { it.key.equals(normOrg, ignoreCase = true) }?.value
            ?: repository.getDefaultProject(normOrg)
    }

    private fun resolveAndPersistDefaultProjectForOrg(
        organization: String,
        orgProjects: List<String>
    ): String? {
        val normOrg = repository.extractOrganization(organization)
        if (normOrg.isBlank()) return null
        val currentSaved = getDefaultProjectForOrgInternal(normOrg)
        if (orgProjects.isEmpty()) return currentSaved

        val resolved = currentSaved?.let { saved ->
            orgProjects.firstOrNull { it.equals(saved, ignoreCase = true) }
        } ?: orgProjects.first()

        repository.saveDefaultProject(resolved, normOrg)
        val updatedMap = _defaultProjectsByOrg.value.toMutableMap()
        val existingKey = updatedMap.keys.firstOrNull { it.equals(normOrg, ignoreCase = true) }
        if (existingKey != null && existingKey != normOrg) {
            updatedMap.remove(existingKey)
        }
        updatedMap[normOrg] = resolved
        _defaultProjectsByOrg.value = updatedMap

        if (normOrg.equals(_defaultOrganization.value, ignoreCase = true)) {
            _defaultProject.value = resolved
        }
        return resolved
    }

    companion object {
        private const val RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE = 5
        private const val PROPOSED_INITIAL_BATCH_SIZE = 40
        private const val PROPOSED_PAGE_STEP = 40
        private const val STATE_INITIAL_BATCH_SIZE = 40
        private const val STATE_PAGE_STEP = 40
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isBackgroundSyncing = MutableStateFlow(false)
    val isBackgroundSyncing: StateFlow<Boolean> = _isBackgroundSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<Float?>(null)
    val syncProgress: StateFlow<Float?> = _syncProgress.asStateFlow()

    private val _isPullRefreshing = MutableStateFlow(false)
    val isPullRefreshing: StateFlow<Boolean> = _isPullRefreshing.asStateFlow()

    private val _syncStatusText = MutableStateFlow<String?>(null)
    val syncStatusText: StateFlow<String?> = _syncStatusText.asStateFlow()

    private val _workItemsOrg = MutableStateFlow<String?>(null)

    private var backgroundStreamJob: Job? = null
    private var activeConnectJob: Job? = null

    private val _isSearchingServer = MutableStateFlow(false)
    val isSearchingServer: StateFlow<Boolean> = _isSearchingServer.asStateFlow()

    private val _proposedDisplayLimit = MutableStateFlow(PROPOSED_INITIAL_BATCH_SIZE)
    private val _stateDisplayLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _hasMoreServerItems = MutableStateFlow(false)
    private val exhaustedStateQueries = ConcurrentHashMap.newKeySet<String>()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedWorkItem = MutableStateFlow<WorkItem?>(null)
    val selectedWorkItem: StateFlow<WorkItem?> = _selectedWorkItem.asStateFlow()

    val selectedWorkItemChildren: StateFlow<List<WorkItem>> = combine(
        _selectedWorkItem,
        _allWorkItems
    ) { selected, allItems ->
        if (selected == null) return@combine emptyList()
        val childIdSet = selected.childIds.toSet()
        allItems.filter { candidate ->
            candidate.id != selected.id && (candidate.parentId == selected.id || candidate.id in childIdSet)
        }.sortedWith(
            compareBy<WorkItem>(
                { it.boardOrder ?: Double.MAX_VALUE },
                { it.id }
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Strictly returns only the projects belonging to the currently active organization.
     */
    val availableProjects: StateFlow<List<String>> = combine(
        _connectionStatus,
        _projectsByOrg,
        _allWorkItems,
        _workItemsOrg
    ) { status, projectsMap, items, itemsOrg ->
        val connected = status as? ConnectionStatus.Connected
        val activeOrg = connected?.organization
            ?: _defaultOrganization.value
            ?: _organizations.value.firstOrNull()?.organization
            ?: ""
        val fetchedForOrg = projectsMap.entries
            .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItems = if (!itemsOrg.isNullOrBlank() && itemsOrg.equals(activeOrg, ignoreCase = true)) {
            items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val cachedForOrg = if (activeOrg.isNotBlank()) repository.getCachedProjectsForOrg(activeOrg) else emptyList()
        (fetchedForOrg + fromItems + cachedForOrg)
            .distinctBy { it.lowercase() }
            .sorted()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        (_defaultOrganization.value?.let { repository.getCachedProjectsForOrg(it) }.orEmpty())
            .distinctBy { it.lowercase() }
            .sorted()
    )

    /**
     * Returns the projects belonging strictly to the Default Organization (used in Settings
     * so users can choose their single Default Project under their single Default Organization).
     */
    val defaultOrgProjects: StateFlow<List<String>> = combine(
        _defaultOrganization,
        _projectsByOrg,
        _connectionStatus,
        _allWorkItems,
        _workItemsOrg
    ) { defOrg, projectsMap, status, items, itemsOrg ->
        val connected = status as? ConnectionStatus.Connected
        val targetOrg = defOrg
            ?: _organizations.value.firstOrNull()?.organization
            ?: connected?.organization
            ?: ""
        if (targetOrg.isBlank()) return@combine emptyList()

        val fetchedForDefOrg = projectsMap.entries
            .firstOrNull { it.key.equals(targetOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItemsIfActive = if (!itemsOrg.isNullOrBlank() && itemsOrg.equals(targetOrg, ignoreCase = true)) {
            items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val cachedForDefOrg = repository.getCachedProjectsForOrg(targetOrg)
        (fetchedForDefOrg + fromItemsIfActive + cachedForDefOrg)
            .distinctBy { it.lowercase() }
            .sorted()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        (_defaultOrganization.value?.let { repository.getCachedProjectsForOrg(it) }.orEmpty())
            .distinctBy { it.lowercase() }
            .sorted()
    )

    private fun resolveEffectiveProject(project: String?, items: List<WorkItem>): String? {
        val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
            ?: _organizations.value.firstOrNull()?.organization
            ?: ""
        val fetchedForOrg = _projectsByOrg.value.entries
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
            val fallback = _defaultProjectsByOrg.value.entries
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

    val availableTypes: StateFlow<List<String>> = combine(
        _connectionStatus,
        _allWorkItems,
        _selectedProject,
        _projectProcessMap,
        _projectCategoryMap
    ) { status, items, rawProject, processMap, categoryMap ->
        val project = resolveEffectiveProject(rawProject, items)
        if (project.isNullOrBlank()) return@combine emptyList()
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization ?: _defaultOrganization.value
        val matchedProcessMap = findProcessHierarchy(activeOrg, project, processMap)
        val matchedCategoryMap = findCategoryMap(activeOrg, project, categoryMap).orEmpty()
        val projectItems = items.filter { it.teamProject?.equals(project, ignoreCase = true) == true }
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
            projectWorkItems = projectItems
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableStates: StateFlow<List<String>> = combine(
        _connectionStatus,
        _allWorkItems,
        _selectedProject,
        _typeFilter,
        _projectProcessMap
    ) { status, items, rawProject, type, processMap ->
        val project = resolveEffectiveProject(rawProject, items)
        if (project.isNullOrBlank() || type.isNullOrBlank()) return@combine emptyList()
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization ?: _defaultOrganization.value
        val matchedProjMap = findProcessHierarchy(activeOrg, project, processMap)
        val fromApi = matchedProjMap?.entries?.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value
        val typeStates = if (!fromApi.isNullOrEmpty()) {
            fromApi
        } else {
            val fromItems = items.filter {
                it.teamProject?.equals(project, ignoreCase = true) == true &&
                it.workItemType.equals(type, ignoreCase = true)
            }.map { it.state }.distinct()
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projectAssignees: StateFlow<List<String>> = combine(
        _allWorkItems,
        _selectedProject
    ) { items, project ->
        val projectItems = if (project.isNullOrBlank()) {
            items
        } else {
            items.filter { it.teamProject?.equals(project, ignoreCase = true) == true }
        }
        val fromProject = projectItems.mapNotNull { it.assignedToName?.trim() }
            .filter { it.isNotBlank() && !it.equals("Unassigned", ignoreCase = true) }
        val fromAll = items.mapNotNull { it.assignedToName?.trim() }
            .filter { it.isNotBlank() && !it.equals("Unassigned", ignoreCase = true) }
        (fromProject + fromAll).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projectFrequentTags: StateFlow<List<String>> = combine(
        _allWorkItems,
        _selectedProject
    ) { items, project ->
        val projectItems = if (project.isNullOrBlank()) {
            items
        } else {
            items.filter { it.teamProject?.equals(project, ignoreCase = true) == true }
        }
        projectItems
            .flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val remainingItemsCount: StateFlow<Int> = combine(
        _allWorkItems,
        _selectedProject
    ) { items, project ->
        items.filter { project == null || it.teamProject?.equals(project, ignoreCase = true) == true }
            .count { item ->
                val s = item.state.lowercase().trim()
                s != "done" && s != "closed" && s != "resolved" && s != "تکمیل" && s != "completed"
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val completedItemsCount: StateFlow<Int> = combine(
        _allWorkItems,
        _selectedProject
    ) { items, project ->
        items.filter { project == null || it.teamProject?.equals(project, ignoreCase = true) == true }
            .count { item ->
                val s = item.state.lowercase().trim()
                s == "done" || s == "closed" || s == "resolved" || s == "تکمیل" || s == "completed"
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val hasMoreItems: StateFlow<Boolean> = combine(
        combine(_allWorkItems, _selectedProject, _typeFilter) { items, project, type ->
            Triple(items, project, type)
        },
        combine(_stateFilter, _proposedDisplayLimit, _stateDisplayLimits) { state, propLimit, stateLimits ->
            Triple(state, propLimit, stateLimits)
        },
        _hasMoreServerItems
    ) { (items, rawProject, type), (state, propLimit, stateLimits), hasMoreServer ->
        val project = resolveEffectiveProject(rawProject, items)
        val matchingProjectAndType = items.filter { item ->
            (project == null || item.teamProject?.equals(project, ignoreCase = true) == true) &&
                (type == null || item.workItemType.equals(type, ignoreCase = true))
        }
        if (state == null || state.equals("All", ignoreCase = true)) {
            val totalProposedInMemory = matchingProjectAndType.count { item ->
                repository.resolveStateCategory(item.state, item.teamProject, item.workItemType) ==
                    AzureDevOpsRepository.CATEGORY_PROPOSED
            }
            totalProposedInMemory > propLimit || hasMoreServer
        } else {
            val stateKey = "${project.orEmpty().lowercase()}|${type.orEmpty().lowercase()}|${state.trim().lowercase()}"
            val currentLimit = stateLimits[stateKey] ?: STATE_INITIAL_BATCH_SIZE
            val totalStateInMemory = matchingProjectAndType.count { item ->
                item.state.equals(state, ignoreCase = true)
            }
            val isStateExhaustedOnServer = stateKey in exhaustedStateQueries
            totalStateInMemory > currentLimit || hasMoreServer || !isStateExhaustedOnServer
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val filteredWorkItems: StateFlow<List<WorkItem>> = combine(
        combine(_allWorkItems, _searchQuery, _selectedProject) { items, query, project ->
            Triple(items, query, project)
        },
        combine(_typeFilter, _stateFilter, _assigneeFilter) { type, state, assignee ->
            Triple(type, state, assignee)
        },
        combine(_proposedDisplayLimit, _stateDisplayLimits) { propLimit, stateLimits ->
            propLimit to stateLimits
        }
    ) { (items, query, rawProject), (type, state, assignee), (propLimit, stateLimits) ->
        val project = resolveEffectiveProject(rawProject, items)
        val cleanQuery = query.trim()
        val numericQuery = cleanQuery.removePrefix("#").trim()

        // 1. Active Search Mode:
        //    Search across ALL Work Item Types, States, and Assignees in the current project
        //    (with automatic organization-wide fallback if an exact ID or item is in another project),
        //    ranked by relevance so any item that exists in Azure is immediately shown.
        if (cleanQuery.isNotEmpty()) {
            val allQueryMatches = items.filter { item ->
                item.title.contains(cleanQuery, ignoreCase = true) ||
                    (numericQuery.isNotEmpty() && item.id.toString().contains(numericQuery)) ||
                    (item.description?.contains(cleanQuery, ignoreCase = true) == true) ||
                    (item.assignedToName?.contains(cleanQuery, ignoreCase = true) == true) ||
                    item.workItemType.contains(cleanQuery, ignoreCase = true) ||
                    item.state.contains(cleanQuery, ignoreCase = true) ||
                    item.tags.any { it.contains(cleanQuery, ignoreCase = true) }
            }

            val projectScopedMatches = if (project.isNullOrBlank()) {
                allQueryMatches
            } else {
                val inProject = allQueryMatches.filter { item ->
                    item.teamProject.isNullOrBlank() ||
                        item.teamProject.equals(project, ignoreCase = true) ||
                        (numericQuery.isNotEmpty() && item.id.toString() == numericQuery)
                }
                inProject.ifEmpty { allQueryMatches }
            }

            return@combine projectScopedMatches.sortedWith(
                compareBy<WorkItem>(
                    { item ->
                        when {
                            numericQuery.isNotEmpty() && item.id.toString() == numericQuery -> 0
                            item.title.equals(cleanQuery, ignoreCase = true) -> 1
                            item.title.startsWith(cleanQuery, ignoreCase = true) -> 2
                            item.title.contains(cleanQuery, ignoreCase = true) -> 3
                            item.tags.any { it.contains(cleanQuery, ignoreCase = true) } -> 4
                            numericQuery.isNotEmpty() && item.id.toString().contains(numericQuery) -> 5
                            else -> 6
                        }
                    },
                    { repository.getStatePriorityTier(it.state, it.teamProject, it.workItemType) },
                    { it.boardOrder ?: Double.MAX_VALUE },
                    { it.id }
                )
            )
        }

        val baseMatched = items.filter { item ->
            val matchesProject = project == null || item.teamProject?.equals(project, ignoreCase = true) == true
            val matchesType = type == null || item.workItemType.equals(type, ignoreCase = true)
            val matchesState = state == null || state.equals("All", ignoreCase = true) || item.state.equals(state, ignoreCase = true)
            val matchesAssignee = when {
                assignee.isNullOrBlank() || assignee.equals("All", ignoreCase = true) -> true
                assignee.equals("Unassigned", ignoreCase = true) ->
                    item.assignedToName.isNullOrBlank() || item.assignedToName.equals("Unassigned", ignoreCase = true)
                else -> item.assignedToName?.trim()?.equals(assignee.trim(), ignoreCase = true) == true
            }

            matchesProject && matchesType && matchesState && matchesAssignee
        }

        val boardOrderComparator = compareBy<WorkItem>(
            { it.boardOrder ?: Double.MAX_VALUE },
            { it.id }
        )
        val recentChangedComparator = compareByDescending<WorkItem> { it.changedDate ?: "" }
            .thenBy { it.boardOrder ?: Double.MAX_VALUE }
            .thenByDescending { it.id }

        // 2. "All" States Mode:
        //    - Tier 1 (InProgress category): Show 100% of items across all InProgress states
        //    - Tier 2 (Proposed category): Show up to [propLimit] items (auto-streams in background & on scroll)
        //    - Tier 3 (Completed / Resolved / Removed categories): Show 5 most recently changed items per (workItemType, state)
        if (state == null || state.equals("All", ignoreCase = true)) {
            val inProgressItems = mutableListOf<WorkItem>()
            val proposedItems = mutableListOf<WorkItem>()
            val terminalItems = mutableListOf<WorkItem>()

            for (item in baseMatched) {
                when (repository.resolveStateCategory(item.state, item.teamProject, item.workItemType)) {
                    AzureDevOpsRepository.CATEGORY_IN_PROGRESS -> inProgressItems.add(item)
                    AzureDevOpsRepository.CATEGORY_PROPOSED -> proposedItems.add(item)
                    else -> terminalItems.add(item)
                }
            }

            val sortedInProgress = inProgressItems.sortedWith(boardOrderComparator)
            val sortedProposed = proposedItems.sortedWith(boardOrderComparator).take(propLimit)
            val cappedRecentTerminal = terminalItems
                .groupBy { "${it.workItemType.trim().lowercase()}|${it.state.trim().lowercase()}" }
                .values
                .flatMap { group ->
                    group.sortedWith(recentChangedComparator)
                        .take(RECENT_COMPLETED_LIMIT_PER_TYPE_AND_STATE)
                }
                .sortedWith(recentChangedComparator)

            return@combine sortedInProgress + sortedProposed + cappedRecentTerminal
        }

        // 3. Specific State Chip Selected Mode:
        //    - Lift the 5-item cap and show up to [stateLimit] items for that state (auto-loads more on selection & scroll)
        val stateKey = "${project.orEmpty().lowercase()}|${type.orEmpty().lowercase()}|${state.trim().lowercase()}"
        val stateLimit = stateLimits[stateKey] ?: STATE_INITIAL_BATCH_SIZE
        val selectedCategory = repository.resolveStateCategory(state, project, type)

        when (selectedCategory) {
            AzureDevOpsRepository.CATEGORY_IN_PROGRESS -> baseMatched.sortedWith(boardOrderComparator)
            AzureDevOpsRepository.CATEGORY_PROPOSED -> baseMatched.sortedWith(boardOrderComparator).take(stateLimit)
            else -> baseMatched.sortedWith(recentChangedComparator).take(stateLimit)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Immediately restores cached work items and project process/category metadata from local device
     * storage so the user never waits on initial loading when opening the app or switching organizations.
     */
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
        }

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
            _connectionStatus.value = ConnectionStatus.Connected(normOrg)
        }
        return snapshot
    }

    /**
     * Continuously streams remaining unloaded work items in small non-blocking batches (50 items)
     * in the background even when the user does not scroll, persisting every batch to local device storage.
     */
    private fun startIterativeBackgroundStream(organization: String, pat: String) {
        val normOrg = repository.extractOrganization(organization)
        val cleanPat = pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) return

        backgroundStreamJob?.cancel()
        backgroundStreamJob = viewModelScope.launch {
            try {
                while (isActive && repository.hasUnloadedServerItems(normOrg)) {
                    val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
                    if (activeOrg != null && !activeOrg.equals(normOrg, ignoreCase = true)) {
                        break
                    }

                    val loadedCount = _allWorkItems.value.size
                    val remainingCount = repository.getRemainingUnloadedCount(normOrg)
                    if (remainingCount <= 0) break

                    val totalExpected = (loadedCount + remainingCount).coerceAtLeast(1)
                    _isBackgroundSyncing.value = true
                    _syncProgress.value = (loadedCount.toFloat() / totalExpected.toFloat()).coerceIn(0.08f, 0.96f)
                    _syncStatusText.value = "Syncing..."

                    val existingIds = _allWorkItems.value.map { it.id }.toSet()
                    val batchResult = repository.fetchNextWorkItemBatch(
                        organization = normOrg,
                        pat = cleanPat,
                        project = _selectedProject.value,
                        alreadyLoadedIds = existingIds,
                        batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
                    )

                    val activeOrgAfterFetch = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
                    if (!isActive || (activeOrgAfterFetch != null && !activeOrgAfterFetch.equals(normOrg, ignoreCase = true))) {
                        break
                    }

                    val fetchedBatch = batchResult.getOrNull().orEmpty()
                    if (fetchedBatch.isEmpty()) {
                        break
                    }

                    mergeHydratedWorkItemsSuspend(fetchedBatch)
                    val updatedLoaded = _allWorkItems.value.size
                    val updatedRemaining = repository.getRemainingUnloadedCount(normOrg)
                    val updatedTotal = (updatedLoaded + updatedRemaining).coerceAtLeast(1)
                    _syncProgress.value = (updatedLoaded.toFloat() / updatedTotal.toFloat()).coerceIn(0.1f, 0.98f)

                    // Expand display limit automatically so streamed items appear without requiring user scroll
                    _proposedDisplayLimit.value = maxOf(
                        _proposedDisplayLimit.value + fetchedBatch.size,
                        _allWorkItems.value.size
                    )
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)

                    repository.saveCachedOrganizationSnapshot(normOrg, _allWorkItems.value)

                    // Yield/pause briefly between batches so UI scrolling and animations stay at 60fps
                    delay(100L)
                }

                val finalActiveOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
                if (finalActiveOrg == null || finalActiveOrg.equals(normOrg, ignoreCase = true)) {
                    repository.saveCachedOrganizationSnapshot(normOrg, _allWorkItems.value)
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)
                }
            } finally {
                _isBackgroundSyncing.value = false
                _syncProgress.value = null
                _syncStatusText.value = null
            }
        }
    }

    private fun persistCurrentItemsInBackground(organization: String? = null) {
        val targetOrg = organization
            ?: (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
            ?: return
        val currentItems = _allWorkItems.value
        if (currentItems.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveCachedOrganizationSnapshot(targetOrg, currentItems)
        }
    }

    init {
        // Automatically ensure the active organization's default project is selected when projects are available.
        // Pair activeOrg and its strictly-scoped projects together so a stale emission from a previous
        // organization can never overwrite the newly selected organization's project.
        viewModelScope.launch {
            combine(
                _connectionStatus,
                _projectsByOrg,
                _allWorkItems,
                _workItemsOrg
            ) { status, projectsMap, items, itemsOrg ->
                val connected = status as? ConnectionStatus.Connected
                val activeOrg = connected?.organization
                    ?: _defaultOrganization.value
                    ?: _organizations.value.firstOrNull()?.organization
                    ?: ""
                val fetchedForOrg = projectsMap.entries
                    .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value.orEmpty()
                val fromItems = if (!itemsOrg.isNullOrBlank() && itemsOrg.equals(activeOrg, ignoreCase = true)) {
                    items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                val cachedForOrg = if (activeOrg.isNotBlank()) repository.getCachedProjectsForOrg(activeOrg) else emptyList()
                val orgProjects = (fetchedForOrg + fromItems + cachedForOrg)
                    .distinctBy { it.lowercase() }
                    .sorted()
                activeOrg to orgProjects
            }.collect { (activeOrg, projects) ->
                if (activeOrg.isBlank() || projects.isEmpty()) return@collect

                val resolvedDefaultForActiveOrg = resolveAndPersistDefaultProjectForOrg(activeOrg, projects)

                if (!userManuallySelectedProject && !resolvedDefaultForActiveOrg.isNullOrBlank()) {
                    if (_selectedProject.value == null || !_selectedProject.value.equals(resolvedDefaultForActiveOrg, ignoreCase = true)) {
                        _selectedProject.value = resolvedDefaultForActiveOrg
                    }
                    return@collect
                }
                if (_selectedProject.value == null || projects.none { it.equals(_selectedProject.value, ignoreCase = true) }) {
                    val fallbackProj = resolvedDefaultForActiveOrg ?: projects.first()
                    _selectedProject.value = fallbackProj
                }
            }
        }

        // Always keep one valid Default Project for the Default Organization
        viewModelScope.launch {
            defaultOrgProjects.collect { defProjects ->
                if (defProjects.isEmpty()) return@collect
                val defOrg = _defaultOrganization.value ?: return@collect
                resolveAndPersistDefaultProjectForOrg(defOrg, defProjects)
            }
        }

        // Automatically ensure the biggest enabled type is selected by default for the selected project
        viewModelScope.launch {
            availableTypes.collect { types ->
                if (types.isEmpty()) return@collect
                if (!userManuallySelectedType || _typeFilter.value == null || types.none { it.equals(_typeFilter.value, ignoreCase = true) }) {
                    val biggestType = types.first()
                    if (_typeFilter.value != biggestType) {
                        _typeFilter.value = biggestType
                    }
                } else {
                    val exact = types.firstOrNull { it.equals(_typeFilter.value, ignoreCase = true) }
                    if (exact != null && exact != _typeFilter.value) {
                        _typeFilter.value = exact
                    }
                }
            }
        }

        // Automatically ensure a valid enabled state is selected for the selected type
        viewModelScope.launch {
            availableStates.collect { states ->
                if (states.isNotEmpty() && (_stateFilter.value == null || states.none { it.equals(_stateFilter.value, ignoreCase = true) })) {
                    _stateFilter.value = states.first()
                } else if (states.isNotEmpty() && _stateFilter.value != null) {
                    val exact = states.firstOrNull { it.equals(_stateFilter.value, ignoreCase = true) }
                    if (exact != null && exact != _stateFilter.value) {
                        _stateFilter.value = exact
                    }
                }
            }
        }

        // Debounced live server-side search for unloaded work items when searchQuery is non-blank
        viewModelScope.launch {
            _searchQuery.collectLatest { rawQuery ->
                val clean = rawQuery.trim()
                if (clean.isBlank()) {
                    _isSearchingServer.value = false
                    return@collectLatest
                }
                delay(220L)
                val acc = getActiveAccount()
                val org = acc?.organization ?: repository.getConfiguredUrl()
                val pat = acc?.pat ?: repository.getConfiguredPat()
                if (org.isBlank() || pat.isBlank()) {
                    _isSearchingServer.value = false
                    return@collectLatest
                }

                _isSearchingServer.value = true
                val existingIds = _allWorkItems.value.map { it.id }.toSet()
                val searchRes = repository.searchWorkItemsOnServer(
                    organization = org,
                    pat = pat,
                    query = clean,
                    project = _selectedProject.value,
                    alreadyLoadedIds = existingIds
                )
                searchRes.onSuccess { newItems ->
                    if (newItems.isNotEmpty()) {
                        mergeHydratedWorkItemsSuspend(newItems)
                        persistCurrentItemsInBackground(org)
                    }
                }
                _isSearchingServer.value = false
            }
        }

        // Auto-connect to the Default Organization if saved credentials exist
        val savedOrgs = repository.getSavedOrganizations()
        val defOrgName = repository.getDefaultOrganization() ?: savedOrgs.firstOrNull()?.organization
        val defAccount = savedOrgs.firstOrNull { it.organization.equals(defOrgName, ignoreCase = true) }
            ?: savedOrgs.firstOrNull()

        if (defAccount != null) {
            _defaultOrganization.value = defAccount.organization
            _defaultProject.value = repository.getDefaultProject(defAccount.organization)
            _connectionStatus.value = ConnectionStatus.Connected(defAccount.organization)
            // Restore cached items immediately before network call so user sees 0ms startup
            restoreOrganizationFromLocalCache(defAccount.organization)
            connect(defAccount.organization, defAccount.pat, rememberCredentials = defAccount.isRemembered)
            // Also load project lists for any other saved organizations in background
            savedOrgs.filterNot { it.organization.equals(defAccount.organization, ignoreCase = true) }.forEach { otherAcc ->
                loadOrganizationProjectsInBackground(otherAcc)
            }
        } else {
            _isLoading.value = false
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun getActiveAccount(): OrganizationAccount? {
        val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
        if (!activeOrg.isNullOrBlank()) {
            _organizations.value.firstOrNull { it.organization.equals(activeOrg, ignoreCase = true) }?.let {
                return it
            }
        }
        val defOrg = _defaultOrganization.value
        if (!defOrg.isNullOrBlank()) {
            _organizations.value.firstOrNull { it.organization.equals(defOrg, ignoreCase = true) }?.let {
                return it
            }
        }
        return _organizations.value.firstOrNull()
    }

    private fun loadOrganizationProjectsInBackground(account: OrganizationAccount) {
        viewModelScope.launch {
            val fetched = repository.fetchProjects(account.organization, account.pat)
            if (fetched.isNotEmpty()) {
                val merged = (fetched + repository.getCachedProjectsForOrg(account.organization))
                    .distinctBy { it.lowercase() }
                    .sorted()
                repository.saveCachedProjectsForOrg(account.organization, merged)
                _projectsByOrg.value = _projectsByOrg.value + (account.organization to merged)
                resolveAndPersistDefaultProjectForOrg(account.organization, merged)
            }
        }
    }

    private suspend fun fetchAndStoreProjectMetadata(
        organization: String,
        project: String,
        pat: String
    ) {
        val normOrg = repository.extractOrganization(organization)
        if (normOrg.isBlank() || project.isBlank() || pat.isBlank()) return
        coroutineScope {
            val hierarchyDef = async { repository.fetchProjectProcessHierarchy(normOrg, project, pat) }
            val catMapDef = async { repository.fetchProjectCategoryMap(normOrg, project, pat) }
            val hierarchy = hierarchyDef.await()
            val catMap = catMapDef.await()
            val key = processKey(normOrg, project)
            if (hierarchy.isNotEmpty()) {
                _projectProcessMap.value = _projectProcessMap.value + (key to hierarchy) + (project to hierarchy)
            }
            if (catMap.isNotEmpty()) {
                _projectCategoryMap.value = _projectCategoryMap.value + (key to catMap) + (project to catMap)
            }
            if (hierarchy.isNotEmpty() || catMap.isNotEmpty()) {
                repository.saveCachedOrganizationMetadata(
                    organization = normOrg,
                    processHierarchyByProject = _projectProcessMap.value,
                    categoryMapByProject = _projectCategoryMap.value
                )
            }
        }
    }

    fun getInitialOrg(): String = getActiveAccount()?.organization ?: repository.getConfiguredUrl()
    fun getInitialPat(): String = getActiveAccount()?.pat ?: repository.getConfiguredPat()

    fun connect(organization: String, pat: String, rememberCredentials: Boolean = true) {
        val normalizedOrg = repository.extractOrganization(organization)
        val cleanPat = pat.trim()
        val isAlreadyInApp = _connectionStatus.value is ConnectionStatus.Connected || _organizations.value.isNotEmpty()

        if (normalizedOrg.isBlank()) {
            _isLoading.value = false
            _errorMessage.value = "Please provide an Azure DevOps Organization URL or Name."
            if (!isAlreadyInApp) {
                _connectionStatus.value = ConnectionStatus.Disconnected
            }
            return
        }
        if (cleanPat.isBlank()) {
            _isLoading.value = false
            _errorMessage.value = "Please provide your Personal Access Token (PAT)."
            if (!isAlreadyInApp) {
                _connectionStatus.value = ConnectionStatus.Disconnected
            }
            return
        }

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        // 1. Check if we already have cached work items on device for this organization
        val cachedSnapshot = if (_allWorkItems.value.isNotEmpty() &&
            _workItemsOrg.value.equals(normalizedOrg, ignoreCase = true)
        ) {
            repository.getCachedOrganizationSnapshotSync(normalizedOrg)
        } else {
            restoreOrganizationFromLocalCache(normalizedOrg)
        }
        val hasCachedItemsOnDevice = cachedSnapshot != null && cachedSnapshot.workItems.isNotEmpty()

        activeConnectJob = viewModelScope.launch {
            _errorMessage.value = null

            if (hasCachedItemsOnDevice && cachedSnapshot != null) {
                // Instant non-blocking launch: cached items are already visible!
                _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
                _workItemsOrg.value = normalizedOrg
                _isLoading.value = false
                _isBackgroundSyncing.value = true
                _syncProgress.value = null
                _syncStatusText.value = "Syncing changes..."

                val (deltaResult, projList) = coroutineScope {
                    val deltaDef = async {
                        repository.syncDeltaAndQueueRemaining(
                            organization = normalizedOrg,
                            pat = cleanPat,
                            cachedItems = _allWorkItems.value,
                            lastSyncedChangedDate = cachedSnapshot.lastSyncedChangedDate
                        )
                    }
                    val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                    deltaDef.await() to projsDef.await()
                }

                deltaResult.onSuccess { delta ->
                    if (delta.deletedItemIds.isNotEmpty()) {
                        delta.deletedItemIds.forEach { serverSyncedItems.remove(it) }
                        _allWorkItems.value = _allWorkItems.value.filterNot { it.id in delta.deletedItemIds }
                    }
                    if (delta.updatedOrNewItems.isNotEmpty()) {
                        mergeHydratedWorkItemsSuspend(delta.updatedOrNewItems)
                    }
                    _workItemsOrg.value = normalizedOrg
                    _proposedDisplayLimit.value = maxOf(
                        _proposedDisplayLimit.value,
                        _allWorkItems.value.size
                    )
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                    repository.saveCachedOrganizationSnapshot(normalizedOrg, _allWorkItems.value)
                }

                val fromItemsProjects = _allWorkItems.value.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                val mergedProjects = (projList + fromItemsProjects + cachedForOrg)
                    .distinctBy { it.lowercase() }
                    .sorted()

                if (mergedProjects.isNotEmpty()) {
                    repository.saveCachedProjectsForOrg(normalizedOrg, mergedProjects)
                    _projectsByOrg.value = _projectsByOrg.value + (normalizedOrg to mergedProjects)
                }

                val orgDefaultProject = resolveAndPersistDefaultProjectForOrg(normalizedOrg, mergedProjects)
                val targetSelectedProject = if (userManuallySelectedProject &&
                    _selectedProject.value != null &&
                    mergedProjects.any { it.equals(_selectedProject.value, ignoreCase = true) }
                ) {
                    mergedProjects.first { it.equals(_selectedProject.value, ignoreCase = true) }
                } else {
                    orgDefaultProject ?: mergedProjects.firstOrNull()
                }

                if (targetSelectedProject != null) {
                    _selectedProject.value = targetSelectedProject
                    launch {
                        fetchAndStoreProjectMetadata(normalizedOrg, targetSelectedProject, cleanPat)
                    }
                }

                val otherProjects = mergedProjects.filterNot {
                    it.equals(targetSelectedProject, ignoreCase = true)
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

                // Continue streaming any remaining uncached items iteratively in the background
                if (repository.hasUnloadedServerItems(normalizedOrg)) {
                    startIterativeBackgroundStream(normalizedOrg, cleanPat)
                } else {
                    _isBackgroundSyncing.value = false
                    _syncProgress.value = null
                    _syncStatusText.value = null
                }
                return@launch
            }

            // 2. First-time connection for this organization (no cached items on device yet)
            if (isAlreadyInApp) {
                _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
            } else {
                _connectionStatus.value = ConnectionStatus.Connecting
            }
            _isLoading.value = true

            val (result, projList) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, cleanPat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                itemsDef.await() to projsDef.await()
            }

            result.fold(
                onSuccess = { items ->
                    val account = OrganizationAccount(
                        organization = normalizedOrg,
                        pat = cleanPat,
                        isRemembered = rememberCredentials
                    )
                    repository.saveOrganizationAccount(account)

                    val updatedOrgs = _organizations.value.toMutableList()
                    val existingIdx = updatedOrgs.indexOfFirst {
                        it.organization.equals(normalizedOrg, ignoreCase = true)
                    }
                    if (existingIdx >= 0) {
                        updatedOrgs[existingIdx] = account
                    } else {
                        updatedOrgs.add(account)
                    }
                    _organizations.value = updatedOrgs

                    if (_defaultOrganization.value.isNullOrBlank() || updatedOrgs.size == 1) {
                        _defaultOrganization.value = normalizedOrg
                        if (rememberCredentials) {
                            repository.saveDefaultOrganization(normalizedOrg)
                        }
                    }

                    val fromItemsProjects = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                    val mergedProjects = (projList + fromItemsProjects + cachedForOrg)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (mergedProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, mergedProjects)
                        _projectsByOrg.value = _projectsByOrg.value + (normalizedOrg to mergedProjects)
                    }

                    val orgDefaultProject = resolveAndPersistDefaultProjectForOrg(normalizedOrg, mergedProjects)
                    val targetSelectedProject = if (userManuallySelectedProject &&
                        _selectedProject.value != null &&
                        mergedProjects.any { it.equals(_selectedProject.value, ignoreCase = true) }
                    ) {
                        mergedProjects.first { it.equals(_selectedProject.value, ignoreCase = true) }
                    } else {
                        orgDefaultProject ?: mergedProjects.firstOrNull()
                    }

                    _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
                    _workItemsOrg.value = normalizedOrg
                    if (targetSelectedProject != null) {
                        _selectedProject.value = targetSelectedProject
                    }

                    serverSyncedItems.clear()
                    items.forEach { serverSyncedItems[it.id] = it }
                    _allWorkItems.value = items
                    _proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()
                    _hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    // Save initial batch to local device storage immediately
                    launch(Dispatchers.IO) {
                        repository.saveCachedOrganizationSnapshot(normalizedOrg, items)
                    }

                    // Render the initial batch immediately without blocking on metadata calls
                    _isLoading.value = false

                    if (targetSelectedProject != null) {
                        launch {
                            fetchAndStoreProjectMetadata(normalizedOrg, targetSelectedProject, cleanPat)
                        }
                    }

                    val otherProjects = mergedProjects.filterNot {
                        it.equals(targetSelectedProject, ignoreCase = true)
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

                    // Automatically stream all remaining work items iteratively in the background without requiring scroll
                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
                    }
                },
                onFailure = { error ->
                    _isLoading.value = false
                    if (_organizations.value.isEmpty()) {
                        _connectionStatus.value = ConnectionStatus.Disconnected
                    } else {
                        _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
                    }
                    _errorMessage.value = error.message ?: "Failed to connect to Azure DevOps."
                }
            )
        }
    }

    fun switchOrganization(organizationName: String) {
        val normTarget = repository.extractOrganization(organizationName)
        val account = _organizations.value.firstOrNull {
            it.organization.equals(normTarget, ignoreCase = true)
        } ?: repository.getSavedOrganizations().firstOrNull {
            it.organization.equals(normTarget, ignoreCase = true)
        } ?: return

        val currentConnected = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
        if (currentConnected != null &&
            currentConnected.equals(account.organization, ignoreCase = true) &&
            _workItemsOrg.value.equals(account.organization, ignoreCase = true) &&
            _allWorkItems.value.isNotEmpty()
        ) {
            return
        }

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()
        _isBackgroundSyncing.value = false
        _syncProgress.value = null
        _syncStatusText.value = null

        // Immediately switch the active organization state and select its Default Project
        userManuallySelectedProject = false
        userManuallySelectedType = false
        _searchQuery.value = ""
        _typeFilter.value = null
        _stateFilter.value = null
        _assigneeFilter.value = null
        _selectedWorkItem.value = null
        _workItemsOrg.value = account.organization
        _allWorkItems.value = emptyList()

        val knownProjectsForOrg = (_projectsByOrg.value.entries
            .firstOrNull { it.key.equals(account.organization, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(account.organization))
            .distinctBy { it.lowercase() }
            .sorted()

        _connectionStatus.value = ConnectionStatus.Connected(account.organization)
        _selectedProject.value = resolveAndPersistDefaultProjectForOrg(account.organization, knownProjectsForOrg)
            ?: knownProjectsForOrg.firstOrNull()

        // Restore cached items for the target organization immediately if available
        val restored = restoreOrganizationFromLocalCache(account.organization)
        _isLoading.value = restored == null || restored.workItems.isEmpty()

        viewModelScope.launch {
            // Flush any pending edits on the previous organization before fetching new org items
            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            val dirtyList = pendingDirtyItems.values.toList()
            for (dirtyItem in dirtyList) {
                syncWorkItemToServer(dirtyItem)
            }
            pendingDirtyItems.clear()

            connect(account.organization, account.pat, rememberCredentials = account.isRemembered)
        }
    }

    fun setDefaultOrganization(organizationName: String) {
        val normOrg = repository.extractOrganization(organizationName)
        if (normOrg.isBlank()) return
        val matchedAccount = _organizations.value.firstOrNull {
            it.organization.equals(normOrg, ignoreCase = true)
        }
        val targetOrg = matchedAccount?.organization ?: normOrg
        _defaultOrganization.value = targetOrg
        if (matchedAccount?.isRemembered != false) {
            repository.saveDefaultOrganization(targetOrg)
        }

        val knownProjects = (_projectsByOrg.value.entries
            .firstOrNull { it.key.equals(targetOrg, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(targetOrg))
            .distinctBy { it.lowercase() }
            .sorted()

        val resolvedDefProj = resolveAndPersistDefaultProjectForOrg(targetOrg, knownProjects)
        if (resolvedDefProj != null) {
            _defaultProject.value = resolvedDefProj
        }

        if (matchedAccount != null) {
            viewModelScope.launch {
                val fetched = repository.fetchProjects(matchedAccount.organization, matchedAccount.pat)
                if (fetched.isNotEmpty()) {
                    repository.saveCachedProjectsForOrg(matchedAccount.organization, fetched)
                    _projectsByOrg.value = _projectsByOrg.value + (matchedAccount.organization to fetched)
                    val chosen = resolveAndPersistDefaultProjectForOrg(matchedAccount.organization, fetched)
                    if (_defaultOrganization.value.equals(matchedAccount.organization, ignoreCase = true) && chosen != null) {
                        _defaultProject.value = chosen
                    }
                }
            }
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

        // Stop any background streaming or connection job from the previous organization immediately
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
                        _projectsByOrg.value = _projectsByOrg.value + (normalizedOrg to orgProjects)
                    }

                    val newAccount = OrganizationAccount(
                        organization = normalizedOrg,
                        pat = cleanPat,
                        isRemembered = isRemembered
                    )

                    val currentList = _organizations.value.toMutableList()
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
                    _organizations.value = currentList.distinctBy { it.organization.lowercase() }
                    repository.saveOrganizationAccount(newAccount)

                    val wasPreviousDefault = !previousOrgName.isNullOrBlank() &&
                        previousOrgName.equals(_defaultOrganization.value, ignoreCase = true)
                    if (_organizations.value.size == 1 || _defaultOrganization.value.isNullOrBlank() || wasPreviousDefault) {
                        _defaultOrganization.value = normalizedOrg
                        if (isRemembered) {
                            repository.saveDefaultOrganization(normalizedOrg)
                        }
                    }

                    val chosenProj = resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    // Switch active organization and project BEFORE updating _allWorkItems so collectors
                    // never associate the new organization with the previous organization's project
                    userManuallySelectedProject = false
                    userManuallySelectedType = false
                    _searchQuery.value = ""
                    _typeFilter.value = null
                    _stateFilter.value = null
                    _assigneeFilter.value = null
                    _selectedWorkItem.value = null
                    _stateDisplayLimits.value = emptyMap()
                    exhaustedStateQueries.clear()

                    _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
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

                    onResult(true, null)
                },
                onFailure = { err ->
                    onResult(false, err.message ?: "Failed to verify organization and PAT.")
                }
            )
        }
    }

    fun removeOrganization(organizationName: String) {
        val normOrg = repository.extractOrganization(organizationName)
        if (normOrg.isBlank()) return

        repository.removeOrganizationAccount(normOrg)
        val remaining = _organizations.value.filterNot {
            it.organization.equals(normOrg, ignoreCase = true)
        }
        _organizations.value = remaining

        if (remaining.isEmpty()) {
            disconnect()
            return
        }

        if (_defaultOrganization.value.equals(normOrg, ignoreCase = true)) {
            setDefaultOrganization(remaining.first().organization)
        }

        val currentConnected = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
        if (currentConnected != null && currentConnected.equals(normOrg, ignoreCase = true)) {
            val nextOrg = _defaultOrganization.value ?: remaining.first().organization
            switchOrganization(nextOrg)
        }
    }

    fun retryConnection() {
        val acc = getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            connect(org, pat, rememberCredentials = acc?.isRemembered ?: true)
        }
    }

    private suspend fun syncWorkItemToServer(itemToSync: WorkItem) {
        val acc = getActiveAccount()
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
        val acc = getActiveAccount()
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
            _errorMessage.value = null

            // Flush any pending or debounced work item edits to Azure DevOps before fetching
            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            val dirtyList = pendingDirtyItems.values.toList()
            for (dirtyItem in dirtyList) {
                syncWorkItemToServer(dirtyItem)
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
                            _projectsByOrg.value = _projectsByOrg.value + (normalizedOrg to mergedProjects)
                        }

                        val resolvedDefProj = resolveAndPersistDefaultProjectForOrg(normalizedOrg, mergedProjects)
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
                        _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, pat)
                        } else {
                            _isBackgroundSyncing.value = false
                            _syncProgress.value = null
                            _syncStatusText.value = null
                        }
                    },
                    onFailure = { error ->
                        _isLoading.value = false
                        _isPullRefreshing.value = false
                        _isBackgroundSyncing.value = false
                        _syncProgress.value = null
                        _syncStatusText.value = null
                        _errorMessage.value = error.message ?: "Failed to refresh work items."
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
                        _projectsByOrg.value = _projectsByOrg.value + (normalizedOrg to mergedProjects)
                    }

                    val resolvedDefProj = resolveAndPersistDefaultProjectForOrg(normalizedOrg, mergedProjects)
                    val validSelectedProj = _selectedProject.value?.takeIf { current ->
                        mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                    } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                    _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
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
                },
                onFailure = { error ->
                    _isLoading.value = false
                    _isPullRefreshing.value = false
                    _isBackgroundSyncing.value = false
                    _syncProgress.value = null
                    _syncStatusText.value = null
                    _errorMessage.value = error.message ?: "Failed to refresh work items."
                }
            )
        }
    }

    private suspend fun mergeHydratedWorkItemsSuspend(newItems: List<WorkItem>) {
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

            // Cross-link parent and child IDs across the combined in-memory store
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

    /**
     * Loads the next batch of work items when scrolling near the bottom of the Home list
     * or when expanding a specific state chip.
     */
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
        val acc = getActiveAccount()
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
        _organizations.value = emptyList()
        _defaultOrganization.value = null
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
        _selectedWorkItem.value = null
        _errorMessage.value = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProject(project: String?) {
        val trimmed = project?.trim()
        if (trimmed.isNullOrBlank()) return
        userManuallySelectedProject = true
        userManuallySelectedType = false
        _selectedProject.value = trimmed
        ensureProjectProcessLoaded(trimmed)
    }

    private fun ensureProjectProcessLoaded(project: String) {
        val acc = getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            viewModelScope.launch {
                fetchAndStoreProjectMetadata(org, project, pat)
            }
        }
    }

    fun setDefaultProjectForOrg(organization: String, project: String) {
        val normOrg = repository.extractOrganization(organization)
        val trimmedProj = project.trim()
        if (normOrg.isBlank() || trimmedProj.isBlank()) return

        repository.saveDefaultProject(trimmedProj, normOrg)
        val updatedMap = _defaultProjectsByOrg.value.toMutableMap()
        val existingKey = updatedMap.keys.firstOrNull { it.equals(normOrg, ignoreCase = true) }
        if (existingKey != null && existingKey != normOrg) {
            updatedMap.remove(existingKey)
        }
        updatedMap[normOrg] = trimmedProj
        _defaultProjectsByOrg.value = updatedMap

        if (normOrg.equals(_defaultOrganization.value, ignoreCase = true)) {
            _defaultProject.value = trimmedProj
        }

        val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
        if (activeOrg != null && activeOrg.equals(normOrg, ignoreCase = true)) {
            userManuallySelectedProject = false
            userManuallySelectedType = false
            _selectedProject.value = trimmedProj
            ensureProjectProcessLoaded(trimmedProj)
        }
    }

    fun setDefaultProject(project: String) {
        val targetOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
            ?: _organizations.value.firstOrNull()?.organization
            ?: return
        setDefaultProjectForOrg(targetOrg, project)
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

            // When user explicitly selects a Completed, Resolved, Removed, or Proposed state chip,
            // load more items for that state from the server if not already exhausted.
            val cat = repository.resolveStateCategory(trimmedState, currentProject, currentType)
            if (cat != AzureDevOpsRepository.CATEGORY_IN_PROGRESS && stateKey !in exhaustedStateQueries) {
                val inMemoryForState = _allWorkItems.value.count { item ->
                    (currentProject == null || item.teamProject?.equals(currentProject, ignoreCase = true) == true) &&
                        (currentType == null || item.workItemType.equals(currentType, ignoreCase = true)) &&
                        item.state.equals(trimmedState, ignoreCase = true)
                }
                val targetLimit = _stateDisplayLimits.value[stateKey] ?: STATE_INITIAL_BATCH_SIZE
                if (inMemoryForState < targetLimit && !_isLoadingMore.value) {
                    val acc = getActiveAccount()
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

    fun selectWorkItem(item: WorkItem?) {
        if (item != null && _searchQuery.value.isNotEmpty()) {
            _searchQuery.value = ""
        }
        _selectedWorkItem.value = item
    }

    fun updateWorkItem(updatedItem: WorkItem) {
        val currentInMemory = _allWorkItems.value.firstOrNull { it.id == updatedItem.id }

        // Resolve assignee email (uniqueName) if this assignee is known in the organization's work items
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

        val onlyTextChanged = currentInMemory != null &&
            currentInMemory.state == normalizedUpdated.state &&
            currentInMemory.assignedToName == normalizedUpdated.assignedToName &&
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
        val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProj)
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

    /**
     * Returns the list of valid child work item types for [item] in its project's hierarchy.
     * If [item] is at the last level of the project's work item hierarchy (e.g. Task),
     * returns an empty list so the Detail page does not show a Children section for it.
     */
    fun getAllowedChildTypesForWorkItem(item: WorkItem): List<String> {
        return getAllowedChildTypesForType(item.teamProject, item.workItemType)
    }

    /**
     * Returns the list of valid child work item types for a given [workItemType] in [project].
     */
    fun getAllowedChildTypesForType(project: String?, workItemType: String): List<String> {
        val resolvedProject = project ?: _selectedProject.value ?: ""
        val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProject)
        val activeTypesFromProcess = processMapForProj?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
            .orEmpty()

        val projectItems = _allWorkItems.value.filter {
            it.teamProject?.equals(resolvedProject, ignoreCase = true) == true
        }
        val activeTypesFromItems = projectItems.map { it.workItemType }
            .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            .distinct()

        val activeTypes = when {
            activeTypesFromProcess.isNotEmpty() -> activeTypesFromProcess
            activeTypesFromItems.isNotEmpty() -> activeTypesFromItems
            else -> repository.getKnownProjectProcessHierarchy(resolvedProject).keys.toList()
        }

        val catMap = findCategoryMap(activeOrg, resolvedProject).orEmpty()

        val levels = repository.resolveOrderedHierarchyLevels(
            activeTypes = activeTypes,
            categoryMap = catMap,
            projectWorkItems = projectItems
        )
        if (levels.size <= 1) return emptyList()

        val levelIdx = levels.indexOfFirst { levelTypes ->
            levelTypes.any { it.equals(workItemType, ignoreCase = true) }
        }
        if (levelIdx < 0 || levelIdx >= levels.lastIndex) {
            return emptyList()
        }

        return levels.drop(levelIdx + 1).flatten()
    }

    /**
     * Creates a new work item in the active organization & project with the given fields.
     * Invokes [onResult] with the created [WorkItem] once saved.
     */
    fun createWorkItem(
        project: String?,
        workItemType: String,
        title: String,
        state: String,
        assignedToName: String?,
        description: String?,
        tags: List<String>,
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

        val acc = getActiveAccount()
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
                areaPath = resolvedProject.ifBlank { null },
                iterationPath = resolvedProject.ifBlank { null },
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
                tags = cleanTags
            )
            result.fold(
                onSuccess = { serverCreated ->
                    val finalCreated = serverCreated.copy(
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

    /**
     * Creates a new child work item under [parent], immediately updating UI state and
     * persisting the new child work item and hierarchy link to Azure DevOps.
     */
    fun addChildWorkItem(
        parent: WorkItem,
        title: String,
        childType: String,
        initialState: String
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return

        val project = parent.teamProject ?: _selectedProject.value ?: ""
        val resolvedType = childType.ifBlank {
            getAllowedChildTypesForWorkItem(parent).firstOrNull() ?: "Task"
        }
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

        val acc = getActiveAccount()
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

    fun clearError() {
        _errorMessage.value = null
    }
}
