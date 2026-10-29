package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AzureDevOpsViewModel
import com.example.ui.ConnectionStatus
import com.example.ui.screens.ConnectionScreen
import com.example.ui.screens.WorkItemsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeumorphicBg

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    color = NeumorphicBg,
                    modifier = Modifier.fillMaxSize()
                ) {
                    AzureBoardsApp()
                }
            }
        }
    }
}

@Composable
fun AzureBoardsApp(
    viewModel: AzureDevOpsViewModel = viewModel()
) {
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val organizations by viewModel.organizations.collectAsStateWithLifecycle()
    val defaultOrganization by viewModel.defaultOrganization.collectAsStateWithLifecycle()
    val defaultOrgProjects by viewModel.defaultOrgProjects.collectAsStateWithLifecycle()
    val projectsByOrg by viewModel.projectsByOrg.collectAsStateWithLifecycle()
    val defaultProjectsByOrg by viewModel.defaultProjectsByOrg.collectAsStateWithLifecycle()
    val filteredWorkItems by viewModel.filteredWorkItems.collectAsStateWithLifecycle()
    val allWorkItems by viewModel.allWorkItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedType by viewModel.typeFilter.collectAsStateWithLifecycle()
    val selectedState by viewModel.stateFilter.collectAsStateWithLifecycle()
    val selectedAssignee by viewModel.assigneeFilter.collectAsStateWithLifecycle()
    val selectedProject by viewModel.selectedProject.collectAsStateWithLifecycle()
    val defaultProject by viewModel.defaultProject.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val availableStates by viewModel.availableStates.collectAsStateWithLifecycle()
    val availableProjects by viewModel.availableProjects.collectAsStateWithLifecycle()
    val projectAssignees by viewModel.projectAssignees.collectAsStateWithLifecycle()
    val projectFrequentTags by viewModel.projectFrequentTags.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val isSearchingServer by viewModel.isSearchingServer.collectAsStateWithLifecycle()
    val isBackgroundSyncing by viewModel.isBackgroundSyncing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val isPullRefreshing by viewModel.isPullRefreshing.collectAsStateWithLifecycle()
    val syncStatusText by viewModel.syncStatusText.collectAsStateWithLifecycle()
    val hasMoreItems by viewModel.hasMoreItems.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val selectedWorkItem by viewModel.selectedWorkItem.collectAsStateWithLifecycle()
    val selectedWorkItemChildren by viewModel.selectedWorkItemChildren.collectAsStateWithLifecycle()

    val isConnected = connectionStatus is ConnectionStatus.Connected || organizations.isNotEmpty()
    val activeOrganization = (connectionStatus as? ConnectionStatus.Connected)?.organization
        ?: defaultOrganization
        ?: organizations.firstOrNull()?.organization
        ?: ""

    Crossfade(
        targetState = isConnected,
        label = "screen_transition"
    ) { connected ->
        if (connected) {
            WorkItemsScreen(
                organization = activeOrganization,
                organizations = organizations,
                defaultOrganization = defaultOrganization,
                defaultOrgProjects = defaultOrgProjects,
                projectsByOrg = projectsByOrg,
                defaultProjectsByOrg = defaultProjectsByOrg,
                workItems = filteredWorkItems,
                allWorkItems = allWorkItems,
                totalCount = allWorkItems.size,
                searchQuery = searchQuery,
                selectedType = selectedType,
                selectedState = selectedState,
                selectedProject = selectedProject,
                selectedAssignee = selectedAssignee,
                availableTypes = availableTypes,
                availableStates = availableStates,
                availableProjects = availableProjects,
                defaultProject = defaultProject,
                availableAssignees = projectAssignees,
                availableTags = projectFrequentTags,
                isLoading = isLoading,
                isLoadingMore = isLoadingMore,
                isSearchingServer = isSearchingServer,
                isBackgroundSyncing = isBackgroundSyncing,
                syncProgress = syncProgress,
                isPullRefreshing = isPullRefreshing,
                syncStatusText = syncStatusText,
                hasMoreItems = hasMoreItems,
                onLoadMore = viewModel::loadMoreWorkItems,
                selectedWorkItem = selectedWorkItem,
                childWorkItems = selectedWorkItemChildren,
                getAllowedChildTypes = viewModel::getAllowedChildTypesForWorkItem,
                getAllowedChildTypesForType = viewModel::getAllowedChildTypesForType,
                getStatesForWorkItem = viewModel::getStatesForWorkItem,
                getStatesForChildType = { proj, type -> viewModel.getStatesForType(proj, type) },
                onCreateWorkItem = viewModel::createWorkItem,
                onAddChildWorkItem = viewModel::addChildWorkItem,
                onSearchChange = viewModel::setSearchQuery,
                onTypeSelect = viewModel::setTypeFilter,
                onStateSelect = viewModel::setStateFilter,
                onProjectSelect = viewModel::setProject,
                onAssigneeSelect = viewModel::setAssigneeFilter,
                onOrganizationSelect = viewModel::switchOrganization,
                onDefaultOrganizationSelect = viewModel::setDefaultOrganization,
                onDefaultProjectSelect = viewModel::setDefaultProject,
                onDefaultProjectForOrgSelect = viewModel::setDefaultProjectForOrg,
                onSaveOrganizationAccount = viewModel::saveOrganizationFromSettings,
                onRemoveOrganization = viewModel::removeOrganization,
                onWorkItemClick = viewModel::selectWorkItem,
                onDismissDetail = { viewModel.selectWorkItem(null) },
                onRefresh = viewModel::refreshWorkItems,
                onDisconnect = viewModel::disconnect,
                onUpdateWorkItem = viewModel::updateWorkItem
            )
        } else {
            ConnectionScreen(
                initialOrg = viewModel.getInitialOrg(),
                initialPat = viewModel.getInitialPat(),
                isLoading = isLoading || connectionStatus is ConnectionStatus.Connecting,
                errorMessage = errorMessage,
                onConnect = { org, pat, remember -> viewModel.connect(org, pat, remember) },
                onRetry = viewModel::retryConnection,
                onDismissError = viewModel::clearError
            )
        }
    }
}
