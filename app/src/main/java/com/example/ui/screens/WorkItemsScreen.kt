package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.ui.components.WorkItemCard
import com.example.ui.components.WorkItemCreateSheet
import com.example.ui.components.WorkItemDetailSheet
import com.example.ui.components.isAlmostPersian
import com.example.ui.theme.getAzureStateColor
import com.example.ui.theme.getAzureStateTextColor
import com.example.ui.theme.getAzureTypeColor
import com.example.ui.theme.getAzureTypeTextColor

// Exact color tokens from user provided SVG and PNG specification
private val HomeBgTop = Color(0xFFFFFFFF)
private val HomeBgBottom = Color(0xFFDFE4F1)
private val CardBorderColor = Color(0xFFDCE1EF)
private val TextDark = Color(0xFF0D101C)
private val TextMuted = Color(0xFF6E7591)
private val AccentPurple = Color(0xFF0078D4)
private val SettingsIconColor = Color(0xFF1D1B20)
private val BottomBarIconMuted = Color(0xFF93989D)

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
    availableTypes: List<String>,
    availableStates: List<String>,
    availableProjects: List<String>,
    defaultProject: String? = null,
    availableAssignees: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
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
    childWorkItems: List<WorkItem> = emptyList(),
    getAllowedChildTypes: (WorkItem) -> List<String> = { emptyList() },
    getAllowedChildTypesForType: (String?, String) -> List<String> = { _, _ -> emptyList() },
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
        onResult: (Result<WorkItem>) -> Unit
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onAddChildWorkItem: (WorkItem, String, String, String) -> Unit = { _, _, _, _ -> },
    onSearchChange: (String) -> Unit,
    onTypeSelect: (String?) -> Unit,
    onStateSelect: (String?) -> Unit,
    onProjectSelect: (String?) -> Unit,
    onAssigneeSelect: (String?) -> Unit = {},
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
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdateWorkItem: (WorkItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCreateSheet by remember { mutableStateOf(false) }

    // Handle hardware/gesture back to dismiss detail or create sheet when open
    BackHandler(enabled = selectedWorkItem != null) {
        onDismissDetail()
    }
    BackHandler(enabled = showCreateSheet) {
        showCreateSheet = false
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var isSearchBarOpen by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var searchBarBounds by remember { mutableStateOf<Rect?>(null) }
    val latestSearchQuery by rememberUpdatedState(searchQuery)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Keep search bar visible if a search query is set externally
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty() && !isSearchBarOpen) {
            isSearchBarOpen = true
        }
    }

    // Handle back button when inline search bar is open
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
            // Header: {Organization} on left, Refresh & Settings gear beside each other on right
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

                // Compact circular sync indicator (circle + sync symbol, no numbers) beside Settings button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isBackgroundSyncing || isLoading) {
                        Surface(
                            color = AccentPurple.copy(alpha = 0.08f),
                            shape = CircleShape,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("background_sync_indicator")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (syncProgress != null) {
                                    CircularProgressIndicator(
                                        progress = { syncProgress },
                                        color = AccentPurple,
                                        trackColor = AccentPurple.copy(alpha = 0.2f),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(26.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = AccentPurple,
                                        trackColor = AccentPurple.copy(alpha = 0.16f),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Syncing work items",
                                    tint = AccentPurple,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
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

            // Create New Items Button between Organization name and Project name
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

            Spacer(modifier = Modifier.height(16.dp))

            // Second Section: Interactive {Project} Title on left & Assignee Dropdown on right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                HomeProjectDropdown(
                    selectedProject = selectedProject,
                    availableProjects = availableProjects,
                    onProjectSelected = onProjectSelect,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(12.dp))

                HomeAssigneeDropdown(
                    selectedAssignee = selectedAssignee,
                    availableAssignees = availableAssignees,
                    onAssigneeSelected = onAssigneeSelect
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Capsule 1: Work Item Types (only enabled types, no "All" option)
            if (availableTypes.isNotEmpty()) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, CardBorderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(42.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 3.dp, vertical = 3.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        availableTypes.forEach { type ->
                            val isSelected = selectedType == type
                            val chipColor = getAzureTypeColor(type)
                            val chipTextColor = getAzureTypeTextColor(type)

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) chipColor else Color.Transparent)
                                    .clickable {
                                        onTypeSelect(type)
                                    }
                                    .padding(horizontal = 18.dp, vertical = 7.dp)
                                    .testTag("type_filter_${type}")
                            ) {
                                Text(
                                    text = type,
                                    color = if (isSelected) chipTextColor else TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Capsule 2: States for selected Type (includes "All" + active states)
            if (availableStates.isNotEmpty()) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, CardBorderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(42.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 3.dp, vertical = 3.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        availableStates.forEach { state ->
                            val isSelected = if (state.equals("All", ignoreCase = true)) {
                                selectedState == null || selectedState.equals("All", ignoreCase = true)
                            } else {
                                selectedState.equals(state, ignoreCase = true)
                            }
                            val stateColor = getAzureStateColor(state)
                            val stateTextColor = getAzureStateTextColor(state)

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) stateColor else Color.Transparent)
                                    .clickable {
                                        onStateSelect(state)
                                    }
                                    .padding(horizontal = 18.dp, vertical = 7.dp)
                                    .testTag("state_filter_${state}")
                            ) {
                                Text(
                                    text = state,
                                    color = if (isSelected) stateTextColor else TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Collapsible Inline Search Bar directly below the States bar
            AnimatedVisibility(
                visible = isSearchBarOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LaunchedEffect(Unit) {
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                }

                val isQueryRtl = isAlmostPersian(searchQuery)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            width = if (isSearchFocused || searchQuery.isNotEmpty()) 1.5.dp else 1.dp,
                            color = if (isSearchFocused || searchQuery.isNotEmpty()) AccentPurple else CardBorderColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .onGloballyPositioned { coords ->
                                searchBarBounds = coords.boundsInRoot()
                            }
                            .testTag("inline_search_bar")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 14.dp, end = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = if (isSearchFocused || searchQuery.isNotEmpty()) AccentPurple else BottomBarIconMuted,
                                modifier = Modifier.size(20.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search work items...",
                                        color = BottomBarIconMuted,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                CompositionLocalProvider(
                                    LocalLayoutDirection provides if (isQueryRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                                ) {
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = onSearchChange,
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = TextDark,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                                            textDirection = if (isQueryRtl) TextDirection.Rtl else TextDirection.Ltr
                                        ),
                                        cursorBrush = SolidColor(AccentPurple),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                                if (searchQuery.isBlank()) {
                                                    isSearchBarOpen = false
                                                }
                                            },
                                            onDone = {
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                                if (searchQuery.isBlank()) {
                                                    isSearchBarOpen = false
                                                }
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(searchFocusRequester)
                                            .onFocusChanged { isSearchFocused = it.isFocused }
                                            .testTag("search_input")
                                    )
                                }
                            }

                            if (isSearchingServer) {
                                CircularProgressIndicator(
                                    color = AccentPurple,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .testTag("server_search_loading_indicator")
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }

                            // Single X button: clears text if non-empty, or removes the search bar if empty
                            IconButton(
                                onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        onSearchChange("")
                                        searchFocusRequester.requestFocus()
                                    } else {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        isSearchBarOpen = false
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("clear_or_close_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = if (searchQuery.isNotEmpty()) "Clear search text" else "Close search bar",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (searchQuery.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "Searching all types, states & assignees in ${selectedProject ?: "all projects"}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSearchingServer) "Searching Azure..." else "${workItems.size} found",
                                color = AccentPurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Work Items List with progressive auto-load on scroll
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
                    // Empty State (scrollable so pull-to-refresh works even when 0 items match)
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
                                    imageVector = if (searchQuery.isNotEmpty() || selectedType != null || (selectedState != null && !selectedState.equals("All", ignoreCase = true)))
                                        Icons.Default.SearchOff
                                    else
                                        Icons.Default.CloudDone,
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

        // Bottom-Left Circular Search Button — hidden while the inline search bar is open
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

        // Detail Bottom Sheet when user clicks a Work Item Card
        if (selectedWorkItem != null) {
            WorkItemDetailSheet(
                workItem = selectedWorkItem,
                onDismiss = onDismissDetail,
                availableStates = getStatesForWorkItem(selectedWorkItem).ifEmpty {
                    availableStates.filterNot { it.equals("All", ignoreCase = true) }
                },
                availableAssignees = availableAssignees,
                availableTags = availableTags,
                childWorkItems = childWorkItems,
                allowedChildTypes = getAllowedChildTypes(selectedWorkItem),
                getChildStates = getStatesForWorkItem,
                getStatesForChildType = { childType ->
                    getStatesForChildType(selectedWorkItem.teamProject, childType)
                },
                onAddChildWorkItem = { title, childType, initialState ->
                    onAddChildWorkItem(selectedWorkItem, title, childType, initialState)
                },
                onUpdateWorkItem = onUpdateWorkItem
            )
        }

        // Create New Item Bottom Sheet when user taps "Create New Items"
        if (showCreateSheet) {
            WorkItemCreateSheet(
                project = selectedProject ?: availableProjects.firstOrNull(),
                availableTypes = availableTypes,
                initialType = selectedType ?: availableTypes.firstOrNull(),
                availableAssignees = availableAssignees,
                availableTags = availableTags,
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

        // Settings / Account Options Dialog
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

@Composable
private fun HomeOrganizationHeader(
    organization: String,
    organizations: List<OrganizationAccount>,
    defaultOrganization: String?,
    onOrganizationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultipleOrgs = organizations.size > 1

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (hasMultipleOrgs) {
                        Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    } else {
                        Modifier.padding(vertical = 4.dp)
                    }
                )
                .testTag("organization_header_button")
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_azure_devops),
                contentDescription = "Azure DevOps",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(26.dp)
                    .testTag("organization_azure_icon")
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = organization.ifBlank { "Organization" },
                color = TextDark,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("organization_title")
            )
            if (hasMultipleOrgs) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.dp, CardBorderColor),
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("organization_dropdown_icon")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Switch organization",
                            tint = TextDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        LaunchedEffect(hasMultipleOrgs) {
            if (!hasMultipleOrgs && expanded) {
                expanded = false
            }
        }

        DropdownMenu(
            expanded = expanded && hasMultipleOrgs,
            onDismissRequest = { expanded = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorderColor),
            modifier = Modifier.widthIn(min = 200.dp)
        ) {
            organizations.forEach { acc ->
                val isCurrent = acc.organization.equals(organization, ignoreCase = true)
                val isDefault = acc.organization.equals(defaultOrganization, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = acc.organization,
                                    color = if (isCurrent) AccentPurple else TextDark,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isDefault) {
                                    Text(
                                        text = "Default",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            if (isCurrent) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AccentPurple,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onOrganizationSelected(acc.organization)
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeProjectDropdown(
    selectedProject: String?,
    availableProjects: List<String>,
    onProjectSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultipleProjects = availableProjects.size > 1
    val displayedProject = selectedProject?.takeIf { sel ->
        availableProjects.isEmpty() || availableProjects.any { it.equals(sel, ignoreCase = true) }
    } ?: availableProjects.firstOrNull() ?: selectedProject ?: "Project"

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (hasMultipleProjects) {
                        Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    } else {
                        Modifier.padding(vertical = 4.dp)
                    }
                )
                .testTag("home_project_dropdown_button")
        ) {
            Text(
                text = displayedProject,
                color = TextDark,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Only show dropdown icon when there are multiple projects under this organization
            if (hasMultipleProjects) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.dp, CardBorderColor),
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("project_dropdown_icon")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select project",
                            tint = TextDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        LaunchedEffect(hasMultipleProjects) {
            if (!hasMultipleProjects && expanded) {
                expanded = false
            }
        }

        DropdownMenu(
            expanded = expanded && hasMultipleProjects,
            onDismissRequest = { expanded = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorderColor),
            modifier = Modifier.widthIn(min = 160.dp)
        ) {
            availableProjects.forEach { project ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = project,
                                color = if (selectedProject == project) AccentPurple else TextDark,
                                fontWeight = if (selectedProject == project) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selectedProject == project) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AccentPurple,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onProjectSelected(project)
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeAssigneeDropdown(
    selectedAssignee: String?,
    availableAssignees: List<String>,
    onAssigneeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val assigneeOptions = remember(availableAssignees) {
        val realAssignees = availableAssignees
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) && !it.equals("Unassigned", ignoreCase = true) }
            .distinct()
        listOf("All") + realAssignees + "Unassigned"
    }

    val currentLabel = selectedAssignee?.takeIf { it.isNotBlank() } ?: "All"

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(10.dp),
            color = Color.White,
            border = BorderStroke(1.dp, CardBorderColor),
            modifier = Modifier
                .height(34.dp)
                .widthIn(min = 96.dp, max = 180.dp)
                .testTag("home_assignee_dropdown_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonOutline,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentLabel,
                    color = TextDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select assignee",
                    tint = Color(0xFF292D32),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorderColor),
            modifier = Modifier.widthIn(min = 170.dp)
        ) {
            assigneeOptions.forEach { option ->
                val isSelected = if (option == "All") {
                    selectedAssignee.isNullOrBlank() || selectedAssignee.equals("All", ignoreCase = true)
                } else {
                    selectedAssignee?.equals(option, ignoreCase = true) == true
                }
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = option,
                                color = if (isSelected) AccentPurple else TextDark,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AccentPurple,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onAssigneeSelected(if (option == "All") null else option)
                    }
                )
            }
        }
    }
}
