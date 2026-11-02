package com.example.di

import com.example.data.model.CachedNotificationsState
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.repository.AzureDevOpsRepository
import com.example.ui.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared reactive session coordinator managed by [AppContainer] that synchronizes
 * organization accounts, connection status, default projects, and cross-feature events
 * between `ConnectionViewModel`, `WorkItemsViewModel`, and `NotificationsViewModel`.
 */
class SessionManager(
    val repository: AzureDevOpsRepository
) {
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _organizations = MutableStateFlow<List<OrganizationAccount>>(emptyList())
    val organizations: StateFlow<List<OrganizationAccount>> = _organizations.asStateFlow()

    private val _defaultOrganization = MutableStateFlow<String?>(null)
    val defaultOrganization: StateFlow<String?> = _defaultOrganization.asStateFlow()

    private val _defaultProject = MutableStateFlow<String?>(null)
    val defaultProject: StateFlow<String?> = _defaultProject.asStateFlow()

    private val _projectsByOrg = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val projectsByOrg: StateFlow<Map<String, List<String>>> = _projectsByOrg.asStateFlow()

    private val _defaultProjectsByOrg = MutableStateFlow<Map<String, String>>(emptyMap())
    val defaultProjectsByOrg: StateFlow<Map<String, String>> = _defaultProjectsByOrg.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _notificationsState = MutableStateFlow(CachedNotificationsState(organization = ""))
    val notificationsState: StateFlow<CachedNotificationsState> = _notificationsState.asStateFlow()

    private val _isSyncingNotifications = MutableStateFlow(false)
    val isSyncingNotifications: StateFlow<Boolean> = _isSyncingNotifications.asStateFlow()

    // Cross-ViewModel coordination callbacks
    var onConnectRequested: ((org: String, pat: String, rememberCredentials: Boolean) -> Unit)? = null
    var onSwitchOrganizationRequested: ((organizationName: String) -> Unit)? = null
    var onSaveOrganizationFromSettingsRequested: ((
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) -> Unit)? = null
    var onDisconnectRequested: (() -> Unit)? = null
    var onDefaultProjectChangedForActiveOrg: ((project: String) -> Unit)? = null
    var onSyncNotificationsRequested: ((postDeviceNotifications: Boolean) -> Unit)? = null
    var onSelectWorkItemRequested: ((item: WorkItem?) -> Unit)? = null
    var onMergeHydratedWorkItemsRequested: (suspend (items: List<WorkItem>) -> Unit)? = null
    var currentWorkItemsProvider: (() -> Pair<String?, List<WorkItem>>)? = null

    fun updateConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun updateOrganizations(list: List<OrganizationAccount>) {
        _organizations.value = list
    }

    fun updateDefaultOrganization(org: String?) {
        _defaultOrganization.value = org
    }

    fun updateDefaultProject(project: String?) {
        _defaultProject.value = project
    }

    fun updateProjectsByOrg(map: Map<String, List<String>>) {
        _projectsByOrg.value = map
    }

    fun putProjectsForOrg(org: String, projects: List<String>) {
        if (projects.isEmpty()) return
        _projectsByOrg.value = _projectsByOrg.value + (org to projects)
    }

    fun updateDefaultProjectsByOrg(map: Map<String, String>) {
        _defaultProjectsByOrg.value = map
    }

    fun updateErrorMessage(message: String?) {
        _errorMessage.value = message
    }

    fun updateNotificationsState(state: CachedNotificationsState) {
        _notificationsState.value = state
    }

    fun updateIsSyncingNotifications(syncing: Boolean) {
        _isSyncingNotifications.value = syncing
    }

    fun getActiveAccount(): OrganizationAccount? {
        val currentConnectedOrg = (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
        val targetOrg = currentConnectedOrg ?: _defaultOrganization.value
        val orgs = _organizations.value
        if (targetOrg != null) {
            orgs.firstOrNull { it.organization.equals(targetOrg, ignoreCase = true) }?.let { return it }
        }
        return orgs.firstOrNull()
    }

    fun getActiveOrganization(): String {
        return (_connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: _defaultOrganization.value
            ?: _organizations.value.firstOrNull()?.organization
            ?: ""
    }

    /**
     * Resolves and persists the default project for [organization] given [candidateProjects].
     */
    fun resolveAndPersistDefaultProjectForOrg(
        organization: String,
        candidateProjects: List<String>
    ): String? {
        val normOrg = repository.extractOrganization(organization)
        if (normOrg.isBlank()) return null

        val distinctProjects = candidateProjects
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val existingDefault = _defaultProjectsByOrg.value.entries
            .firstOrNull { it.key.equals(normOrg, ignoreCase = true) }?.value
            ?: repository.getDefaultProject(normOrg)

        val validExisting = existingDefault?.takeIf { saved ->
            distinctProjects.isEmpty() || distinctProjects.any { it.equals(saved, ignoreCase = true) }
        }?.let { saved ->
            distinctProjects.firstOrNull { it.equals(saved, ignoreCase = true) } ?: saved
        }

        val chosen = validExisting ?: distinctProjects.firstOrNull()
        if (chosen != null) {
            repository.saveDefaultProject(chosen, normOrg)
            val updatedMap = _defaultProjectsByOrg.value.toMutableMap()
            val existingKey = updatedMap.keys.firstOrNull { it.equals(normOrg, ignoreCase = true) }
            if (existingKey != null && existingKey != normOrg) {
                updatedMap.remove(existingKey)
            }
            updatedMap[normOrg] = chosen
            _defaultProjectsByOrg.value = updatedMap

            if (normOrg.equals(_defaultOrganization.value, ignoreCase = true)) {
                _defaultProject.value = chosen
            }
        }
        return chosen
    }
}
