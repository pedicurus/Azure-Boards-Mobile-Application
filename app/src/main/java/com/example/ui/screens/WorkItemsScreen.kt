package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppNotificationItem
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.ui.components.WorkItemCard
import com.example.ui.components.WorkItemCreateSheet
import com.example.ui.components.WorkItemDetailSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkItemsScreen(
    organization: String,
    organizations: List<OrganizationAccount> = emptyList(),
    defaultOrganization: String? = null,
    defaultOrgProjects: List<String> = emptyList(),
    projectsByOrg: Map<String, List<String>> = emptyMap(),
    defaultProjectsByOrg: Map<String, String> = emptyMap(),
    workItems: List<WorkItem>,
    allWorkItems: List<WorkItem> = workItems,
    totalCount: Int,
    searchQuery: String,
    selectedType: String?,
    selectedState: String?,
    selectedProject: String?,
    selectedAssignee: String? = null,
    selectedArea: String? = null,
    selectedIteration: String? = null,
    availableTypes: List<String>,
    availableStates: List<String>,
    availableProjects: List<String>,
    defaultProject: String? = null,
    availableAssignees: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
    availableAreas: List<String> = emptyList(),
    availableIterations: List<String> = emptyList(),
    getAreasForProject: (String?) -> List<String> = { availableAreas },
    getIterationsForProject: (String?) -> List<String> = { availableIterations },
    isLoading: Boolean,
    isLoadingMore: Boolean = false,
    isSearchingServer: Boolean = false,
    isBackgroundSyncing: Boolean = false,
    syncProgress: Float? = null,
    isPullRefreshing: Boolean = false,
    syncStatusText: String? = null,
    hasMoreItems: Boolean = false,
    onLoadMore: () -> Unit = {},
    selectedWorkItem: WorkItem?,
    parentWorkItem: WorkItem? = null,
    childWorkItems: List<WorkItem> = emptyList(),
    selectedWorkItemComments: List<WorkItemComment> = emptyList(),
    isLoadingComments: Boolean = false,
    getAllowedChildTypes: (WorkItem) -> List<String> = { emptyList() },
    getAllowedChildTypesForType: (String?, String) -> List<String> = { _, _ -> emptyList() },
    getAllowedParentTypes: (WorkItem) -> List<String> = { emptyList() },
    getAllowedParentTypesForType: (String?, String) -> List<String> = { _, _ -> emptyList() },
    getStatesForWorkItem: (WorkItem) -> List<String> = { availableStates },
    getStatesForChildType: (String?, String) -> List<String> = { _, _ -> emptyList() },
    onCreateWorkItem: (
        project: String?,
        workItemType: String,
        title: String,
        state: String,
        assignedToName: String?,
        description: String?,
        tags: List<String>,
        areaPath: String?,
        iterationPath: String?,
        onResult: (Result<WorkItem>) -> Unit
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onAddChildWorkItem: (WorkItem, String, String, String) -> Unit = { _, _, _, _ -> },
    onAddExistingChildWorkItem: (WorkItem, Int, (Result<WorkItem>) -> Unit) -> Unit = { _, _, _ -> },
    onRemoveChildWorkItem: (WorkItem, Int) -> Unit = { _, _ -> },
    onCreateParentWorkItem: (WorkItem, String, String, String) -> Unit = { _, _, _, _ -> },
    onAddExistingParentWorkItem: (WorkItem, Int, (Result<WorkItem>) -> Unit) -> Unit = { _, _, _ -> },
    onUnlinkParentWorkItem: (WorkItem, Int) -> Unit = { _, _ -> },
    onAddComment: (WorkItem, String, (Result<WorkItemComment>) -> Unit) -> Unit = { _, _, _ -> },
    onSearchChange: (String) -> Unit,
    onTypeSelect: (String?) -> Unit,
    onStateSelect: (String?) -> Unit,
    onProjectSelect: (String?) -> Unit,
    onAssigneeSelect: (String?) -> Unit = {},
    onAreaSelect: (String?) -> Unit = {},
    onIterationSelect: (String?) -> Unit = {},
    onOrganizationSelect: (String) -> Unit = {},
    onDefaultOrganizationSelect: (String) -> Unit = {},
    onDefaultProjectSelect: (String) -> Unit = {},
    onDefaultProjectForOrgSelect: (String, String) -> Unit = { _, _ -> },
    onSaveOrganizationAccount: (
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) -> Unit = { _, _, _, _, onResult -> onResult(true, null) },
    onRemoveOrganization: (String) -> Unit = {},
    onWorkItemClick: (WorkItem) -> Unit,
    onDismissDetail: () -> Unit,
    onOpenRelatedWorkItem: (WorkItem) -> Unit = onWorkItemClick,
    onNavigateBackInDetail: () -> Unit = onDismissDetail,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdateWorkItem: (WorkItem) -> Unit = {},
    assignedNotifications: List<AppNotificationItem> = emptyList(),
    mentionedNotifications: List<AppNotificationItem> = emptyList(),
    unreadNotificationsCount: Int = 0,
    isSyncingNotifications: Boolean = false,
    onRefreshNotifications: () -> Unit = {},
    onNotificationClick: (AppNotificationItem) -> Unit = {},
    onMarkAllNotificationsRead: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCreateSheet by remember { mutableStateOf(false) }
    var showNotificationsPage by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isSearchBarOpen by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    var searchBarBounds by remember { mutableStateOf<Rect?>(null) }

    val latestSearchQuery by rememberUpdatedState(searchQuery)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(enabled = selectedWorkItem != null) {
        onNavigateBackInDetail()
    }
    BackHandler(enabled = showCreateSheet) {
        showCreateSheet = false
    }
    BackHandler(enabled = showNotificationsPage && selectedWorkItem == null) {
        showNotificationsPage = false
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty() && !isSearchBarOpen) {
            isSearchBarOpen = true
        }
    }

    BackHandler(enabled = isSearchBarOpen && selectedWorkItem == null && !showCreateSheet) {
        if (searchQuery.isNotEmpty()) {
            onSearchChange("")
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
            isSearchBarOpen = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isSearchBarOpen) {
                if (!isSearchBarOpen) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    val bounds = searchBarBounds
                    val touchedInsideSearchBar = bounds != null && bounds.contains(down.position)
                    if (!touchedInsideSearchBar) {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        if (latestSearchQuery.isBlank()) {
                            isSearchBarOpen = false
                        }
                    }
                }
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(HomeBgTop, HomeBgBottom)
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                HomeOrganizationHeader(
                    organization = organization,
                    organizations = organizations,
                    defaultOrganization = defaultOrganization,
                    onOrganizationSelected = onOrganizationSelect,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isSearchBarOpen) {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                            showNotificationsPage = true
                            onRefreshNotifications()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("notifications_bell_button")
                    ) {
                        Icon(
                            imageVector = if (unreadNotificationsCount > 0) {
                                Icons.Default.Notifications
                            } else {
                                Icons.Default.NotificationsNone
                            },
                            contentDescription = "Notifications",
                            tint = if (unreadNotificationsCount > 0) AccentPurple else SettingsIconColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = SettingsIconColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (isSearchBarOpen) {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                    showCreateSheet = true
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPurple,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp)
                    .testTag("create_new_items_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Create New Items",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            HomeFiltersAndCapsulesSection(
                selectedProject = selectedProject,
                availableProjects = availableProjects,
                selectedAssignee = selectedAssignee,
                availableAssignees = availableAssignees,
                selectedArea = selectedArea,
                availableAreas = availableAreas,
                selectedIteration = selectedIteration,
                availableIterations = availableIterations,
                selectedType = selectedType,
                availableTypes = availableTypes,
                selectedState = selectedState,
                availableStates = availableStates,
                onProjectSelect = onProjectSelect,
                onAssigneeSelect = onAssigneeSelect,
                onAreaSelect = onAreaSelect,
                onIterationSelect = onIterationSelect,
                onTypeSelect = onTypeSelect,
                onStateSelect = onStateSelect
            )

            HomeInlineSearchBarSection(
                isSearchBarOpen = isSearchBarOpen,
                searchQuery = searchQuery,
                selectedProject = selectedProject,
                isSearchingServer = isSearchingServer,
                matchingItemsCount = workItems.size,
                searchFocusRequester = searchFocusRequester,
                onSearchChange = onSearchChange,
                onCloseSearchBar = { isSearchBarOpen = false },
                onBoundsChanged = { searchBarBounds = it }
            )

            val listState = rememberLazyListState()
            val shouldLoadMore by remember {
                derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val totalItemsNumber = layoutInfo.totalItemsCount
                    val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                    totalItemsNumber > 0 && lastVisibleItemIndex >= (totalItemsNumber - 3)
                }
            }

            LaunchedEffect(shouldLoadMore, hasMoreItems, isLoadingMore, searchQuery) {
                if (shouldLoadMore && hasMoreItems && !isLoadingMore && searchQuery.isBlank()) {
                    onLoadMore()
                }
            }

            val pullRefreshState = rememberPullToRefreshState()

            PullToRefreshBox(
                isRefreshing = isPullRefreshing,
                onRefresh = onRefresh,
                state = pullRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = isPullRefreshing,
                        containerColor = Color.White,
                        color = AccentPurple,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("pull_to_refresh_box")
            ) {
                if (workItems.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(AccentPurple.copy(alpha = 0.1f))
                        ) {
                            if (isSearchingServer || isLoadingMore) {
                                CircularProgressIndicator(
                                    color = AccentPurple,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(26.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (searchQuery.isNotEmpty() || selectedType != null || (selectedState != null && !selectedState.equals("All", ignoreCase = true))) {
                                        Icons.Default.SearchOff
                                    } else {
                                        Icons.Default.CloudDone
                                    },
                                    contentDescription = null,
                                    tint = AccentPurple,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = when {
                                searchQuery.isNotEmpty() && isSearchingServer ->
                                    "Searching Azure Boards for \"$searchQuery\"..."
                                isLoadingMore ->
                                    "Loading work items..."
                                searchQuery.isNotEmpty() ->
                                    "No items matching \"$searchQuery\""
                                else ->
                                    "No work items for current selection"
                            },
                            color = TextDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = when {
                                searchQuery.isNotEmpty() && isSearchingServer ->
                                    "Checking unloaded items on the server..."
                                isLoadingMore ->
                                    "Fetching items from Azure DevOps..."
                                searchQuery.isNotEmpty() ->
                                    "Try adjusting your search query or clear the filter."
                                else ->
                                    "Select another type or project from the options above."
                            },
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                        )

                        if (searchQuery.isNotEmpty() && !isSearchingServer) {
                            OutlinedButton(
                                onClick = { onSearchChange("") },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                            ) {
                                Text("Clear Search", fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 10.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("work_items_list")
                    ) {
                        items(
                            items = workItems,
                            key = { it.id }
                        ) { item ->
                            WorkItemCard(
                                workItem = item,
                                onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        onSearchChange("")
                                    }
                                    if (isSearchBarOpen) {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        isSearchBarOpen = false
                                    }
                                    onWorkItemClick(item)
                                },
                                availableStates = getStatesForWorkItem(item).ifEmpty {
                                    availableStates.filterNot { it.equals("All", ignoreCase = true) }
                                },
                                onUpdateState = { newState ->
                                    onUpdateWorkItem(item.copy(state = newState))
                                }
                            )
                        }

                        if (isLoadingMore) {
                            item(key = "loading_more_footer") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .testTag("loading_more_footer")
                                ) {
                                    CircularProgressIndicator(
                                        color = AccentPurple,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Loading more items...",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isSearchBarOpen,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, bottom = 16.dp)
        ) {
            Surface(
                onClick = { isSearchBarOpen = true },
                color = Color.White,
                shape = CircleShape,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, CardBorderColor),
                modifier = Modifier
                    .size(54.dp)
                    .testTag("floating_search_button")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Open search",
                        tint = TextDark,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showNotificationsPage,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            NotificationsScreen(
                organization = organization,
                effectiveUserIdentity = null,
                assignedNotifications = assignedNotifications,
                mentionedNotifications = mentionedNotifications,
                isSyncingNotifications = isSyncingNotifications,
                onRefreshNotifications = onRefreshNotifications,
                onNotificationClick = { notifItem ->
                    onNotificationClick(notifItem)
                },
                onMarkAllRead = onMarkAllNotificationsRead,
                onClose = { showNotificationsPage = false }
            )
        }

        if (showCreateSheet) {
            WorkItemCreateSheet(
                project = selectedProject ?: availableProjects.firstOrNull(),
                availableTypes = availableTypes,
                initialType = selectedType ?: availableTypes.firstOrNull(),
                availableAssignees = availableAssignees,
                availableTags = availableTags,
                availableAreas = availableAreas,
                availableIterations = availableIterations,
                initialAreaPath = selectedArea,
                initialIterationPath = selectedIteration,
                allWorkItems = allWorkItems,
                getStatesForType = getStatesForChildType,
                getAllowedChildTypesForType = getAllowedChildTypesForType,
                getChildStates = getStatesForWorkItem,
                onCreateWorkItem = onCreateWorkItem,
                onAddChildWorkItem = onAddChildWorkItem,
                onUpdateWorkItem = onUpdateWorkItem,
                onDismiss = { showCreateSheet = false }
            )
        }

        if (selectedWorkItem != null) {
            val detailAllowedChildTypes = remember(selectedWorkItem.id, selectedWorkItem.workItemType, availableTypes) {
                getAllowedChildTypes(selectedWorkItem)
            }
            val detailAllowedParentTypes = remember(selectedWorkItem.id, selectedWorkItem.workItemType, availableTypes) {
                getAllowedParentTypes(selectedWorkItem)
            }
            val detailAreas = remember(selectedWorkItem.teamProject, availableAreas) {
                getAreasForProject(selectedWorkItem.teamProject).ifEmpty { availableAreas }
            }
            val detailIterations = remember(selectedWorkItem.teamProject, availableIterations) {
                getIterationsForProject(selectedWorkItem.teamProject).ifEmpty { availableIterations }
            }
            WorkItemDetailSheet(
                workItem = selectedWorkItem,
                onDismiss = onDismissDetail,
                onBack = onNavigateBackInDetail,
                availableStates = getStatesForWorkItem(selectedWorkItem).ifEmpty {
                    availableStates.filterNot { it.equals("All", ignoreCase = true) }
                },
                availableAssignees = availableAssignees,
                availableTags = availableTags,
                availableAreas = detailAreas,
                availableIterations = detailIterations,
                allWorkItems = allWorkItems,
                parentWorkItem = parentWorkItem,
                childWorkItems = childWorkItems,
                allowedChildTypes = detailAllowedChildTypes,
                allowedParentTypes = detailAllowedParentTypes,
                canHaveParent = detailAllowedParentTypes.isNotEmpty(),
                getChildStates = getStatesForWorkItem,
                getStatesForChildType = { childType ->
                    getStatesForChildType(selectedWorkItem.teamProject, childType)
                },
                getParentStates = getStatesForWorkItem,
                getStatesForParentType = { parentType ->
                    getStatesForChildType(selectedWorkItem.teamProject, parentType)
                },
                onAddChildWorkItem = { title, childType, initialState ->
                    onAddChildWorkItem(selectedWorkItem, title, childType, initialState)
                },
                onAddExistingChildWorkItem = { childId, onResult ->
                    onAddExistingChildWorkItem(selectedWorkItem, childId, onResult)
                },
                onRemoveChildWorkItem = { childId ->
                    onRemoveChildWorkItem(selectedWorkItem, childId)
                },
                onAddParentWorkItem = { title, parentType, initialState ->
                    onCreateParentWorkItem(selectedWorkItem, title, parentType, initialState)
                },
                onAddExistingParentWorkItem = { parentId, onResult ->
                    onAddExistingParentWorkItem(selectedWorkItem, parentId, onResult)
                },
                onUnlinkParentWorkItem = { parentId ->
                    onUnlinkParentWorkItem(selectedWorkItem, parentId)
                },
                onNavigateToParent = { parent ->
                    onOpenRelatedWorkItem(parent)
                },
                onNavigateToChild = { child ->
                    onOpenRelatedWorkItem(child)
                },
                comments = selectedWorkItemComments,
                isLoadingComments = isLoadingComments,
                onAddComment = { commentText, onResult ->
                    onAddComment(selectedWorkItem, commentText, onResult)
                },
                onUpdateWorkItem = onUpdateWorkItem
            )
        }

        if (showSettingsDialog) {
            AzureBoardsSettingsDialog(
                activeOrganization = organization,
                organizations = organizations,
                defaultOrganization = defaultOrganization,
                defaultOrgProjects = defaultOrgProjects.ifEmpty { availableProjects },
                availableProjects = availableProjects,
                projectsByOrg = projectsByOrg,
                defaultProjectsByOrg = defaultProjectsByOrg,
                defaultProject = defaultProject,
                selectedProject = selectedProject,
                totalCount = totalCount,
                onDismiss = { showSettingsDialog = false },
                onSwitchOrganization = { orgName ->
                    onOrganizationSelect(orgName)
                },
                onDefaultOrganizationSelect = onDefaultOrganizationSelect,
                onDefaultProjectSelect = onDefaultProjectSelect,
                onDefaultProjectForOrgSelect = onDefaultProjectForOrgSelect,
                onSaveOrganizationAccount = onSaveOrganizationAccount,
                onRemoveOrganization = onRemoveOrganization,
                onRefresh = {
                    showSettingsDialog = false
                    onRefresh()
                },
                onDisconnect = {
                    showSettingsDialog = false
                    onDisconnect()
                }
            )
        }
    }
}
