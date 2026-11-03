package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppNotificationItem
import com.example.data.model.CachedNotificationsState
import com.example.data.model.NotificationCategory
import com.example.data.model.WorkItem
import com.example.di.SessionManager
import com.example.notifications.AzureSyncWorker
import com.example.notifications.NotificationHelper
import com.example.ui.ConnectionStatus
import com.example.ui.state.NotificationsUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feature ViewModel responsible for Azure Boards notifications (`Assigned to You` & `@Mentions`),
 * unread badge counts, identity detection/confirmation/override, and deep-link navigation.
 */
class NotificationsViewModel(
    application: Application,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    private val repository = sessionManager.repository
    private var activeNotificationsSyncJob: Job? = null

    val notificationsState: StateFlow<CachedNotificationsState> = sessionManager.notificationsState
    val isSyncingNotifications: StateFlow<Boolean> = sessionManager.isSyncingNotifications

    val assignedNotifications: StateFlow<List<AppNotificationItem>> = notificationsState.map { state ->
        state.notifications
            .filter { it.category == NotificationCategory.ASSIGNED }
            .sortedByDescending { it.createdDateIso }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mentionedNotifications: StateFlow<List<AppNotificationItem>> = notificationsState.map { state ->
        state.notifications
            .filter { it.category == NotificationCategory.MENTIONED }
            .sortedByDescending { it.createdDateIso }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadNotificationsCount: StateFlow<Int> = notificationsState.map { state ->
        state.notifications.count { !it.isRead }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val uiState: StateFlow<NotificationsUiState> = combine(
        assignedNotifications,
        mentionedNotifications,
        unreadNotificationsCount,
        isSyncingNotifications
    ) { assigned, mentioned, unread, isSyncing ->
        NotificationsUiState(
            assignedNotifications = assigned,
            mentionedNotifications = mentioned,
            unreadNotificationsCount = unread,
            isSyncingNotifications = isSyncing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationsUiState())

    init {
        sessionManager.onSyncNotificationsRequested = { postDeviceNotifications ->
            syncNotificationsForActiveOrganization(postDeviceNotifications)
        }

        runCatching {
            val appContext = getApplication<Application>().applicationContext
            NotificationHelper.ensureNotificationChannels(appContext)
            AzureSyncWorker.schedulePeriodicSync(appContext)
        }

        // Periodic foreground poll while the app is open
        viewModelScope.launch {
            while (true) {
                delay(3L * 60L * 1000L)
                val activeOrg = (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
                if (!activeOrg.isNullOrBlank()) {
                    syncNotificationsForActiveOrganization(postDeviceNotifications = true)
                }
            }
        }
    }

    /**
     * Syncs Azure DevOps work item assignments and `@mentions` in discussions for the active organization.
     */
    fun syncNotificationsForActiveOrganization(postDeviceNotifications: Boolean = true) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val normOrg = repository.extractOrganization(org)
        activeNotificationsSyncJob?.cancel()
        activeNotificationsSyncJob = viewModelScope.launch {
            sessionManager.updateIsSyncingNotifications(true)
            try {
                val (workItemsOrg, currentItems) = sessionManager.currentWorkItemsProvider?.invoke()
                    ?: (null to emptyList())
                val knownItems = if (workItemsOrg != null && workItemsOrg.equals(normOrg, ignoreCase = true)) {
                    currentItems
                } else {
                    emptyList()
                }
                val outcome = repository.syncOrganizationNotifications(
                    organization = normOrg,
                    pat = pat,
                    knownWorkItems = knownItems
                )
                if (outcome.newlyHydratedWorkItems.isNotEmpty() &&
                    workItemsOrg != null &&
                    workItemsOrg.equals(normOrg, ignoreCase = true)
                ) {
                    sessionManager.onMergeHydratedWorkItemsRequested?.invoke(outcome.newlyHydratedWorkItems)
                }
                val activeOrgNow = (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
                    ?: sessionManager.defaultOrganization.value
                if (activeOrgNow == null || activeOrgNow.equals(normOrg, ignoreCase = true)) {
                    sessionManager.updateNotificationsState(outcome.state)
                }
                if (postDeviceNotifications && outcome.newlyTriggeredNotifications.isNotEmpty()) {
                    val appContext = getApplication<Application>().applicationContext
                    outcome.newlyTriggeredNotifications.forEach { notif ->
                        NotificationHelper.postSystemNotification(appContext, notif)
                    }
                }
            } finally {
                sessionManager.updateIsSyncingNotifications(false)
            }
        }
    }



    /**
     * Marks all notifications (or all notifications in [category]) as read for the active organization.
     */
    fun markAllNotificationsRead(category: String? = null) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: sessionManager.defaultOrganization.value ?: repository.getConfiguredUrl()
        if (org.isBlank()) return
        viewModelScope.launch {
            val updated = repository.markAllNotificationsRead(org, category)
            sessionManager.updateNotificationsState(updated)
        }
    }

    /**
     * Handles clicking a notification item on the Notifications screen:
     * 1. Marks the notification as read.
     * 2. Opens the corresponding Work Item Detail view (using the in-memory WorkItem or a
     *    hydrated fallback while fetching latest details/comments).
     */
    fun onNotificationClicked(notification: AppNotificationItem) {
        val acc = sessionManager.getActiveAccount()
        val org = notification.organization.ifBlank {
            acc?.organization ?: sessionManager.defaultOrganization.value ?: repository.getConfiguredUrl()
        }
        if (org.isNotBlank()) {
            viewModelScope.launch {
                val updated = repository.markNotificationRead(org, notification.id)
                sessionManager.updateNotificationsState(updated)
            }
        }

        val currentItems = sessionManager.currentWorkItemsProvider?.invoke()?.second.orEmpty()
        val existingItem = currentItems.firstOrNull { it.id == notification.workItemId }
        if (existingItem != null) {
            sessionManager.onSelectWorkItemRequested?.invoke(existingItem)
            return
        }

        val fallbackItem = WorkItem(
            id = notification.workItemId,
            title = notification.workItemTitle,
            workItemType = notification.workItemType,
            state = notification.workItemState,
            teamProject = notification.teamProject,
            assignedToName = if (notification.category == NotificationCategory.ASSIGNED) {
                notification.actorName
            } else {
                null
            },
            createdDate = notification.createdDateIso,
            changedDate = notification.createdDateIso
        )
        sessionManager.onSelectWorkItemRequested?.invoke(fallbackItem)

        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            viewModelScope.launch {
                val searchRes = repository.searchWorkItemsOnServer(
                    organization = org,
                    pat = pat,
                    query = notification.workItemId.toString(),
                    project = notification.teamProject,
                    alreadyLoadedIds = emptySet()
                )
                searchRes.onSuccess { fetched ->
                    val hydrated = fetched.firstOrNull { it.id == notification.workItemId }
                    if (hydrated != null) {
                        sessionManager.onMergeHydratedWorkItemsRequested?.invoke(listOf(hydrated))
                    }
                }
            }
        }
    }

    /**
     * Handles deep-link navigation when the user taps an Android system push notification.
     */
    fun handleNotificationDeepLink(
        organization: String?,
        workItemId: Int,
        notificationId: String?
    ) {
        if (workItemId <= 0) return
        val targetOrg = organization?.takeIf { it.isNotBlank() }
            ?: (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: sessionManager.defaultOrganization.value
            ?: ""

        if (targetOrg.isNotBlank()) {
            val activeOrg = (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
            if (activeOrg != null && !activeOrg.equals(targetOrg, ignoreCase = true)) {
                sessionManager.onSwitchOrganizationRequested?.invoke(targetOrg)
            }
            viewModelScope.launch {
                val updated = if (!notificationId.isNullOrBlank()) {
                    repository.markNotificationRead(targetOrg, notificationId)
                } else {
                    repository.markNotificationsReadForWorkItem(targetOrg, workItemId)
                }
                sessionManager.updateNotificationsState(updated)
            }
        }

        val matchingNotif = notificationsState.value.notifications.firstOrNull {
            it.id == notificationId || it.workItemId == workItemId
        }
        if (matchingNotif != null) {
            onNotificationClicked(matchingNotif)
            return
        }

        val currentItems = sessionManager.currentWorkItemsProvider?.invoke()?.second.orEmpty()
        val existingItem = currentItems.firstOrNull { it.id == workItemId }
        if (existingItem != null) {
            sessionManager.onSelectWorkItemRequested?.invoke(existingItem)
        }
    }
}
