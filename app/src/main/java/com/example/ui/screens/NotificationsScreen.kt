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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val NotifBgTop = Color(0xFFF6F7FB)
private val NotifBgBottom = Color(0xFFECEEF6)
private val NotifAccentBlue = Color(0xFF0078D4)
private val NotifTextDark = Color(0xFF0D101C)
private val NotifTextMuted = Color(0xFF6E7591)
private val NotifCardBorder = Color(0xFFDCE1EF)
private val NotifUnreadTint = Color(0xFFF0F7FF)

internal fun computeStartOfCurrentMonthEpochMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowEpochMs
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

internal fun formatCurrentMonthLabel(nowEpochMs: Long = System.currentTimeMillis()): String {
    return SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(nowEpochMs))
}

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
    var showOlderAssigned by rememberSaveable { mutableStateOf(false) }
    var showOlderMentioned by rememberSaveable { mutableStateOf(false) }

    val startOfMonthMs = remember { computeStartOfCurrentMonthEpochMs() }
    val currentMonthName = remember { formatCurrentMonthLabel() }

    val sortedAssigned = remember(assignedNotifications) {
        assignedNotifications.sortedWith(
            compareByDescending<AppNotificationItem> { it.epochMs }
                .thenByDescending { it.workItemId }
        )
    }
    val sortedMentioned = remember(mentionedNotifications) {
        mentionedNotifications.sortedWith(
            compareByDescending<AppNotificationItem> { it.epochMs }
                .thenByDescending { it.workItemId }
        )
    }

    val thisMonthAssigned = remember(sortedAssigned, startOfMonthMs) {
        sortedAssigned.filter { it.epochMs >= startOfMonthMs }
    }
    val olderAssigned = remember(sortedAssigned, startOfMonthMs) {
        sortedAssigned.filter { it.epochMs < startOfMonthMs }
    }

    val thisMonthMentioned = remember(sortedMentioned, startOfMonthMs) {
        sortedMentioned.filter { it.epochMs >= startOfMonthMs }
    }
    val olderMentioned = remember(sortedMentioned, startOfMonthMs) {
        sortedMentioned.filter { it.epochMs < startOfMonthMs }
    }

    val unreadAssignedCount = remember(thisMonthAssigned, olderAssigned, showOlderAssigned) {
        if (showOlderAssigned) {
            sortedAssigned.count { !it.isRead }
        } else {
            thisMonthAssigned.count { !it.isRead }
        }
    }
    val unreadMentionedCount = remember(thisMonthMentioned, olderMentioned, showOlderMentioned) {
        if (showOlderMentioned) {
            sortedMentioned.count { !it.isRead }
        } else {
            thisMonthMentioned.count { !it.isRead }
        }
    }
    val totalUnreadCount = remember(sortedAssigned, sortedMentioned) {
        sortedAssigned.count { !it.isRead } + sortedMentioned.count { !it.isRead }
    }

    val activeThisMonthItems = if (selectedTabIndex == 0) thisMonthAssigned else thisMonthMentioned
    val activeOlderItems = if (selectedTabIndex == 0) olderAssigned else olderMentioned
    val isShowingOlder = if (selectedTabIndex == 0) showOlderAssigned else showOlderMentioned
    val onToggleShowOlder: (Boolean) -> Unit = { expand ->
        if (selectedTabIndex == 0) {
            showOlderAssigned = expand
        } else {
            showOlderMentioned = expand
        }
    }
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
                        text = organization.ifBlank { "Azure Boards" },
                        color = NotifTextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (totalUnreadCount > 0) {
                    IconButton(
                        onClick = { onMarkAllRead(null) },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("notifications_mark_all_read_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = stringResource(R.string.notifications_mark_all_read),
                            tint = NotifAccentBlue,
                            modifier = Modifier.size(20.dp)
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
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh notifications",
                        tint = NotifTextDark,
                        modifier = Modifier.size(20.dp)
                    )
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
                    totalCount = if (showOlderAssigned) sortedAssigned.size else thisMonthAssigned.size,
                    unreadCount = unreadAssignedCount,
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    testTag = "notifications_tab_assigned",
                    modifier = Modifier.weight(1f)
                )

                NotificationSectionTab(
                    title = stringResource(R.string.notifications_tab_mentioned),
                    icon = Icons.Default.AlternateEmail,
                    totalCount = if (showOlderMentioned) sortedMentioned.size else thisMonthMentioned.size,
                    unreadCount = unreadMentionedCount,
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    testTag = "notifications_tab_mentioned",
                    modifier = Modifier.weight(1f)
                )
            }
        }





        // Notifications List or Empty State
        if (activeThisMonthItems.isEmpty() && (!isShowingOlder || activeOlderItems.isEmpty())) {
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
                            "Nothing Assigned This Month."
                        } else {
                            "No Mentions This Month"
                        },
                        color = NotifTextDark,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    if (activeOlderItems.isEmpty()) {
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
                    }

                    if (activeOlderItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onToggleShowOlder(true) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NotifAccentBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.testTag("notifications_see_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "See More Items",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }


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
                // "This Month" Section Header
                item(key = "header_this_month") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = NotifAccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "This Month",
                                color = NotifTextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (activeThisMonthItems.isEmpty()) {
                    item(key = "empty_this_month_banner") {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, NotifCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (selectedTabIndex == 0) {
                                    "Nothing Assigned This Month."
                                } else {
                                    "No Mentions This Month"
                                },
                                color = NotifTextMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 16.dp)
                            )
                        }
                    }
                } else {
                    items(
                        items = activeThisMonthItems,
                        key = { it.id }
                    ) { item ->
                        NotificationItemCard(
                            item = item,
                            onClick = { onNotificationClick(item) }
                        )
                    }
                }

                // "See More Items" Button or Expanded Older Items
                if (activeOlderItems.isNotEmpty()) {
                    if (!isShowingOlder) {
                        item(key = "see_more_items_button") {
                            Surface(
                                onClick = { onToggleShowOlder(true) },
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, NotifAccentBlue.copy(alpha = 0.45f)),
                                shadowElevation = 1.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .testTag("notifications_see_more_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 13.dp, horizontal = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = NotifAccentBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "See More Items",
                                        color = NotifAccentBlue,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        item(key = "header_earlier_items") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp)
                            ) {
                                HorizontalDivider(
                                    color = NotifCardBorder,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = NotifTextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Past Months",
                                            color = NotifTextDark,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Text(
                                        text = "Show Less Items",
                                        color = NotifAccentBlue,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onToggleShowOlder(false) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                            .testTag("notifications_show_less_button")
                                    )
                                }
                            }
                        }

                        items(
                            items = activeOlderItems,
                            key = { "older_${it.id}" }
                        ) { item ->
                            NotificationItemCard(
                                item = item,
                                onClick = { onNotificationClick(item) }
                            )
                        }

                        item(key = "collapse_older_footer") {
                            Surface(
                                onClick = { onToggleShowOlder(false) },
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, NotifCardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("notifications_collapse_older_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ExpandLess,
                                        contentDescription = null,
                                        tint = NotifTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Show Less Items",
                                        color = NotifTextDark,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
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

    val formattedDateTime = remember(item.epochMs, item.formattedDate) {
        if (item.epochMs > 0L) {
            SimpleDateFormat("yyyy-MM-dd · HH:mm", Locale.US).format(Date(item.epochMs))
        } else {
            item.formattedDate
        }
    }

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
            // Top Row: Unread indicator + Type Icon + Work Item ID + Project + Date + Time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (!item.isRead) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NotifAccentBlue)
                        )
                    }

                    // Work Item Type Icon (icon only, no label)
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = item.workItemType,
                        tint = typeColor,
                        modifier = Modifier.size(16.dp)
                    )

                    // Work Item ID
                    Text(
                        text = "#${item.workItemId}",
                        color = NotifTextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

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

                // Date + Time
                if (formattedDateTime.isNotBlank()) {
                    Text(
                        text = formattedDateTime,
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

            // Bottom Row: State Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
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


internal fun highlightMentionsInNotificationText(text: String) = buildAnnotatedString {
    val mentionRegex = Regex("@<([^>\\r\\n]+)>|@[\\p{L}\\p{N}._-]+(?:\\s+[\\p{L}\\p{N}._-]+)?")
    val pillStyle = SpanStyle(
        color = NotifAccentBlue,
        fontWeight = FontWeight.Bold,
        background = NotifAccentBlue.copy(alpha = 0.12f)
    )
    var lastIndex = 0
    for (match in mentionRegex.findAll(text)) {
        if (match.range.first > lastIndex) {
            append(text.substring(lastIndex, match.range.first))
        }
        val bracketedInner = match.groups[1]?.value?.trim()
        val displayPillText = if (!bracketedInner.isNullOrEmpty()) {
            val resolvedName = com.example.data.repository.AzureDevOpsRepository
                .resolveIdentityRecord(bracketedInner)
                ?.displayName
                ?: bracketedInner.removePrefix("@").trim()
            "@$resolvedName"
        } else {
            match.value
        }
        withStyle(pillStyle) {
            append(displayPillText)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}
