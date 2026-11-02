package com.example.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ConnectionScreen
import com.example.ui.screens.WorkItemsScreen
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.NotificationsViewModel
import com.example.ui.viewmodel.WorkItemsViewModel

/**
 * Typed navigation destinations for the Azure Boards app.
 */
sealed class AzureDestination(val route: String) {
    data object Connection : AzureDestination("connection")
    data object WorkItems : AzureDestination("work_items")
}

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
    val startDestination = if (isConnected) {
        AzureDestination.WorkItems.route
    } else {
        AzureDestination.Connection.route
    }

    LaunchedEffect(isConnected) {
        val targetRoute = if (isConnected) {
            AzureDestination.WorkItems.route
        } else {
            AzureDestination.Connection.route
        }
        val currentRoute = navController.currentDestination?.route
        if (currentRoute != null && currentRoute != targetRoute) {
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
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
        composable(route = AzureDestination.Connection.route) {
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

        composable(route = AzureDestination.WorkItems.route) {
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
