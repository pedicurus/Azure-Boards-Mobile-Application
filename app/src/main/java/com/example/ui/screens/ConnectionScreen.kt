package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.readClipboardText
import com.example.ui.components.writeClipboardText
import com.example.ui.theme.TypeBug

private val LoginBgTop = Color(0xFFFFFFFF)
private val LoginBgBottom = Color(0xFFDFE4F1)
private val LoginCardBorder = Color(0xFFDCE1EF)
private val LoginTextDark = Color(0xFF0D101C)
private val LoginTextMuted = Color(0xFF6E7591)
private val LoginAccentPurple = Color(0xFF613BE7)
private val LoginAzureBlue = Color(0xFF0078D4)
private val LoginInputFill = Color(0xFFF8FAFC)

@Composable
fun ConnectionScreen(
    initialOrg: String,
    initialPat: String,
    isLoading: Boolean,
    errorMessage: String?,
    onConnect: (org: String, pat: String, rememberCredentials: Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var orgInput by rememberSaveable { mutableStateOf(initialOrg) }
    var patInput by rememberSaveable { mutableStateOf(initialPat) }
    var rememberCredentials by rememberSaveable { mutableStateOf(true) }
    var patVisible by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(LoginBgTop, LoginBgBottom)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Brand Header with Azure DevOps Blue Emblem
            Surface(
                color = Color.White,
                shape = CircleShape,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, LoginCardBorder),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_azure_devops),
                        contentDescription = "Azure Boards",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Azure Boards",
                color = LoginTextDark,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            Text(
                text = "Sign in with your Organization and Personal Access Token",
                color = LoginTextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(26.dp))

            // Main Login Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LoginCardBorder, RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(22.dp)
                ) {
                    Text(
                        text = "Sign In to Organization",
                        color = LoginTextDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Connect to your Azure DevOps organization projects and work items",
                        color = LoginTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp, bottom = 18.dp)
                    )

                    // Error Banner
                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            color = TypeBug.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .border(1.dp, TypeBug.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = TypeBug,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = TypeBug,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Organization Name or URL
                    Text(
                        text = "Organization Name or URL",
                        color = LoginTextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = orgInput,
                        onValueChange = {
                            orgInput = it
                            if (errorMessage != null) onDismissError()
                        },
                        placeholder = {
                            Text(
                                "https://dev.azure.com/OrganizationName",
                                color = LoginTextMuted.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = LoginAzureBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (orgInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            orgInput = ""
                                            if (errorMessage != null) onDismissError()
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("clear_org_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear organization",
                                            tint = LoginTextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        readClipboardText(context)?.let { pasted ->
                                            orgInput = pasted.trim()
                                            if (errorMessage != null) onDismissError()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("paste_org_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste organization from clipboard",
                                        tint = LoginAzureBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LoginAccentPurple,
                            unfocusedBorderColor = LoginCardBorder,
                            focusedContainerColor = LoginInputFill,
                            unfocusedContainerColor = LoginInputFill,
                            focusedTextColor = LoginTextDark,
                            unfocusedTextColor = LoginTextDark
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                                    if ((isCtrlOrMeta && keyEvent.key == Key.V) || keyEvent.key == Key.Paste) {
                                        val pasted = readClipboardText(context)
                                        if (!pasted.isNullOrEmpty()) {
                                            orgInput = pasted.trim()
                                            if (errorMessage != null) onDismissError()
                                            return@onPreviewKeyEvent true
                                        }
                                    } else if ((isCtrlOrMeta && keyEvent.key == Key.C) || keyEvent.key == Key.Copy) {
                                        if (orgInput.isNotEmpty()) {
                                            writeClipboardText(context, orgInput)
                                        }
                                    }
                                }
                                false
                            }
                            .testTag("org_input")
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Personal Access Token (PAT)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Personal Access Token (PAT)",
                            color = LoginTextDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { showHelp = !showHelp },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "How to get a PAT",
                                tint = LoginAzureBlue,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = patInput,
                        onValueChange = {
                            patInput = it
                            if (errorMessage != null) onDismissError()
                        },
                        placeholder = {
                            Text(
                                "Paste your Personal Access Token",
                                color = LoginTextMuted.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = LoginAzureBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (patInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            patInput = ""
                                            if (errorMessage != null) onDismissError()
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("clear_pat_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear PAT",
                                            tint = LoginTextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        readClipboardText(context)?.let { pasted ->
                                            patInput = pasted.trim()
                                            if (errorMessage != null) onDismissError()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("paste_pat_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste PAT from clipboard",
                                        tint = LoginAzureBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { patVisible = !patVisible },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("toggle_pat_visibility_button")
                                ) {
                                    Icon(
                                        imageVector = if (patVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (patVisible) "Hide PAT" else "Show PAT",
                                        tint = LoginTextMuted,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        },
                        visualTransformation = if (patVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                onConnect(orgInput, patInput, rememberCredentials)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LoginAccentPurple,
                            unfocusedBorderColor = LoginCardBorder,
                            focusedContainerColor = LoginInputFill,
                            unfocusedContainerColor = LoginInputFill,
                            focusedTextColor = LoginTextDark,
                            unfocusedTextColor = LoginTextDark
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                                    if ((isCtrlOrMeta && keyEvent.key == Key.V) || keyEvent.key == Key.Paste) {
                                        val pasted = readClipboardText(context)
                                        if (!pasted.isNullOrEmpty()) {
                                            patInput = pasted.trim()
                                            if (errorMessage != null) onDismissError()
                                            return@onPreviewKeyEvent true
                                        }
                                    } else if ((isCtrlOrMeta && keyEvent.key == Key.C) || keyEvent.key == Key.Copy) {
                                        if (patInput.isNotEmpty()) {
                                            writeClipboardText(context, patInput)
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                }
                                false
                            }
                            .testTag("pat_input")
                    )

                    // Collapsible PAT Help Guide
                    AnimatedVisibility(visible = showHelp) {
                        Surface(
                            color = LoginAzureBlue.copy(alpha = 0.07f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .border(1.dp, LoginAzureBlue.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "How to generate an Azure DevOps PAT:",
                                    color = LoginAzureBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "1. Sign in to your Azure DevOps organization.\n2. In the top right, open User Settings > Personal access tokens.\n3. Select + New Token.\n4. Grant 'Work Items (Read, write, & manage)' and 'Project and Team (Read)' scopes.\n5. Copy the token and paste it above.",
                                    color = LoginTextDark,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Remember and save credentials checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { rememberCredentials = !rememberCredentials }
                            .padding(vertical = 4.dp)
                            .testTag("remember_credentials_row")
                    ) {
                        Checkbox(
                            checked = rememberCredentials,
                            onCheckedChange = { rememberCredentials = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = LoginAccentPurple,
                                uncheckedColor = LoginTextMuted,
                                checkmarkColor = Color.White
                            ),
                            modifier = Modifier.testTag("remember_credentials_checkbox")
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Remember and save credentials",
                                color = LoginTextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (rememberCredentials) {
                                    "Saved on device so you stay signed in"
                                } else {
                                    "Kept for this session only"
                                },
                                color = LoginTextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Sign In / Connect Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onConnect(orgInput, patInput, rememberCredentials)
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LoginAccentPurple,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("connect_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Signing in...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "Sign In to Azure Boards",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
