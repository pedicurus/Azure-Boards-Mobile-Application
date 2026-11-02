package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.OrganizationAccount
import com.example.di.SessionManager
import com.example.ui.ConnectionStatus
import com.example.ui.state.ConnectionUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feature ViewModel responsible for Azure DevOps organization authentication, PAT management,
 * account switching, default organization/project selection, and connection lifecycle.
 */
class ConnectionViewModel(
    application: Application,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    private val repository = sessionManager.repository

    val connectionStatus: StateFlow<ConnectionStatus> = sessionManager.connectionStatus
    val organizations: StateFlow<List<OrganizationAccount>> = sessionManager.organizations
    val defaultOrganization: StateFlow<String?> = sessionManager.defaultOrganization
    val defaultProject: StateFlow<String?> = sessionManager.defaultProject
    val projectsByOrg: StateFlow<Map<String, List<String>>> = sessionManager.projectsByOrg
    val defaultProjectsByOrg: StateFlow<Map<String, String>> = sessionManager.defaultProjectsByOrg
    val errorMessage: StateFlow<String?> = sessionManager.errorMessage

    val defaultOrgProjects: StateFlow<List<String>> = combine(
        defaultOrganization,
        projectsByOrg
    ) { defOrg, map ->
        if (defOrg.isNullOrBlank()) {
            emptyList()
        } else {
            val fromMem = map.entries.firstOrNull { it.key.equals(defOrg, ignoreCase = true) }?.value.orEmpty()
            val fromCache = repository.getCachedProjectsForOrg(defOrg)
            (fromMem + fromCache).distinctBy { it.lowercase() }.sorted()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<ConnectionUiState> = combine(
        connectionStatus,
        organizations,
        defaultOrganization,
        defaultProject,
        defaultOrgProjects,
        projectsByOrg,
        defaultProjectsByOrg,
        errorMessage
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val status = values[0] as ConnectionStatus
        @Suppress("UNCHECKED_CAST")
        val orgs = values[1] as List<OrganizationAccount>
        val defOrg = values[2] as String?
        val defProj = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val defOrgProjs = values[4] as List<String>
        @Suppress("UNCHECKED_CAST")
        val projsByOrg = values[5] as Map<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val defProjsByOrg = values[6] as Map<String, String>
        val errMsg = values[7] as String?

        ConnectionUiState(
            connectionStatus = status,
            organizations = orgs,
            defaultOrganization = defOrg,
            defaultProject = defProj,
            defaultOrgProjects = defOrgProjs,
            projectsByOrg = projsByOrg,
            defaultProjectsByOrg = defProjsByOrg,
            initialOrg = getInitialOrg(),
            initialPat = getInitialPat(),
            isLoading = status is ConnectionStatus.Connecting,
            errorMessage = errMsg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionUiState())

    val availableAccounts: List<OrganizationAccount>
        get() = organizations.value.ifEmpty { repository.getSavedOrganizations() }

    fun getInitialOrg(): String {
        val acc = sessionManager.getActiveAccount()
        return acc?.organization ?: repository.getConfiguredUrl()
    }

    fun getInitialPat(): String {
        val acc = sessionManager.getActiveAccount()
        return acc?.pat ?: repository.getConfiguredPat()
    }

    fun connect(
        urlOrOrg: String,
        pat: String,
        rememberCredentials: Boolean = true
    ) {
        sessionManager.onConnectRequested?.invoke(urlOrOrg, pat, rememberCredentials)
    }

    fun retryConnection() {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            connect(org, pat, rememberCredentials = acc?.isRemembered ?: true)
        }
    }

    fun switchOrganization(organizationName: String) {
        sessionManager.onSwitchOrganizationRequested?.invoke(organizationName)
    }

    fun setDefaultOrganization(organizationName: String) {
        val normOrg = repository.extractOrganization(organizationName)
        if (normOrg.isBlank()) return
        val matchedAccount = organizations.value.firstOrNull {
            it.organization.equals(normOrg, ignoreCase = true)
        }
        val targetOrg = matchedAccount?.organization ?: normOrg
        sessionManager.updateDefaultOrganization(targetOrg)
        if (matchedAccount?.isRemembered != false) {
            repository.saveDefaultOrganization(targetOrg)
        }

        val knownProjects = (projectsByOrg.value.entries
            .firstOrNull { it.key.equals(targetOrg, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(targetOrg))
            .distinctBy { it.lowercase() }
            .sorted()

        val resolvedDefProj = sessionManager.resolveAndPersistDefaultProjectForOrg(targetOrg, knownProjects)
        if (resolvedDefProj != null) {
            sessionManager.updateDefaultProject(resolvedDefProj)
        }

        if (matchedAccount != null) {
            viewModelScope.launch {
                val fetched = repository.fetchProjects(matchedAccount.organization, matchedAccount.pat)
                if (fetched.isNotEmpty()) {
                    repository.saveCachedProjectsForOrg(matchedAccount.organization, fetched)
                    sessionManager.putProjectsForOrg(matchedAccount.organization, fetched)
                    val chosen = sessionManager.resolveAndPersistDefaultProjectForOrg(
                        matchedAccount.organization,
                        fetched
                    )
                    if (defaultOrganization.value.equals(matchedAccount.organization, ignoreCase = true) && chosen != null) {
                        sessionManager.updateDefaultProject(chosen)
                    }
                }
            }
        }
    }

    fun setDefaultProjectForOrg(organization: String, project: String) {
        val normOrg = repository.extractOrganization(organization)
        val trimmedProj = project.trim()
        if (normOrg.isBlank() || trimmedProj.isBlank()) return

        repository.saveDefaultProject(trimmedProj, normOrg)
        val updatedMap = defaultProjectsByOrg.value.toMutableMap()
        val existingKey = updatedMap.keys.firstOrNull { it.equals(normOrg, ignoreCase = true) }
        if (existingKey != null && existingKey != normOrg) {
            updatedMap.remove(existingKey)
        }
        updatedMap[normOrg] = trimmedProj
        sessionManager.updateDefaultProjectsByOrg(updatedMap)

        if (normOrg.equals(defaultOrganization.value, ignoreCase = true)) {
            sessionManager.updateDefaultProject(trimmedProj)
        }

        val activeOrg = (connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: defaultOrganization.value
        if (activeOrg != null && activeOrg.equals(normOrg, ignoreCase = true)) {
            sessionManager.onDefaultProjectChangedForActiveOrg?.invoke(trimmedProj)
        }
    }

    fun setDefaultProject(project: String) {
        val targetOrg = (connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: defaultOrganization.value
            ?: organizations.value.firstOrNull()?.organization
            ?: return
        setDefaultProjectForOrg(targetOrg, project)
    }

    fun saveOrganizationFromSettings(
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        sessionManager.onSaveOrganizationFromSettingsRequested?.invoke(
            previousOrgName,
            orgInput,
            patInput,
            isRemembered,
            onResult
        )
    }

    fun removeOrganization(organizationName: String) {
        val normOrg = repository.extractOrganization(organizationName)
        if (normOrg.isBlank()) return

        repository.removeOrganizationAccount(normOrg)
        val remaining = organizations.value.filterNot {
            it.organization.equals(normOrg, ignoreCase = true)
        }
        sessionManager.updateOrganizations(remaining)

        if (remaining.isEmpty()) {
            disconnect()
            return
        }

        if (defaultOrganization.value.equals(normOrg, ignoreCase = true)) {
            setDefaultOrganization(remaining.first().organization)
        }

        val currentConnected = (connectionStatus.value as? ConnectionStatus.Connected)?.organization
        if (currentConnected != null && currentConnected.equals(normOrg, ignoreCase = true)) {
            val nextOrg = defaultOrganization.value ?: remaining.first().organization
            switchOrganization(nextOrg)
        }
    }

    fun disconnect() {
        sessionManager.onDisconnectRequested?.invoke()
    }

    fun clearError() {
        sessionManager.updateErrorMessage(null)
    }
}
