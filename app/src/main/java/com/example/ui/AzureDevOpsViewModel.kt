package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.data.model.AppNotificationItem
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.di.AppContainer
import com.example.ui.state.ConnectionUiState
import com.example.ui.state.NotificationsUiState
import com.example.ui.state.WorkItemsUiState
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.NotificationsViewModel
import com.example.ui.viewmodel.WorkItemsViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight delegating facade over [ConnectionViewModel], [WorkItemsViewModel], and
 * [NotificationsViewModel] backed by [AppContainer].
 */
class AzureDevOpsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.getInstance(application)

    val connectionViewModel = ConnectionViewModel(application, container.sessionManager)
    val workItemsViewModel = WorkItemsViewModel(application, container.sessionManager)
    val notificationsViewModel = NotificationsViewModel(application, container.sessionManager)

    val connectionUiState: StateFlow<ConnectionUiState> = connectionViewModel.uiState
    val workItemsUiState: StateFlow<WorkItemsUiState> = workItemsViewModel.uiState
    val notificationsUiState: StateFlow<NotificationsUiState> = notificationsViewModel.uiState

    val connectionStatus: StateFlow<ConnectionStatus> = connectionViewModel.connectionStatus
    val organizations: StateFlow<List<OrganizationAccount>> = connectionViewModel.organizations
    val defaultOrganization: StateFlow<String?> = connectionViewModel.defaultOrganization
    val defaultOrgProjects: StateFlow<List<String>> = connectionViewModel.defaultOrgProjects
    val projectsByOrg: StateFlow<Map<String, List<String>>> = connectionViewModel.projectsByOrg
    val defaultProjectsByOrg: StateFlow<Map<String, String>> = connectionViewModel.defaultProjectsByOrg
    val defaultProject: StateFlow<String?> = connectionViewModel.defaultProject
    val errorMessage: StateFlow<String?> = connectionViewModel.errorMessage
    val availableAccounts: List<OrganizationAccount> get() = connectionViewModel.availableAccounts

    val filteredWorkItems: StateFlow<List<WorkItem>> = workItemsViewModel.filteredWorkItems
    val allWorkItems: StateFlow<List<WorkItem>> = workItemsViewModel.allWorkItems
    val totalWorkItemsCount: StateFlow<Int> = workItemsViewModel.totalWorkItemsCount
    val searchQuery: StateFlow<String> = workItemsViewModel.searchQuery
    val typeFilter: StateFlow<String?> = workItemsViewModel.typeFilter
    val stateFilter: StateFlow<String?> = workItemsViewModel.stateFilter
    val assigneeFilter: StateFlow<String?> = workItemsViewModel.assigneeFilter
    val areaFilter: StateFlow<String?> = workItemsViewModel.areaFilter
    val iterationFilter: StateFlow<String?> = workItemsViewModel.iterationFilter
    val selectedProject: StateFlow<String?> = workItemsViewModel.selectedProject
    val availableTypes: StateFlow<List<String>> = workItemsViewModel.availableTypes
    val availableStates: StateFlow<List<String>> = workItemsViewModel.availableStates
    val availableProjects: StateFlow<List<String>> = workItemsViewModel.availableProjects
    val projectAssignees: StateFlow<List<String>> = workItemsViewModel.projectAssignees
    val projectFrequentTags: StateFlow<List<String>> = workItemsViewModel.projectFrequentTags
    val projectAreas: StateFlow<List<String>> = workItemsViewModel.projectAreas
    val projectIterations: StateFlow<List<String>> = workItemsViewModel.projectIterations
    val isLoading: StateFlow<Boolean> = workItemsViewModel.isLoading
    val isLoadingMore: StateFlow<Boolean> = workItemsViewModel.isLoadingMore
    val isSearchingServer: StateFlow<Boolean> = workItemsViewModel.isSearchingServer
    val isBackgroundSyncing: StateFlow<Boolean> = workItemsViewModel.isBackgroundSyncing
    val syncProgress: StateFlow<Float?> = workItemsViewModel.syncProgress
    val isPullRefreshing: StateFlow<Boolean> = workItemsViewModel.isPullRefreshing
    val syncStatusText: StateFlow<String?> = workItemsViewModel.syncStatusText
    val hasMoreItems: StateFlow<Boolean> = workItemsViewModel.hasMoreItems
    val selectedWorkItem: StateFlow<WorkItem?> = workItemsViewModel.selectedWorkItem
    val selectedWorkItemChildren: StateFlow<List<WorkItem>> = workItemsViewModel.selectedWorkItemChildren
    val selectedWorkItemComments: StateFlow<List<WorkItemComment>> = workItemsViewModel.selectedWorkItemComments
    val isLoadingComments: StateFlow<Boolean> = workItemsViewModel.isLoadingComments

    val assignedNotifications: StateFlow<List<AppNotificationItem>> = notificationsViewModel.assignedNotifications
    val mentionedNotifications: StateFlow<List<AppNotificationItem>> = notificationsViewModel.mentionedNotifications
    val unreadNotificationsCount: StateFlow<Int> = notificationsViewModel.unreadNotificationsCount
    val isSyncingNotifications: StateFlow<Boolean> = notificationsViewModel.isSyncingNotifications
    val detectedUserIdentity: StateFlow<String?> = notificationsViewModel.detectedUserIdentity
    val manualIdentityOverride: StateFlow<String?> = notificationsViewModel.manualIdentityOverride
    val isIdentityConfirmed: StateFlow<Boolean> = notificationsViewModel.isIdentityConfirmed

    fun getInitialOrg(): String = connectionViewModel.getInitialOrg()
    fun getInitialPat(): String = connectionViewModel.getInitialPat()
    fun connect(urlOrOrg: String, pat: String, rememberCredentials: Boolean = true) =
        connectionViewModel.connect(urlOrOrg, pat, rememberCredentials)
    fun retryConnection() = connectionViewModel.retryConnection()
    fun switchOrganization(organizationName: String) = connectionViewModel.switchOrganization(organizationName)
    fun setDefaultOrganization(organizationName: String) = connectionViewModel.setDefaultOrganization(organizationName)
    fun setDefaultProject(project: String) = connectionViewModel.setDefaultProject(project)
    fun setDefaultProjectForOrg(organization: String, project: String) =
        connectionViewModel.setDefaultProjectForOrg(organization, project)
    fun saveOrganizationFromSettings(
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) = connectionViewModel.saveOrganizationFromSettings(previousOrgName, orgInput, patInput, isRemembered, onResult)
    fun removeOrganization(organizationName: String) = connectionViewModel.removeOrganization(organizationName)
    fun disconnect() = connectionViewModel.disconnect()
    fun clearError() = connectionViewModel.clearError()

    fun refreshWorkItems() = workItemsViewModel.refreshWorkItems()
    fun loadMoreWorkItems() = workItemsViewModel.loadMoreWorkItems()
    fun setSearchQuery(query: String) = workItemsViewModel.setSearchQuery(query)
    fun setProject(project: String?) = workItemsViewModel.setProject(project)
    fun setTypeFilter(type: String?) = workItemsViewModel.setTypeFilter(type)
    fun setStateFilter(state: String?) = workItemsViewModel.setStateFilter(state)
    fun setAssigneeFilter(assignee: String?) = workItemsViewModel.setAssigneeFilter(assignee)
    fun setAreaFilter(areaPath: String?) = workItemsViewModel.setAreaFilter(areaPath)
    fun setIterationFilter(iterationPath: String?) = workItemsViewModel.setIterationFilter(iterationPath)
    fun getAreasForProject(project: String?): List<String> = workItemsViewModel.getAreasForProject(project)
    fun getIterationsForProject(project: String?): List<String> = workItemsViewModel.getIterationsForProject(project)
    fun selectWorkItem(item: WorkItem?) = workItemsViewModel.selectWorkItem(item)
    fun addCommentToWorkItem(workItem: WorkItem, commentText: String, onResult: (Result<WorkItemComment>) -> Unit = {}) =
        workItemsViewModel.addCommentToWorkItem(workItem, commentText, onResult)
    fun updateWorkItem(updatedItem: WorkItem) = workItemsViewModel.updateWorkItem(updatedItem)
    fun getStatesForWorkItem(item: WorkItem): List<String> = workItemsViewModel.getStatesForWorkItem(item)
    fun getStatesForType(project: String?, workItemType: String, currentState: String? = null): List<String> =
        workItemsViewModel.getStatesForType(project, workItemType, currentState)
    fun getAllowedChildTypesForWorkItem(item: WorkItem): List<String> =
        workItemsViewModel.getAllowedChildTypesForWorkItem(item)
    fun getAllowedChildTypesForType(project: String?, workItemType: String): List<String> =
        workItemsViewModel.getAllowedChildTypesForType(project, workItemType)
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
    ) = workItemsViewModel.createWorkItem(
        project, workItemType, title, state, assignedToName, description, tags, areaPath, iterationPath, onResult
    )
    fun addChildWorkItem(parent: WorkItem, title: String, childType: String, initialState: String) =
        workItemsViewModel.addChildWorkItem(parent, title, childType, initialState)
    fun addExistingChildWorkItem(parent: WorkItem, childId: Int, onResult: (Result<WorkItem>) -> Unit = {}) =
        workItemsViewModel.addExistingChildWorkItem(parent, childId, onResult)
    fun removeChildWorkItem(parent: WorkItem, childId: Int) =
        workItemsViewModel.removeChildWorkItem(parent, childId)

    fun syncNotificationsForActiveOrganization(postDeviceNotifications: Boolean = true) =
        notificationsViewModel.syncNotificationsForActiveOrganization(postDeviceNotifications)
    fun setManualIdentityOverride(overrideName: String?) =
        notificationsViewModel.setManualIdentityOverride(overrideName)
    fun confirmUserIdentity(confirmed: Boolean) = notificationsViewModel.confirmUserIdentity(confirmed)
    fun saveCorrectedIdentity(correctedName: String) = notificationsViewModel.saveCorrectedIdentity(correctedName)
    fun markAllNotificationsRead(category: String? = null) = notificationsViewModel.markAllNotificationsRead(category)
    fun onNotificationClicked(notification: AppNotificationItem) =
        notificationsViewModel.onNotificationClicked(notification)
    fun handleNotificationDeepLink(organization: String?, workItemId: Int, notificationId: String?) =
        notificationsViewModel.handleNotificationDeepLink(organization, workItemId, notificationId)
}
