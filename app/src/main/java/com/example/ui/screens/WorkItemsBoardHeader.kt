package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.example.ui.components.HierarchyTreePickerDialog
import com.example.ui.components.extractHierarchyLeafName
import com.example.ui.components.isAlmostPersian
import com.example.ui.theme.getAzureStateColor
import com.example.ui.theme.getAzureStateTextColor
import com.example.ui.theme.getAzureTypeColor
import com.example.ui.theme.getAzureTypeTextColor

internal val HomeBgTop = Color(0xFFFFFFFF)
internal val HomeBgBottom = Color(0xFFDFE4F1)
internal val CardBorderColor = Color(0xFFDCE1EF)
internal val TextDark = Color(0xFF0D101C)
internal val TextMuted = Color(0xFF6E7591)
internal val AccentPurple = Color(0xFF0078D4)
internal val SettingsIconColor = Color(0xFF1D1B20)
internal val BottomBarIconMuted = Color(0xFF93989D)

@Composable
internal fun HomeFiltersAndCapsulesSection(
    selectedProject: String?,
    availableProjects: List<String>,
    selectedAssignee: String?,
    availableAssignees: List<String>,
    selectedArea: String?,
    availableAreas: List<String>,
    selectedIteration: String?,
    availableIterations: List<String>,
    selectedType: String?,
    availableTypes: List<String>,
    selectedState: String?,
    availableStates: List<String>,
    onProjectSelect: (String?) -> Unit,
    onAssigneeSelect: (String?) -> Unit,
    onAreaSelect: (String?) -> Unit,
    onIterationSelect: (String?) -> Unit,
    onTypeSelect: (String?) -> Unit,
    onStateSelect: (String?) -> Unit
) {
    var isFiltersSectionVisible by remember { mutableStateOf(true) }
    val isAssigneeFilterActive = !selectedAssignee.isNullOrBlank() && !selectedAssignee.equals("All", ignoreCase = true)
    val isAreaFilterActive = !selectedArea.isNullOrBlank() && !selectedArea.equals("All", ignoreCase = true)
    val isIterationFilterActive = !selectedIteration.isNullOrBlank() && !selectedIteration.equals("All", ignoreCase = true)
    val activeSecondaryFilterCount =
        (if (isAssigneeFilterActive) 1 else 0) +
            (if (isAreaFilterActive) 1 else 0) +
            (if (isIterationFilterActive) 1 else 0)

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

        Surface(
            onClick = { isFiltersSectionVisible = !isFiltersSectionVisible },
            shape = RoundedCornerShape(10.dp),
            color = if (activeSecondaryFilterCount > 0) AccentPurple.copy(alpha = 0.1f) else Color.White,
            border = BorderStroke(
                1.dp,
                if (activeSecondaryFilterCount > 0) AccentPurple.copy(alpha = 0.5f) else CardBorderColor
            ),
            modifier = Modifier
                .height(34.dp)
                .testTag("toggle_filters_visibility_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = if (isFiltersSectionVisible) "Hide filters" else "Show filters",
                    tint = if (activeSecondaryFilterCount > 0) AccentPurple else TextMuted,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isFiltersSectionVisible) "Hide Filters" else "Show Filters",
                    color = if (activeSecondaryFilterCount > 0) AccentPurple else TextDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Icon(
                    imageVector = if (isFiltersSectionVisible) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = if (activeSecondaryFilterCount > 0) AccentPurple else TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    AnimatedVisibility(
        visible = isFiltersSectionVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("home_combined_filters_section")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                HomeAssigneeDropdown(
                    selectedAssignee = selectedAssignee,
                    availableAssignees = availableAssignees,
                    onAssigneeSelected = onAssigneeSelect
                )

                HomeHierarchyFilterButton(
                    labelPrefix = "Area",
                    selectedPath = selectedArea,
                    availablePaths = availableAreas,
                    defaultProjectRoot = selectedProject,
                    isIteration = false,
                    onPathSelected = onAreaSelect,
                    testTagPrefix = "home_area"
                )

                HomeHierarchyFilterButton(
                    labelPrefix = "Iteration",
                    selectedPath = selectedIteration,
                    availablePaths = availableIterations,
                    defaultProjectRoot = selectedProject,
                    isIteration = true,
                    onPathSelected = onIterationSelect,
                    testTagPrefix = "home_iteration"
                )

                if (activeSecondaryFilterCount > 0) {
                    Surface(
                        onClick = {
                            onAssigneeSelect(null)
                            onAreaSelect(null)
                            onIterationSelect(null)
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFF1F2),
                        border = BorderStroke(1.dp, Color(0xFFFECDD3)),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("reset_combined_filters_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear filters",
                                tint = Color(0xFFE11D48),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Reset",
                                color = Color(0xFFE11D48),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

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
}

@Composable
internal fun HomeInlineSearchBarSection(
    isSearchBarOpen: Boolean,
    searchQuery: String,
    selectedProject: String?,
    isSearchingServer: Boolean,
    matchingItemsCount: Int,
    searchFocusRequester: FocusRequester,
    onSearchChange: (String) -> Unit,
    onCloseSearchBar: () -> Unit,
    onBoundsChanged: (Rect) -> Unit
) {
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    AnimatedVisibility(
        visible = isSearchBarOpen,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        LaunchedEffect(Unit) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }

        val isQueryRtl = remember(searchQuery) { isAlmostPersian(searchQuery) }

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
                        onBoundsChanged(coords.boundsInRoot())
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
                                            onCloseSearchBar()
                                        }
                                    },
                                    onDone = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        if (searchQuery.isBlank()) {
                                            onCloseSearchBar()
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

                    IconButton(
                        onClick = {
                            if (searchQuery.isNotEmpty()) {
                                onSearchChange("")
                                searchFocusRequester.requestFocus()
                            } else {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onCloseSearchBar()
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
                        text = if (isSearchingServer) "Searching Azure..." else "$matchingItemsCount found",
                        color = AccentPurple,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeOrganizationHeader(
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
internal fun HomeProjectDropdown(
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
internal fun HomeAssigneeDropdown(
    selectedAssignee: String?,
    availableAssignees: List<String>,
    onAssigneeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val realAssignees = remember(availableAssignees) {
        availableAssignees
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) && !it.equals("Unassigned", ignoreCase = true) }
            .distinct()
    }

    val currentLabel = selectedAssignee?.takeIf { it.isNotBlank() } ?: "All"

    Box(modifier = modifier) {
        Surface(
            onClick = {
                searchQuery = ""
                expanded = true
            },
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

        if (expanded) {
            val trimmedQuery = searchQuery.trim()
            val isQueryRtl = isAlmostPersian(trimmedQuery)
            val matchingAssignees = remember(realAssignees, trimmedQuery) {
                if (trimmedQuery.isEmpty()) {
                    realAssignees
                } else {
                    realAssignees.filter { it.contains(trimmedQuery, ignoreCase = true) }
                }
            }
            val staticOptions = remember(selectedAssignee) {
                val activeCustom = selectedAssignee?.trim()?.takeIf {
                    it.isNotBlank() && !it.equals("All", ignoreCase = true) && !it.equals("Unassigned", ignoreCase = true)
                }
                listOfNotNull("All", "Unassigned", activeCustom).distinct()
            }

            AlertDialog(
                onDismissRequest = { expanded = false },
                title = {
                    Text(
                        text = "Filter by Assignee",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (isQueryRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        text = "Search people by name...",
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                        textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = AccentPurple,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear search",
                                                tint = TextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                } else null,
                                textStyle = TextStyle(
                                    color = TextDark,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                                    textDirection = if (isQueryRtl) TextDirection.Rtl else TextDirection.Ltr
                                ),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark,
                                    cursorColor = AccentPurple,
                                    focusedBorderColor = AccentPurple,
                                    unfocusedBorderColor = CardBorderColor,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("home_assignee_search_input")
                            )
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            staticOptions.forEach { option ->
                                val isSelected = if (option == "All") {
                                    selectedAssignee.isNullOrBlank() || selectedAssignee.equals("All", ignoreCase = true)
                                } else {
                                    selectedAssignee?.equals(option, ignoreCase = true) == true
                                }
                                Surface(
                                    onClick = {
                                        expanded = false
                                        onAssigneeSelected(if (option == "All") null else option)
                                    },
                                    color = if (isSelected) AccentPurple.copy(alpha = 0.1f) else Color(0xFFF8FAFC),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) AccentPurple else CardBorderColor
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("home_assignee_option_${option}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = option,
                                            color = if (isSelected) AccentPurple else TextDark,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = AccentPurple,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = CardBorderColor, thickness = 1.dp)

                        if (matchingAssignees.isEmpty() && trimmedQuery.isNotEmpty()) {
                            Text(
                                text = "No matching assignees found.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("home_assignee_no_matches")
                            )
                        } else if (matchingAssignees.isNotEmpty()) {
                            Text(
                                text = if (trimmedQuery.isEmpty()) "Team members (${matchingAssignees.size})" else "Matching assignees (${matchingAssignees.size})",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .testTag("home_assignee_suggestions_list")
                            ) {
                                matchingAssignees.forEach { option ->
                                    val isSelected = selectedAssignee?.equals(option, ignoreCase = true) == true
                                    Surface(
                                        onClick = {
                                            expanded = false
                                            onAssigneeSelected(option)
                                        },
                                        color = if (isSelected) AccentPurple.copy(alpha = 0.1f) else Color.White,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) AccentPurple else CardBorderColor
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("home_assignee_suggestion_${option}")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 9.dp)
                                        ) {
                                            Text(
                                                text = option,
                                                color = if (isSelected) AccentPurple else TextDark,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
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
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { expanded = false }) {
                        Text("Close", color = TextMuted)
                    }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
internal fun HomeHierarchyFilterButton(
    labelPrefix: String,
    selectedPath: String?,
    availablePaths: List<String>,
    defaultProjectRoot: String?,
    isIteration: Boolean,
    onPathSelected: (String?) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    var showPickerDialog by remember { mutableStateOf(false) }
    val isFilterActive = !selectedPath.isNullOrBlank() && !selectedPath.equals("All", ignoreCase = true)
    val displayLabel = remember(selectedPath, labelPrefix) {
        if (!selectedPath.isNullOrBlank() && !selectedPath.equals("All", ignoreCase = true)) {
            extractHierarchyLeafName(selectedPath)
        } else {
            "$labelPrefix: All"
        }
    }

    Box(modifier = modifier) {
        Surface(
            onClick = { showPickerDialog = true },
            shape = RoundedCornerShape(10.dp),
            color = if (isFilterActive) AccentPurple.copy(alpha = 0.08f) else Color.White,
            border = BorderStroke(
                1.dp,
                if (isFilterActive) AccentPurple.copy(alpha = 0.55f) else CardBorderColor
            ),
            modifier = Modifier
                .height(34.dp)
                .widthIn(min = 104.dp, max = 210.dp)
                .testTag("${testTagPrefix}_filter_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (isIteration) Icons.Default.CalendarToday else Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = if (isFilterActive) AccentPurple else TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayLabel,
                    color = if (isFilterActive) AccentPurple else TextDark,
                    fontSize = 12.sp,
                    fontWeight = if (isFilterActive) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select $labelPrefix",
                    tint = if (isFilterActive) AccentPurple else Color(0xFF292D32),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (showPickerDialog) {
            HierarchyTreePickerDialog(
                title = "Filter by $labelPrefix",
                fieldLabel = labelPrefix,
                icon = if (isIteration) Icons.Default.CalendarToday else Icons.Default.AccountTree,
                availablePaths = availablePaths,
                selectedPath = selectedPath,
                fallbackRootProject = defaultProjectRoot,
                allowAllOption = true,
                testTagPrefix = testTagPrefix,
                onSelectPath = { chosen ->
                    showPickerDialog = false
                    onPathSelected(chosen)
                },
                onDismiss = { showPickerDialog = false }
            )
        }
    }
}
