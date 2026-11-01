package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AppNotificationItem
import com.example.data.model.NotificationCategory
import com.example.notifications.NotificationHelper
import com.example.ui.components.isAlmostPersian
import com.example.ui.theme.getAzureStateColor
import com.example.ui.theme.getAzureTypeColor
import com.example.ui.theme.getAzureTypeVectorIcon

private val NotifBgTop = Color(0xFFF6F7FB)
private val NotifBgBottom = Color(0xFFECEEF6)
private val NotifAccentBlue = Color(0xFF0078D4)
private val NotifTextDark = Color(0xFF0D101C)
private val NotifTextMuted = Color(0xFF6E7591)
private val NotifCardBorder = Color(0xFFDCE1EF)
private val NotifUnreadTint = Color(0xFFF0F7FF)

@Composable
fun NotificationsScreen(
    organization: String,
    effectiveUserIdentity: String?,
    assignedNotifications: List<AppNotificationItem>,
    mentionedNotifications: List<AppNotificationItem>,
    isSyncingNotifications: Boolean,
    onRefreshNotifications: () -> Unit,
    onNotificationClick: (AppNotificationItem) -> Unit,
    onMarkAllRead: (String?) -> Unit,
    onOpenIdentitySettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onClose()
    }

    val context = LocalContext.current
    var hasDevicePermission by remember {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasDevicePermission = granted
    }

    // 0 = Assigned tab, 1 = Mentioned tab
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    val unreadAssignedCount = remember(assignedNotifications) {
        assignedNotifications.count { !it.isRead }
    }
    val unreadMentionedCount = remember(mentionedNotifications) {
        mentionedNotifications.count { !it.isRead }
    }
    val totalUnreadCount = unreadAssignedCount + unreadMentionedCount

    val activeItems = if (selectedTabIndex == 0) assignedNotifications else mentionedNotifications
    val activeCategory = if (selectedTabIndex == 0) NotificationCategory.ASSIGNED else NotificationCategory.MENTIONED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NotifBgTop, NotifBgBottom)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("notifications_screen")
    ) {
        // Top App Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    onClick = onClose,
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.dp, NotifCardBorder),
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("notifications_back_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NotifTextDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notifications_title),
                        color = NotifTextDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(organization.ifBlank { "Azure Boards" })
                            if (!effectiveUserIdentity.isNullOrBlank()) {
                                append(" • ")
                                append(effectiveUserIdentity)
                            }
                        },
                        color = NotifTextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpenIdentitySettings() }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (totalUnreadCount > 0) {
                    TextButton(
                        onClick = { onMarkAllRead(null) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("notifications_mark_all_read_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = null,
                            tint = NotifAccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.notifications_mark_all_read),
                            color = NotifAccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(
                    onClick = onRefreshNotifications,
                    enabled = !isSyncingNotifications,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("notifications_refresh_button")
                ) {
                    if (isSyncingNotifications) {
                        CircularProgressIndicator(
                            color = NotifAccentBlue,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh notifications",
                            tint = NotifTextDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Optional Android 13+ runtime permission banner if POST_NOTIFICATIONS is not yet granted
        AnimatedVisibility(
            visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasDevicePermission
        ) {
            Surface(
                color = Color(0xFFEFF6FF),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NotifAccentBlue.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .testTag("notifications_permission_banner")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = NotifAccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Enable device push alerts",
                                color = NotifTextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Get notified on your phone when assigned or @mentioned.",
                                color = NotifTextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NotifAccentBlue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("enable_device_notifications_button")
                    ) {
                        Text(
                            text = "Allow",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Switchable Tabs: Assigned vs Mentioned
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, NotifCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NotificationSectionTab(
                    title = stringResource(R.string.notifications_tab_assigned),
                    icon = Icons.Default.AssignmentInd,
                    totalCount = assignedNotifications.size,
                    unreadCount = unreadAssignedCount,
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    testTag = "notifications_tab_assigned",
                    modifier = Modifier.weight(1f)
                )

                NotificationSectionTab(
                    title = stringResource(R.string.notifications_tab_mentioned),
                    icon = Icons.Default.AlternateEmail,
                    totalCount = mentionedNotifications.size,
                    unreadCount = unreadMentionedCount,
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    testTag = "notifications_tab_mentioned",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Identity status bar (allows switching or setting identity in Settings if needed)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = NotifTextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (!effectiveUserIdentity.isNullOrBlank()) {
                        "Tracking for: $effectiveUserIdentity"
                    } else {
                        "Detecting your Azure DevOps profile..."
                    },
                    color = NotifTextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "Change Identity",
                color = NotifAccentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenIdentitySettings() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("notifications_change_identity_button")
            )
        }

        // Notifications List or Empty State
        if (activeItems.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(NotifAccentBlue.copy(alpha = 0.1f))
                    ) {
                        if (isSyncingNotifications) {
                            CircularProgressIndicator(
                                color = NotifAccentBlue,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (selectedTabIndex == 0) {
                                    Icons.Default.AssignmentInd
                                } else {
                                    Icons.Default.NotificationsNone
                                },
                                contentDescription = null,
                                tint = NotifAccentBlue,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isSyncingNotifications) {
                            "Checking Azure Boards for notifications..."
                        } else if (selectedTabIndex == 0) {
                            stringResource(R.string.notifications_empty_assigned_title)
                        } else {
                            stringResource(R.string.notifications_empty_mentioned_title)
                        },
                        color = NotifTextDark,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (selectedTabIndex == 0) {
                            stringResource(R.string.notifications_empty_assigned_subtitle)
                        } else {
                            stringResource(R.string.notifications_empty_mentioned_subtitle)
                        },
                        color = NotifTextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onRefreshNotifications,
                        enabled = !isSyncingNotifications,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NotifAccentBlue),
                        modifier = Modifier.testTag("notifications_empty_check_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Check Now",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("notifications_list_${activeCategory.lowercase()}")
            ) {
                items(
                    items = activeItems,
                    key = { it.id }
                ) { item ->
                    NotificationItemCard(
                        item = item,
                        onClick = { onNotificationClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationSectionTab(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    totalCount: Int,
    unreadCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NotifAccentBlue else Color.Transparent,
        modifier = modifier
            .height(42.dp)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else NotifTextMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = if (selected) Color.White else NotifTextDark,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (totalCount > 0 || unreadCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = when {
                        selected && unreadCount > 0 -> Color.White
                        selected -> Color.White.copy(alpha = 0.22f)
                        unreadCount > 0 -> NotifAccentBlue
                        else -> Color(0xFFE2E8F0)
                    }
                ) {
                    Text(
                        text = if (unreadCount > 0) "$unreadCount" else "$totalCount",
                        color = when {
                            selected && unreadCount > 0 -> NotifAccentBlue
                            selected -> Color.White
                            unreadCount > 0 -> Color.White
                            else -> NotifTextDark
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationItemCard(
    item: AppNotificationItem,
    onClick: () -> Unit
) {
    val isMention = item.category == NotificationCategory.MENTIONED
    val typeColor = getAzureTypeColor(item.workItemType)
    val stateColor = getAzureStateColor(item.workItemState)
    val typeIcon = getAzureTypeVectorIcon(item.workItemType)
    val isTitleRtl = isAlmostPersian(item.workItemTitle)
    val isSnippetRtl = isAlmostPersian(item.messageSnippet.orEmpty())

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (!item.isRead) NotifUnreadTint else Color.White,
        border = BorderStroke(
            width = if (!item.isRead) 1.5.dp else 1.dp,
            color = if (!item.isRead) NotifAccentBlue.copy(alpha = 0.55f) else NotifCardBorder
        ),
        shadowElevation = if (!item.isRead) 3.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notification_card_${item.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Top Meta Row: Unread indicator + Category pill + Work Item ID + Timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (!item.isRead) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NotifAccentBlue)
                        )
                    }

                    Surface(
                        color = if (isMention) Color(0xFF7C3AED).copy(alpha = 0.12f) else NotifAccentBlue.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = if (isMention) Icons.Default.AlternateEmail else Icons.Default.AssignmentInd,
                                contentDescription = null,
                                tint = if (isMention) Color(0xFF7C3AED) else NotifAccentBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (isMention) "Mentioned" else "Assigned to you",
                                color = if (isMention) Color(0xFF7C3AED) else NotifAccentBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Work Item Type + ID badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = item.workItemType,
                            tint = typeColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "#${item.workItemId}",
                            color = NotifTextDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!item.teamProject.isNullOrBlank()) {
                        Text(
                            text = "• ${item.teamProject}",
                            color = NotifTextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.formattedDate.isNotBlank()) {
                    Text(
                        text = item.formattedDate,
                        color = NotifTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Work Item Title
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isTitleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Text(
                    text = item.workItemTitle,
                    color = NotifTextDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isMention && !item.messageSnippet.isNullOrBlank()) {
                // Comment snippet with @mention highlighting
                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NotifCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (!item.actorName.isNullOrBlank()) {
                            Text(
                                text = "${item.actorName} commented:",
                                color = NotifTextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                        }

                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (isSnippetRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        ) {
                            Text(
                                text = highlightMentionsInNotificationText(item.messageSnippet),
                                color = NotifTextDark,
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bottom Row: Work Item Type Pill + State Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = typeColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = item.workItemType,
                        color = typeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    color = stateColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(stateColor)
                        )
                        Text(
                            text = item.workItemState,
                            color = stateColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun highlightMentionsInNotificationText(text: String) = buildAnnotatedString {
    val mentionRegex = Regex("@[\\p{L}\\p{N}._-]+(?:\\s+[\\p{L}\\p{N}._-]+)?")
    var lastIndex = 0
    for (match in mentionRegex.findAll(text)) {
        if (match.range.first > lastIndex) {
            append(text.substring(lastIndex, match.range.first))
        }
        withStyle(
            SpanStyle(
                color = NotifAccentBlue,
                fontWeight = FontWeight.Bold,
                background = NotifAccentBlue.copy(alpha = 0.12f)
            )
        ) {
            append(match.value)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}
