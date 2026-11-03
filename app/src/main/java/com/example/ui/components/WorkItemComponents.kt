package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.example.ui.theme.getAzureTypeColor
import com.example.ui.theme.getAzureTypeTextColor
import com.example.ui.theme.getAzureTypeVectorIcon

@Composable
fun AzureWorkItemTypeSymbol(
    workItemType: String,
    iconSize: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val typeColor = getAzureTypeColor(workItemType)
    val typeIcon = getAzureTypeVectorIcon(workItemType)
    Icon(
        imageVector = typeIcon,
        contentDescription = workItemType,
        tint = typeColor,
        modifier = modifier.size(iconSize)
    )
}

/**
 * Returns true if the text is predominantly ("almost") Persian/Arabic script,
 * even when mixed with English terms, numbers, or punctuation.
 */
fun isAlmostPersian(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    var rtlLetters = 0
    var totalLetters = 0
    for (ch in text) {
        if (ch.isLetter()) {
            totalLetters++
            val code = ch.code
            if (code in 0x0600..0x06FF ||
                code in 0x0750..0x077F ||
                code in 0x08A0..0x08FF ||
                code in 0xFB50..0xFDFF ||
                code in 0xFE70..0xFEFF
            ) {
                rtlLetters++
            }
        }
    }
    if (totalLetters == 0) return false
    return (rtlLetters.toFloat() / totalLetters.toFloat()) >= 0.4f
}

enum class ChildFilterCategory(val label: String) {
    ALL("All"),
    NOT_STARTED("Not Started"),
    IN_PROGRESS("In Progress"),
    DONE("Done")
}

fun matchesChildFilter(state: String?, filter: ChildFilterCategory): Boolean {
    if (filter == ChildFilterCategory.ALL) return true
    val clean = state?.trim()?.lowercase() ?: ""
    val isDone = clean.contains("done") || clean.contains("close") ||
        clean.contains("resolved") || clean.contains("completed") ||
        clean.contains("finished") || clean.contains("تکمیل") ||
        clean.contains("بسته") || clean.contains("پایان") ||
        clean.contains("remove") || clean.contains("منتفی") ||
        clean.contains("لغو") || clean.contains("cut")

    val isNotStarted = clean.contains("new") || clean.contains("to do") ||
        clean.contains("todo") || clean.contains("proposed") ||
        clean.contains("approved") || clean.contains("open") ||
        clean.contains("design") || clean.contains("کارها") ||
        clean.contains("اهداف") || clean.contains("جدید")

    return when (filter) {
        ChildFilterCategory.ALL -> true
        ChildFilterCategory.NOT_STARTED -> isNotStarted
        ChildFilterCategory.DONE -> isDone
        ChildFilterCategory.IN_PROGRESS -> !isDone && !isNotStarted
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    modifier: Modifier = Modifier
) {
    var currentTitle by remember(workItem.id) {
        mutableStateOf(workItem.title)
    }
    var isEditingTitle by remember(workItem.id) {
        mutableStateOf(false)
    }
    val titleFocusRequester = remember { FocusRequester() }
    var showAllChildren by remember(workItem.id) {
        mutableStateOf(false)
    }
    var selectedChildFilter by remember(workItem.id) {
        mutableStateOf(ChildFilterCategory.ALL)
    }
    var showAllComments by remember(workItem.id) {
        mutableStateOf(false)
    }
    var commentFieldValue by remember(workItem.id) {
        mutableStateOf(TextFieldValue(""))
    }
    var isPostingComment by remember(workItem.id) {
        mutableStateOf(false)
    }
    val commentFocusRequester = remember { FocusRequester() }
    var currentState by remember(workItem.id, workItem.state) {
        mutableStateOf(workItem.state)
    }
    var currentAssignee by remember(workItem.id, workItem.assignedToName) {
        mutableStateOf(workItem.assignedToName?.trim()?.takeIf { it.isNotBlank() } ?: "Unassigned")
    }
    val defaultRootPath = workItem.teamProject?.trim()?.takeIf { it.isNotBlank() }
        ?: availableAreas.firstOrNull()
        ?: "Project"
    var currentAreaPath by remember(workItem.id, workItem.areaPath) {
        mutableStateOf(
            normalizeHierarchyPathUI(workItem.areaPath).ifBlank { defaultRootPath }
        )
    }
    var currentIterationPath by remember(workItem.id, workItem.iterationPath) {
        mutableStateOf(
            normalizeHierarchyPathUI(workItem.iterationPath).ifBlank { defaultRootPath }
        )
    }
    var currentTags by remember(workItem.id, workItem.tags) {
        mutableStateOf(workItem.tags.map { it.trim() }.filter { it.isNotBlank() })
    }
    var currentDescription by remember(workItem.id) {
        mutableStateOf(workItem.description ?: "")
    }

    var stateMenuExpanded by remember { mutableStateOf(false) }
    var assigneeMenuExpanded by remember { mutableStateOf(false) }
    var showAreaPickerDialog by remember { mutableStateOf(false) }
    var showIterationPickerDialog by remember { mutableStateOf(false) }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var customTagInput by remember { mutableStateOf("") }

    var showAddChildOptionDialog by remember { mutableStateOf(false) }
    var showAddChildDialog by remember { mutableStateOf(false) }
    var showAddExistingChildDialog by remember { mutableStateOf(false) }
    var existingChildIdInput by remember { mutableStateOf("") }
    var existingChildError by remember { mutableStateOf<String?>(null) }
    var isLinkingExistingChild by remember { mutableStateOf(false) }

    var newChildTitleInput by remember { mutableStateOf("") }
    var newChildSelectedType by remember(workItem.id, allowedChildTypes) {
        mutableStateOf(allowedChildTypes.firstOrNull() ?: "کار")
    }
    val newChildTypeStates = remember(newChildSelectedType, allowedChildTypes) {
        getStatesForChildType(newChildSelectedType).ifEmpty {
            if (newChildSelectedType.contains("کار")) listOf("کارها", "امروز", "تکمیل", "منتفی")
            else listOf("To Do", "In Progress", "Done")
        }
    }
    var newChildSelectedState by remember(newChildSelectedType, newChildTypeStates) {
        mutableStateOf(newChildTypeStates.firstOrNull() ?: "New")
    }

    // Ensure child creation dialogs are closed if the item type is at the last level of the hierarchy
    LaunchedEffect(allowedChildTypes) {
        if (allowedChildTypes.isEmpty()) {
            showAddChildOptionDialog = false
            showAddChildDialog = false
            showAddExistingChildDialog = false
        }
    }

    var showAddParentOptionDialog by remember { mutableStateOf(false) }
    var showAddParentDialog by remember { mutableStateOf(false) }
    var showAddExistingParentDialog by remember { mutableStateOf(false) }
    var showReplaceParentConfirmDialog by remember { mutableStateOf(false) }
    var existingParentIdInput by remember { mutableStateOf("") }
    var existingParentError by remember { mutableStateOf<String?>(null) }
    var isLinkingExistingParent by remember { mutableStateOf(false) }

    var newParentTitleInput by remember { mutableStateOf("") }
    var newParentSelectedType by remember(workItem.id, allowedParentTypes) {
        mutableStateOf(allowedParentTypes.firstOrNull() ?: "")
    }
    val newParentTypeStates = remember(newParentSelectedType, allowedParentTypes) {
        getStatesForParentType(newParentSelectedType).ifEmpty {
            listOf("New", "In Progress", "Done")
        }
    }
    var newParentSelectedState by remember(newParentSelectedType, newParentTypeStates) {
        mutableStateOf(newParentTypeStates.firstOrNull() ?: "New")
    }

    LaunchedEffect(allowedParentTypes, canHaveParent) {
        if (!canHaveParent || allowedParentTypes.isEmpty()) {
            showAddParentOptionDialog = false
            showAddParentDialog = false
            showAddExistingParentDialog = false
            showReplaceParentConfirmDialog = false
        }
    }

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

    val detailScrollState = rememberScrollState()
    LaunchedEffect(workItem.id) {
        detailScrollState.scrollTo(0)
    }

    val descriptionBringIntoViewRequester = remember { BringIntoViewRequester() }
    val commentBringIntoViewRequester = remember { BringIntoViewRequester() }
    var isDescriptionFocused by remember(workItem.id) { mutableStateOf(false) }
    var isCommentFocused by remember(workItem.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    // Pre-indexed people directory for O(1)-allocation @mention autocomplete
    val indexedMentionPeople = remember(availableAssignees, allWorkItems, comments) {
        buildMentionPeopleIndex(
            availableAssignees = availableAssignees,
            allWorkItems = allWorkItems,
            comments = comments
        )
    }

    val activeMentionToken = remember(commentFieldValue.text, commentFieldValue.selection) {
        extractActiveMentionToken(commentFieldValue)
    }

    // Trigger people suggestions as soon as '@' is typed with 1+ character filter
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

    LaunchedEffect(isDescriptionFocused, isImeVisible, currentDescription.length) {
        if (isDescriptionFocused) {
            try {
                delay(60)
                descriptionBringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(isCommentFocused, isImeVisible, isMentionBarVisible, commentFieldValue.text.length) {
        if (isCommentFocused || isMentionBarVisible) {
            try {
                delay(60)
                commentBringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {}
        }
    }

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
        // 1. Top Bar: < Detail ... [X]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
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

        // Elevated White Container Sheet
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
                    .verticalScroll(detailScrollState)
                    .navigationBarsPadding()
                    .padding(
                        start = 20.dp,
                        top = 22.dp,
                        end = 20.dp,
                        bottom = if (isMentionBarVisible) 116.dp else 28.dp
                    )
            ) {
                // Title (touch to edit) and Counter/ID
                val isDetailTitleRtl = isAlmostPersian(currentTitle.ifBlank { workItem.title })
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
                                    onValueChange = { updatedTitle ->
                                        currentTitle = updatedTitle
                                        val effectiveTitle = updatedTitle.trim().ifBlank { workItem.title }
                                        onUpdateWorkItem(
                                            workItem.copy(
                                                title = effectiveTitle,
                                                state = currentState,
                                                assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                areaPath = currentAreaPath,
                                                iterationPath = currentIterationPath,
                                                tags = currentTags,
                                                description = currentDescription
                                            )
                                        )
                                    },
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
                                                if (currentTitle.isBlank()) {
                                                    currentTitle = workItem.title
                                                }
                                                isEditingTitle = false
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
                                            if (currentTitle.isBlank()) {
                                                currentTitle = workItem.title
                                            }
                                            isEditingTitle = false
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
                                    text = currentTitle.ifBlank { workItem.title },
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
                            workItemType = workItem.workItemType,
                            iconSize = 18.dp,
                            modifier = Modifier.testTag("detail_type_symbol_${workItem.id}")
                        )
                        Text(
                            text = "#${workItem.id}",
                            color = NeumorphicTeal,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Side-by-side below Title: State (Single Selected) & Assigned To (Single Selected)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_metadata_row")
                ) {
                    // (1) State (Single Selected) — matched to Azure Boards state color with high-contrast text
                    Box {
                        val stateColor = getAzureStateColor(currentState)
                        val stateBg = getAzureStateBadgeBg(currentState)
                        val stateText = getAzureStateBadgeText(currentState)

                        Surface(
                            onClick = { stateMenuExpanded = true },
                            color = stateBg,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
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
                                        onUpdateWorkItem(
                                            workItem.copy(
                                                title = currentTitle.trim().ifBlank { workItem.title },
                                                state = stateOption,
                                                assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                areaPath = currentAreaPath,
                                                iterationPath = currentIterationPath,
                                                tags = currentTags,
                                                description = currentDescription
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // (2) Assigned To (Single Selected) — Unassigned + instant type-to-search people
                    Box {
                        Surface(
                            onClick = { assigneeMenuExpanded = true },
                            color = SurfaceCard,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
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

                Spacer(modifier = Modifier.height(18.dp))

                // 2. Area & Iteration Section (placed before Description section)
                ClassificationHierarchySection(
                    areaPath = currentAreaPath,
                    iterationPath = currentIterationPath,
                    onAreaClick = { showAreaPickerDialog = true },
                    onIterationClick = { showIterationPickerDialog = true },
                    testTagPrefix = "detail"
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 3. Description section (Editable, with RTL support for Persian text)
                Text(
                    text = "Description",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isDetailDescRtl = isAlmostPersian(currentDescription.ifBlank { workItem.title })
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isDetailDescRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = currentDescription,
                        onValueChange = { updatedText ->
                            currentDescription = updatedText
                            onUpdateWorkItem(
                                workItem.copy(
                                    title = currentTitle.trim().ifBlank { workItem.title },
                                    state = currentState,
                                    assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                    areaPath = currentAreaPath,
                                    iterationPath = currentIterationPath,
                                    tags = currentTags,
                                    description = currentDescription
                                )
                            )
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
                            }
                            .testTag("detail_description_input")
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 3. Tags section below Description section
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
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
                                            currentTags = currentTags.filterNot { it == tag }
                                            onUpdateWorkItem(
                                                workItem.copy(
                                                    title = currentTitle.trim().ifBlank { workItem.title },
                                                    state = currentState,
                                                    assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                    areaPath = currentAreaPath,
                                                    iterationPath = currentIterationPath,
                                                    tags = currentTags,
                                                    description = currentDescription
                                                )
                                            )
                                        }
                                )
                            }
                        }
                    }

                    // Plus button for searching/adding tags (instant autocomplete)
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

                // 3.5 Parent section (placed right before Children section) — omitted for highest backlog level (e.g. Epic)
                if (canHaveParent) {
                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Parent",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Add or replace parent item button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEEF2F8))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                                .clickable {
                                    if (parentWorkItem != null) {
                                        showReplaceParentConfirmDialog = true
                                    } else {
                                        showAddParentOptionDialog = true
                                    }
                                }
                                .testTag("detail_add_parent_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = if (parentWorkItem != null) "Change parent item" else "Add parent item",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (parentWorkItem == null) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_parent_empty")
                        ) {
                            Text(
                                text = "No parent work item linked",
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    } else {
                        ParentWorkItemStatusRow(
                            parent = parentWorkItem,
                            onNavigateToParent = {
                                onNavigateToParent(parentWorkItem)
                            },
                            onChangeParent = {
                                showReplaceParentConfirmDialog = true
                            },
                            onUnlinkParent = {
                                onUnlinkParentWorkItem(parentWorkItem.id)
                            }
                        )
                    }
                }

                // 4. Children section at the bottom of the detail page — omitted for last-level hierarchy types
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
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
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

                        // Add child work item button — first shows New Item vs Existing Item options
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEEF2F8))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                                .clickable {
                                    if (allowedChildTypes.isNotEmpty()) {
                                        showAddChildOptionDialog = true
                                    }
                                }
                                .testTag("detail_add_child_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add child item",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (childWorkItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        // Horizontal filter chips without item count badges
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 2.dp)
                                .testTag("detail_children_filter_row")
                        ) {
                            ChildFilterCategory.entries.forEach { category ->
                                val isSelected = selectedChildFilter == category
                                Surface(
                                    onClick = { selectedChildFilter = category },
                                    color = if (isSelected) NeumorphicTeal else Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(20.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) NeumorphicTeal else SurfaceBorder
                                    ),
                                    shadowElevation = if (isSelected) 2.dp else 0.dp,
                                    modifier = Modifier.testTag("detail_children_filter_${category.name.lowercase()}")
                                ) {
                                    Text(
                                        text = category.label,
                                        color = if (isSelected) Color.White else Color(0xFF334155),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val filteredChildWorkItems = remember(childWorkItems, selectedChildFilter) {
                        if (selectedChildFilter == ChildFilterCategory.ALL) {
                            childWorkItems
                        } else {
                            childWorkItems.filter { matchesChildFilter(it.state, selectedChildFilter) }
                        }
                    }

                    if (childWorkItems.isEmpty()) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_children_empty")
                        ) {
                            Text(
                                text = "No child work items",
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    } else if (filteredChildWorkItems.isEmpty()) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_children_filter_empty")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "No ${selectedChildFilter.label.lowercase()} child items",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 13.sp
                                )
                                TextButton(
                                    onClick = { selectedChildFilter = ChildFilterCategory.ALL },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = "Show All",
                                        color = NeumorphicTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        val displayedChildren = if (showAllChildren) {
                            filteredChildWorkItems
                        } else {
                            filteredChildWorkItems.take(3)
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_children_section")
                        ) {
                            displayedChildren.forEach { child ->
                                ChildWorkItemStatusRow(
                                    child = child,
                                    availableStates = getChildStates(child),
                                    onStateChange = { newState ->
                                        onUpdateWorkItem(child.copy(state = newState))
                                    },
                                    onRemoveChild = {
                                        onRemoveChildWorkItem(child.id)
                                    },
                                    onNavigateToChild = {
                                        onNavigateToChild(child)
                                    }
                                )
                            }

                            if (!showAllChildren && filteredChildWorkItems.size > 3) {
                                OutlinedButton(
                                    onClick = { showAllChildren = true },
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color(0xFFF8FAFC),
                                        contentColor = NeumorphicTeal
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("detail_more_children_button")
                                ) {
                                    Text(
                                        text = "More Children (${filteredChildWorkItems.size - 3})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Discussion Section (placed directly below Children section)
                Spacer(modifier = Modifier.height(22.dp))
                HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(18.dp))

                // Discussion Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_discussion_header")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Forum,
                            contentDescription = null,
                            tint = NeumorphicTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Discussion",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (comments.isNotEmpty()) {
                            Surface(
                                color = Color(0xFFEEF2F8),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
                            ) {
                                Text(
                                    text = comments.size.toString(),
                                    color = Color(0xFF0F172A),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (isLoadingComments) {
                        CircularProgressIndicator(
                            color = NeumorphicTeal,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(16.dp)
                                .testTag("detail_discussion_loading")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Top Comment Composer Box (placed at top of Discussion section, above comments)
                val isCommentInputRtl = isAlmostPersian(commentFieldValue.text.ifBlank { workItem.title })
                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (matchingMentionPeople.isNotEmpty()) NeumorphicTeal else SurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(commentBringIntoViewRequester)
                        .testTag("detail_discussion_composer_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (isCommentInputRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        ) {
                            OutlinedTextField(
                                value = commentFieldValue,
                                onValueChange = { updatedValue ->
                                    commentFieldValue = updatedValue
                                },
                                placeholder = {
                                    Text(
                                        text = "Add a comment... Type @ to mention people",
                                        color = NeumorphicTextSecondary,
                                        fontSize = 13.sp,
                                        textAlign = if (isCommentInputRtl) TextAlign.Right else TextAlign.Left,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                },
                                textStyle = TextStyle(
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    textAlign = if (isCommentInputRtl) TextAlign.Right else TextAlign.Left,
                                    textDirection = if (isCommentInputRtl) TextDirection.Rtl else TextDirection.Ltr
                                ),
                                minLines = 2,
                                maxLines = 5,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeumorphicTeal,
                                    unfocusedBorderColor = SurfaceBorder,
                                    focusedContainerColor = SurfaceCard,
                                    unfocusedContainerColor = SurfaceCard,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    cursorColor = NeumorphicTeal
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(commentFocusRequester)
                                    .onFocusChanged { focusState ->
                                        isCommentFocused = focusState.isFocused
                                    }
                                    .testTag("detail_comment_input")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Composer Action Bar: '@ Mention' helper chip on left, 'Comment' submit button on right
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                onClick = {
                                    val text = commentFieldValue.text
                                    val cursor = commentFieldValue.selection.end.coerceIn(0, text.length)
                                    val needsSpace = cursor > 0 && !text[cursor - 1].isWhitespace()
                                    val prefix = if (needsSpace) " @" else "@"
                                    val updatedText = buildString(text.length + prefix.length) {
                                        append(text, 0, cursor)
                                        append(prefix)
                                        append(text, cursor, text.length)
                                    }
                                    val newCursor = cursor + prefix.length
                                    commentFieldValue = TextFieldValue(
                                        text = updatedText,
                                        selection = TextRange(newCursor)
                                    )
                                    commentFocusRequester.requestFocus()
                                },
                                color = SurfaceCard,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                                modifier = Modifier.testTag("detail_insert_mention_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AlternateEmail,
                                        contentDescription = "Mention someone",
                                        tint = NeumorphicTeal,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Mention",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            val canSubmitComment = commentFieldValue.text.trim().isNotEmpty() && !isPostingComment
                            Button(
                                onClick = {
                                    val textToSubmit = commentFieldValue.text.trim()
                                    if (textToSubmit.isNotEmpty() && !isPostingComment) {
                                        isPostingComment = true
                                        commentFieldValue = TextFieldValue("")
                                        onAddComment(textToSubmit) {
                                            isPostingComment = false
                                        }
                                    }
                                },
                                enabled = canSubmitComment,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 7.dp
                                ),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeumorphicTeal,
                                    contentColor = Color.White,
                                    disabledContainerColor = Color(0xFFE2E8F0),
                                    disabledContentColor = Color(0xFF94A3B8)
                                ),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("detail_post_comment_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Post comment",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Comment",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Discussion Comments List (Newest First, Max 3 Initially + "More Comments" button)
                val knownPeopleNames = remember(indexedMentionPeople) {
                    indexedMentionPeople.map { it.displayName }
                }

                if (comments.isEmpty()) {
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail_discussion_empty")
                    ) {
                        Text(
                            text = if (isLoadingComments) "Loading discussion..." else "No comments yet. Start the discussion above.",
                            color = NeumorphicTextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    val displayedComments = remember(comments, showAllComments) {
                        if (showAllComments) comments else comments.take(3)
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail_discussion_section")
                    ) {
                        displayedComments.forEach { comment ->
                            DiscussionCommentCard(
                                comment = comment,
                                knownPeopleNames = knownPeopleNames
                            )
                        }

                        if (!showAllComments && comments.size > 3) {
                            OutlinedButton(
                                onClick = { showAllComments = true },
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFF8FAFC),
                                    contentColor = NeumorphicTeal
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("detail_more_comments_button")
                            ) {
                                Text(
                                    text = "More Comments (${comments.size - 3})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Step 1 Dialog: Choose between "New Item" or "Existing Item"
    if (showAddChildOptionDialog && allowedChildTypes.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showAddChildOptionDialog = false },
            title = {
                Text(
                    text = "Add Child Item",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_child_options_dialog")
                ) {
                    Surface(
                        onClick = {
                            showAddChildOptionDialog = false
                            newChildTitleInput = ""
                            newChildSelectedType = allowedChildTypes.firstOrNull() ?: "Task"
                            showAddChildDialog = true
                        },
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_child_option_new")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(NeumorphicTeal.copy(alpha = 0.14f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = NeumorphicTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "New Item",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Create a new child work item",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            showAddChildOptionDialog = false
                            existingChildIdInput = ""
                            existingChildError = null
                            isLinkingExistingChild = false
                            showAddExistingChildDialog = true
                        },
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_child_option_existing")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(NeumorphicTeal.copy(alpha = 0.14f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = NeumorphicTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Existing Item",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Add an existing work item by ID",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddChildOptionDialog = false }) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Step 2B Dialog: Add Existing Item by ID
    if (showAddExistingChildDialog && allowedChildTypes.isNotEmpty()) {
        val parsedCandidateId = existingChildIdInput.trim().removePrefix("#").trim().toIntOrNull()
        val matchedCandidate = remember(parsedCandidateId, allWorkItems) {
            if (parsedCandidateId == null) null
            else allWorkItems.firstOrNull { it.id == parsedCandidateId }
        }

        AlertDialog(
            onDismissRequest = {
                if (!isLinkingExistingChild) {
                    showAddExistingChildDialog = false
                }
            },
            title = {
                Text(
                    text = "Add Existing Child",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Enter the Work Item ID to link as a child of #${workItem.id}:",
                        color = NeumorphicTextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = existingChildIdInput,
                        onValueChange = { updated ->
                            existingChildIdInput = updated
                            existingChildError = null
                        },
                        placeholder = {
                            Text(
                                text = "Work item ID (e.g. 1234)",
                                color = NeumorphicTextSecondary,
                                fontSize = 14.sp
                            )
                        },
                        isError = existingChildError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                            .testTag("existing_child_id_input")
                    )

                    if (matchedCandidate != null) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    AzureWorkItemTypeSymbol(
                                        workItemType = matchedCandidate.workItemType,
                                        iconSize = 15.dp
                                    )
                                    Text(
                                        text = "#${matchedCandidate.id}",
                                        color = NeumorphicTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "· ${matchedCandidate.state}",
                                        color = NeumorphicTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = matchedCandidate.title,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (existingChildError != null) {
                        Text(
                            text = existingChildError!!,
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("existing_child_error_text")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isLinkingExistingChild,
                    onClick = {
                        val targetId = existingChildIdInput.trim().removePrefix("#").trim().toIntOrNull()
                        when {
                            targetId == null || targetId <= 0 -> {
                                existingChildError = "Please enter a valid numeric work item ID."
                            }
                            targetId == workItem.id -> {
                                existingChildError = "A work item cannot be added as a child of itself."
                            }
                            targetId == workItem.parentId -> {
                                existingChildError = "Work item #$targetId is the parent of this item."
                            }
                            childWorkItems.any { it.id == targetId } -> {
                                existingChildError = "Work item #$targetId is already a child of this item."
                            }
                            else -> {
                                isLinkingExistingChild = true
                                existingChildError = null
                                onAddExistingChildWorkItem(targetId) { result ->
                                    isLinkingExistingChild = false
                                    result.fold(
                                        onSuccess = {
                                            showAddExistingChildDialog = false
                                        },
                                        onFailure = { err ->
                                            existingChildError = err.message ?: "Failed to link work item #$targetId."
                                        }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_existing_child_button")
                ) {
                    if (isLinkingExistingChild) {
                        CircularProgressIndicator(
                            color = NeumorphicTeal,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Add", color = NeumorphicTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLinkingExistingChild,
                    onClick = { showAddExistingChildDialog = false }
                ) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showAddChildDialog && allowedChildTypes.isNotEmpty()) {
        val isNewChildTitleRtl = isAlmostPersian(newChildTitleInput.ifBlank { workItem.title })
        AlertDialog(
            onDismissRequest = { showAddChildDialog = false },
            title = {
                Text(
                    text = "New Child (${newChildSelectedType})",
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
                                    border = androidx.compose.foundation.BorderStroke(
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
                                .testTag("new_child_title_input")
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
                                    border = androidx.compose.foundation.BorderStroke(
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
                        val trimmedTitle = newChildTitleInput.trim()
                        if (trimmedTitle.isNotEmpty()) {
                            onAddChildWorkItem(trimmedTitle, newChildSelectedType, newChildSelectedState)
                            showAddChildDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_child_button")
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

    // Step 0 Dialog: Confirm replacing existing parent
    if (showReplaceParentConfirmDialog && canHaveParent) {
        AlertDialog(
            onDismissRequest = { showReplaceParentConfirmDialog = false },
            title = {
                Text(
                    text = "Replace Parent Link?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Work item #${workItem.id} is currently linked to parent #${parentWorkItem?.id}. Do you want to replace it with a new parent?",
                    color = NeumorphicTextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceParentConfirmDialog = false
                        showAddParentOptionDialog = true
                    },
                    modifier = Modifier.testTag("confirm_replace_parent_button")
                ) {
                    Text("Replace", color = NeumorphicTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceParentConfirmDialog = false }) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Step 1 Parent Dialog: Choose between "New Item" or "Existing Item"
    if (showAddParentOptionDialog && canHaveParent) {
        AlertDialog(
            onDismissRequest = { showAddParentOptionDialog = false },
            title = {
                Text(
                    text = if (parentWorkItem != null) "Change Parent Item" else "Add Parent Item",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_parent_options_dialog")
                ) {
                    Surface(
                        onClick = {
                            showAddParentOptionDialog = false
                            newParentTitleInput = ""
                            newParentSelectedType = allowedParentTypes.firstOrNull() ?: ""
                            showAddParentDialog = true
                        },
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_parent_option_new")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(NeumorphicTeal.copy(alpha = 0.14f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = NeumorphicTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "New Item",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Create a new parent work item",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            showAddParentOptionDialog = false
                            existingParentIdInput = ""
                            existingParentError = null
                            isLinkingExistingParent = false
                            showAddExistingParentDialog = true
                        },
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_parent_option_existing")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(NeumorphicTeal.copy(alpha = 0.14f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = NeumorphicTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Existing Item",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Add an existing work item by ID",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddParentOptionDialog = false }) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Step 2B Parent Dialog: Add Existing Parent Item by ID
    if (showAddExistingParentDialog && canHaveParent) {
        val parsedParentCandidateId = existingParentIdInput.trim().removePrefix("#").trim().toIntOrNull()
        val matchedParentCandidate = remember(parsedParentCandidateId, allWorkItems) {
            if (parsedParentCandidateId == null) null
            else allWorkItems.firstOrNull { it.id == parsedParentCandidateId }
        }

        AlertDialog(
            onDismissRequest = {
                if (!isLinkingExistingParent) {
                    showAddExistingParentDialog = false
                }
            },
            title = {
                Text(
                    text = "Add Existing Parent",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Enter the Work Item ID to link as parent of #${workItem.id}:",
                        color = NeumorphicTextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = existingParentIdInput,
                        onValueChange = { updated ->
                            existingParentIdInput = updated
                            existingParentError = null
                        },
                        placeholder = {
                            Text(
                                text = "Work item ID (e.g. 1234)",
                                color = NeumorphicTextSecondary,
                                fontSize = 14.sp
                            )
                        },
                        isError = existingParentError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                            .testTag("existing_parent_id_input")
                    )

                    if (matchedParentCandidate != null) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    AzureWorkItemTypeSymbol(
                                        workItemType = matchedParentCandidate.workItemType,
                                        iconSize = 15.dp
                                    )
                                    Text(
                                        text = "#${matchedParentCandidate.id}",
                                        color = NeumorphicTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "· ${matchedParentCandidate.state}",
                                        color = NeumorphicTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = matchedParentCandidate.title,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (existingParentError != null) {
                        Text(
                            text = existingParentError!!,
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("existing_parent_error_text")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isLinkingExistingParent,
                    onClick = {
                        val targetId = existingParentIdInput.trim().removePrefix("#").trim().toIntOrNull()
                        when {
                            targetId == null || targetId <= 0 -> {
                                existingParentError = "Please enter a valid numeric work item ID."
                            }
                            targetId == workItem.id -> {
                                existingParentError = "A work item cannot be its own parent."
                            }
                            targetId in workItem.childIds -> {
                                existingParentError = "Work item #$targetId is a child of this item and cannot be its parent."
                            }
                            targetId == workItem.parentId -> {
                                existingParentError = "Work item #$targetId is already the parent of this item."
                            }
                            else -> {
                                isLinkingExistingParent = true
                                existingParentError = null
                                onAddExistingParentWorkItem(targetId) { result ->
                                    isLinkingExistingParent = false
                                    result.fold(
                                        onSuccess = {
                                            showAddExistingParentDialog = false
                                        },
                                        onFailure = { err ->
                                            existingParentError = err.message ?: "Failed to link work item #$targetId."
                                        }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_existing_parent_button")
                ) {
                    if (isLinkingExistingParent) {
                        CircularProgressIndicator(
                            color = NeumorphicTeal,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Set Parent", color = NeumorphicTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLinkingExistingParent,
                    onClick = { showAddExistingParentDialog = false }
                ) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Step 2A Parent Dialog: Create New Parent Item
    if (showAddParentDialog && canHaveParent) {
        val isNewParentTitleRtl = isAlmostPersian(newParentTitleInput.ifBlank { workItem.title })
        AlertDialog(
            onDismissRequest = { showAddParentDialog = false },
            title = {
                Text(
                    text = "New Parent (${newParentSelectedType})",
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
                    if (allowedParentTypes.size > 1) {
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
                            allowedParentTypes.forEach { parentTypeOption ->
                                val isTypeSelected = parentTypeOption == newParentSelectedType
                                Surface(
                                    onClick = { newParentSelectedType = parentTypeOption },
                                    color = if (isTypeSelected) NeumorphicTeal.copy(alpha = 0.14f) else Color(0xFFF8FAFC),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isTypeSelected) NeumorphicTeal else SurfaceBorder
                                    )
                                ) {
                                    Text(
                                        text = parentTypeOption,
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
                        LocalLayoutDirection provides if (isNewParentTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        OutlinedTextField(
                            value = newParentTitleInput,
                            onValueChange = { newParentTitleInput = it },
                            placeholder = {
                                Text(
                                    text = "Enter parent title...",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 14.sp,
                                    textAlign = if (isNewParentTitleRtl) TextAlign.Right else TextAlign.Left,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = if (isNewParentTitleRtl) TextAlign.Right else TextAlign.Left,
                                textDirection = if (isNewParentTitleRtl) TextDirection.Rtl else TextDirection.Ltr
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
                                .testTag("new_parent_title_input")
                        )
                    }

                    if (newParentTypeStates.isNotEmpty()) {
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
                            newParentTypeStates.forEach { stateOption ->
                                val isStateSelected = stateOption == newParentSelectedState
                                val stateColor = getAzureStateColor(stateOption)
                                val stateBg = getAzureStateBadgeBg(stateOption)
                                val stateText = getAzureStateBadgeText(stateOption)
                                Surface(
                                    onClick = { newParentSelectedState = stateOption },
                                    color = if (isStateSelected) stateBg else Color(0xFFF8FAFC),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
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
                        val trimmedTitle = newParentTitleInput.trim()
                        if (trimmedTitle.isNotEmpty()) {
                            onAddParentWorkItem(trimmedTitle, newParentSelectedType, newParentSelectedState)
                            showAddParentDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_parent_button")
                ) {
                    Text("Create Parent", color = NeumorphicTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddParentDialog = false }) {
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
            testTagPrefix = "detail",
            onSelectAssignee = { selected ->
                currentAssignee = selected
                onUpdateWorkItem(
                    workItem.copy(
                        title = currentTitle.trim().ifBlank { workItem.title },
                        state = currentState,
                        assignedToName = selected.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                        areaPath = currentAreaPath,
                        iterationPath = currentIterationPath,
                        tags = currentTags,
                        description = currentDescription
                    )
                )
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
                onUpdateWorkItem(
                    workItem.copy(
                        title = currentTitle.trim().ifBlank { workItem.title },
                        state = currentState,
                        assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                        areaPath = resolved,
                        iterationPath = currentIterationPath,
                        tags = currentTags,
                        description = currentDescription
                    )
                )
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
                onUpdateWorkItem(
                    workItem.copy(
                        title = currentTitle.trim().ifBlank { workItem.title },
                        state = currentState,
                        assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                        areaPath = currentAreaPath,
                        iterationPath = resolved,
                        tags = currentTags,
                        description = currentDescription
                    )
                )
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
                    currentTags = currentTags + tagOption
                    onUpdateWorkItem(
                        workItem.copy(
                            title = currentTitle.trim().ifBlank { workItem.title },
                            state = currentState,
                            assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                            areaPath = currentAreaPath,
                            iterationPath = currentIterationPath,
                            tags = currentTags,
                            description = currentDescription
                        )
                    )
                }
                showAddTagDialog = false
            },
            onConfirmAddTag = { trimmed ->
                val canonical = projectTagOptions.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
                if (canonical.isNotEmpty() && currentTags.none { it.equals(canonical, ignoreCase = true) }) {
                    currentTags = currentTags + canonical
                    onUpdateWorkItem(
                        workItem.copy(
                            title = currentTitle.trim().ifBlank { workItem.title },
                            state = currentState,
                            assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                            areaPath = currentAreaPath,
                            iterationPath = currentIterationPath,
                            tags = currentTags,
                            description = currentDescription
                        )
                    )
                }
                showAddTagDialog = false
            },
            onDismiss = { showAddTagDialog = false }
        )
    }

    // Floating Mention Suggestions Bar docked directly above the software keyboard (IME)
    AnimatedVisibility(
        visible = activeMentionToken != null && matchingMentionPeople.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            color = SurfaceCard,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, NeumorphicTeal),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("floating_mention_suggestions_bar")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = null,
                            tint = NeumorphicTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (activeMentionToken?.query?.trim().isNullOrEmpty()) "Mention someone" else "Matching \"@${activeMentionToken?.query?.trim()}\"",
                            color = NeumorphicTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Tap to insert",
                        color = NeumorphicTextSecondary,
                        fontSize = 11.sp
                    )
                }
                HorizontalDivider(
                    color = SurfaceBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp)
                ) {
                    matchingMentionPeople.forEach { candidate ->
                        Surface(
                            onClick = {
                                val currentText = commentFieldValue.text
                                val token = activeMentionToken ?: return@Surface
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
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeumorphicTeal.copy(alpha = 0.35f)),
                            modifier = Modifier.testTag("floating_mention_chip_${candidate.displayName}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(NeumorphicTeal.copy(alpha = 0.16f))
                                ) {
                                    Text(
                                        text = candidate.initial,
                                        color = NeumorphicTeal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = candidate.displayName,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (!candidate.email.isNullOrBlank()) {
                                        Text(
                                            text = candidate.email,
                                            color = NeumorphicTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun WorkItemCard(
    workItem: WorkItem,
    onClick: () -> Unit,
    availableStates: List<String> = emptyList(),
    onUpdateState: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val assignee = workItem.assignedToName?.trim()?.takeIf { it.isNotBlank() }
        ?: "Unassigned"

    val description = workItem.description?.takeIf { it.isNotBlank() }
        ?: workItem.title

    var stateMenuExpanded by remember { mutableStateOf(false) }

    val stateOptions = remember(availableStates, workItem.state, workItem.workItemType) {
        val cleanAvailable = availableStates.filterNot { it.equals("All", ignoreCase = true) }
        val defaults = when {
            workItem.workItemType.contains("هدف") -> listOf("اهداف", "در راه", "تکمیل")
            workItem.workItemType.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
            else -> listOf("New", "Active", "Resolved", "Closed", "Done")
        }
        ((if (cleanAvailable.isNotEmpty()) cleanAvailable else defaults) + workItem.state)
            .filterNot { it.equals("All", ignoreCase = true) }
            .distinct()
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFDCE1EF), RoundedCornerShape(12.dp))
            .testTag("work_item_card_${workItem.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            val isTitleRtl = isAlmostPersian(workItem.title)
            val isDescriptionRtl = isAlmostPersian(description)

            // Top Row: Title on the left, Work Item #ID on the right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (isTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        Text(
                            text = workItem.title,
                            color = Color(0xFF0D101C),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            style = TextStyle(
                                textDirection = if (isTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AzureWorkItemTypeSymbol(
                        workItemType = workItem.workItemType,
                        iconSize = 16.dp,
                        modifier = Modifier.testTag("work_item_type_symbol_${workItem.id}")
                    )
                    Text(
                        text = "#${workItem.id}",
                        color = NeumorphicTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("work_item_id_${workItem.id}")
                    )
                }
            }

            // Description
            Spacer(modifier = Modifier.height(6.dp))
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isDescriptionRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Text(
                    text = description,
                    color = Color(0xFF475569),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    style = TextStyle(
                        textDirection = if (isDescriptionRtl) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom metadata row: Assignee on the left, State Badge Pill on the right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Assigned To with Person Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonOutline,
                        contentDescription = "Assignee",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = assignee,
                        color = Color(0xFF334155),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right: State Badge Pill with high-contrast text and interactive state selector
                val stateColor = getAzureStateColor(workItem.state)
                val badgeBg = getAzureStateBadgeBg(workItem.state)
                val badgeText = getAzureStateBadgeText(workItem.state)

                Box {
                    Surface(
                        onClick = {
                            if (onUpdateState != null && stateOptions.size > 1) {
                                stateMenuExpanded = true
                            } else {
                                onClick()
                            }
                        },
                        color = badgeBg,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("work_item_card_state_badge_${workItem.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = if (onUpdateState != null) 8.dp else 12.dp,
                                top = 5.dp,
                                bottom = 5.dp
                            )
                        ) {
                            Text(
                                text = workItem.state,
                                color = badgeText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (onUpdateState != null) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Change state for #${workItem.id}",
                                    tint = badgeText,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    if (onUpdateState != null) {
                        DropdownMenu(
                            expanded = stateMenuExpanded,
                            onDismissRequest = { stateMenuExpanded = false },
                            modifier = Modifier
                                .background(SurfaceCard)
                                .testTag("work_item_state_menu_${workItem.id}")
                        ) {
                            stateOptions.forEach { stateOption ->
                                val isCurrent = stateOption.equals(workItem.state, ignoreCase = true)
                                val optColor = getAzureStateColor(stateOption)
                                val optBg = getAzureStateBadgeBg(stateOption)
                                val optText = getAzureStateBadgeText(stateOption)

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Surface(
                                                color = optBg,
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, optColor.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = stateOption,
                                                    color = optText,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                            if (isCurrent) {
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = NeumorphicTeal,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        stateMenuExpanded = false
                                        if (!isCurrent) {
                                            onUpdateState(stateOption)
                                        }
                                    },
                                    modifier = Modifier.testTag("work_item_${workItem.id}_state_option_${stateOption}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ParentWorkItemStatusRow(
    parent: WorkItem,
    onNavigateToParent: () -> Unit,
    onChangeParent: () -> Unit,
    onUnlinkParent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stateColor = getAzureStateColor(parent.state)
    val stateBg = getAzureStateBadgeBg(parent.state)
    val stateText = getAzureStateBadgeText(parent.state)
    val isTitleRtl = isAlmostPersian(parent.title)

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onNavigateToParent() }
            .testTag("parent_work_item_row_${parent.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AzureWorkItemTypeSymbol(
                        workItemType = parent.workItemType.ifBlank { "Work Item" },
                        iconSize = 15.dp,
                        modifier = Modifier.testTag("parent_type_symbol_${parent.id}")
                    )
                    Text(
                        text = "#${parent.id}",
                        color = NeumorphicTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (parent.state.isNotBlank()) {
                        Surface(
                            color = stateBg,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = parent.state,
                                color = stateText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Text(
                        text = parent.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (isTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onChangeParent,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("change_parent_button_${parent.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Change parent",
                        tint = NeumorphicTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onUnlinkParent,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("unlink_parent_button_${parent.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Unlink parent",
                        tint = NeumorphicTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChildWorkItemStatusRow(
    child: WorkItem,
    availableStates: List<String>,
    onStateChange: (String) -> Unit,
    onRemoveChild: (() -> Unit)? = null,
    onNavigateToChild: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentChildState by remember(child.id, child.state) {
        mutableStateOf(child.state)
    }
    var stateMenuExpanded by remember { mutableStateOf(false) }

    val stateOptions = remember(availableStates, currentChildState, child.workItemType) {
        val cleanAvailable = availableStates.filterNot { it.equals("All", ignoreCase = true) }
        val defaults = when {
            child.workItemType.contains("هدف") -> listOf("اهداف", "در راه", "تکمیل")
            child.workItemType.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
            else -> listOf("To Do", "In Progress", "Active", "Done", "New")
        }
        ((if (cleanAvailable.isNotEmpty()) cleanAvailable else defaults) + currentChildState)
            .filterNot { it.equals("All", ignoreCase = true) }
            .distinct()
    }

    val isTitleRtl = isAlmostPersian(child.title)

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onNavigateToChild != null) {
                    Modifier.clickable { onNavigateToChild() }
                } else {
                    Modifier
                }
            )
            .testTag("child_work_item_row_${child.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AzureWorkItemTypeSymbol(
                        workItemType = child.workItemType,
                        iconSize = 15.dp,
                        modifier = Modifier.testTag("child_type_symbol_${child.id}")
                    )
                    Text(
                        text = "#${child.id}",
                        color = NeumorphicTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Text(
                        text = child.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (isTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Interactive status selector dropdown for the child work item
                Box {
                    val stateColor = getAzureStateColor(currentChildState)
                    val stateBg = getAzureStateBadgeBg(currentChildState)
                    val stateText = getAzureStateBadgeText(currentChildState)

                    Surface(
                        onClick = { stateMenuExpanded = true },
                        color = stateBg,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("child_state_selector_${child.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentChildState,
                                color = stateText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Change child status",
                                tint = stateText,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = stateMenuExpanded,
                        onDismissRequest = { stateMenuExpanded = false },
                        containerColor = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
                    ) {
                        stateOptions.forEach { stateOption ->
                            val isSelected = stateOption.equals(currentChildState, ignoreCase = true)
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
                                    currentChildState = stateOption
                                    onStateChange(stateOption)
                                }
                            )
                        }
                    }
                }

                // Simple 'X' button to unlink/remove child from its parent
                if (onRemoveChild != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEEF2F8))
                            .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                            .clickable { onRemoveChild() }
                            .testTag("remove_child_button_${child.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove child #${child.id}",
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    Surface(
        color = NeumorphicBg,
        modifier = modifier
            .fillMaxSize()
            .testTag("work_item_detail_screen")
    ) {
        androidx.activity.compose.BackHandler {
            onBack()
        }
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
            modifier = Modifier.fillMaxSize()
        )
    }
}

internal data class MentionCandidate(
    val userId: String? = null,
    val displayName: String,
    val email: String?,
    val lowerName: String,
    val lowerEmail: String?,
    val initial: String
)

internal data class MentionToken(
    val startIndex: Int,
    val endIndex: Int,
    val query: String
)

internal fun buildMentionPeopleIndex(
    availableAssignees: List<String>,
    allWorkItems: List<WorkItem>,
    comments: List<WorkItemComment>
): List<MentionCandidate> {
    data class MutablePersonEntry(
        var userId: String?,
        val displayName: String,
        var email: String?
    )
    val byLowerName = LinkedHashMap<String, MutablePersonEntry>()

    fun register(rawId: String?, rawName: String?, rawEmail: String?, rawAvatar: String? = null) {
        val cleanName = rawName?.trim().orEmpty()
        if (cleanName.isBlank() ||
            cleanName.equals("Unassigned", ignoreCase = true) ||
            cleanName.equals("All", ignoreCase = true) ||
            cleanName.equals("You", ignoreCase = true)
        ) {
            return
        }
        val cleanId = rawId?.trim()?.takeIf { it.isNotBlank() }
        val cleanEmail = rawEmail?.trim()?.takeIf { it.isNotBlank() }
        com.example.data.repository.AzureDevOpsRepository.registerKnownIdentity(
            id = cleanId,
            displayName = cleanName,
            email = cleanEmail,
            avatarUrl = rawAvatar
        )

        val key = cleanName.lowercase()
        val existing = byLowerName[key]
        if (existing == null) {
            val resolvedRecord = com.example.data.repository.AzureDevOpsRepository.resolveIdentityRecord(cleanName)
            byLowerName[key] = MutablePersonEntry(
                userId = cleanId ?: resolvedRecord?.id,
                displayName = cleanName,
                email = cleanEmail ?: resolvedRecord?.email
            )
        } else {
            if (existing.userId == null && cleanId != null) {
                existing.userId = cleanId
            }
            if (existing.email == null && cleanEmail != null) {
                existing.email = cleanEmail
            }
        }
    }

    for (name in availableAssignees) {
        register(null, name, null)
    }
    for (item in allWorkItems) {
        register(item.assignedToId, item.assignedToName, item.assignedToEmail, item.assignedToAvatar)
    }
    for (comment in comments) {
        register(comment.authorId, comment.authorName, comment.authorEmail, comment.authorAvatar)
    }

    return byLowerName.values
        .map { entry ->
            val firstChar = entry.displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            MentionCandidate(
                userId = entry.userId,
                displayName = entry.displayName,
                email = entry.email,
                lowerName = entry.displayName.lowercase(),
                lowerEmail = entry.email?.lowercase(),
                initial = firstChar
            )
        }
        .sortedBy { it.lowerName }
}

internal fun extractActiveMentionToken(textFieldValue: TextFieldValue): MentionToken? {
    val text = textFieldValue.text
    if (text.isEmpty()) return null
    val cursor = textFieldValue.selection.end.coerceIn(0, text.length)
    if (cursor == 0) return null

    var idx = cursor - 1
    while (idx >= 0) {
        val ch = text[idx]
        // Stop if we hit a newline or the closing '>' of an already-completed @<member> mention
        if (ch == '\n' || ch == '\r' || ch == '>') {
            return null
        }
        if (ch == '@') {
            val validBoundary = idx == 0 ||
                text[idx - 1].isWhitespace() ||
                text[idx - 1] in listOf('(', '[', '{', ',', ';', ':', '-', '/')
            if (!validBoundary) return null

            val rawQuery = text.substring(idx + 1, cursor)
            if (rawQuery.contains('>')) return null
            val query = rawQuery.removePrefix("<")
            // Stop treating as an active mention if user typed two consecutive spaces or finished a long phrase
            if (query.contains("  ") || query.length > 35) return null
            return MentionToken(
                startIndex = idx,
                endIndex = cursor,
                query = query
            )
        }
        idx--
    }
    return null
}

internal fun formatCommentWithMentions(
    text: String,
    knownPeopleNames: List<String>
): AnnotatedString {
    if (!text.contains('@')) {
        return AnnotatedString(text)
    }
    val mentionStyle = SpanStyle(
        color = NeumorphicTeal,
        fontWeight = FontWeight.Bold,
        background = NeumorphicTeal.copy(alpha = 0.12f)
    )
    val sortedNames = knownPeopleNames.sortedByDescending { it.length }

    return buildAnnotatedString {
        var searchIndex = 0
        while (searchIndex < text.length) {
            val atIndex = text.indexOf('@', searchIndex)
            if (atIndex < 0) {
                append(text.substring(searchIndex))
                break
            }

            if (atIndex > searchIndex) {
                append(text.substring(searchIndex, atIndex))
            }

            // 1. Check for Azure DevOps Markdown bracketed mention: @<member> or @<GUID>
            if (atIndex + 1 < text.length && text[atIndex + 1] == '<') {
                val closeBracketIndex = text.indexOf('>', atIndex + 2)
                val newlineIndex = text.indexOf('\n', atIndex + 2)
                if (closeBracketIndex > atIndex + 2 && (newlineIndex < 0 || closeBracketIndex < newlineIndex)) {
                    val innerToken = text.substring(atIndex + 2, closeBracketIndex).trim()
                    val resolvedName = com.example.data.repository.AzureDevOpsRepository
                        .resolveIdentityRecord(innerToken)
                        ?.displayName
                        ?: innerToken.removePrefix("@").trim()
                    withStyle(mentionStyle) {
                        append("@$resolvedName")
                    }
                    searchIndex = closeBracketIndex + 1
                    continue
                }
            }

            // 2. Check for known person display name after '@'
            val afterAt = text.substring(atIndex + 1)
            val matchedKnownName = sortedNames.firstOrNull { candidateName ->
                afterAt.startsWith(candidateName, ignoreCase = true) &&
                    (afterAt.length == candidateName.length ||
                        !afterAt[candidateName.length].isLetterOrDigit())
            }

            if (matchedKnownName != null) {
                val actualSlice = text.substring(atIndex, atIndex + 1 + matchedKnownName.length)
                withStyle(mentionStyle) {
                    append(actualSlice)
                }
                searchIndex = atIndex + 1 + matchedKnownName.length
            } else {
                // 3. Highlight single-token @handle if not in knownPeopleNames
                var tokenEnd = atIndex + 1
                while (tokenEnd < text.length &&
                    (text[tokenEnd].isLetterOrDigit() || text[tokenEnd] in listOf('_', '.', '-'))
                ) {
                    tokenEnd++
                }
                if (tokenEnd > atIndex + 1) {
                    withStyle(mentionStyle) {
                        append(text.substring(atIndex, tokenEnd))
                    }
                    searchIndex = tokenEnd
                } else {
                    append('@')
                    searchIndex = atIndex + 1
                }
            }
        }
    }
}

@Composable
private fun DiscussionCommentCard(
    comment: WorkItemComment,
    knownPeopleNames: List<String>,
    modifier: Modifier = Modifier
) {
    val isRtl = isAlmostPersian(comment.text)
    val initial = remember(comment.authorName) {
        comment.authorName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"
    }
    val annotatedBody = remember(comment.text, knownPeopleNames) {
        formatCommentWithMentions(comment.text, knownPeopleNames)
    }

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("discussion_comment_card_${comment.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(NeumorphicTeal.copy(alpha = 0.14f))
                    ) {
                        Text(
                            text = initial,
                            color = NeumorphicTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = comment.authorName,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = comment.formattedDate,
                    color = NeumorphicTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Text(
                    text = annotatedBody,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Start,
                    style = TextStyle(
                        textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("discussion_comment_text_${comment.id}")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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

    // Tracks the parent work item once auto-saved (e.g., when adding a child work item)
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
    var tagMenuExpanded by remember { mutableStateOf(false) }
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
    var isCreateDescriptionFocused by remember { mutableStateOf(false) }
    val createDensity = LocalDensity.current
    val isCreateImeVisible = WindowInsets.ime.getBottom(createDensity) > 0

    LaunchedEffect(isCreateDescriptionFocused, isCreateImeVisible, currentDescription.length) {
        if (isCreateDescriptionFocused) {
            try {
                delay(60)
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
        // Top Bar: < Create New Item   ...   [Create / Done]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
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

        // Elevated White Container Sheet matching FigmaItemDetailView
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
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                // 1. First Essential Field: Work Item Type Capsule Selector at top of Create page
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
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

                // 2. Second Essential Field: Title (Required)
                Text(
                    text = "Title *",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isTitleRtl = isAlmostPersian(titleInput)
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

                // 3. State & Assignee Row (matching Detail page)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_metadata_row")
                ) {
                    // State Selector
                    Box {
                        val stateColor = getAzureStateColor(currentState)
                        val stateBg = getAzureStateBadgeBg(currentState)
                        val stateText = getAzureStateBadgeText(currentState)

                        Surface(
                            onClick = { stateMenuExpanded = true },
                            color = stateBg,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
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

                    // Assignee Selector (Unassigned + instant type-to-search people)
                    Box {
                        Surface(
                            onClick = { assigneeMenuExpanded = true },
                            color = SurfaceCard,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
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

                // 4. Area & Iteration Section (placed before Description section)
                ClassificationHierarchySection(
                    areaPath = currentAreaPath,
                    iterationPath = currentIterationPath,
                    onAreaClick = { showAreaPickerDialog = true },
                    onIterationClick = { showIterationPickerDialog = true },
                    testTagPrefix = "create"
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 5. Description Section (matching Detail page)
                Text(
                    text = "Description",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isDescRtl = isAlmostPersian(currentDescription.ifBlank { titleInput })
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
                            }
                            .testTag("create_description_input")
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 5. Tags Section (matching Detail page)
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
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

                // 6. Children Section — Auto-saves parent item first when adding a child
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
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
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

                        // Add new child work item button
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
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
                                    border = androidx.compose.foundation.BorderStroke(
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
                                    border = androidx.compose.foundation.BorderStroke(
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
                                // Auto-save parent work item first, then immediately add the child work item
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

@Composable
private fun AssigneeSearchDialog(
    currentAssignee: String,
    availableAssignees: List<String>,
    testTagPrefix: String,
    onSelectAssignee: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val trimmedQuery = searchQuery.trim()
    val isQueryRtl = isAlmostPersian(trimmedQuery)

    val realAssignees = remember(availableAssignees) {
        availableAssignees
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) && !it.equals("Unassigned", ignoreCase = true) }
            .distinct()
    }

    val staticOptions = remember(currentAssignee) {
        val activePerson = currentAssignee.trim().takeIf {
            it.isNotBlank() && !it.equals("All", ignoreCase = true) && !it.equals("Unassigned", ignoreCase = true)
        }
        listOfNotNull("Unassigned", activePerson).distinct()
    }

    val matchingAssignees = remember(realAssignees, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            realAssignees
        } else {
            realAssignees.filter { it.contains(trimmedQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Assignee",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
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
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = NeumorphicTeal,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = NeumorphicTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                            textDirection = if (isQueryRtl) TextDirection.Rtl else TextDirection.Ltr
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
                            unfocusedContainerColor = SurfaceCard
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("${testTagPrefix}_assignee_search_input")
                    )
                }

                // Always visible quick options: Unassigned (plus current assignee if assigned)
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    staticOptions.forEach { option ->
                        val isSelected = option.equals(currentAssignee, ignoreCase = true)
                        Surface(
                            onClick = {
                                onSelectAssignee(option)
                                onDismiss()
                            },
                            color = if (isSelected) NeumorphicTeal.copy(alpha = 0.12f) else Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) NeumorphicTeal else SurfaceBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("${testTagPrefix}_assignee_option_${option}")
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
                                    color = if (isSelected) NeumorphicTeal else TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = NeumorphicTeal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)

                if (matchingAssignees.isEmpty() && trimmedQuery.isNotEmpty()) {
                    Text(
                        text = "No matching assignees found.",
                        color = NeumorphicTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("${testTagPrefix}_assignee_no_matches")
                    )
                } else if (matchingAssignees.isNotEmpty()) {
                    Text(
                        text = if (trimmedQuery.isEmpty()) "Team members (${matchingAssignees.size})" else "Matching assignees (${matchingAssignees.size})",
                        color = NeumorphicTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("${testTagPrefix}_assignee_suggestions_list")
                    ) {
                        matchingAssignees.forEach { option ->
                            val isSelected = option.equals(currentAssignee, ignoreCase = true)
                            Surface(
                                onClick = {
                                    onSelectAssignee(option)
                                    onDismiss()
                                },
                                color = if (isSelected) NeumorphicTeal.copy(alpha = 0.12f) else SurfaceCard,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) NeumorphicTeal else SurfaceBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("${testTagPrefix}_assignee_suggestion_${option}")
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
                                        color = if (isSelected) NeumorphicTeal else TextPrimary,
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
                                            tint = NeumorphicTeal,
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
            TextButton(onClick = onDismiss) {
                Text("Close", color = NeumorphicTextSecondary)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun TagSearchAndAddDialog(
    customTagInput: String,
    onCustomTagInputChange: (String) -> Unit,
    fallbackRtlText: String,
    currentTags: List<String>,
    projectTagOptions: List<String>,
    inputTestTag: String,
    onSelectExistingTag: (String) -> Unit,
    onConfirmAddTag: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val trimmedQuery = customTagInput.trim()
    val isTagInputRtl = isAlmostPersian(customTagInput.ifBlank { fallbackRtlText })

    val matchingTags = remember(projectTagOptions, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            projectTagOptions
        } else {
            projectTagOptions.filter { it.contains(trimmedQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Tag",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isTagInputRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = customTagInput,
                        onValueChange = onCustomTagInputChange,
                        placeholder = {
                            Text(
                                text = "Search or create tag...",
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                textAlign = if (isTagInputRtl) TextAlign.Right else TextAlign.Left,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = NeumorphicTeal,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = if (customTagInput.isNotEmpty()) {
                            {
                                IconButton(onClick = { onCustomTagInputChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear tag input",
                                        tint = NeumorphicTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = if (isTagInputRtl) TextAlign.Right else TextAlign.Left,
                            textDirection = if (isTagInputRtl) TextDirection.Rtl else TextDirection.Ltr
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
                            .testTag(inputTestTag)
                    )
                }

                if (matchingTags.isEmpty() && trimmedQuery.isNotEmpty()) {
                    Text(
                        text = "No matching tags found. Tap Add to create \"$trimmedQuery\".",
                        color = NeumorphicTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .testTag("${inputTestTag}_no_matches")
                    )
                } else if (matchingTags.isEmpty()) {
                    Text(
                        text = "Type a name and tap Add to create a new tag.",
                        color = NeumorphicTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .testTag("${inputTestTag}_min_chars_hint")
                    )
                } else {
                    Text(
                        text = if (trimmedQuery.isEmpty()) "Existing tags in project (${matchingTags.size})" else "Matching tags (${matchingTags.size})",
                        color = NeumorphicTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("${inputTestTag}_suggestions_list")
                    ) {
                        matchingTags.forEach { tagOption ->
                            val isSelected = currentTags.any { it.equals(tagOption, ignoreCase = true) }
                            Surface(
                                onClick = { onSelectExistingTag(tagOption) },
                                color = if (isSelected) NeumorphicTeal.copy(alpha = 0.12f) else Color(0xFFF8FAFC),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) NeumorphicTeal else SurfaceBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("${inputTestTag}_suggestion_${tagOption}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Sell,
                                            contentDescription = null,
                                            tint = if (isSelected) NeumorphicTeal else Color(0xFF475569),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = tagOption,
                                            color = if (isSelected) NeumorphicTeal else TextPrimary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = NeumorphicTeal,
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
        confirmButton = {
            TextButton(
                enabled = trimmedQuery.isNotEmpty(),
                onClick = { onConfirmAddTag(trimmedQuery) }
            ) {
                Text(
                    text = "Add",
                    color = if (trimmedQuery.isNotEmpty()) NeumorphicTeal else NeumorphicTextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NeumorphicTextSecondary)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(16.dp)
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    Surface(
        color = NeumorphicBg,
        modifier = modifier
            .fillMaxSize()
            .testTag("work_item_create_screen")
    ) {
        androidx.activity.compose.BackHandler {
            onDismiss()
        }
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
            modifier = Modifier.fillMaxSize()
        )
    }
}

fun normalizeHierarchyPathUI(rawPath: String?): String {
    if (rawPath.isNullOrBlank()) return ""
    return rawPath
        .replace('/', '\\')
        .split('\\')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\\")
}

fun extractHierarchyLeafName(rawPath: String?): String {
    val norm = normalizeHierarchyPathUI(rawPath)
    if (norm.isEmpty()) return "Root"
    return norm.substringAfterLast('\\')
}

fun formatHierarchyBreadcrumb(rawPath: String?): String {
    val norm = normalizeHierarchyPathUI(rawPath)
    if (norm.isEmpty()) return ""
    return norm.split('\\').joinToString(" › ")
}

fun extractParentBreadcrumb(rawPath: String?): String? {
    val norm = normalizeHierarchyPathUI(rawPath)
    if (!norm.contains('\\')) return null
    return norm.substringBeforeLast('\\').split('\\').joinToString(" › ")
}

@Composable
fun ClassificationHierarchySection(
    areaPath: String,
    iterationPath: String,
    onAreaClick: () -> Unit,
    onIterationClick: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_classification_section")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Area & Iteration",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap to browse tree",
                color = NeumorphicTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ClassificationNodeCard(
                label = "Area",
                icon = Icons.Default.AccountTree,
                fullPath = areaPath,
                onClick = onAreaClick,
                testTag = "${testTagPrefix}_area_selector",
                modifier = Modifier.weight(1f)
            )

            ClassificationNodeCard(
                label = "Iteration",
                icon = Icons.Default.CalendarToday,
                fullPath = iterationPath,
                onClick = onIterationClick,
                testTag = "${testTagPrefix}_iteration_selector",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ClassificationNodeCard(
    label: String,
    icon: ImageVector,
    fullPath: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val leafName = remember(fullPath) { extractHierarchyLeafName(fullPath) }
    val parentBreadcrumb = remember(fullPath) { extractParentBreadcrumb(fullPath) }
    val fullBreadcrumb = remember(fullPath) { formatHierarchyBreadcrumb(fullPath) }
    val isRtl = isAlmostPersian(fullPath)

    Surface(
        onClick = onClick,
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier.testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(NeumorphicTeal.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = NeumorphicTeal,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = label,
                        color = NeumorphicTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select $label",
                    tint = NeumorphicTextSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Text(
                    text = leafName,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = parentBreadcrumb ?: fullBreadcrumb.ifBlank { "Root" },
                    color = if (parentBreadcrumb != null) NeumorphicTeal else NeumorphicTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

data class HierarchyTreeNode(
    val fullPath: String,
    val name: String,
    val depth: Int,
    val parentPath: String?,
    val children: List<HierarchyTreeNode>
)

private fun buildHierarchyTreeNodes(
    availablePaths: List<String>,
    selectedPath: String?,
    fallbackRootProject: String?
): List<HierarchyTreeNode> {
    val canonicalByLower = LinkedHashMap<String, String>()

    fun addWithAncestors(raw: String?) {
        val norm = normalizeHierarchyPathUI(raw)
        if (norm.isEmpty()) return
        val parts = norm.split('\\')
        var prefix = ""
        for (part in parts) {
            prefix = if (prefix.isEmpty()) part else "$prefix\\$part"
            val key = prefix.lowercase()
            if (!canonicalByLower.containsKey(key)) {
                canonicalByLower[key] = prefix
            }
        }
    }

    addWithAncestors(fallbackRootProject)
    availablePaths.forEach { addWithAncestors(it) }
    addWithAncestors(selectedPath)

    if (canonicalByLower.isEmpty()) {
        addWithAncestors("Project")
    }

    val allPaths = canonicalByLower.values.toList()
    val childrenByParentLower = mutableMapOf<String?, MutableList<String>>()

    for (path in allPaths) {
        val parentLower = if (path.contains('\\')) {
            path.substringBeforeLast('\\').lowercase()
        } else {
            null
        }
        val effectiveParentLower = if (parentLower != null && canonicalByLower.containsKey(parentLower)) {
            parentLower
        } else {
            null
        }
        childrenByParentLower.getOrPut(effectiveParentLower) { mutableListOf() }.add(path)
    }

    fun buildNode(path: String, depth: Int): HierarchyTreeNode {
        val leafName = path.substringAfterLast('\\')
        val parentPath = if (path.contains('\\')) path.substringBeforeLast('\\') else null
        val childPaths = childrenByParentLower[path.lowercase()]
            .orEmpty()
            .sortedBy { it.substringAfterLast('\\').lowercase() }
        return HierarchyTreeNode(
            fullPath = path,
            name = leafName,
            depth = depth,
            parentPath = parentPath,
            children = childPaths.map { childPath -> buildNode(childPath, depth + 1) }
        )
    }

    val rootPaths = childrenByParentLower[null]
        .orEmpty()
        .sortedWith { a, b ->
            val cleanRoot = normalizeHierarchyPathUI(fallbackRootProject)
            val aIsProj = cleanRoot.isNotEmpty() && a.equals(cleanRoot, ignoreCase = true)
            val bIsProj = cleanRoot.isNotEmpty() && b.equals(cleanRoot, ignoreCase = true)
            when {
                aIsProj && !bIsProj -> -1
                !aIsProj && bIsProj -> 1
                else -> a.lowercase().compareTo(b.lowercase())
            }
        }

    return rootPaths.map { buildNode(it, 0) }
}

private fun computeInitialExpandedLowerPaths(
    roots: List<HierarchyTreeNode>,
    selectedPath: String?
): Set<String> {
    val expanded = mutableSetOf<String>()
    // Expand root level nodes by default
    for (root in roots) {
        expanded.add(root.fullPath.lowercase())
    }
    // Expand all ancestors of the currently selected path
    val normSelected = normalizeHierarchyPathUI(selectedPath)
    if (normSelected.isNotEmpty()) {
        val parts = normSelected.split('\\')
        var prefix = ""
        for (i in 0 until parts.size) {
            prefix = if (prefix.isEmpty()) parts[i] else "$prefix\\${parts[i]}"
            expanded.add(prefix.lowercase())
        }
    }
    return expanded
}

private fun collectAllExpandableLowerPaths(nodes: List<HierarchyTreeNode>): Set<String> {
    val result = mutableSetOf<String>()
    fun walk(node: HierarchyTreeNode) {
        if (node.children.isNotEmpty()) {
            result.add(node.fullPath.lowercase())
            node.children.forEach { walk(it) }
        }
    }
    nodes.forEach { walk(it) }
    return result
}

@Composable
fun HierarchyTreePickerDialog(
    title: String,
    fieldLabel: String,
    icon: ImageVector,
    availablePaths: List<String>,
    selectedPath: String?,
    fallbackRootProject: String?,
    allowAllOption: Boolean,
    testTagPrefix: String,
    onSelectPath: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val trimmedQuery = searchQuery.trim()
    val isQueryRtl = isAlmostPersian(trimmedQuery)

    val rootNodes = remember(availablePaths, selectedPath, fallbackRootProject) {
        buildHierarchyTreeNodes(
            availablePaths = availablePaths,
            selectedPath = selectedPath,
            fallbackRootProject = fallbackRootProject
        )
    }

    val allExpandableLower = remember(rootNodes) {
        collectAllExpandableLowerPaths(rootNodes)
    }

    var expandedLowerPaths by remember(rootNodes, selectedPath) {
        mutableStateOf(computeInitialExpandedLowerPaths(rootNodes, selectedPath))
    }

    // Flatten visible tree rows based on search query or expanded state
    val visibleTreeNodes = remember(rootNodes, expandedLowerPaths, trimmedQuery) {
        val lowerQuery = trimmedQuery.lowercase()
        val matchedAndAncestorsLower = mutableSetOf<String>()

        if (lowerQuery.isNotEmpty()) {
            fun checkMatch(node: HierarchyTreeNode): Boolean {
                val selfMatches = node.name.lowercase().contains(lowerQuery) ||
                    node.fullPath.lowercase().contains(lowerQuery)
                var anyChildMatches = false
                for (child in node.children) {
                    if (checkMatch(child)) {
                        anyChildMatches = true
                    }
                }
                if (selfMatches || anyChildMatches) {
                    matchedAndAncestorsLower.add(node.fullPath.lowercase())
                }
                return selfMatches || anyChildMatches
            }
            rootNodes.forEach { checkMatch(it) }
        }

        val flattened = mutableListOf<HierarchyTreeNode>()
        fun traverse(node: HierarchyTreeNode) {
            val lowerPath = node.fullPath.lowercase()
            if (lowerQuery.isNotEmpty() && lowerPath !in matchedAndAncestorsLower) {
                return
            }
            flattened.add(node)
            val isExpanded = if (lowerQuery.isNotEmpty()) {
                true
            } else {
                lowerPath in expandedLowerPaths
            }
            if (isExpanded) {
                node.children.forEach { traverse(it) }
            }
        }
        rootNodes.forEach { traverse(it) }
        flattened
    }

    val normSelected = remember(selectedPath) { normalizeHierarchyPathUI(selectedPath) }
    val isAllSelected = allowAllOption && normSelected.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(NeumorphicTeal.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = NeumorphicTeal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                if (allExpandableLower.isNotEmpty() && trimmedQuery.isEmpty()) {
                    val allExpanded = expandedLowerPaths.containsAll(allExpandableLower)
                    TextButton(
                        onClick = {
                            expandedLowerPaths = if (allExpanded) {
                                emptySet()
                            } else {
                                allExpandableLower
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("${testTagPrefix}_expand_collapse_all_button")
                    ) {
                        Text(
                            text = if (allExpanded) "Collapse All" else "Expand All",
                            color = NeumorphicTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Path Preview Banner
                Surface(
                    color = NeumorphicTeal.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeumorphicTeal.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_path_preview")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Selected $fieldLabel Path",
                                color = NeumorphicTeal,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (allowAllOption && normSelected.isNotEmpty()) {
                                Text(
                                    text = "Includes sub-paths",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val previewText = if (isAllSelected) {
                            "All ${fieldLabel}s"
                        } else {
                            formatHierarchyBreadcrumb(normSelected).ifBlank { "Root" }
                        }
                        Text(
                            text = previewText,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Search Bar
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isQueryRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Search ${fieldLabel.lowercase()} hierarchy...",
                                color = NeumorphicTextSecondary,
                                fontSize = 13.sp,
                                textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = NeumorphicTeal,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = NeumorphicTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = if (isQueryRtl) TextAlign.Right else TextAlign.Left,
                            textDirection = if (isQueryRtl) TextDirection.Rtl else TextDirection.Ltr
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
                            unfocusedContainerColor = SurfaceCard
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("${testTagPrefix}_tree_search_input")
                    )
                }

                // Optional "All Areas" / "All Iterations" option for Home filter
                if (allowAllOption) {
                    Surface(
                        onClick = {
                            onSelectPath(null)
                        },
                        color = if (isAllSelected) NeumorphicTeal.copy(alpha = 0.12f) else Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isAllSelected) NeumorphicTeal else SurfaceBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("${testTagPrefix}_option_all")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "All ${fieldLabel}s",
                                    color = if (isAllSelected) NeumorphicTeal else TextPrimary,
                                    fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Show work items across all ${fieldLabel.lowercase()} paths",
                                    color = NeumorphicTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            if (isAllSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = NeumorphicTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = SurfaceBorder, thickness = 1.dp)
                }

                // Expandable Indented Hierarchy Tree List
                if (visibleTreeNodes.isEmpty()) {
                    Text(
                        text = "No matching ${fieldLabel.lowercase()} paths found.",
                        color = NeumorphicTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .testTag("${testTagPrefix}_no_matches")
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("${testTagPrefix}_tree_list")
                    ) {
                        visibleTreeNodes.forEach { node ->
                            val isNodeSelected = !isAllSelected &&
                                normSelected.equals(node.fullPath, ignoreCase = true)
                            val isDescendantSelected = !isAllSelected &&
                                !isNodeSelected &&
                                normSelected.startsWith("${node.fullPath}\\", ignoreCase = true)
                            val hasChildren = node.children.isNotEmpty()
                            val isExpanded = trimmedQuery.isNotEmpty() ||
                                node.fullPath.lowercase() in expandedLowerPaths
                            val indentDp = (node.depth.coerceAtMost(6) * 18).dp

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = indentDp)
                            ) {
                                Surface(
                                    onClick = {
                                        onSelectPath(node.fullPath)
                                    },
                                    color = when {
                                        isNodeSelected -> NeumorphicTeal.copy(alpha = 0.14f)
                                        isDescendantSelected -> NeumorphicTeal.copy(alpha = 0.05f)
                                        else -> Color(0xFFF8FAFC)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = if (isNodeSelected) 1.5.dp else 1.dp,
                                        color = when {
                                            isNodeSelected -> NeumorphicTeal
                                            isDescendantSelected -> NeumorphicTeal.copy(alpha = 0.4f)
                                            else -> SurfaceBorder
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("${testTagPrefix}_node_${node.fullPath}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 6.dp, end = 10.dp, top = 7.dp, bottom = 7.dp)
                                    ) {
                                        if (hasChildren) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        val key = node.fullPath.lowercase()
                                                        expandedLowerPaths = if (key in expandedLowerPaths) {
                                                            expandedLowerPaths - key
                                                        } else {
                                                            expandedLowerPaths + key
                                                        }
                                                    }
                                                    .testTag("${testTagPrefix}_toggle_${node.fullPath}")
                                            ) {
                                                Icon(
                                                    imageVector = if (isExpanded) {
                                                        Icons.Default.KeyboardArrowDown
                                                    } else {
                                                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                                                    },
                                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                                    tint = if (isNodeSelected || isDescendantSelected) {
                                                        NeumorphicTeal
                                                    } else {
                                                        NeumorphicTextSecondary
                                                    },
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        } else {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isNodeSelected) NeumorphicTeal
                                                            else Color(0xFFCBD5E1)
                                                        )
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = node.name,
                                                    color = if (isNodeSelected) NeumorphicTeal else TextPrimary,
                                                    fontWeight = if (isNodeSelected || hasChildren) {
                                                        FontWeight.Bold
                                                    } else {
                                                        FontWeight.Medium
                                                    },
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (hasChildren) {
                                                    Surface(
                                                        color = Color(0xFFEEF2F8),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text(
                                                            text = node.children.size.toString(),
                                                            color = NeumorphicTextSecondary,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            if (node.depth > 0) {
                                                Text(
                                                    text = formatHierarchyBreadcrumb(node.fullPath),
                                                    color = NeumorphicTextSecondary,
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        if (isNodeSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = NeumorphicTeal,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
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
            TextButton(onClick = onDismiss) {
                Text("Close", color = NeumorphicTextSecondary)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(16.dp)
    )
}


