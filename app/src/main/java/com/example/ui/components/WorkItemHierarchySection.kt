package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WorkItem
import com.example.ui.theme.NeumorphicTeal
import com.example.ui.theme.NeumorphicTextSecondary
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.getAzureStateBadgeBg
import com.example.ui.theme.getAzureStateBadgeText
import com.example.ui.theme.getAzureStateColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailParentAndChildrenSection(
    workItem: WorkItem,
    allWorkItems: List<WorkItem>,
    parentWorkItem: WorkItem?,
    childWorkItems: List<WorkItem>,
    allowedChildTypes: List<String>,
    allowedParentTypes: List<String>,
    canHaveParent: Boolean,
    getChildStates: (WorkItem) -> List<String>,
    getStatesForChildType: (String) -> List<String>,
    getStatesForParentType: (String) -> List<String>,
    onAddChildWorkItem: (title: String, childType: String, initialState: String) -> Unit,
    onAddExistingChildWorkItem: (childId: Int, onResult: (Result<WorkItem>) -> Unit) -> Unit,
    onRemoveChildWorkItem: (childId: Int) -> Unit,
    onAddParentWorkItem: (title: String, parentType: String, initialState: String) -> Unit,
    onAddExistingParentWorkItem: (parentId: Int, onResult: (Result<WorkItem>) -> Unit) -> Unit,
    onUnlinkParentWorkItem: (parentId: Int) -> Unit,
    onNavigateToParent: (WorkItem) -> Unit,
    onNavigateToChild: (WorkItem) -> Unit,
    onUpdateWorkItem: (WorkItem) -> Unit
) {
    var showAllChildren by remember(workItem.id) {
        mutableStateOf(false)
    }
    var selectedChildFilter by remember(workItem.id) {
        mutableStateOf(ChildFilterCategory.ALL)
    }

    var showAddChildOptionDialog by remember(workItem.id) { mutableStateOf(false) }
    var showAddChildDialog by remember(workItem.id) { mutableStateOf(false) }
    var showAddExistingChildDialog by remember(workItem.id) { mutableStateOf(false) }
    var existingChildIdInput by remember(workItem.id) { mutableStateOf("") }
    var existingChildError by remember(workItem.id) { mutableStateOf<String?>(null) }
    var isLinkingExistingChild by remember(workItem.id) { mutableStateOf(false) }

    var newChildTitleInput by remember(workItem.id) { mutableStateOf("") }
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

    LaunchedEffect(allowedChildTypes) {
        if (allowedChildTypes.isEmpty()) {
            showAddChildOptionDialog = false
            showAddChildDialog = false
            showAddExistingChildDialog = false
        }
    }

    var showAddParentOptionDialog by remember(workItem.id) { mutableStateOf(false) }
    var showAddParentDialog by remember(workItem.id) { mutableStateOf(false) }
    var showAddExistingParentDialog by remember(workItem.id) { mutableStateOf(false) }
    var showReplaceParentConfirmDialog by remember(workItem.id) { mutableStateOf(false) }
    var existingParentIdInput by remember(workItem.id) { mutableStateOf("") }
    var existingParentError by remember(workItem.id) { mutableStateOf<String?>(null) }
    var isLinkingExistingParent by remember(workItem.id) { mutableStateOf(false) }

    var newParentTitleInput by remember(workItem.id) { mutableStateOf("") }
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
                border = BorderStroke(1.dp, SurfaceBorder),
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
                        border = BorderStroke(
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
                border = BorderStroke(1.dp, SurfaceBorder),
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
                border = BorderStroke(1.dp, SurfaceBorder),
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
            val displayedChildren = remember(filteredChildWorkItems, showAllChildren) {
                if (showAllChildren) filteredChildWorkItems else filteredChildWorkItems.take(3)
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
                        border = BorderStroke(1.dp, SurfaceBorder),
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
                        border = BorderStroke(1.dp, SurfaceBorder),
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
                        border = BorderStroke(1.dp, SurfaceBorder),
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
                            border = BorderStroke(1.dp, SurfaceBorder),
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
                        border = BorderStroke(1.dp, SurfaceBorder),
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
                        border = BorderStroke(1.dp, SurfaceBorder),
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
                            border = BorderStroke(1.dp, SurfaceBorder),
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

    if (showAddParentDialog && canHaveParent) {
        val isNewParentTitleRtl = isAlmostPersian(newParentTitleInput.ifBlank { workItem.title })
        AlertDialog(
            onDismissRequest = { showAddParentDialog = false },
            title = {
                Text(
                    text = "New Parent ($newParentSelectedType)",
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
                                    border = BorderStroke(
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
}
