package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Immutable
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
import com.example.ui.theme.NeumorphicTeal
import com.example.ui.theme.NeumorphicTextSecondary
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextPrimary

@Composable
internal fun AssigneeSearchDialog(
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
                            border = BorderStroke(
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
                                border = BorderStroke(
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
internal fun TagSearchAndAddDialog(
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
                                border = BorderStroke(
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

@Immutable
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
    for (root in roots) {
        expanded.add(root.fullPath.lowercase())
    }
    val normSelected = normalizeHierarchyPathUI(selectedPath)
    if (normSelected.isNotEmpty()) {
        val parts = normSelected.split('\\')
        var prefix = ""
        for (i in parts.indices) {
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
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
                Surface(
                    color = NeumorphicTeal.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, NeumorphicTeal.copy(alpha = 0.3f)),
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

                if (allowAllOption) {
                    Surface(
                        onClick = {
                            onSelectPath(null)
                        },
                        color = if (isAllSelected) NeumorphicTeal.copy(alpha = 0.12f) else Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
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
                                    border = BorderStroke(
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
