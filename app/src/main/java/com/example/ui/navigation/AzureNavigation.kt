package com.example.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ConnectionScreen
import com.example.ui.screens.WorkItemsScreen
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.NotificationsViewModel
import com.example.ui.viewmodel.WorkItemsViewModel
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations for the Azure Boards app using Kotlinx Serialization.
 */
sealed interface AzureRoute {
    @Serializable
    data object Connection : AzureRoute

    @Serializable
    data object WorkItems : AzureRoute
}

// Backwards-compatible alias for any legacy references
typealias AzureDestination = AzureRoute

@Composable
fun AzureBoardsNavHost(
    connectionViewModel: ConnectionViewModel,
    workItemsViewModel: WorkItemsViewModel,
    notificationsViewModel: NotificationsViewModel,
    navController: NavHostController = rememberNavController()
) {
    val connectionUiState by connectionViewModel.uiState.collectAsStateWithLifecycle()
    val workItemsUiState by workItemsViewModel.uiState.collectAsStateWithLifecycle()
    val notificationsUiState by notificationsViewModel.uiState.collectAsStateWithLifecycle()

    val isConnected = connectionUiState.isConnected
    val startDestination: Any = if (isConnected) AzureRoute.WorkItems else AzureRoute.Connection

    LaunchedEffect(isConnected) {
        val currentDestination = navController.currentDestination
        if (isConnected) {
            if (currentDestination?.hasRoute<AzureRoute.WorkItems>() != true) {
                navController.navigate(AzureRoute.WorkItems) {
                    popUpTo<AzureRoute.Connection> { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else {
            if (currentDestination?.hasRoute<AzureRoute.Connection>() != true) {
                navController.navigate(AzureRoute.Connection) {
                    popUpTo<AzureRoute.WorkItems> { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() }
    ) {
        composable<AzureRoute.Connection> {
            ConnectionScreen(
                initialOrg = connectionViewModel.getInitialOrg(),
                initialPat = connectionViewModel.getInitialPat(),
                availableAccounts = connectionViewModel.availableAccounts,
                isLoading = workItemsUiState.isLoading || connectionUiState.isLoading,
                errorMessage = connectionUiState.errorMessage,
                onConnect = { org, pat, remember ->
                    connectionViewModel.connect(org, pat, remember)
                },
                onRetry = connectionViewModel::retryConnection,
                onDismissError = connectionViewModel::clearError
            )
        }

        composable<AzureRoute.WorkItems> {
            WorkItemsScreen(
                organization = connectionUiState.activeOrganization.ifBlank { workItemsUiState.organization },
                organizations = connectionUiState.organizations,
                defaultOrganization = connectionUiState.defaultOrganization,
                defaultOrgProjects = connectionUiState.defaultOrgProjects,
                projectsByOrg = connectionUiState.projectsByOrg,
                defaultProjectsByOrg = connectionUiState.defaultProjectsByOrg,
                workItems = workItemsUiState.workItems,
                allWorkItems = workItemsUiState.allWorkItems,
                totalCount = workItemsUiState.totalCount,
                searchQuery = workItemsUiState.searchQuery,
                selectedType = workItemsUiState.selectedType,
                selectedState = workItemsUiState.selectedState,
                selectedProject = workItemsUiState.selectedProject,
                selectedAssignee = workItemsUiState.selectedAssignee,
                selectedArea = workItemsUiState.selectedArea,
                selectedIteration = workItemsUiState.selectedIteration,
                availableTypes = workItemsUiState.availableTypes,
                availableStates = workItemsUiState.availableStates,
                availableProjects = workItemsUiState.availableProjects,
                defaultProject = connectionUiState.defaultProject,
                availableAssignees = workItemsUiState.availableAssignees,
                availableTags = workItemsUiState.availableTags,
                availableAreas = workItemsUiState.availableAreas,
                availableIterations = workItemsUiState.availableIterations,
                getAreasForProject = workItemsViewModel::getAreasForProject,
                getIterationsForProject = workItemsViewModel::getIterationsForProject,
                isLoading = workItemsUiState.isLoading,
                isLoadingMore = workItemsUiState.isLoadingMore,
                isSearchingServer = workItemsUiState.isSearchingServer,
                isBackgroundSyncing = workItemsUiState.isBackgroundSyncing,
                syncProgress = workItemsUiState.syncProgress,
                isPullRefreshing = workItemsUiState.isPullRefreshing,
                syncStatusText = workItemsUiState.syncStatusText,
                hasMoreItems = workItemsUiState.hasMoreItems,
                onLoadMore = workItemsViewModel::loadMoreWorkItems,
                selectedWorkItem = workItemsUiState.selectedWorkItem,
                childWorkItems = workItemsUiState.childWorkItems,
                selectedWorkItemComments = workItemsUiState.selectedWorkItemComments,
                isLoadingComments = workItemsUiState.isLoadingComments,
                assignedNotifications = notificationsUiState.assignedNotifications,
                mentionedNotifications = notificationsUiState.mentionedNotifications,
                unreadNotificationsCount = notificationsUiState.unreadNotificationsCount,
                isSyncingNotifications = notificationsUiState.isSyncingNotifications,
                detectedUserIdentity = notificationsUiState.detectedUserIdentity,
                manualIdentityOverride = notificationsUiState.manualIdentityOverride,
                isIdentityConfirmed = notificationsUiState.isIdentityConfirmed,
                onConfirmIdentity = notificationsViewModel::confirmUserIdentity,
                onSaveCorrectedIdentity = notificationsViewModel::saveCorrectedIdentity,
                onManualIdentityOverrideChange = notificationsViewModel::setManualIdentityOverride,
                onRefreshNotifications = {
                    notificationsViewModel.syncNotificationsForActiveOrganization(postDeviceNotifications = true)
                },
                onNotificationClick = notificationsViewModel::onNotificationClicked,
                onMarkAllNotificationsRead = notificationsViewModel::markAllNotificationsRead,
                getAllowedChildTypes = workItemsViewModel::getAllowedChildTypesForWorkItem,
                getAllowedChildTypesForType = workItemsViewModel::getAllowedChildTypesForType,
                getStatesForWorkItem = workItemsViewModel::getStatesForWorkItem,
                getStatesForChildType = { proj, type -> workItemsViewModel.getStatesForType(proj, type) },
                onCreateWorkItem = workItemsViewModel::createWorkItem,
                onAddChildWorkItem = workItemsViewModel::addChildWorkItem,
                onAddExistingChildWorkItem = workItemsViewModel::addExistingChildWorkItem,
                onRemoveChildWorkItem = workItemsViewModel::removeChildWorkItem,
                onAddComment = workItemsViewModel::addCommentToWorkItem,
                onSearchChange = workItemsViewModel::setSearchQuery,
                onTypeSelect = workItemsViewModel::setTypeFilter,
                onStateSelect = workItemsViewModel::setStateFilter,
                onProjectSelect = workItemsViewModel::setProject,
                onAssigneeSelect = workItemsViewModel::setAssigneeFilter,
                onAreaSelect = workItemsViewModel::setAreaFilter,
                onIterationSelect = workItemsViewModel::setIterationFilter,
                onOrganizationSelect = connectionViewModel::switchOrganization,
                onDefaultOrganizationSelect = connectionViewModel::setDefaultOrganization,
                onDefaultProjectSelect = connectionViewModel::setDefaultProject,
                onDefaultProjectForOrgSelect = connectionViewModel::setDefaultProjectForOrg,
                onSaveOrganizationAccount = connectionViewModel::saveOrganizationFromSettings,
                onRemoveOrganization = connectionViewModel::removeOrganization,
                onWorkItemClick = workItemsViewModel::selectWorkItem,
                onDismissDetail = { workItemsViewModel.selectWorkItem(null) },
                onRefresh = workItemsViewModel::refreshWorkItems,
                onDisconnect = connectionViewModel::disconnect,
                onUpdateWorkItem = workItemsViewModel::updateWorkItem
            )
        }
    }
}
