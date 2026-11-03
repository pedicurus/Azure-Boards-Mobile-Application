package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.di.SessionManager
import com.example.ui.ConnectionStatus
import com.example.ui.state.WorkItemsUiState
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.normalizeClassificationPath
import com.example.ui.viewmodel.delegates.WorkItemCrudDelegate
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate.Companion.PROPOSED_INITIAL_BATCH_SIZE
import com.example.ui.viewmodel.delegates.WorkItemHierarchyDelegate
import com.example.ui.viewmodel.delegates.WorkItemProcessDelegate
import com.example.ui.viewmodel.delegates.WorkItemStreamDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Lightweight coordinator ViewModel (<300 lines) for Azure Boards work items, delegating all
 * domain logic to 5 focused feature delegates:
 * - [WorkItemFilterDelegate]: Search, facet filters, and reactive filtered item flows
 * - [WorkItemStreamDelegate]: Connection, delta sync, progressive background streaming, and search
 * - [WorkItemHierarchyDelegate]: Parent/child hierarchy, allowed child types, and comments
 * - [WorkItemCrudDelegate]: Optimistic CRUD, dirty tracking, and debounced JSON Patch sync
 * - [WorkItemProcessDelegate]: Process hierarchies, category maps, and classification trees
 */
class WorkItemsViewModel(
    application: Application,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    private val repository = sessionManager.repository

    private val _allWorkItems = MutableStateFlow<List<WorkItem>>(emptyList())
    val allWorkItems: StateFlow<List<WorkItem>> = _allWorkItems.asStateFlow()

    private val _workItemsOrg = MutableStateFlow<String?>(null)

    private val processDelegate = WorkItemProcessDelegate(repository)

    private val filterDelegate: WorkItemFilterDelegate = WorkItemFilterDelegate(
        scope = viewModelScope,
        sessionManager = sessionManager,
        repository = repository,
        processDelegate = processDelegate,
        allWorkItems = _allWorkItems,
        workItemsOrg = _workItemsOrg,
        onEnsureProjectProcessLoaded = ::onEnsureProjectProcessLoaded
    )

    private val crudDelegate: WorkItemCrudDelegate = WorkItemCrudDelegate(
        scope = viewModelScope,
        sessionManager = sessionManager,
        repository = repository,
        allWorkItems = _allWorkItems,
        filterDelegate = filterDelegate,
        onSyncSelectedWorkItem = ::onSyncSelectedWorkItem,
        onPersistSnapshot = ::onPersistSnapshot
    )

    private val hierarchyDelegate: WorkItemHierarchyDelegate = WorkItemHierarchyDelegate(
        application = application,
        scope = viewModelScope,
        sessionManager = sessionManager,
        repository = repository,
        allWorkItems = _allWorkItems,
        filterDelegate = filterDelegate,
        processDelegate = processDelegate,
        crudDelegate = crudDelegate,
        onPersistSnapshot = ::onPersistSnapshot
    )

    private val streamDelegate: WorkItemStreamDelegate = WorkItemStreamDelegate(
        scope = viewModelScope,
        sessionManager = sessionManager,
        repository = repository,
        allWorkItems = _allWorkItems,
        workItemsOrg = _workItemsOrg,
        filterDelegate = filterDelegate,
        processDelegate = processDelegate,
        crudDelegate = crudDelegate,
        hierarchyDelegate = hierarchyDelegate
    )

    private fun onEnsureProjectProcessLoaded(project: String) {
        streamDelegate.ensureProjectProcessLoaded(project)
    }

    private fun onSyncSelectedWorkItem(updated: WorkItem) {
        hierarchyDelegate.syncSelectedIfMatches(updated)
    }

    private fun onPersistSnapshot(org: String?) {
        streamDelegate.persistCurrentItemsInBackground(org)
    }

    // Filter & Facet StateFlows
    val searchQuery: StateFlow<String> = filterDelegate.searchQuery
    val selectedProject: StateFlow<String?> = filterDelegate.selectedProject
    val typeFilter: StateFlow<String?> = filterDelegate.typeFilter
    val stateFilter: StateFlow<String?> = filterDelegate.stateFilter
    val assigneeFilter: StateFlow<String?> = filterDelegate.assigneeFilter
    val areaFilter: StateFlow<String?> = filterDelegate.areaFilter
    val iterationFilter: StateFlow<String?> = filterDelegate.iterationFilter
    val availableProjects: StateFlow<List<String>> = filterDelegate.availableProjects
    val availableTypes: StateFlow<List<String>> = filterDelegate.availableTypes
    val availableStates: StateFlow<List<String>> = filterDelegate.availableStates
    val projectAssignees: StateFlow<List<String>> = filterDelegate.projectAssignees
    val projectFrequentTags: StateFlow<List<String>> = filterDelegate.projectFrequentTags
    val projectAreas: StateFlow<List<String>> = filterDelegate.projectAreas
    val projectIterations: StateFlow<List<String>> = filterDelegate.projectIterations
    val hasMoreItems: StateFlow<Boolean> = filterDelegate.hasMoreItems
    val filteredWorkItems: StateFlow<List<WorkItem>> = filterDelegate.filteredWorkItems
    val totalWorkItemsCount: StateFlow<Int> = filterDelegate.totalWorkItemsCount

    // Stream & Sync StateFlows
    val isLoading: StateFlow<Boolean> = streamDelegate.isLoading
    val isLoadingMore: StateFlow<Boolean> = streamDelegate.isLoadingMore
    val isSearchingServer: StateFlow<Boolean> = streamDelegate.isSearchingServer
    val isBackgroundSyncing: StateFlow<Boolean> = streamDelegate.isBackgroundSyncing
    val syncProgress: StateFlow<Float?> = streamDelegate.syncProgress
    val isPullRefreshing: StateFlow<Boolean> = streamDelegate.isPullRefreshing
    val syncStatusText: StateFlow<String?> = streamDelegate.syncStatusText

    // Hierarchy & Discussion StateFlows
    val selectedWorkItem: StateFlow<WorkItem?> = hierarchyDelegate.selectedWorkItem
    val selectedWorkItemParent: StateFlow<WorkItem?> = hierarchyDelegate.selectedWorkItemParent
    val selectedWorkItemComments: StateFlow<List<WorkItemComment>> = hierarchyDelegate.selectedWorkItemComments
    val isLoadingComments: StateFlow<Boolean> = hierarchyDelegate.isLoadingComments
    val selectedWorkItemChildren: StateFlow<List<WorkItem>> = hierarchyDelegate.selectedWorkItemChildren

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
        selectedWorkItemParent,
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
            selectedWorkItemParent = values[29] as WorkItem?,
            childWorkItems = values[30] as List<WorkItem>,
            selectedWorkItemComments = values[31] as List<WorkItemComment>,
            isLoadingComments = values[32] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkItemsUiState())

    init {
        registerSessionCallbacks()
        streamDelegate.initializeSavedAccountsAndAutoConnect()
        filterDelegate.observeProjectAndTypeSelection()
        streamDelegate.observeOnDemandServerSearch()
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
            filterDelegate.userManuallySelectedProject = false
            filterDelegate.userManuallySelectedType = false
            if (isDifferentProject) {
                filterDelegate.setAreaFilter(null)
                filterDelegate.setIterationFilter(null)
            }
            filterDelegate.setProject(trimmedProj)
            if (!trimmedProj.isNullOrBlank()) {
                streamDelegate.ensureProjectProcessLoaded(trimmedProj)
            }
        }
        sessionManager.onSelectWorkItemRequested = { item ->
            selectWorkItem(item)
        }
        sessionManager.onMergeHydratedWorkItemsRequested = { items ->
            streamDelegate.mergeHydratedWorkItemsSuspend(items)
        }
        sessionManager.currentWorkItemsProvider = {
            _workItemsOrg.value to _allWorkItems.value
        }
    }

    fun connect(urlOrOrg: String, pat: String, rememberCredentials: Boolean = true) =
        streamDelegate.connect(urlOrOrg, pat, rememberCredentials)

    fun switchOrganization(organizationName: String) =
        streamDelegate.switchOrganization(organizationName)

    fun saveOrganizationFromSettings(
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) = streamDelegate.saveOrganizationFromSettings(previousOrgName, orgInput, patInput, isRemembered, onResult)

    fun refreshWorkItems() = streamDelegate.refreshWorkItems()

    fun loadMoreWorkItems() = streamDelegate.loadMoreWorkItems()

    fun disconnect() = streamDelegate.disconnect()

    fun setSearchQuery(query: String) = filterDelegate.setSearchQuery(query)

    fun setProject(project: String?) {
        val trimmed = project?.trim()
        if (trimmed.isNullOrBlank()) return
        filterDelegate.userManuallySelectedProject = true
        filterDelegate.userManuallySelectedType = false
        filterDelegate.setProject(trimmed)
        streamDelegate.ensureProjectProcessLoaded(trimmed)
    }

    fun setTypeFilter(type: String?) {
        if (type.isNullOrBlank()) return
        filterDelegate.userManuallySelectedType = true
        filterDelegate.setTypeFilter(type)
        filterDelegate.proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, _allWorkItems.value.size)
    }

    fun setStateFilter(state: String?) {
        if (state.isNullOrBlank()) return
        val trimmedState = state.trim()
        filterDelegate.setStateFilter(trimmedState)
        streamDelegate.onStateFilterSelected(trimmedState)
    }

    fun setAssigneeFilter(assignee: String?) = filterDelegate.setAssigneeFilter(assignee)

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

    fun getAreasForProject(project: String?): List<String> = filterDelegate.getAreasForProject(project)

    fun getIterationsForProject(project: String?): List<String> = filterDelegate.getIterationsForProject(project)

    fun selectWorkItem(item: WorkItem?) = hierarchyDelegate.selectWorkItem(item)

    fun openWorkItemInDetailHierarchy(item: WorkItem) = hierarchyDelegate.openWorkItemInDetailHierarchy(item)

    fun navigateBackInDetail() = hierarchyDelegate.navigateBackInDetail()

    fun dismissDetail() = hierarchyDelegate.dismissDetail()

    fun addCommentToWorkItem(
        workItem: WorkItem,
        commentText: String,
        onResult: (Result<WorkItemComment>) -> Unit = {}
    ) = hierarchyDelegate.addCommentToWorkItem(workItem, commentText, onResult)

    fun updateWorkItem(updatedItem: WorkItem) = crudDelegate.updateWorkItem(updatedItem)

    fun getStatesForWorkItem(item: WorkItem): List<String> = hierarchyDelegate.getStatesForWorkItem(item)

    fun getStatesForType(project: String?, workItemType: String, currentState: String? = null): List<String> =
        hierarchyDelegate.getStatesForType(project, workItemType, currentState)

    fun getAllowedChildTypesForWorkItem(item: WorkItem): List<String> =
        hierarchyDelegate.getAllowedChildTypesForWorkItem(item)

    fun getAllowedChildTypesForType(project: String?, workItemType: String): List<String> =
        hierarchyDelegate.getAllowedChildTypesForType(project, workItemType)

    fun getAllowedParentTypesForWorkItem(item: WorkItem): List<String> =
        hierarchyDelegate.getAllowedParentTypesForWorkItem(item)

    fun getAllowedParentTypesForType(project: String?, workItemType: String): List<String> =
        hierarchyDelegate.getAllowedParentTypesForType(project, workItemType)

    fun canWorkItemHaveParent(item: WorkItem): Boolean =
        hierarchyDelegate.canWorkItemHaveParent(item)

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
    ) = crudDelegate.createWorkItem(
        project = project,
        workItemType = workItemType,
        title = title,
        state = state,
        assignedToName = assignedToName,
        description = description,
        tags = tags,
        areaPath = areaPath,
        iterationPath = iterationPath,
        resolveDefaultState = { proj, type -> getStatesForType(proj, type).firstOrNull() ?: "New" },
        onResult = onResult
    )

    fun addChildWorkItem(parent: WorkItem, title: String, childType: String, initialState: String) =
        hierarchyDelegate.addChildWorkItem(parent, title, childType, initialState)

    fun addExistingChildWorkItem(parent: WorkItem, childId: Int, onResult: (Result<WorkItem>) -> Unit = {}) =
        hierarchyDelegate.addExistingChildWorkItem(parent, childId, onResult)

    fun removeChildWorkItem(parent: WorkItem, childId: Int) =
        hierarchyDelegate.removeChildWorkItem(parent, childId)

    fun createParentWorkItem(child: WorkItem, title: String, parentType: String, initialState: String) =
        hierarchyDelegate.createParentWorkItem(child, title, parentType, initialState)

    fun addExistingParentWorkItem(child: WorkItem, parentId: Int, onResult: (Result<WorkItem>) -> Unit = {}) =
        hierarchyDelegate.linkExistingParentWorkItem(child, parentId, onResult)

    fun unlinkParentWorkItem(child: WorkItem, parentId: Int) =
        hierarchyDelegate.unlinkParentWorkItem(child, parentId)
}
