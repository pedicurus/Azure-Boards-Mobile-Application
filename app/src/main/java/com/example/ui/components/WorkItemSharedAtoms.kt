package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.Dp
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
import com.example.ui.theme.getAzureTypeColor
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
    val isRtl = remember(fullPath) { isAlmostPersian(fullPath) }

    Surface(
        onClick = onClick,
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceBorder),
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

@Composable
fun WorkItemCard(
    workItem: WorkItem,
    onClick: () -> Unit,
    availableStates: List<String> = emptyList(),
    onUpdateState: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val assignee = remember(workItem.assignedToName) {
        workItem.assignedToName?.trim()?.takeIf { it.isNotBlank() } ?: "Unassigned"
    }
    val description = remember(workItem.description, workItem.title) {
        workItem.description?.takeIf { it.isNotBlank() } ?: workItem.title
    }
    val isTitleRtl = remember(workItem.title) { isAlmostPersian(workItem.title) }
    val isDescriptionRtl = remember(description) { isAlmostPersian(description) }

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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
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
                        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
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
                                                border = BorderStroke(1.dp, optColor.copy(alpha = 0.35f))
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
    val isTitleRtl = remember(parent.title) { isAlmostPersian(parent.title) }

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SurfaceBorder),
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
                            border = BorderStroke(1.dp, stateColor.copy(alpha = 0.35f))
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

    val isTitleRtl = remember(child.title) { isAlmostPersian(child.title) }

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SurfaceBorder),
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
                Box {
                    val stateColor = getAzureStateColor(currentChildState)
                    val stateBg = getAzureStateBadgeBg(currentChildState)
                    val stateText = getAzureStateBadgeText(currentChildState)

                    Surface(
                        onClick = { stateMenuExpanded = true },
                        color = stateBg,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.45f)),
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
                        border = BorderStroke(1.dp, SurfaceBorder)
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
