package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkItemDetailSheet(
    workItem: WorkItem,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    availableStates: List<String> = emptyList(),
    availableAssignees: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
    availableAreas: List<String> = emptyList(),
    availableIterations: List<String> = emptyList(),
    allWorkItems: List<WorkItem> = emptyList(),
    parentWorkItem: WorkItem? = null,
    childWorkItems: List<WorkItem> = emptyList(),
    allowedChildTypes: List<String> = emptyList(),
    allowedParentTypes: List<String> = emptyList(),
    canHaveParent: Boolean = false,
    getChildStates: (WorkItem) -> List<String> = { emptyList() },
    getStatesForChildType: (String) -> List<String> = { emptyList() },
    getParentStates: (WorkItem) -> List<String> = { emptyList() },
    getStatesForParentType: (String) -> List<String> = { emptyList() },
    onAddChildWorkItem: (title: String, childType: String, initialState: String) -> Unit = { _, _, _ -> },
    onAddExistingChildWorkItem: (childId: Int, onResult: (Result<WorkItem>) -> Unit) -> Unit = { _, _ -> },
    onRemoveChildWorkItem: (childId: Int) -> Unit = {},
    onAddParentWorkItem: (title: String, parentType: String, initialState: String) -> Unit = { _, _, _ -> },
    onAddExistingParentWorkItem: (parentId: Int, onResult: (Result<WorkItem>) -> Unit) -> Unit = { _, _ -> },
    onUnlinkParentWorkItem: (parentId: Int) -> Unit = {},
    onNavigateToParent: (WorkItem) -> Unit = {},
    onNavigateToChild: (WorkItem) -> Unit = {},
    comments: List<WorkItemComment> = emptyList(),
    isLoadingComments: Boolean = false,
    onAddComment: (commentText: String, onResult: (Result<WorkItemComment>) -> Unit) -> Unit = { _, _ -> },
    onUpdateWorkItem: (WorkItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val detailScrollState = rememberScrollState()
    AnimatedTwoStageSheetContainer(
        testTag = "work_item_detail_screen",
        contentScrollState = detailScrollState,
        onBack = onBack,
        onDismiss = onDismiss,
        modifier = modifier
    ) { requestExpand ->
        FigmaItemDetailView(
            workItem = workItem,
            onBack = onBack,
            onClose = onDismiss,
            onCreateOrUpdate = onDismiss,
            availableStates = availableStates,
            availableAssignees = availableAssignees,
            availableTags = availableTags,
            availableAreas = availableAreas,
            availableIterations = availableIterations,
            allWorkItems = allWorkItems,
            parentWorkItem = parentWorkItem,
            childWorkItems = childWorkItems,
            allowedChildTypes = allowedChildTypes,
            allowedParentTypes = allowedParentTypes,
            canHaveParent = canHaveParent,
            getChildStates = getChildStates,
            getStatesForChildType = getStatesForChildType,
            getParentStates = getParentStates,
            getStatesForParentType = getStatesForParentType,
            onAddChildWorkItem = onAddChildWorkItem,
            onAddExistingChildWorkItem = onAddExistingChildWorkItem,
            onRemoveChildWorkItem = onRemoveChildWorkItem,
            onAddParentWorkItem = onAddParentWorkItem,
            onAddExistingParentWorkItem = onAddExistingParentWorkItem,
            onUnlinkParentWorkItem = onUnlinkParentWorkItem,
            onNavigateToParent = onNavigateToParent,
            onNavigateToChild = onNavigateToChild,
            comments = comments,
            isLoadingComments = isLoadingComments,
            onAddComment = onAddComment,
            onUpdateWorkItem = onUpdateWorkItem,
            scrollState = detailScrollState,
            onExpandRequest = requestExpand,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FigmaItemDetailView(
    workItem: WorkItem,
    onBack: () -> Unit,
    onClose: () -> Unit = onBack,
    onCreateOrUpdate: () -> Unit = {},
    availableStates: List<String> = emptyList(),
    availableAssignees: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
    availableAreas: List<String> = emptyList(),
    availableIterations: List<String> = emptyList(),
    allWorkItems: List<WorkItem> = emptyList(),
    parentWorkItem: WorkItem? = null,
    childWorkItems: List<WorkItem> = emptyList(),
    allowedChildTypes: List<String> = emptyList(),
    allowedParentTypes: List<String> = emptyList(),
    canHaveParent: Boolean = false,
    getChildStates: (WorkItem) -> List<String> = { emptyList() },
    getStatesForChildType: (String) -> List<String> = { emptyList() },
    getParentStates: (WorkItem) -> List<String> = { emptyList() },
    getStatesForParentType: (String) -> List<String> = { emptyList() },
    onAddChildWorkItem: (title: String, childType: String, initialState: String) -> Unit = { _, _, _ -> },
    onAddExistingChildWorkItem: (childId: Int, onResult: (Result<WorkItem>) -> Unit) -> Unit = { _, _ -> },
    onRemoveChildWorkItem: (childId: Int) -> Unit = {},
    onAddParentWorkItem: (title: String, parentType: String, initialState: String) -> Unit = { _, _, _ -> },
    onAddExistingParentWorkItem: (parentId: Int, onResult: (Result<WorkItem>) -> Unit) -> Unit = { _, _ -> },
    onUnlinkParentWorkItem: (parentId: Int) -> Unit = {},
    onNavigateToParent: (WorkItem) -> Unit = {},
    onNavigateToChild: (WorkItem) -> Unit = {},
    comments: List<WorkItemComment> = emptyList(),
    isLoadingComments: Boolean = false,
    onAddComment: (commentText: String, onResult: (Result<WorkItemComment>) -> Unit) -> Unit = { _, _ -> },
    onUpdateWorkItem: (WorkItem) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    onExpandRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentTitle by remember(workItem.id) { mutableStateOf(workItem.title) }
    var currentState by remember(workItem.id, workItem.state) { mutableStateOf(workItem.state) }
    var currentAssignee by remember(workItem.id, workItem.assignedToName) {
        mutableStateOf(workItem.assignedToName?.trim()?.takeIf { it.isNotBlank() } ?: "Unassigned")
    }
    val defaultRootPath = workItem.teamProject?.trim()?.takeIf { it.isNotBlank() }
        ?: availableAreas.firstOrNull()
        ?: "Project"
    var currentAreaPath by remember(workItem.id, workItem.areaPath) {
        mutableStateOf(normalizeHierarchyPathUI(workItem.areaPath).ifBlank { defaultRootPath })
    }
    var currentIterationPath by remember(workItem.id, workItem.iterationPath) {
        mutableStateOf(normalizeHierarchyPathUI(workItem.iterationPath).ifBlank { defaultRootPath })
    }
    var currentTags by remember(workItem.id, workItem.tags) {
        mutableStateOf(workItem.tags.map { it.trim() }.filter { it.isNotBlank() })
    }
    var currentDescription by remember(workItem.id) {
        mutableStateOf(workItem.description ?: "")
    }

    val latestOnUpdateWorkItem by rememberUpdatedState(onUpdateWorkItem)
    val latestWorkItem by rememberUpdatedState(workItem)

    fun buildCurrentWorkItem(
        titleOverride: String = currentTitle,
        stateOverride: String = currentState,
        assigneeOverride: String = currentAssignee,
        areaOverride: String = currentAreaPath,
        iterationOverride: String = currentIterationPath,
        tagsOverride: List<String> = currentTags,
        descriptionOverride: String = currentDescription
    ): WorkItem {
        return latestWorkItem.copy(
            title = titleOverride.trim().ifBlank { latestWorkItem.title },
            state = stateOverride,
            assignedToName = assigneeOverride.takeUnless { it.equals("Unassigned", ignoreCase = true) },
            areaPath = areaOverride,
            iterationPath = iterationOverride,
            tags = tagsOverride,
            description = descriptionOverride
        )
    }

    // Debounce rapid title & description edits so keystrokes never trigger synchronous full-list copies on every frame
    var pendingTextSyncVersion by remember(workItem.id) { mutableStateOf(0) }
    LaunchedEffect(pendingTextSyncVersion) {
        if (pendingTextSyncVersion > 0) {
            delay(320L)
            latestOnUpdateWorkItem(buildCurrentWorkItem())
        }
    }

    // Flush any pending title or description edits immediately if the user closes or switches items
    DisposableEffect(workItem.id) {
        onDispose {
            if (pendingTextSyncVersion > 0) {
                latestOnUpdateWorkItem(buildCurrentWorkItem())
            }
        }
    }

    var showAreaPickerDialog by remember(workItem.id) { mutableStateOf(false) }
    var showIterationPickerDialog by remember(workItem.id) { mutableStateOf(false) }
    var assigneeMenuExpanded by remember(workItem.id) { mutableStateOf(false) }
    var showAddTagDialog by remember(workItem.id) { mutableStateOf(false) }
    var customTagInput by remember(workItem.id) { mutableStateOf("") }

    val stateOptions = remember(availableStates, currentState, workItem.workItemType) {
        val cleanAvailable = availableStates.filterNot { it.equals("All", ignoreCase = true) }
        val defaults = when {
            workItem.workItemType.contains("هدف") -> listOf("اهداف", "در راه", "تکمیل")
            workItem.workItemType.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
            else -> listOf("In Progress", "Active", "Done", "New")
        }
        ((if (cleanAvailable.isNotEmpty()) cleanAvailable else defaults) + currentState)
            .filterNot { it.equals("All", ignoreCase = true) }
            .distinct()
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

    LaunchedEffect(workItem.id) {
        scrollState.scrollTo(0)
    }

    // Comment & @mention state (shared between composer and floating suggestion bar)
    var commentFieldValue by remember(workItem.id) { mutableStateOf(TextFieldValue("")) }
    val indexedMentionPeople = remember(availableAssignees, allWorkItems.size, comments) {
        buildMentionPeopleIndex(
            availableAssignees = availableAssignees,
            allWorkItems = allWorkItems,
            comments = comments
        )
    }
    val activeMentionToken = remember(commentFieldValue.text, commentFieldValue.selection) {
        extractActiveMentionToken(commentFieldValue)
    }
    val matchingMentionPeople = remember(activeMentionToken, indexedMentionPeople) {
        val token = activeMentionToken ?: return@remember emptyList()
        val cleanQuery = token.query.trim()
        if (cleanQuery.isEmpty()) {
            indexedMentionPeople.take(8)
        } else {
            val lowerQuery = cleanQuery.lowercase()
            indexedMentionPeople.asSequence()
                .filter { candidate ->
                    candidate.lowerName.contains(lowerQuery) ||
                        (candidate.lowerEmail != null && candidate.lowerEmail.contains(lowerQuery))
                }
                .take(8)
                .toList()
        }
    }
    val isMentionBarVisible = activeMentionToken != null && matchingMentionPeople.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeumorphicBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            DetailSheetTopBar(
                onBack = onBack,
                onClose = onClose
            )

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
                        .padding(
                            start = 20.dp,
                            top = 22.dp,
                            end = 20.dp,
                            bottom = if (isMentionBarVisible) 116.dp else 28.dp
                        )
                ) {
                    DetailTitleEditorRow(
                        workItemId = workItem.id,
                        workItemType = workItem.workItemType,
                        fallbackTitle = workItem.title,
                        currentTitle = currentTitle,
                        onTitleChange = { updatedTitle ->
                            currentTitle = updatedTitle
                            pendingTextSyncVersion++
                        },
                        onTitleEditCommit = {
                            if (currentTitle.isBlank()) {
                                currentTitle = workItem.title
                            }
                            onUpdateWorkItem(buildCurrentWorkItem(titleOverride = currentTitle))
                        },
                        onExpandRequest = onExpandRequest
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    DetailMetadataPillsRow(
                        currentState = currentState,
                        stateOptions = stateOptions,
                        currentAssignee = currentAssignee,
                        onSelectState = { chosenState ->
                            currentState = chosenState
                            onUpdateWorkItem(buildCurrentWorkItem(stateOverride = chosenState))
                        },
                        onOpenAssigneePicker = { assigneeMenuExpanded = true }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    ClassificationHierarchySection(
                        areaPath = currentAreaPath,
                        iterationPath = currentIterationPath,
                        onAreaClick = { showAreaPickerDialog = true },
                        onIterationClick = { showIterationPickerDialog = true },
                        testTagPrefix = "detail"
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    DetailDescriptionEditor(
                        workItemId = workItem.id,
                        fallbackTitle = workItem.title,
                        currentDescription = currentDescription,
                        onDescriptionChange = { updatedText ->
                            currentDescription = updatedText
                            pendingTextSyncVersion++
                        },
                        onDescriptionBlurCommit = {
                            onUpdateWorkItem(buildCurrentWorkItem(descriptionOverride = currentDescription))
                        },
                        onExpandRequest = onExpandRequest
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    DetailTagsSection(
                        currentTags = currentTags,
                        onRemoveTag = { tagToRemove ->
                            val updatedTags = currentTags.filterNot { it == tagToRemove }
                            currentTags = updatedTags
                            onUpdateWorkItem(buildCurrentWorkItem(tagsOverride = updatedTags))
                        },
                        onOpenAddTagDialog = {
                            customTagInput = ""
                            showAddTagDialog = true
                        }
                    )

                    DetailParentAndChildrenSection(
                        workItem = workItem,
                        allWorkItems = allWorkItems,
                        parentWorkItem = parentWorkItem,
                        childWorkItems = childWorkItems,
                        allowedChildTypes = allowedChildTypes,
                        allowedParentTypes = allowedParentTypes,
                        canHaveParent = canHaveParent,
                        getChildStates = getChildStates,
                        getStatesForChildType = getStatesForChildType,
                        getStatesForParentType = getStatesForParentType,
                        onAddChildWorkItem = onAddChildWorkItem,
                        onAddExistingChildWorkItem = onAddExistingChildWorkItem,
                        onRemoveChildWorkItem = onRemoveChildWorkItem,
                        onAddParentWorkItem = onAddParentWorkItem,
                        onAddExistingParentWorkItem = onAddExistingParentWorkItem,
                        onUnlinkParentWorkItem = onUnlinkParentWorkItem,
                        onNavigateToParent = onNavigateToParent,
                        onNavigateToChild = onNavigateToChild,
                        onUpdateWorkItem = onUpdateWorkItem
                    )

                    DetailDiscussionSection(
                        workItemId = workItem.id,
                        fallbackRtlTitle = workItem.title,
                        comments = comments,
                        isLoadingComments = isLoadingComments,
                        indexedMentionPeople = indexedMentionPeople,
                        commentFieldValue = commentFieldValue,
                        onCommentFieldValueChange = { commentFieldValue = it },
                        matchingMentionPeople = matchingMentionPeople,
                        onAddComment = onAddComment,
                        onExpandRequest = onExpandRequest
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (assigneeMenuExpanded) {
            AssigneeSearchDialog(
                currentAssignee = currentAssignee,
                availableAssignees = assigneeOptions,
                testTagPrefix = "detail",
                onSelectAssignee = { selected ->
                    currentAssignee = selected
                    onUpdateWorkItem(buildCurrentWorkItem(assigneeOverride = selected))
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
                testTagPrefix = "detail_area",
                onSelectPath = { chosenArea ->
                    val resolved = normalizeHierarchyPathUI(chosenArea).ifBlank { defaultRootPath }
                    currentAreaPath = resolved
                    onUpdateWorkItem(buildCurrentWorkItem(areaOverride = resolved))
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
                testTagPrefix = "detail_iteration",
                onSelectPath = { chosenIteration ->
                    val resolved = normalizeHierarchyPathUI(chosenIteration).ifBlank { defaultRootPath }
                    currentIterationPath = resolved
                    onUpdateWorkItem(buildCurrentWorkItem(iterationOverride = resolved))
                    showIterationPickerDialog = false
                },
                onDismiss = { showIterationPickerDialog = false }
            )
        }

        if (showAddTagDialog) {
            TagSearchAndAddDialog(
                customTagInput = customTagInput,
                onCustomTagInputChange = { customTagInput = it },
                fallbackRtlText = currentTitle.ifBlank { workItem.title },
                currentTags = currentTags,
                projectTagOptions = projectTagOptions,
                inputTestTag = "detail_new_tag_input",
                onSelectExistingTag = { tagOption ->
                    val isAlreadySelected = currentTags.any { it.equals(tagOption, ignoreCase = true) }
                    if (!isAlreadySelected) {
                        val updated = currentTags + tagOption
                        currentTags = updated
                        onUpdateWorkItem(buildCurrentWorkItem(tagsOverride = updated))
                    }
                    showAddTagDialog = false
                },
                onConfirmAddTag = { trimmed ->
                    val canonical = projectTagOptions.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
                    if (canonical.isNotEmpty() && currentTags.none { it.equals(canonical, ignoreCase = true) }) {
                        val updated = currentTags + canonical
                        currentTags = updated
                        onUpdateWorkItem(buildCurrentWorkItem(tagsOverride = updated))
                    }
                    showAddTagDialog = false
                },
                onDismiss = { showAddTagDialog = false }
            )
        }

        FloatingMentionSuggestionsBar(
            activeMentionToken = activeMentionToken,
            matchingMentionPeople = matchingMentionPeople,
            onSelectCandidate = { candidate ->
                val currentText = commentFieldValue.text
                val token = activeMentionToken ?: return@FloatingMentionSuggestionsBar
                val safeStart = token.startIndex.coerceIn(0, currentText.length)
                val safeEnd = token.endIndex.coerceIn(safeStart, currentText.length)
                val mentionInsertion = "@<${candidate.displayName}> "
                val newText = buildString(currentText.length + mentionInsertion.length) {
                    append(currentText, 0, safeStart)
                    append(mentionInsertion)
                    append(currentText, safeEnd, currentText.length)
                }
                val newCursor = safeStart + mentionInsertion.length
                commentFieldValue = TextFieldValue(
                    text = newText,
                    selection = TextRange(newCursor)
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun DetailSheetTopBar(
    onBack: () -> Unit,
    onClose: () -> Unit
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
                .testTag("detail_sheet_drag_handle")
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
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("detail_top_back_button")
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
                    text = "Detail",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("detail_top_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close detail sheet",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailTitleEditorRow(
    workItemId: Int,
    workItemType: String,
    fallbackTitle: String,
    currentTitle: String,
    onTitleChange: (String) -> Unit,
    onTitleEditCommit: () -> Unit,
    onExpandRequest: () -> Unit
) {
    var isEditingTitle by remember(workItemId) { mutableStateOf(false) }
    val titleFocusRequester = remember { FocusRequester() }
    val isDetailTitleRtl = remember(currentTitle, fallbackTitle) {
        isAlmostPersian(currentTitle.ifBlank { fallbackTitle })
    }

    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) {
            onExpandRequest()
        }
    }

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isDetailTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                if (isEditingTitle) {
                    LaunchedEffect(Unit) {
                        titleFocusRequester.requestFocus()
                    }
                    OutlinedTextField(
                        value = currentTitle,
                        onValueChange = onTitleChange,
                        placeholder = {
                            Text(
                                text = "Enter title...",
                                color = NeumorphicTextSecondary,
                                fontSize = 18.sp
                            )
                        },
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 26.sp,
                            textAlign = if (isDetailTitleRtl) TextAlign.Right else TextAlign.Left,
                            textDirection = if (isDetailTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    isEditingTitle = false
                                    onTitleEditCommit()
                                },
                                modifier = Modifier.testTag("detail_title_done_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Finish editing title",
                                    tint = NeumorphicTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                isEditingTitle = false
                                onTitleEditCommit()
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
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
                            .focusRequester(titleFocusRequester)
                            .testTag("detail_title_input")
                    )
                } else {
                    Text(
                        text = currentTitle.ifBlank { fallbackTitle },
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp,
                        textAlign = if (isDetailTitleRtl) TextAlign.Right else TextAlign.Left,
                        style = TextStyle(
                            textDirection = if (isDetailTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isEditingTitle = true }
                            .padding(vertical = 2.dp)
                            .testTag("detail_title_text")
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(top = 3.dp)
        ) {
            AzureWorkItemTypeSymbol(
                workItemType = workItemType,
                iconSize = 18.dp,
                modifier = Modifier.testTag("detail_type_symbol_$workItemId")
            )
            Text(
                text = "#$workItemId",
                color = NeumorphicTeal,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailMetadataPillsRow(
    currentState: String,
    stateOptions: List<String>,
    currentAssignee: String,
    onSelectState: (String) -> Unit,
    onOpenAssigneePicker: () -> Unit
) {
    var stateMenuExpanded by remember { mutableStateOf(false) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail_metadata_row")
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
                modifier = Modifier.testTag("detail_state_selector")
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
                            onSelectState(stateOption)
                        }
                    )
                }
            }
        }

        Box {
            Surface(
                onClick = onOpenAssigneePicker,
                color = SurfaceCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.testTag("detail_assignee_selector")
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailDescriptionEditor(
    workItemId: Int,
    fallbackTitle: String,
    currentDescription: String,
    onDescriptionChange: (String) -> Unit,
    onDescriptionBlurCommit: () -> Unit,
    onExpandRequest: () -> Unit
) {
    val descriptionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var isDescriptionFocused by remember(workItemId) { mutableStateOf(false) }
    var hadFocus by remember(workItemId) { mutableStateOf(false) }
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(isDescriptionFocused, isImeVisible, currentDescription.length) {
        if (isDescriptionFocused) {
            onExpandRequest()
            try {
                delay(45)
                descriptionBringIntoViewRequester.bringIntoView()
                delay(140)
                descriptionBringIntoViewRequester.bringIntoView()
                delay(120)
                descriptionBringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

    Text(
        text = "Description",
        color = TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))

    val isDetailDescRtl = remember(currentDescription, fallbackTitle) {
        isAlmostPersian(currentDescription.ifBlank { fallbackTitle })
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isDetailDescRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        OutlinedTextField(
            value = currentDescription,
            onValueChange = onDescriptionChange,
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
                textAlign = if (isDetailDescRtl) TextAlign.Right else TextAlign.Left,
                textDirection = if (isDetailDescRtl) TextDirection.Rtl else TextDirection.Ltr
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
                .bringIntoViewRequester(descriptionBringIntoViewRequester)
                .onFocusChanged { focusState ->
                    isDescriptionFocused = focusState.isFocused
                    if (focusState.isFocused) {
                        hadFocus = true
                        onExpandRequest()
                    } else if (hadFocus) {
                        onDescriptionBlurCommit()
                    }
                }
                .testTag("detail_description_input")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailTagsSection(
    currentTags: List<String>,
    onRemoveTag: (String) -> Unit,
    onOpenAddTagDialog: () -> Unit
) {
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
            .testTag("detail_tags_section")
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
                            .clickable { onRemoveTag(tag) }
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
                    .clickable(onClick = onOpenAddTagDialog)
                    .testTag("detail_add_tag_button")
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
}
