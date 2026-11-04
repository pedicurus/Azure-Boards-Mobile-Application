package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WorkItem
import com.example.ui.theme.NeumorphicBg
import com.example.ui.theme.NeumorphicTeal
import com.example.ui.theme.NeumorphicTealLight
import com.example.ui.theme.NeumorphicTextSecondary
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.getAzureStateBadgeBg
import com.example.ui.theme.getAzureStateBadgeText
import com.example.ui.theme.getAzureStateColor
import com.example.ui.theme.getAzureTypeColor
import com.example.ui.theme.getAzureTypeTextColor
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkItemCreateSheet(
    project: String?,
    availableTypes: List<String>,
    initialType: String?,
    availableAssignees: List<String>,
    availableTags: List<String>,
    availableAreas: List<String> = emptyList(),
    availableIterations: List<String> = emptyList(),
    initialAreaPath: String? = null,
    initialIterationPath: String? = null,
    allWorkItems: List<WorkItem>,
    getStatesForType: (String?, String) -> List<String>,
    getAllowedChildTypesForType: (String?, String) -> List<String>,
    getChildStates: (WorkItem) -> List<String>,
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
    ) -> Unit,
    onAddChildWorkItem: (parent: WorkItem, title: String, childType: String, initialState: String) -> Unit,
    onUpdateWorkItem: (WorkItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val createScrollState = rememberScrollState()
    AnimatedTwoStageSheetContainer(
        testTag = "work_item_create_screen",
        contentScrollState = createScrollState,
        onBack = onDismiss,
        onDismiss = onDismiss,
        modifier = modifier
    ) { requestExpand ->
        FigmaCreateWorkItemView(
            project = project,
            availableTypes = availableTypes,
            initialType = initialType,
            availableAssignees = availableAssignees,
            availableTags = availableTags,
            availableAreas = availableAreas,
            availableIterations = availableIterations,
            initialAreaPath = initialAreaPath,
            initialIterationPath = initialIterationPath,
            allWorkItems = allWorkItems,
            getStatesForType = getStatesForType,
            getAllowedChildTypesForType = getAllowedChildTypesForType,
            getChildStates = getChildStates,
            onCreateWorkItem = onCreateWorkItem,
            onAddChildWorkItem = onAddChildWorkItem,
            onUpdateWorkItem = onUpdateWorkItem,
            onDismiss = onDismiss,
            scrollState = createScrollState,
            onExpandRequest = requestExpand,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FigmaCreateWorkItemView(
    project: String?,
    availableTypes: List<String>,
    initialType: String?,
    availableAssignees: List<String>,
    availableTags: List<String>,
    availableAreas: List<String> = emptyList(),
    availableIterations: List<String> = emptyList(),
    initialAreaPath: String? = null,
    initialIterationPath: String? = null,
    allWorkItems: List<WorkItem>,
    getStatesForType: (String?, String) -> List<String>,
    getAllowedChildTypesForType: (String?, String) -> List<String>,
    getChildStates: (WorkItem) -> List<String>,
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
    ) -> Unit,
    onAddChildWorkItem: (parent: WorkItem, title: String, childType: String, initialState: String) -> Unit,
    onUpdateWorkItem: (WorkItem) -> Unit,
    onDismiss: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    onExpandRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val typeOptions = remember(availableTypes) {
        availableTypes.ifEmpty { listOf("Epic", "Feature", "User Story", "Task", "Bug") }
    }

    var selectedType by remember(typeOptions, initialType) {
        val initial = initialType?.takeIf { candidate ->
            typeOptions.any { it.equals(candidate, ignoreCase = true) }
        } ?: typeOptions.first()
        mutableStateOf(initial)
    }

    val stateOptions = remember(project, selectedType) {
        val raw = getStatesForType(project, selectedType)
            .filterNot { it.equals("All", ignoreCase = true) }
        if (raw.isNotEmpty()) {
            raw.distinct()
        } else {
            when {
                selectedType.contains("هدف") -> listOf("اهداف", "در راه", "تکمیل")
                selectedType.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
                else -> listOf("New", "To Do", "Active", "In Progress", "Done")
            }
        }
    }

    var currentState by remember(selectedType, stateOptions) {
        mutableStateOf(stateOptions.firstOrNull() ?: "New")
    }
    var titleInput by remember { mutableStateOf("") }
    var titleError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    var currentAssignee by remember { mutableStateOf("Unassigned") }
    val defaultRootPath = project?.trim()?.takeIf { it.isNotBlank() }
        ?: availableAreas.firstOrNull()
        ?: "Project"
    var currentAreaPath by remember(project) {
        mutableStateOf(
            normalizeHierarchyPathUI(initialAreaPath).ifBlank { defaultRootPath }
        )
    }
    var currentIterationPath by remember(project) {
        mutableStateOf(
            normalizeHierarchyPathUI(initialIterationPath).ifBlank { defaultRootPath }
        )
    }
    var currentTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentDescription by remember { mutableStateOf("") }

    var savedParentId by remember { mutableStateOf<Int?>(null) }
    var fallbackSavedParent by remember { mutableStateOf<WorkItem?>(null) }
    val savedParent = remember(savedParentId, allWorkItems, fallbackSavedParent) {
        if (savedParentId == null) null
        else allWorkItems.firstOrNull { it.id == savedParentId } ?: fallbackSavedParent
    }

    val childWorkItems = remember(savedParent, allWorkItems) {
        val parent = savedParent ?: return@remember emptyList()
        allWorkItems.filter { candidate ->
            candidate.id != parent.id &&
                (candidate.parentId == parent.id || candidate.id in parent.childIds)
        }
    }

    val allowedChildTypes = remember(project, selectedType, availableTypes, allWorkItems) {
        getAllowedChildTypesForType(project, selectedType)
    }

    var stateMenuExpanded by remember { mutableStateOf(false) }
    var assigneeMenuExpanded by remember { mutableStateOf(false) }
    var showAreaPickerDialog by remember { mutableStateOf(false) }
    var showIterationPickerDialog by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var customTagInput by remember { mutableStateOf("") }

    var showAddChildDialog by remember { mutableStateOf(false) }
    LaunchedEffect(allowedChildTypes) {
        if (allowedChildTypes.isEmpty()) {
            showAddChildDialog = false
        }
    }
    var newChildTitleInput by remember { mutableStateOf("") }
    var newChildSelectedType by remember(selectedType, allowedChildTypes) {
        mutableStateOf(allowedChildTypes.firstOrNull() ?: "کار")
    }
    val newChildTypeStates = remember(project, newChildSelectedType, allowedChildTypes) {
        getStatesForType(project, newChildSelectedType)
            .filterNot { it.equals("All", ignoreCase = true) }
            .ifEmpty {
                if (newChildSelectedType.contains("کار")) listOf("کارها", "امروز", "تکمیل", "منتفی")
                else listOf("To Do", "In Progress", "Done")
            }
    }
    var newChildSelectedState by remember(newChildSelectedType, newChildTypeStates) {
        mutableStateOf(newChildTypeStates.firstOrNull() ?: "New")
    }

    val assigneeOptions = remember(availableAssignees, currentAssignee) {
        val activeAssignee = currentAssignee.takeIf {
            it.isNotBlank() && !it.equals("Unassigned", ignoreCase = true)
        }
        (listOfNotNull(activeAssignee) + availableAssignees + "Unassigned").distinct()
    }

    val projectTagOptions = remember(availableTags, currentTags) {
        (availableTags + currentTags)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun pushUpdateIfAlreadySaved(
        updatedTitle: String = titleInput,
        updatedState: String = currentState,
        updatedAssignee: String = currentAssignee,
        updatedAreaPath: String = currentAreaPath,
        updatedIterationPath: String = currentIterationPath,
        updatedTags: List<String> = currentTags,
        updatedDescription: String = currentDescription
    ) {
        val existing = savedParent ?: return
        val updated = existing.copy(
            title = updatedTitle.trim().ifBlank { existing.title },
            state = updatedState,
            assignedToName = updatedAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
            areaPath = updatedAreaPath,
            iterationPath = updatedIterationPath,
            tags = updatedTags,
            description = updatedDescription
        )
        fallbackSavedParent = updated
        onUpdateWorkItem(updated)
    }

    val createDescriptionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var isCreateTitleFocused by remember { mutableStateOf(false) }
    var isCreateDescriptionFocused by remember { mutableStateOf(false) }
    val createDensity = LocalDensity.current
    val isCreateImeVisible = WindowInsets.ime.getBottom(createDensity) > 0

    LaunchedEffect(isCreateTitleFocused, isCreateDescriptionFocused, isCreateImeVisible) {
        if (isCreateTitleFocused || isCreateDescriptionFocused || isCreateImeVisible) {
            onExpandRequest()
        }
    }

    LaunchedEffect(isCreateDescriptionFocused, isCreateImeVisible, currentDescription.length) {
        if (isCreateDescriptionFocused) {
            onExpandRequest()
            try {
                delay(45)
                createDescriptionBringIntoViewRequester.bringIntoView()
                delay(140)
                createDescriptionBringIntoViewRequester.bringIntoView()
                delay(120)
                createDescriptionBringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NeumorphicBg)
            .imePadding()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF94A3B8).copy(alpha = 0.55f))
                    .testTag("create_sheet_drag_handle")
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("create_top_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Create New Item",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        val cleanTitle = titleInput.trim()
                        if (cleanTitle.isBlank()) {
                            titleError = "Title is required"
                            return@Button
                        }
                        if (savedParent != null) {
                            pushUpdateIfAlreadySaved()
                            onDismiss()
                        } else if (!isSaving) {
                            isSaving = true
                            saveError = null
                            onCreateWorkItem(
                                project,
                                selectedType,
                                cleanTitle,
                                currentState,
                                currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                currentDescription,
                                currentTags,
                                currentAreaPath,
                                currentIterationPath
                            ) { result ->
                                isSaving = false
                                result.fold(
                                    onSuccess = {
                                        onDismiss()
                                    },
                                    onFailure = { err ->
                                        saveError = err.message ?: "Failed to create item"
                                    }
                                )
                            }
                        }
                    },
                    enabled = !isSaving,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeumorphicTeal,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("create_work_item_save_button")
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = if (savedParent != null) "Done" else "Create",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Surface(
            color = SurfaceCard,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Item Type",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (savedParent != null) {
                        Text(
                            text = "#${savedParent.id}",
                            color = NeumorphicTeal,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("created_parent_id_badge")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("create_type_capsule_row")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 3.dp, vertical = 3.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        typeOptions.forEach { type ->
                            val isSelected = selectedType.equals(type, ignoreCase = true)
                            val chipColor = getAzureTypeColor(type)
                            val chipTextColor = getAzureTypeTextColor(type)

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) chipColor else Color.Transparent)
                                    .clickable(enabled = savedParent == null) {
                                        selectedType = type
                                    }
                                    .padding(horizontal = 18.dp, vertical = 7.dp)
                                    .testTag("create_type_option_${type}")
                            ) {
                                Text(
                                    text = type,
                                    color = if (isSelected) chipTextColor else NeumorphicTextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Title *",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isTitleRtl = remember(titleInput) { isAlmostPersian(titleInput) }
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { updated ->
                            titleInput = updated
                            if (updated.isNotBlank()) {
                                titleError = null
                            }
                            pushUpdateIfAlreadySaved(updatedTitle = updated)
                        },
                        placeholder = {
                            Text(
                                text = "Enter work item title...",
                                color = NeumorphicTextSecondary,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        isError = titleError != null,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp,
                            textAlign = if (isTitleRtl) TextAlign.Right else TextAlign.Left,
                            textDirection = if (isTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        maxLines = 3,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeumorphicTeal,
                            unfocusedBorderColor = SurfaceBorder,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isCreateTitleFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    onExpandRequest()
                                }
                            }
                            .testTag("create_work_item_title_input")
                    )
                }

                if (titleError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = titleError!!,
                        color = Color(0xFFDC2626),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("create_title_error_text")
                    )
                }

                if (saveError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = saveError!!,
                        color = Color(0xFFDC2626),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_metadata_row")
                ) {
                    Box {
                        val stateColor = getAzureStateColor(currentState)
                        val stateBg = getAzureStateBadgeBg(currentState)
                        val stateText = getAzureStateBadgeText(currentState)

                        Surface(
                            onClick = { stateMenuExpanded = true },
                            color = stateBg,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
                            modifier = Modifier.testTag("create_state_selector")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(stateColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = currentState,
                                    color = stateText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select state",
                                    tint = stateText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = stateMenuExpanded,
                            onDismissRequest = { stateMenuExpanded = false },
                            containerColor = SurfaceCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SurfaceBorder)
                        ) {
                            stateOptions.forEach { stateOption ->
                                val isSelected = stateOption.equals(currentState, ignoreCase = true)
                                val optionColor = getAzureStateColor(stateOption)
                                val optionReadableText = getAzureStateBadgeText(stateOption)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(optionColor)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = stateOption,
                                                    color = if (isSelected) optionReadableText else TextPrimary,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 13.sp
                                                )
                                            }
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = optionReadableText,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        stateMenuExpanded = false
                                        currentState = stateOption
                                        pushUpdateIfAlreadySaved(updatedState = stateOption)
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        Surface(
                            onClick = { assigneeMenuExpanded = true },
                            color = SurfaceCard,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier.testTag("create_assignee_selector")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(NeumorphicTealLight.copy(alpha = 0.2f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = NeumorphicTeal,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = currentAssignee,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select assignee",
                                    tint = NeumorphicTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                ClassificationHierarchySection(
                    areaPath = currentAreaPath,
                    iterationPath = currentIterationPath,
                    onAreaClick = { showAreaPickerDialog = true },
                    onIterationClick = { showIterationPickerDialog = true },
                    testTagPrefix = "create"
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Description",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isDescRtl = remember(currentDescription, titleInput) {
                    isAlmostPersian(currentDescription.ifBlank { titleInput })
                }
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isDescRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = currentDescription,
                        onValueChange = { updatedText ->
                            currentDescription = updatedText
                            pushUpdateIfAlreadySaved(updatedDescription = updatedText)
                        },
                        placeholder = {
                            Text(
                                text = "Add a description...",
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            textAlign = if (isDescRtl) TextAlign.Right else TextAlign.Left,
                            textDirection = if (isDescRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeumorphicTeal,
                            unfocusedBorderColor = SurfaceBorder,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(createDescriptionBringIntoViewRequester)
                            .onFocusChanged { focusState ->
                                isCreateDescriptionFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    onExpandRequest()
                                }
                            }
                            .testTag("create_description_input")
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Tags",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_tags_section")
                ) {
                    currentTags.forEach { tag ->
                        Surface(
                            color = Color(0xFFEEF2F8),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sell,
                                    contentDescription = null,
                                    tint = Color(0xFF334155),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = tag,
                                    color = Color(0xFF0F172A),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove tag $tag",
                                    tint = Color(0xFF475569),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            val updated = currentTags.filterNot { it == tag }
                                            currentTags = updated
                                            pushUpdateIfAlreadySaved(updatedTags = updated)
                                        }
                                )
                            }
                        }
                    }

                    Box {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEEF2F8))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                                .clickable {
                                    customTagInput = ""
                                    showAddTagDialog = true
                                }
                                .testTag("create_add_tag_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add tag",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (allowedChildTypes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Children",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (childWorkItems.isNotEmpty()) {
                                Surface(
                                    color = Color(0xFFEEF2F8),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                                ) {
                                    Text(
                                        text = childWorkItems.size.toString(),
                                        color = Color(0xFF0F172A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEEF2F8))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                                .clickable {
                                    if (titleInput.trim().isBlank()) {
                                        titleError = "Please enter a title before adding a child item"
                                    } else {
                                        titleError = null
                                        newChildTitleInput = ""
                                        newChildSelectedType = allowedChildTypes.firstOrNull() ?: "کار"
                                        showAddChildDialog = true
                                    }
                                }
                                .testTag("create_add_child_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add child item",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (childWorkItems.isEmpty()) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_children_empty")
                        ) {
                            Text(
                                text = "No child work items",
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_children_section")
                        ) {
                            childWorkItems.forEach { child ->
                                ChildWorkItemStatusRow(
                                    child = child,
                                    availableStates = getChildStates(child),
                                    onStateChange = { newState ->
                                        onUpdateWorkItem(child.copy(state = newState))
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAddChildDialog && allowedChildTypes.isNotEmpty()) {
        val isNewChildTitleRtl = isAlmostPersian(newChildTitleInput.ifBlank { titleInput })
        AlertDialog(
            onDismissRequest = { showAddChildDialog = false },
            title = {
                Text(
                    text = "New Child ($newChildSelectedType)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (allowedChildTypes.size > 1) {
                        Text(
                            text = "Type",
                            color = NeumorphicTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            allowedChildTypes.forEach { childTypeOption ->
                                val isTypeSelected = childTypeOption == newChildSelectedType
                                Surface(
                                    onClick = { newChildSelectedType = childTypeOption },
                                    color = if (isTypeSelected) NeumorphicTeal.copy(alpha = 0.14f) else Color(0xFFF8FAFC),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isTypeSelected) NeumorphicTeal else SurfaceBorder
                                    )
                                ) {
                                    Text(
                                        text = childTypeOption,
                                        color = if (isTypeSelected) NeumorphicTeal else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isTypeSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (isNewChildTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        OutlinedTextField(
                            value = newChildTitleInput,
                            onValueChange = { newChildTitleInput = it },
                            placeholder = {
                                Text(
                                    text = "Enter child title...",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 14.sp,
                                    textAlign = if (isNewChildTitleRtl) TextAlign.Right else TextAlign.Left,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = if (isNewChildTitleRtl) TextAlign.Right else TextAlign.Left,
                                textDirection = if (isNewChildTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = NeumorphicTeal,
                                focusedBorderColor = NeumorphicTeal,
                                unfocusedBorderColor = SurfaceBorder,
                                focusedContainerColor = SurfaceCard,
                                unfocusedContainerColor = SurfaceCard,
                                focusedPlaceholderColor = NeumorphicTextSecondary,
                                unfocusedPlaceholderColor = NeumorphicTextSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_new_child_title_input")
                        )
                    }

                    if (newChildTypeStates.isNotEmpty()) {
                        Text(
                            text = "State",
                            color = NeumorphicTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            newChildTypeStates.forEach { stateOption ->
                                val isStateSelected = stateOption == newChildSelectedState
                                val stateColor = getAzureStateColor(stateOption)
                                val stateBg = getAzureStateBadgeBg(stateOption)
                                val stateText = getAzureStateBadgeText(stateOption)
                                Surface(
                                    onClick = { newChildSelectedState = stateOption },
                                    color = if (isStateSelected) stateBg else Color(0xFFF8FAFC),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isStateSelected) stateColor else SurfaceBorder
                                    )
                                ) {
                                    Text(
                                        text = stateOption,
                                        color = if (isStateSelected) stateText else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isStateSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedChildTitle = newChildTitleInput.trim()
                        val trimmedParentTitle = titleInput.trim()
                        if (trimmedChildTitle.isNotEmpty() && trimmedParentTitle.isNotEmpty()) {
                            val childTypeToCreate = newChildSelectedType
                            val childStateToCreate = newChildSelectedState
                            showAddChildDialog = false

                            val existingParent = savedParent
                            if (existingParent != null) {
                                onAddChildWorkItem(
                                    existingParent,
                                    trimmedChildTitle,
                                    childTypeToCreate,
                                    childStateToCreate
                                )
                            } else {
                                isSaving = true
                                saveError = null
                                onCreateWorkItem(
                                    project,
                                    selectedType,
                                    trimmedParentTitle,
                                    currentState,
                                    currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                    currentDescription,
                                    currentTags,
                                    currentAreaPath,
                                    currentIterationPath
                                ) { result ->
                                    isSaving = false
                                    result.fold(
                                        onSuccess = { createdParent ->
                                            savedParentId = createdParent.id
                                            fallbackSavedParent = createdParent
                                            onAddChildWorkItem(
                                                createdParent,
                                                trimmedChildTitle,
                                                childTypeToCreate,
                                                childStateToCreate
                                            )
                                        },
                                        onFailure = { err ->
                                            saveError = err.message ?: "Failed to auto-save parent work item"
                                        }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("create_confirm_add_child_button")
                ) {
                    Text("Add", color = NeumorphicTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddChildDialog = false }) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (assigneeMenuExpanded) {
        AssigneeSearchDialog(
            currentAssignee = currentAssignee,
            availableAssignees = assigneeOptions,
            testTagPrefix = "create",
            onSelectAssignee = { selected ->
                currentAssignee = selected
                pushUpdateIfAlreadySaved(updatedAssignee = selected)
            },
            onDismiss = { assigneeMenuExpanded = false }
        )
    }

    if (showAreaPickerDialog) {
        HierarchyTreePickerDialog(
            title = "Select Area",
            fieldLabel = "Area",
            icon = Icons.Default.AccountTree,
            availablePaths = availableAreas,
            selectedPath = currentAreaPath,
            fallbackRootProject = defaultRootPath,
            allowAllOption = false,
            testTagPrefix = "create_area",
            onSelectPath = { chosenArea ->
                val resolved = normalizeHierarchyPathUI(chosenArea).ifBlank { defaultRootPath }
                currentAreaPath = resolved
                pushUpdateIfAlreadySaved(updatedAreaPath = resolved)
                showAreaPickerDialog = false
            },
            onDismiss = { showAreaPickerDialog = false }
        )
    }

    if (showIterationPickerDialog) {
        HierarchyTreePickerDialog(
            title = "Select Iteration",
            fieldLabel = "Iteration",
            icon = Icons.Default.CalendarToday,
            availablePaths = availableIterations,
            selectedPath = currentIterationPath,
            fallbackRootProject = defaultRootPath,
            allowAllOption = false,
            testTagPrefix = "create_iteration",
            onSelectPath = { chosenIteration ->
                val resolved = normalizeHierarchyPathUI(chosenIteration).ifBlank { defaultRootPath }
                currentIterationPath = resolved
                pushUpdateIfAlreadySaved(updatedIterationPath = resolved)
                showIterationPickerDialog = false
            },
            onDismiss = { showIterationPickerDialog = false }
        )
    }

    if (showAddTagDialog) {
        TagSearchAndAddDialog(
            customTagInput = customTagInput,
            onCustomTagInputChange = { customTagInput = it },
            fallbackRtlText = titleInput,
            currentTags = currentTags,
            projectTagOptions = projectTagOptions,
            inputTestTag = "create_new_tag_input",
            onSelectExistingTag = { tagOption ->
                val isAlreadySelected = currentTags.any { it.equals(tagOption, ignoreCase = true) }
                if (!isAlreadySelected) {
                    val updated = currentTags + tagOption
                    currentTags = updated
                    pushUpdateIfAlreadySaved(updatedTags = updated)
                }
                showAddTagDialog = false
            },
            onConfirmAddTag = { trimmed ->
                val canonical = projectTagOptions.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
                if (canonical.isNotEmpty() && currentTags.none { it.equals(canonical, ignoreCase = true) }) {
                    val updated = currentTags + canonical
                    currentTags = updated
                    pushUpdateIfAlreadySaved(updatedTags = updated)
                }
                showAddTagDialog = false
            },
            onDismiss = { showAddTagDialog = false }
        )
    }
}
