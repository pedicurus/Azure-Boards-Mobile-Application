package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.repository.AzureDevOpsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
        _allWorkItems
    ) { status, projectsMap, items ->
        val connected = status as? ConnectionStatus.Connected
        val activeOrg = connected?.organization
            ?: _defaultOrganization.value
            ?: _organizations.value.firstOrNull()?.organization
            ?: ""
        val fetchedForOrg = projectsMap.entries
            .firstOrNull { it.key.equals(activeOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItems = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
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
        _allWorkItems
    ) { defOrg, projectsMap, status, items ->
        val connected = status as? ConnectionStatus.Connected
        val targetOrg = defOrg
            ?: _organizations.value.firstOrNull()?.organization
            ?: connected?.organization
            ?: ""
        if (targetOrg.isBlank()) return@combine emptyList()

        val fetchedForDefOrg = projectsMap.entries
            .firstOrNull { it.key.equals(targetOrg, ignoreCase = true) }?.value.orEmpty()
        val fromItemsIfActive = if (connected?.organization?.equals(targetOrg, ignoreCase = true) == true) {
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

    val availableTypes: StateFlow<List<String>> = combine(
        _connectionStatus,
        _allWorkItems,
        _selectedProject,
        _projectProcessMap
    ) { status, items, project, processMap ->
        if (project.isNullOrBlank()) return@combine emptyList()
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization ?: _defaultOrganization.value
        val matchedProcessMap = findProcessHierarchy(activeOrg, project, processMap)
        val fromApi = matchedProcessMap?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
        if (!fromApi.isNullOrEmpty()) {
            fromApi
        } else {
            val fromItems = items.filter { it.teamProject?.equals(project, ignoreCase = true) == true }
                .map { it.workItemType }
                .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
                .distinct()
            fromItems
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableStates: StateFlow<List<String>> = combine(
        _connectionStatus,
        _allWorkItems,
        _selectedProject,
        _typeFilter,
        _projectProcessMap
    ) { status, items, project, type, processMap ->
        if (project.isNullOrBlank() || type.isNullOrBlank()) return@combine emptyList()
        val activeOrg = (status as? ConnectionStatus.Connected)?.organization ?: _defaultOrganization.value
        val matchedProjMap = findProcessHierarchy(activeOrg, project, processMap)
        val fromApi = matchedProjMap?.entries?.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value
        if (!fromApi.isNullOrEmpty()) {
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

    val filteredWorkItems: StateFlow<List<WorkItem>> = combine(
        combine(_allWorkItems, _searchQuery, _selectedProject) { items, query, project ->
            Triple(items, query, project)
        },
        combine(_typeFilter, _stateFilter, _assigneeFilter) { type, state, assignee ->
            Triple(type, state, assignee)
        }
    ) { (items, query, project), (type, state, assignee) ->
        items.filter { item ->
            val matchesQuery = query.isBlank() ||
                item.title.contains(query, ignoreCase = true) ||
                item.id.toString().contains(query) ||
                (item.description?.contains(query, ignoreCase = true) == true) ||
                (item.assignedToName?.contains(query, ignoreCase = true) == true)

            val matchesProject = project == null || item.teamProject?.equals(project, ignoreCase = true) == true
            val matchesType = type == null || item.workItemType.equals(type, ignoreCase = true)
            val matchesState = state == null || item.state.equals(state, ignoreCase = true)
            val matchesAssignee = when {
                assignee.isNullOrBlank() || assignee.equals("All", ignoreCase = true) -> true
                assignee.equals("Unassigned", ignoreCase = true) ->
                    item.assignedToName.isNullOrBlank() || item.assignedToName.equals("Unassigned", ignoreCase = true)
                else -> item.assignedToName?.trim()?.equals(assignee.trim(), ignoreCase = true) == true
            }

            matchesQuery && matchesProject && matchesType && matchesState && matchesAssignee
        }.sortedWith(
            compareBy<WorkItem>(
                { it.boardOrder ?: Double.MAX_VALUE },
                { it.id }
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Automatically ensure the active organization's default project is selected when projects are available
        viewModelScope.launch {
            availableProjects.collect { projects ->
                if (projects.isEmpty()) return@collect
                val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
                    ?: _defaultOrganization.value
                    ?: return@collect

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

        // Automatically ensure a valid enabled type is selected for the selected project
        viewModelScope.launch {
            availableTypes.collect { types ->
                if (types.isNotEmpty() && (_typeFilter.value == null || types.none { it.equals(_typeFilter.value, ignoreCase = true) })) {
                    _typeFilter.value = types.first()
                } else if (types.isNotEmpty() && _typeFilter.value != null) {
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

        // Auto-connect to the Default Organization if saved credentials exist
        val savedOrgs = repository.getSavedOrganizations()
        val defOrgName = repository.getDefaultOrganization() ?: savedOrgs.firstOrNull()?.organization
        val defAccount = savedOrgs.firstOrNull { it.organization.equals(defOrgName, ignoreCase = true) }
            ?: savedOrgs.firstOrNull()

        if (defAccount != null) {
            _defaultOrganization.value = defAccount.organization
            _defaultProject.value = repository.getDefaultProject(defAccount.organization)
            _connectionStatus.value = ConnectionStatus.Connected(defAccount.organization)
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

        viewModelScope.launch {
            if (isAlreadyInApp) {
                _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
            } else {
                _connectionStatus.value = ConnectionStatus.Connecting
            }
            _isLoading.value = true
            _errorMessage.value = null

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

                    serverSyncedItems.clear()
                    items.forEach { serverSyncedItems[it.id] = it }
                    _allWorkItems.value = items

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

                    if (targetSelectedProject != null) {
                        _selectedProject.value = targetSelectedProject
                        // Load the selected project's process hierarchy and enabled states BEFORE clearing isLoading
                        fetchAndStoreProjectMetadata(normalizedOrg, targetSelectedProject, cleanPat)
                    }

                    _isLoading.value = false
                    _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)

                    // Load process hierarchy for any remaining projects of this organization in parallel
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
        if (currentConnected != null && currentConnected.equals(account.organization, ignoreCase = true)) {
            return
        }

        // Immediately switch the active organization state and select its Default Project
        userManuallySelectedProject = false
        _searchQuery.value = ""
        _typeFilter.value = null
        _stateFilter.value = null
        _assigneeFilter.value = null
        _selectedWorkItem.value = null
        _allWorkItems.value = emptyList()

        val knownProjectsForOrg = (_projectsByOrg.value.entries
            .firstOrNull { it.key.equals(account.organization, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(account.organization))
            .distinctBy { it.lowercase() }
            .sorted()

        _selectedProject.value = resolveAndPersistDefaultProjectForOrg(account.organization, knownProjectsForOrg)
            ?: knownProjectsForOrg.firstOrNull()
        _connectionStatus.value = ConnectionStatus.Connected(account.organization)
        _isLoading.value = true

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

        viewModelScope.launch {
            val workItemsResult = repository.fetchWorkItems(normalizedOrg, cleanPat)
            workItemsResult.fold(
                onSuccess = { items ->
                    val fetchedProjs = repository.fetchProjects(normalizedOrg, cleanPat)
                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val orgProjects = (fetchedProjs + fromItemsProjs).distinctBy { it.lowercase() }.sorted()

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

                    // Switch active view to the newly added or edited organization immediately
                    serverSyncedItems.clear()
                    items.forEach { serverSyncedItems[it.id] = it }
                    _allWorkItems.value = items
                    userManuallySelectedProject = false
                    _searchQuery.value = ""
                    _typeFilter.value = null
                    _stateFilter.value = null
                    _assigneeFilter.value = null
                    _selectedWorkItem.value = null
                    _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
                    if (chosenProj != null) {
                        _selectedProject.value = chosenProj
                        fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, cleanPat)
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
        }
    }

    fun refreshWorkItems() {
        val acc = getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val normalizedOrg = repository.extractOrganization(org)

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            // Flush any pending or debounced work item edits to Azure DevOps before fetching
            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            val dirtyList = pendingDirtyItems.values.toList()
            for (dirtyItem in dirtyList) {
                syncWorkItemToServer(dirtyItem)
            }

            val result = repository.fetchWorkItems(normalizedOrg, pat)

            result.fold(
                onSuccess = { items ->
                    val resolvedItems = items.map { fetched ->
                        // Preserve any local edit if its server sync is still pending
                        pendingDirtyItems[fetched.id] ?: fetched
                    }
                    resolvedItems.forEach { item ->
                        if (!pendingDirtyItems.containsKey(item.id)) {
                            serverSyncedItems[item.id] = item
                        }
                    }
                    _allWorkItems.value = resolvedItems
                    _selectedWorkItem.value?.let { currentSelected ->
                        _selectedWorkItem.value = resolvedItems.firstOrNull { it.id == currentSelected.id }
                    }
                    val targetProj = _selectedProject.value ?: getDefaultProjectForOrgInternal(normalizedOrg)
                    if (!targetProj.isNullOrBlank()) {
                        fetchAndStoreProjectMetadata(normalizedOrg, targetProj, pat)
                    }
                    _isLoading.value = false
                    _connectionStatus.value = ConnectionStatus.Connected(normalizedOrg)
                },
                onFailure = { error ->
                    _isLoading.value = false
                    _errorMessage.value = error.message ?: "Failed to refresh work items."
                }
            )
        }
    }

    fun disconnect() {
        repository.clearCredentials()
        _organizations.value = emptyList()
        _defaultOrganization.value = null
        serverSyncedItems.clear()
        pendingDirtyItems.clear()
        updateJobs.values.forEach { it.cancel() }
        updateJobs.clear()
        _allWorkItems.value = emptyList()
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
        _typeFilter.value = type
    }

    fun setStateFilter(state: String?) {
        if (state.isNullOrBlank()) return
        _stateFilter.value = state
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

        val onlyDescriptionChanged = currentInMemory != null &&
            currentInMemory.state == normalizedUpdated.state &&
            currentInMemory.assignedToName == normalizedUpdated.assignedToName &&
            currentInMemory.tags == normalizedUpdated.tags &&
            currentInMemory.description != normalizedUpdated.description

        updateJobs[normalizedUpdated.id]?.cancel()
        updateJobs[normalizedUpdated.id] = viewModelScope.launch {
            if (onlyDescriptionChanged) {
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
        val project = item.teamProject ?: _selectedProject.value ?: ""
        val activeOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
        val processMapForProj = findProcessHierarchy(activeOrg, project)
        val activeTypesFromProcess = processMapForProj?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
            .orEmpty()

        val projectItems = _allWorkItems.value.filter {
            it.teamProject?.equals(project, ignoreCase = true) == true
        }
        val activeTypesFromItems = projectItems.map { it.workItemType }
            .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            .distinct()

        val activeTypes = when {
            activeTypesFromProcess.isNotEmpty() -> activeTypesFromProcess
            activeTypesFromItems.isNotEmpty() -> activeTypesFromItems
            else -> repository.getKnownProjectProcessHierarchy(project).keys.toList()
        }

        val catMap = findCategoryMap(activeOrg, project).orEmpty()

        val levels = repository.resolveOrderedHierarchyLevels(
            activeTypes = activeTypes,
            categoryMap = catMap,
            projectWorkItems = projectItems
        )
        if (levels.size <= 1) return emptyList()

        val levelIdx = levels.indexOfFirst { levelTypes ->
            levelTypes.any { it.equals(item.workItemType, ignoreCase = true) }
        }
        if (levelIdx < 0 || levelIdx >= levels.lastIndex) {
            return emptyList()
        }

        return levels.drop(levelIdx + 1).flatten()
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
