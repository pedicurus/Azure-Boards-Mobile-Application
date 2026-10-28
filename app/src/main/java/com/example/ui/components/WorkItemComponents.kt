package com.example.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FigmaItemDetailView(
    workItem: WorkItem,
    onBack: () -> Unit,
    onCreateOrUpdate: () -> Unit = {},
    availableStates: List<String> = emptyList(),
    availableAssignees: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
    childWorkItems: List<WorkItem> = emptyList(),
    allowedChildTypes: List<String> = emptyList(),
    getChildStates: (WorkItem) -> List<String> = { emptyList() },
    getStatesForChildType: (String) -> List<String> = { emptyList() },
    onAddChildWorkItem: (title: String, childType: String, initialState: String) -> Unit = { _, _, _ -> },
    onUpdateWorkItem: (WorkItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentState by remember(workItem.id, workItem.state) {
        mutableStateOf(workItem.state)
    }
    var currentAssignee by remember(workItem.id, workItem.assignedToName) {
        mutableStateOf(workItem.assignedToName?.trim()?.takeIf { it.isNotBlank() } ?: "Unassigned")
    }
    var currentTags by remember(workItem.id, workItem.tags) {
        mutableStateOf(workItem.tags.map { it.trim() }.filter { it.isNotBlank() })
    }
    var currentDescription by remember(workItem.id) {
        mutableStateOf(workItem.description ?: "")
    }

    var stateMenuExpanded by remember { mutableStateOf(false) }
    var assigneeMenuExpanded by remember { mutableStateOf(false) }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var customTagInput by remember { mutableStateOf("") }

    var showAddChildDialog by remember { mutableStateOf(false) }
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

    val stateOptions = remember(availableStates, currentState, workItem.workItemType) {
        val defaults = when {
            workItem.workItemType.contains("هدف") -> listOf("اهداف", "در راه", "تکمیل")
            workItem.workItemType.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
            else -> listOf("In Progress", "Active", "Done", "New")
        }
        ((if (availableStates.isNotEmpty()) availableStates else defaults) + currentState).distinct()
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NeumorphicBg)
    ) {
        // 1. Top Bar: < Detail
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
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

        // Elevated White Container Sheet
        Surface(
            color = SurfaceCard,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Title and Counter/ID
                val isDetailTitleRtl = isAlmostPersian(workItem.title)
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (isDetailTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        ) {
                            Text(
                                text = workItem.title,
                                color = TextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 28.sp,
                                textAlign = if (isDetailTitleRtl) TextAlign.Right else TextAlign.Left,
                                style = TextStyle(
                                    textDirection = if (isDetailTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "#${workItem.id}",
                        color = NeumorphicTeal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                                        currentState = stateOption
                                        stateMenuExpanded = false
                                        onUpdateWorkItem(
                                            workItem.copy(
                                                state = currentState,
                                                assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                tags = currentTags,
                                                description = currentDescription
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // (2) Assigned To (Single Selected) — only real project people + Unassigned
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

                        DropdownMenu(
                            expanded = assigneeMenuExpanded,
                            onDismissRequest = { assigneeMenuExpanded = false },
                            containerColor = SurfaceCard,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
                        ) {
                            assigneeOptions.forEach { assigneeOption ->
                                val isSelected = assigneeOption.equals(currentAssignee, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = assigneeOption,
                                                color = if (isSelected) NeumorphicTeal else TextPrimary,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp
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
                                    },
                                    onClick = {
                                        currentAssignee = assigneeOption
                                        assigneeMenuExpanded = false
                                        onUpdateWorkItem(
                                            workItem.copy(
                                                state = currentState,
                                                assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                tags = currentTags,
                                                description = currentDescription
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 2. Description section (Editable, with RTL support for Persian text)
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
                                    state = currentState,
                                    assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
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
                                                    state = currentState,
                                                    assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                    tags = currentTags,
                                                    description = currentDescription
                                                )
                                            )
                                        }
                                )
                            }
                        }
                    }

                    // Plus button for adding/selecting multiple tags
                    Box {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEEF2F8))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                                .clickable { tagMenuExpanded = true }
                                .testTag("detail_add_tag_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add tag",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = tagMenuExpanded,
                            onDismissRequest = { tagMenuExpanded = false },
                            containerColor = SurfaceCard,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
                        ) {
                            projectTagOptions.forEach { tagOption ->
                                val isTagSelected = currentTags.contains(tagOption)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Sell,
                                                    contentDescription = null,
                                                    tint = if (isTagSelected) NeumorphicTeal else Color(0xFF475569),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = tagOption,
                                                    color = if (isTagSelected) NeumorphicTeal else TextPrimary,
                                                    fontWeight = if (isTagSelected) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 13.sp
                                                )
                                            }
                                            if (isTagSelected) {
                                                Spacer(modifier = Modifier.width(8.dp))
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
                                        currentTags = if (isTagSelected) {
                                            currentTags.filterNot { it == tagOption }
                                        } else {
                                            currentTags + tagOption
                                        }
                                        onUpdateWorkItem(
                                            workItem.copy(
                                                state = currentState,
                                                assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                                tags = currentTags,
                                                description = currentDescription
                                            )
                                        )
                                    }
                                )
                            }
                            if (projectTagOptions.isNotEmpty()) {
                                HorizontalDivider(color = SurfaceBorder, thickness = 0.5.dp)
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "+ New Tag",
                                        color = NeumorphicTeal,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    tagMenuExpanded = false
                                    customTagInput = ""
                                    showAddTagDialog = true
                                }
                            )
                        }
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

                        // Add new child work item button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEEF2F8))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                                .clickable {
                                    newChildTitleInput = ""
                                    newChildSelectedType = allowedChildTypes.firstOrNull() ?: "کار"
                                    showAddChildDialog = true
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
                    Spacer(modifier = Modifier.height(10.dp))

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
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_children_section")
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
                                    textAlign = if (isNewChildTitleRtl) TextAlign.Right else TextAlign.Left,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 14.sp,
                                textAlign = if (isNewChildTitleRtl) TextAlign.Right else TextAlign.Left,
                                textDirection = if (isNewChildTitleRtl) TextDirection.Rtl else TextDirection.Ltr
                            ),
                            singleLine = true,
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

    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = {
                Text(
                    text = "New Tag",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = customTagInput,
                    onValueChange = { customTagInput = it },
                    placeholder = { Text("Enter tag name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = customTagInput.trim()
                        if (trimmed.isNotEmpty() && !currentTags.contains(trimmed)) {
                            currentTags = currentTags + trimmed
                            onUpdateWorkItem(
                                workItem.copy(
                                    state = currentState,
                                    assignedToName = currentAssignee.takeUnless { it.equals("Unassigned", ignoreCase = true) },
                                    tags = currentTags,
                                    description = currentDescription
                                )
                            )
                        }
                        showAddTagDialog = false
                    }
                ) {
                    Text("Add", color = NeumorphicTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) {
                    Text("Cancel", color = NeumorphicTextSecondary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun WorkItemCard(
    workItem: WorkItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cleanTags = workItem.tags.map { it.trim() }.filter { it.isNotBlank() }
    val displayTag = if (cleanTags.isNotEmpty()) {
        cleanTags.joinToString(", ")
    } else {
        "No tags"
    }

    val assignee = workItem.assignedToName?.trim()?.takeIf { it.isNotBlank() }
        ?: "Unassigned"

    val description = workItem.description?.takeIf { it.isNotBlank() }
        ?: workItem.title

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
                Text(
                    text = "#${workItem.id}",
                    color = NeumorphicTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("work_item_id_${workItem.id}")
                )
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

            // Bottom metadata row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Left metadata: Tag (with Tag icon) + Assignee
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Tag with Tag Icon (Icons.Outlined.Sell)
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sell,
                            contentDescription = "Tag",
                            tint = Color(0xFF334155),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = displayTag,
                            color = Color(0xFF1E293B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Assigned To with Person Icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically
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
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right: State Badge Pill with high-contrast text
                val stateColor = getAzureStateColor(workItem.state)
                val badgeBg = getAzureStateBadgeBg(workItem.state)
                val badgeText = getAzureStateBadgeText(workItem.state)

                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = workItem.state,
                        color = badgeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
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
    modifier: Modifier = Modifier
) {
    var currentChildState by remember(child.id, child.state) {
        mutableStateOf(child.state)
    }
    var stateMenuExpanded by remember { mutableStateOf(false) }

    val stateOptions = remember(availableStates, currentChildState, child.workItemType) {
        val defaults = when {
            child.workItemType.contains("هدف") -> listOf("اهداف", "در راه", "تکمیل")
            child.workItemType.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
            else -> listOf("To Do", "In Progress", "Active", "Done", "New")
        }
        ((if (availableStates.isNotEmpty()) availableStates else defaults) + currentChildState).distinct()
    }

    val isTitleRtl = isAlmostPersian(child.title)

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "#${child.id}",
                        color = NeumorphicTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = child.workItemType,
                        color = NeumorphicTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
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
                                currentChildState = stateOption
                                stateMenuExpanded = false
                                onStateChange(stateOption)
                            }
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
    availableStates: List<String> = emptyList(),
    availableAssignees: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
    childWorkItems: List<WorkItem> = emptyList(),
    allowedChildTypes: List<String> = emptyList(),
    getChildStates: (WorkItem) -> List<String> = { emptyList() },
    getStatesForChildType: (String) -> List<String> = { emptyList() },
    onAddChildWorkItem: (title: String, childType: String, initialState: String) -> Unit = { _, _, _ -> },
    onUpdateWorkItem: (WorkItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NeumorphicBg,
        modifier = modifier
    ) {
        FigmaItemDetailView(
            workItem = workItem,
            onBack = onDismiss,
            onCreateOrUpdate = onDismiss,
            availableStates = availableStates,
            availableAssignees = availableAssignees,
            availableTags = availableTags,
            childWorkItems = childWorkItems,
            allowedChildTypes = allowedChildTypes,
            getChildStates = getChildStates,
            getStatesForChildType = getStatesForChildType,
            onAddChildWorkItem = onAddChildWorkItem,
            onUpdateWorkItem = onUpdateWorkItem
        )
    }
}
