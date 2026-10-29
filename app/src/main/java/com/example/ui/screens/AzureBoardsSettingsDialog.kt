package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.OrganizationAccount
import com.example.ui.components.readClipboardText
import com.example.ui.components.writeClipboardText
import com.example.ui.theme.TypeBug

private val DialogCardBorderColor = Color(0xFFDCE1EF)
private val DialogTextDark = Color(0xFF0D101C)
private val DialogTextMuted = Color(0xFF6E7591)
private val DialogAccentPurple = Color(0xFF0078D4)

@Composable
fun AzureBoardsSettingsDialog(
    activeOrganization: String,
    organizations: List<OrganizationAccount>,
    defaultOrganization: String?,
    defaultOrgProjects: List<String>,
    availableProjects: List<String> = emptyList(),
    projectsByOrg: Map<String, List<String>> = emptyMap(),
    defaultProjectsByOrg: Map<String, String> = emptyMap(),
    defaultProject: String?,
    selectedProject: String?,
    totalCount: Int,
    onDismiss: () -> Unit,
    onSwitchOrganization: (String) -> Unit,
    onDefaultOrganizationSelect: (String) -> Unit,
    onDefaultProjectSelect: (String) -> Unit,
    onDefaultProjectForOrgSelect: (String, String) -> Unit = { _, _ -> },
    onSaveOrganizationAccount: (
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) -> Unit,
    onRemoveOrganization: (String) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit
) {
    val displayedOrgs = if (organizations.isNotEmpty()) {
        organizations
    } else if (activeOrganization.isNotBlank()) {
        listOf(OrganizationAccount(activeOrganization, "", isRemembered = true))
    } else {
        emptyList()
    }

    val effectiveDefaultOrg = defaultOrganization
        ?: displayedOrgs.firstOrNull()?.organization
        ?: activeOrganization

    fun getProjectsForOrg(orgName: String): List<String> {
        val fromMap = projectsByOrg.entries
            .firstOrNull { it.key.equals(orgName, ignoreCase = true) }
            ?.value
            .orEmpty()
        if (fromMap.isNotEmpty()) return fromMap
        if (orgName.equals(activeOrganization, ignoreCase = true) && availableProjects.isNotEmpty()) {
            return availableProjects
        }
        if (orgName.equals(effectiveDefaultOrg, ignoreCase = true) && defaultOrgProjects.isNotEmpty()) {
            return defaultOrgProjects
        }
        return emptyList()
    }

    fun getDefaultProjectForOrg(orgName: String, orgProjects: List<String>): String? {
        val fromMap = defaultProjectsByOrg.entries
            .firstOrNull { it.key.equals(orgName, ignoreCase = true) }
            ?.value
            ?.takeIf { def -> orgProjects.isEmpty() || orgProjects.any { it.equals(def, ignoreCase = true) } }
        if (!fromMap.isNullOrBlank()) return fromMap
        if (orgName.equals(effectiveDefaultOrg, ignoreCase = true)) {
            val fallbackDef = defaultProject
                ?.takeIf { def -> orgProjects.isEmpty() || orgProjects.any { it.equals(def, ignoreCase = true) } }
            if (!fallbackDef.isNullOrBlank()) return fallbackDef
        }
        return orgProjects.firstOrNull()
            ?: if (orgName.equals(activeOrganization, ignoreCase = true)) selectedProject else null
    }

    val context = LocalContext.current

    var isEditorOpen by remember { mutableStateOf(false) }
    var editingOriginalOrgName by remember { mutableStateOf<String?>(null) }
    var editorOrgInput by remember { mutableStateOf("") }
    var editorPatInput by remember { mutableStateOf("") }
    var editorRemember by remember { mutableStateOf(true) }
    var editorPatVisible by remember { mutableStateOf(false) }
    var editorLoading by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!editorLoading) onDismiss()
        },
        title = {
            Text(
                text = "Azure Boards Settings",
                fontWeight = FontWeight.Bold,
                color = DialogTextDark,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Active Organization: $activeOrganization ($totalCount items)",
                    color = DialogTextMuted,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DialogCardBorderColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // 1. Organizations & Personal Access Tokens Management
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Organizations & PAT",
                        fontWeight = FontWeight.SemiBold,
                        color = DialogTextDark,
                        fontSize = 14.sp
                    )
                    if (!isEditorOpen) {
                        TextButton(
                            onClick = {
                                editingOriginalOrgName = null
                                editorOrgInput = ""
                                editorPatInput = ""
                                editorRemember = true
                                editorPatVisible = false
                                editorError = null
                                isEditorOpen = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("add_organization_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Organization",
                                tint = DialogAccentPurple,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Add Organization",
                                color = DialogAccentPurple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    displayedOrgs.forEach { acc ->
                        val isActive = acc.organization.equals(activeOrganization, ignoreCase = true)
                        val isDefault = acc.organization.equals(effectiveDefaultOrg, ignoreCase = true)
                        Surface(
                            onClick = {
                                if (!isActive) {
                                    onSwitchOrganization(acc.organization)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isActive) DialogAccentPurple else DialogCardBorderColor
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_org_item_${acc.organization}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = acc.organization,
                                            color = DialogTextDark,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isDefault) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = DialogAccentPurple.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "Default",
                                                    color = DialogAccentPurple,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val orgProjs = getProjectsForOrg(acc.organization)
                                    val orgDefProj = getDefaultProjectForOrg(acc.organization, orgProjs)
                                    Text(
                                        text = buildString {
                                            append(if (acc.isRemembered) "Saved on device" else "Session only")
                                            if (!orgDefProj.isNullOrBlank()) {
                                                append(" • Default: $orgDefProj")
                                            }
                                            if (isActive) append(" • Active")
                                        },
                                        color = DialogTextMuted,
                                        fontSize = 11.sp
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            editingOriginalOrgName = acc.organization
                                            editorOrgInput = acc.organization
                                            editorPatInput = acc.pat
                                            editorRemember = acc.isRemembered
                                            editorPatVisible = false
                                            editorError = null
                                            isEditorOpen = true
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("edit_organization_${acc.organization}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit ${acc.organization}",
                                            tint = DialogTextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            onRemoveOrganization(acc.organization)
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("remove_organization_${acc.organization}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Remove ${acc.organization}",
                                            tint = Color(0xFFDC2626),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Inline Add / Edit Organization Form
                AnimatedVisibility(visible = isEditorOpen) {
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, DialogAccentPurple.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag("settings_org_editor_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = if (editingOriginalOrgName == null) "Add Organization" else "Edit $editingOriginalOrgName",
                                color = DialogTextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (editorError != null) {
                                Text(
                                    text = editorError ?: "",
                                    color = TypeBug,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            OutlinedTextField(
                                value = editorOrgInput,
                                onValueChange = {
                                    editorOrgInput = it
                                    editorError = null
                                },
                                label = { Text("Azure DevOps URL", fontSize = 12.sp) },
                                placeholder = {
                                    Text(
                                        "https://dev.azure.com/OrganizationName",
                                        fontSize = 11.sp,
                                        color = DialogTextMuted.copy(alpha = 0.6f)
                                    )
                                },
                                trailingIcon = if (editorOrgInput.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = {
                                                editorOrgInput = ""
                                                editorError = null
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear organization",
                                                tint = DialogTextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                } else null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = DialogAccentPurple,
                                    unfocusedBorderColor = DialogCardBorderColor,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedTextColor = DialogTextDark,
                                    unfocusedTextColor = DialogTextDark
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                                            if ((isCtrlOrMeta && keyEvent.key == Key.V) || keyEvent.key == Key.Paste) {
                                                val pasted = readClipboardText(context)
                                                if (!pasted.isNullOrEmpty()) {
                                                    editorOrgInput = pasted.trim()
                                                    editorError = null
                                                    return@onPreviewKeyEvent true
                                                }
                                            } else if ((isCtrlOrMeta && keyEvent.key == Key.C) || keyEvent.key == Key.Copy) {
                                                if (editorOrgInput.isNotEmpty()) {
                                                    writeClipboardText(context, editorOrgInput)
                                                }
                                            }
                                        }
                                        false
                                    }
                                    .testTag("settings_org_input")
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = editorPatInput,
                                onValueChange = {
                                    editorPatInput = it
                                    editorError = null
                                },
                                label = { Text("Personal Access Token", fontSize = 12.sp) },
                                placeholder = {
                                    Text(
                                        "Paste your Personal Access Token",
                                        fontSize = 11.sp,
                                        color = DialogTextMuted.copy(alpha = 0.6f)
                                    )
                                },
                                singleLine = true,
                                visualTransformation = if (editorPatVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (editorPatInput.isNotEmpty()) {
                                            IconButton(
                                                onClick = {
                                                    editorPatInput = ""
                                                    editorError = null
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Clear,
                                                    contentDescription = "Clear PAT",
                                                    tint = DialogTextMuted,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { editorPatVisible = !editorPatVisible },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (editorPatVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (editorPatVisible) "Hide PAT" else "Show PAT",
                                                tint = DialogTextMuted,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = DialogAccentPurple,
                                    unfocusedBorderColor = DialogCardBorderColor,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedTextColor = DialogTextDark,
                                    unfocusedTextColor = DialogTextDark
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                                            if ((isCtrlOrMeta && keyEvent.key == Key.V) || keyEvent.key == Key.Paste) {
                                                val pasted = readClipboardText(context)
                                                if (!pasted.isNullOrEmpty()) {
                                                    editorPatInput = pasted.trim()
                                                    editorError = null
                                                    return@onPreviewKeyEvent true
                                                }
                                            } else if ((isCtrlOrMeta && keyEvent.key == Key.C) || keyEvent.key == Key.Copy) {
                                                if (editorPatInput.isNotEmpty()) {
                                                    writeClipboardText(context, editorPatInput)
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                        }
                                        false
                                    }
                                    .testTag("settings_pat_input")
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { editorRemember = !editorRemember }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = editorRemember,
                                    onCheckedChange = { editorRemember = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = DialogAccentPurple,
                                        uncheckedColor = DialogTextMuted
                                    ),
                                    modifier = Modifier.testTag("settings_remember_checkbox")
                                )
                                Text(
                                    text = "Remember and save credentials",
                                    color = DialogTextDark,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    onClick = {
                                        if (!editorLoading) {
                                            isEditorOpen = false
                                            editorError = null
                                        }
                                    }
                                ) {
                                    Text("Cancel", color = DialogTextMuted, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        editorLoading = true
                                        editorError = null
                                        onSaveOrganizationAccount(
                                            editingOriginalOrgName,
                                            editorOrgInput,
                                            editorPatInput,
                                            editorRemember
                                        ) { success, errMsg ->
                                            editorLoading = false
                                            if (success) {
                                                isEditorOpen = false
                                            } else {
                                                editorError = errMsg ?: "Failed to save organization."
                                            }
                                        }
                                    },
                                    enabled = !editorLoading,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DialogAccentPurple,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.testTag("settings_save_org_button")
                                ) {
                                    if (editorLoading) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 2. Default Organization Section (when multiple organizations are configured)
                if (displayedOrgs.size > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DialogCardBorderColor, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Default Organization",
                        fontWeight = FontWeight.SemiBold,
                        color = DialogTextDark,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose which organization opens automatically on startup:",
                        color = DialogTextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        displayedOrgs.forEach { acc ->
                            val isDefaultOrg = acc.organization.equals(effectiveDefaultOrg, ignoreCase = true)
                            Surface(
                                onClick = { onDefaultOrganizationSelect(acc.organization) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDefaultOrg) Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isDefaultOrg) DialogAccentPurple else DialogCardBorderColor
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("default_org_option_${acc.organization}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = acc.organization,
                                        color = if (isDefaultOrg) DialogAccentPurple else DialogTextDark,
                                        fontWeight = if (isDefaultOrg) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    if (isDefaultOrg) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Default Organization",
                                            tint = DialogAccentPurple,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Default Project Section (for each organization)
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = DialogCardBorderColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (displayedOrgs.size > 1) "Default Project per Organization" else "Default Project",
                    fontWeight = FontWeight.SemiBold,
                    color = DialogTextDark,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (displayedOrgs.size > 1) {
                        "Choose the default project for each organization:"
                    } else {
                        "Choose the project that opens automatically on startup:"
                    },
                    color = DialogTextMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    displayedOrgs.forEach { acc ->
                        val orgName = acc.organization
                        val orgProjects = getProjectsForOrg(orgName)
                        val orgDefaultProject = getDefaultProjectForOrg(orgName, orgProjects)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (displayedOrgs.size > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    Text(
                                        text = orgName,
                                        color = DialogTextDark,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    if (orgName.equals(effectiveDefaultOrg, ignoreCase = true)) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "(Default Org)",
                                            color = DialogAccentPurple,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            if (orgProjects.isEmpty()) {
                                Text(
                                    text = "No projects found under $orgName.",
                                    color = DialogTextMuted,
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    orgProjects.forEach { project ->
                                        val isDefault = project.equals(orgDefaultProject, ignoreCase = true)
                                        Surface(
                                            onClick = {
                                                onDefaultProjectForOrgSelect(orgName, project)
                                                if (orgName.equals(effectiveDefaultOrg, ignoreCase = true)) {
                                                    onDefaultProjectSelect(project)
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isDefault) Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                                            border = BorderStroke(
                                                width = 1.dp,
                                                color = if (isDefault) DialogAccentPurple else DialogCardBorderColor
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag(
                                                    if (orgName.equals(effectiveDefaultOrg, ignoreCase = true)) {
                                                        "default_project_option_$project"
                                                    } else {
                                                        "default_project_option_${orgName}_$project"
                                                    }
                                                )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = project,
                                                    color = if (isDefault) DialogAccentPurple else DialogTextDark,
                                                    fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 14.sp
                                                )
                                                if (isDefault) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = "Default Project for $orgName",
                                                        tint = DialogAccentPurple,
                                                        modifier = Modifier.size(18.dp)
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
        },
        confirmButton = {
            TextButton(
                enabled = !editorLoading,
                onClick = {
                    if (isEditorOpen && editorOrgInput.isNotBlank() && editorPatInput.isNotBlank()) {
                        editorLoading = true
                        editorError = null
                        onSaveOrganizationAccount(
                            editingOriginalOrgName,
                            editorOrgInput,
                            editorPatInput,
                            editorRemember
                        ) { success, errMsg ->
                            editorLoading = false
                            if (success) {
                                isEditorOpen = false
                                onDismiss()
                            } else {
                                editorError = errMsg ?: "Failed to save organization."
                            }
                        }
                    } else {
                        onRefresh()
                    }
                }
            ) {
                if (editorLoading) {
                    CircularProgressIndicator(
                        color = DialogAccentPurple,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Refresh Data", color = DialogAccentPurple, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) {
                Text("Sign Out All", color = Color(0xFFDC2626))
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}
