package com.example.ui.state

import com.example.data.model.AppNotificationItem
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.ui.ConnectionStatus

/**
 * Consolidated UI state for organization connection, account management, and default project settings.
 */
data class ConnectionUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val organizations: List<OrganizationAccount> = emptyList(),
    val defaultOrganization: String? = null,
    val defaultProject: String? = null,
    val defaultOrgProjects: List<String> = emptyList(),
    val projectsByOrg: Map<String, List<String>> = emptyMap(),
    val defaultProjectsByOrg: Map<String, String> = emptyMap(),
    val initialOrg: String = "",
    val initialPat: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val isConnected: Boolean
        get() = connectionStatus is ConnectionStatus.Connected || organizations.isNotEmpty()

    val activeOrganization: String
        get() = (connectionStatus as? ConnectionStatus.Connected)?.organization
            ?: defaultOrganization
            ?: organizations.firstOrNull()?.organization
            ?: ""
}

/**
 * Consolidated UI state for the Work Items board, filters, background sync, and selected item details.
 */
data class WorkItemsUiState(
    val organization: String = "",
    val workItems: List<WorkItem> = emptyList(),
    val allWorkItems: List<WorkItem> = emptyList(),
    val totalCount: Int = 0,
    val searchQuery: String = "",
    val selectedType: String? = null,
    val selectedState: String? = null,
    val selectedProject: String? = null,
    val selectedAssignee: String? = null,
    val selectedArea: String? = null,
    val selectedIteration: String? = null,
    val availableTypes: List<String> = emptyList(),
    val availableStates: List<String> = emptyList(),
    val availableProjects: List<String> = emptyList(),
    val availableAssignees: List<String> = listOf("All", "Unassigned"),
    val availableTags: List<String> = emptyList(),
    val availableAreas: List<String> = emptyList(),
    val availableIterations: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSearchingServer: Boolean = false,
    val isBackgroundSyncing: Boolean = false,
    val syncProgress: Float? = null,
    val isPullRefreshing: Boolean = false,
    val syncStatusText: String? = null,
    val hasMoreItems: Boolean = false,
    val selectedWorkItem: WorkItem? = null,
    val childWorkItems: List<WorkItem> = emptyList(),
    val selectedWorkItemComments: List<WorkItemComment> = emptyList(),
    val isLoadingComments: Boolean = false
)

/**
 * Consolidated UI state for Assigned & Mentioned notifications and user identity verification.
 */
data class NotificationsUiState(
    val assignedNotifications: List<AppNotificationItem> = emptyList(),
    val mentionedNotifications: List<AppNotificationItem> = emptyList(),
    val unreadNotificationsCount: Int = 0,
    val isSyncingNotifications: Boolean = false,
    val detectedUserIdentity: String? = null,
    val manualIdentityOverride: String? = null,
    val isIdentityConfirmed: Boolean = false
)
