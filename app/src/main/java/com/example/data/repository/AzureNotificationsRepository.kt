package com.example.data.repository

import android.content.Context
import com.example.data.api.AzureDevOpsClient
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.AppNotificationItem
import com.example.data.model.CachedNotificationsState
import com.example.data.model.NotificationCategory
import com.example.data.model.WiqlQueryRequest
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Domain repository responsible for notification synchronization, user identity detection,
 * mention parsing, read status tracking, and notification state persistence.
 */
class AzureNotificationsRepository(
    context: Context,
    private val localStore: WorkItemLocalStore = WorkItemLocalStore(context.applicationContext ?: context),
    private val commentsRepository: AzureCommentsRepository = AzureCommentsRepository()
) {

    data class NotificationSyncOutcome(
        val state: CachedNotificationsState,
        val newlyTriggeredNotifications: List<AppNotificationItem>,
        val newlyHydratedWorkItems: List<WorkItem> = emptyList()
    )

    fun getCachedNotificationsStateSync(organization: String): CachedNotificationsState {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        if (normOrg.isBlank()) {
            return CachedNotificationsState(organization = "")
        }
        return localStore.loadNotificationsStateSync(normOrg)
            ?: CachedNotificationsState(organization = normOrg)
    }

    suspend fun getCachedNotificationsState(organization: String): CachedNotificationsState =
        withContext(Dispatchers.IO) {
            getCachedNotificationsStateSync(organization)
        }

    fun computePatCredentialHash(organization: String, pat: String): String {
        val cleanOrg = AzureAccountRepository.extractOrganization(organization).lowercase().trim()
        val cleanPat = pat.trim()
        val combined = "$cleanOrg:$cleanPat"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(combined.toByteArray(Charsets.UTF_8))
            bytes.take(16).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            cleanPat.hashCode().toString()
        }
    }

    suspend fun setManualIdentityOverride(
        organization: String,
        identityOverride: String?,
        pat: String? = null,
        configuredPatProvider: (() -> String)? = null
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        val cleanOverride = identityOverride?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedPat = pat?.trim()?.takeIf { it.isNotEmpty() } ?: configuredPatProvider?.invoke().orEmpty()
        val patHash = if (resolvedPat.isNotEmpty()) computePatCredentialHash(normOrg, resolvedPat) else current.confirmedForPatHash
        val identityChanged = !current.manualIdentityOverride.equals(cleanOverride, ignoreCase = true)
        val updated = if (identityChanged) {
            current.copy(
                manualIdentityOverride = cleanOverride,
                isIdentityConfirmed = true,
                confirmedForPatHash = patHash,
                baselineAssignedInitialized = false,
                baselineMentionsInitialized = false,
                knownAssignedItemStates = emptyMap()
            )
        } else {
            current.copy(
                manualIdentityOverride = cleanOverride,
                isIdentityConfirmed = true,
                confirmedForPatHash = patHash
            )
        }
        localStore.saveNotificationsState(updated)
        updated
    }

    suspend fun confirmUserIdentity(
        organization: String,
        pat: String,
        confirmed: Boolean,
        configuredPatProvider: (() -> String)? = null
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        val cleanPat = pat.trim().ifEmpty { configuredPatProvider?.invoke().orEmpty() }
        val patHash = computePatCredentialHash(normOrg, cleanPat)
        val updated = current.copy(
            isIdentityConfirmed = confirmed,
            confirmedForPatHash = if (confirmed) patHash else null
        )
        localStore.saveNotificationsState(updated)
        updated
    }

    suspend fun saveCorrectedIdentity(
        organization: String,
        pat: String,
        correctedName: String,
        configuredPatProvider: (() -> String)? = null
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        setManualIdentityOverride(organization, correctedName, pat, configuredPatProvider)
    }

    suspend fun markNotificationRead(
        organization: String,
        notificationId: String
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        val updatedList = current.notifications.map { item ->
            if (item.id == notificationId && !item.isRead) item.copy(isRead = true) else item
        }
        val updated = current.copy(notifications = updatedList)
        localStore.saveNotificationsState(updated)
        updated
    }

    suspend fun markNotificationsReadForWorkItem(
        organization: String,
        workItemId: Int
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        var changed = false
        val updatedList = current.notifications.map { item ->
            if (item.workItemId == workItemId && !item.isRead) {
                changed = true
                item.copy(isRead = true)
            } else {
                item
            }
        }
        if (!changed) return@withContext current
        val updated = current.copy(notifications = updatedList)
        localStore.saveNotificationsState(updated)
        updated
    }

    suspend fun markAllNotificationsRead(
        organization: String,
        category: String? = null
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        val updatedList = current.notifications.map { item ->
            if ((category == null || item.category == category) && !item.isRead) {
                item.copy(isRead = true)
            } else {
                item
            }
        }
        val updated = current.copy(notifications = updatedList)
        localStore.saveNotificationsState(updated)
        updated
    }

    fun getStartOfCurrentMonthEpochMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowEpochMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    suspend fun removeAssignedNotificationForWorkItem(
        organization: String,
        workItemId: Int
    ): CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        val updatedList = current.notifications.filterNot { item ->
            item.category == NotificationCategory.ASSIGNED && item.workItemId == workItemId
        }
        val updatedKnownMap = current.knownAssignedItemStates.toMutableMap().apply {
            remove(workItemId.toString())
        }
        if (updatedList.size == current.notifications.size &&
            updatedKnownMap.size == current.knownAssignedItemStates.size
        ) {
            return@withContext current
        }
        val updated = current.copy(
            notifications = updatedList,
            knownAssignedItemStates = updatedKnownMap
        )
        localStore.saveNotificationsState(updated)
        updated
    }

    private fun parseIsoToEpochMs(iso: String?): Long {
        val clean = iso?.trim().orEmpty()
        if (clean.isEmpty()) return 0L
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(clean.take(24))
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    fun matchesUserIdentity(
        candidateName: String?,
        candidateEmail: String?,
        effectiveDisplayName: String?,
        detectedEmail: String?
    ): Boolean {
        val cName = candidateName?.trim()?.lowercase().orEmpty()
        val cEmail = candidateEmail?.trim()?.lowercase().orEmpty()
        val targetName = effectiveDisplayName?.trim()?.lowercase().orEmpty()
        val targetEmail = detectedEmail?.trim()?.lowercase().orEmpty()

        if (cName.isEmpty() && cEmail.isEmpty()) return false
        if (cName == "unassigned") return false

        if (targetEmail.isNotEmpty()) {
            if (cEmail.isNotEmpty() && cEmail == targetEmail) return true
            if (cName.isNotEmpty() && cName == targetEmail) return true
            val emailPrefix = targetEmail.substringBefore('@')
            if (emailPrefix.length >= 3 && cEmail.startsWith("$emailPrefix@")) return true
        }
        if (targetName.isNotEmpty() && cName.isNotEmpty()) {
            if (cName == targetName) return true
            val tokens = cName.split(' ', '-', '_', '.', '(', ')', '<', '>').filter { it.isNotEmpty() }
            if (targetName.length >= 3 && (tokens.contains(targetName) || cName.startsWith("$targetName "))) {
                return true
            }
        }
        return false
    }

    fun commentMentionsIdentity(
        commentText: String,
        effectiveDisplayName: String?,
        detectedEmail: String?,
        detectedUserId: String?
    ): Boolean {
        val lowerText = commentText.lowercase()
        val targetName = effectiveDisplayName?.trim()?.lowercase().orEmpty()
        val targetEmail = detectedEmail?.trim()?.lowercase().orEmpty()
        val targetId = detectedUserId?.trim()?.lowercase().orEmpty()

        if (targetId.isNotEmpty()) {
            if (lowerText.contains("@<$targetId>") ||
                lowerText.contains(targetId) ||
                (lowerText.contains("data-vss-mention") && lowerText.contains(targetId))
            ) {
                return true
            }
        }

        if (targetName.isNotEmpty()) {
            if (lowerText.contains("@<$targetName>") ||
                lowerText.contains("@$targetName") ||
                (lowerText.contains("data-vss-mention") && lowerText.contains(targetName)) ||
                lowerText.contains("@ $targetName")
            ) {
                return true
            }
            val nameParts = targetName.split(' ', '-', '_', '.').map { it.trim() }.filter { it.length >= 3 }
            for (part in nameParts) {
                if (lowerText.contains("@<$part>") ||
                    lowerText.contains("@$part") ||
                    Regex("(?<![\\w@])@<?${Regex.escape(part)}>?(?![\\w])").containsMatchIn(lowerText)
                ) {
                    return true
                }
            }
            if (lowerText.contains(targetName) && (lowerText.contains("@") || lowerText.contains("mention") || lowerText.contains("<a "))) {
                return true
            }
        }
        if (targetEmail.isNotEmpty()) {
            if (lowerText.contains("@<$targetEmail>") || lowerText.contains("@$targetEmail") || lowerText.contains(targetEmail)) {
                return true
            }
            val emailPrefix = targetEmail.substringBefore('@').trim()
            if (emailPrefix.length >= 3) {
                if (lowerText.contains("@<$emailPrefix>") ||
                    lowerText.contains("@$emailPrefix") ||
                    Regex("(?<![\\w@])@<?${Regex.escape(emailPrefix)}>?(?![\\w])").containsMatchIn(lowerText) ||
                    (lowerText.contains(emailPrefix) && (lowerText.contains("@") || lowerText.contains("mention")))
                ) {
                    return true
                }
            }
        }
        return false
    }

    suspend fun evaluateWorkItemCommentsForMentions(
        organization: String,
        workItem: WorkItem,
        comments: List<WorkItemComment>,
        allowSystemNotification: Boolean = true
    ): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val currentState = getCachedNotificationsStateSync(normOrg)
        val effectiveName = currentState.manualIdentityOverride?.takeIf { it.isNotBlank() }
            ?: currentState.detectedDisplayName
        val detectedEmail = currentState.detectedEmail
        val detectedUserId = currentState.detectedUserId

        if (effectiveName.isNullOrBlank() && detectedEmail.isNullOrBlank()) {
            return@withContext NotificationSyncOutcome(currentState, emptyList())
        }

        val existingById = currentState.notifications.associateBy { it.id }.toMutableMap()
        val notifiedSet = currentState.notifiedNotificationIds.toMutableSet()
        val newlyTriggered = mutableListOf<AppNotificationItem>()
        val startOfMonthMs = getStartOfCurrentMonthEpochMs()
        val recentPushCutoffMs = System.currentTimeMillis() - 48L * 60L * 60L * 1000L

        for (comment in comments) {
            if (commentMentionsIdentity(comment.text, effectiveName, detectedEmail, detectedUserId)) {
                val notifId = "mention_${normOrg.lowercase()}_${workItem.id}_${comment.id}"

                val existing = existingById[notifId]
                if (existing == null) {
                    val epoch = parseIsoToEpochMs(comment.createdDate).takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    val isThisMonth = epoch >= startOfMonthMs
                    val isJustMentioned = epoch >= recentPushCutoffMs && isThisMonth
                    val shouldMarkUnread = allowSystemNotification && isThisMonth
                    val notifItem = AppNotificationItem(
                        id = notifId,
                        organization = normOrg,
                        workItemId = workItem.id,
                        workItemTitle = workItem.title,
                        workItemType = workItem.workItemType,
                        workItemState = workItem.state,
                        teamProject = workItem.teamProject,
                        category = NotificationCategory.MENTIONED,
                        actorName = comment.authorName,
                        messageSnippet = comment.text,
                        createdDateIso = comment.createdDate,
                        formattedDate = comment.formattedDate.ifBlank { commentsRepository.formatCommentTimestamp(comment.createdDate) },
                        epochMs = epoch,
                        commentId = comment.id,
                        isRead = !shouldMarkUnread
                    )
                    existingById[notifId] = notifItem
                    if (shouldMarkUnread && isJustMentioned && notifId !in notifiedSet) {
                        newlyTriggered.add(notifItem)
                    }
                    notifiedSet.add(notifId)
                }
            }
        }

        val sortedNotifications = existingById.values
            .sortedWith(compareByDescending<AppNotificationItem> { it.epochMs }.thenByDescending { it.workItemId })
            .take(150)

        val updatedState = currentState.copy(
            notifications = sortedNotifications,
            notifiedNotificationIds = notifiedSet.toList().takeLast(500)
        )
        localStore.saveNotificationsState(updatedState)
        NotificationSyncOutcome(
            state = updatedState,
            newlyTriggeredNotifications = newlyTriggered.sortedByDescending { it.epochMs }.take(5)
        )
    }

    suspend fun syncOrganizationNotifications(
        organization: String,
        pat: String,
        knownWorkItems: List<WorkItem> = emptyList()
    ): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        val cleanPat = pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) {
            return@withContext NotificationSyncOutcome(
                CachedNotificationsState(organization = normOrg),
                emptyList()
            )
        }

        var currentState = getCachedNotificationsStateSync(normOrg)
        val currentPatHash = computePatCredentialHash(normOrg, cleanPat)
        if (currentState.confirmedForPatHash != null && currentState.confirmedForPatHash != currentPatHash) {
            currentState = currentState.copy(
                isIdentityConfirmed = false,
                confirmedForPatHash = null,
                detectedDisplayName = null,
                detectedEmail = null,
                detectedUserId = null
            )
        }
        val service = AzureDevOpsClient.createService(cleanPat)

        var detectedName = currentState.detectedDisplayName
        var detectedEmail = currentState.detectedEmail
        var detectedUserId = currentState.detectedUserId

        if (detectedName.isNullOrBlank() || detectedUserId.isNullOrBlank()) {
            runCatching {
                val connResp = service.getConnectionData(organization = normOrg)
                if (connResp.isSuccessful) {
                    val user = connResp.body()?.authenticatedUser ?: connResp.body()?.authorizedUser
                    if (user != null) {
                        detectedName = user.customDisplayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: user.providerDisplayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: detectedName
                        val propAccount = (user.properties?.get("Account") as? Map<*, *>)?.get("\$value") as? String
                        detectedEmail = user.uniqueName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: propAccount?.trim()?.takeIf { it.isNotEmpty() }
                            ?: detectedEmail
                        detectedUserId = user.id?.trim()?.takeIf { it.isNotEmpty() } ?: detectedUserId
                    }
                }
            }
        }

        AzureIdentityRegistry.registerKnownIdentity(
            id = detectedUserId,
            displayName = detectedName,
            email = detectedEmail
        )

        val manualOverride = currentState.manualIdentityOverride?.trim()?.takeIf { it.isNotEmpty() }
        val newlyHydratedItems = mutableListOf<WorkItem>()
        val itemsById = knownWorkItems.associateBy { it.id }.toMutableMap()

        var assignedQuerySucceeded = false
        val assignedToMeServerIds = if (manualOverride == null) {
            runCatching {
                val wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.AssignedTo] = @Me AND [System.State] <> 'Removed' ORDER BY [System.ChangedDate] DESC"
                val resp = service.queryWorkItems(
                    organization = normOrg,
                    request = WiqlQueryRequest(query = wiql)
                )
                if (resp.isSuccessful) {
                    assignedQuerySucceeded = true
                    resp.body()?.workItems.orEmpty().map { it.id }.take(100)
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
        } else {
            val escapedOverride = manualOverride.replace("'", "''")
            runCatching {
                val wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.AssignedTo] CONTAINS '$escapedOverride' AND [System.State] <> 'Removed' ORDER BY [System.ChangedDate] DESC"
                val resp = service.queryWorkItems(
                    organization = normOrg,
                    request = WiqlQueryRequest(query = wiql)
                )
                if (resp.isSuccessful) {
                    assignedQuerySucceeded = true
                    resp.body()?.workItems.orEmpty().map { it.id }.take(100)
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
        }

        val effectiveIdentityName = manualOverride ?: detectedName

        val mentionServerIds = mutableSetOf<Int>()
        if (!effectiveIdentityName.isNullOrBlank() || !detectedEmail.isNullOrBlank() || !detectedUserId.isNullOrBlank()) {
            val mentionQueryTokens = mutableListOf<String>()
            val escapedUserId = detectedUserId?.replace("'", "''")?.trim()
            if (!escapedUserId.isNullOrBlank() && manualOverride == null) {
                mentionQueryTokens.add(escapedUserId)
            }
            val escapedName = effectiveIdentityName?.replace("'", "''")?.trim()
            if (!escapedName.isNullOrBlank()) {
                mentionQueryTokens.add(escapedName)
                val firstName = escapedName.substringBefore(' ').trim()
                if (firstName.length >= 3 && firstName != escapedName) {
                    mentionQueryTokens.add(firstName)
                }
            }
            val escapedEmail = detectedEmail?.replace("'", "''")?.trim()
            if (!escapedEmail.isNullOrBlank()) {
                val emailPrefix = escapedEmail.substringBefore('@').trim()
                if (emailPrefix.length >= 3) {
                    mentionQueryTokens.add(emailPrefix)
                }
            }

            for (token in mentionQueryTokens.distinct().take(4)) {
                runCatching {
                    val cleanToken = token.replace("'", "''")
                    val wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.History] CONTAINS WORDS '$cleanToken' ORDER BY [System.ChangedDate] DESC"
                    val resp = service.queryWorkItems(
                        organization = normOrg,
                        request = WiqlQueryRequest(query = wiql)
                    )
                    resp.body()?.workItems.orEmpty().map { it.id }.forEach { mentionServerIds.add(it) }
                }
                if (mentionServerIds.size >= 40) break
            }

            runCatching {
                val wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.ChangedDate] >= @Today - 14 AND [System.State] <> 'Removed' ORDER BY [System.ChangedDate] DESC"
                val resp = service.queryWorkItems(
                    organization = normOrg,
                    request = WiqlQueryRequest(query = wiql)
                )
                resp.body()?.workItems.orEmpty().map { it.id }.take(50).forEach { mentionServerIds.add(it) }
            }
        }

        val missingAssignedIds = assignedToMeServerIds.filter { it !in itemsById }
        val missingMentionIds = mentionServerIds.filter { it !in itemsById }.take(40)
        val combinedMissingIds = (missingAssignedIds + missingMentionIds).distinct().take(100)
        if (combinedMissingIds.isNotEmpty()) {
            runCatching {
                val rawBatch = AzureWorkItemParser.hydrateRawItemsByIds(service, normOrg, combinedMissingIds)
                val parsedBatch = AzureWorkItemParser.parseAndLinkRawWorkItems(rawBatch)
                parsedBatch.forEach { item ->
                    itemsById[item.id] = item
                    newlyHydratedItems.add(item)
                }
            }
        }

        if (detectedName.isNullOrBlank() && manualOverride == null && assignedToMeServerIds.isNotEmpty()) {
            val firstMeItem = assignedToMeServerIds.mapNotNull { itemsById[it] }
                .firstOrNull { !it.assignedToName.isNullOrBlank() && !it.isUnassigned }
            if (firstMeItem != null) {
                detectedName = firstMeItem.assignedToName?.trim()
                if (detectedEmail.isNullOrBlank()) {
                    detectedEmail = firstMeItem.assignedToEmail?.trim()
                }
            }
        }

        val resolvedEffectiveName = manualOverride ?: detectedName
        val assignedServerIdSet = assignedToMeServerIds.toSet()

        val allAssignedItems = itemsById.values.filter { item ->
            if (AzureDevOpsRepository.isExcludedNonWorkItemType(item.workItemType)) return@filter false
            if (item.isUnassigned) return@filter false
            if (item.normStateLower == "removed") return@filter false

            if (assignedQuerySucceeded && manualOverride == null) {
                item.id in assignedServerIdSet && (
                    (resolvedEffectiveName.isNullOrBlank() && detectedEmail.isNullOrBlank()) ||
                        matchesUserIdentity(
                            candidateName = item.assignedToName,
                            candidateEmail = item.assignedToEmail,
                            effectiveDisplayName = resolvedEffectiveName,
                            detectedEmail = detectedEmail
                        )
                    )
            } else if (!resolvedEffectiveName.isNullOrBlank() || !detectedEmail.isNullOrBlank()) {
                matchesUserIdentity(
                    candidateName = item.assignedToName,
                    candidateEmail = item.assignedToEmail,
                    effectiveDisplayName = resolvedEffectiveName,
                    detectedEmail = if (manualOverride == null) detectedEmail else null
                )
            } else {
                false
            }
        }.sortedWith(
            compareByDescending<WorkItem> { parseIsoToEpochMs(it.changedDate ?: it.createdDate) }
                .thenByDescending { it.id }
        )

        val existingNotificationsById = currentState.notifications.associateBy { it.id }.toMutableMap()
        val notifiedSet = currentState.notifiedNotificationIds.toMutableSet()
        val knownAssignedMap = currentState.knownAssignedItemStates.toMutableMap()
        val isInitialAssignedBaseline = !currentState.baselineAssignedInitialized
        val newlyTriggered = mutableListOf<AppNotificationItem>()

        val startOfCurrentMonthMs = getStartOfCurrentMonthEpochMs()
        val lastCheckedEpochMs = parseIsoToEpochMs(currentState.lastCheckedChangedDateIso)
        val recentPushCutoffMs = maxOf(
            startOfCurrentMonthMs,
            if (lastCheckedEpochMs > 0L) lastCheckedEpochMs - 5 * 60 * 1000L else System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        )

        val currentAssignedWorkItemIds = allAssignedItems.map { it.id }.toSet()
        val staleAssignedNotifKeys = existingNotificationsById.filter { (_, notif) ->
            notif.category == NotificationCategory.ASSIGNED && notif.workItemId !in currentAssignedWorkItemIds
        }.map { it.key }
        staleAssignedNotifKeys.forEach { existingNotificationsById.remove(it) }

        val currentAssignedKeySet = currentAssignedWorkItemIds.map { it.toString() }.toSet()
        knownAssignedMap.keys.retainAll(currentAssignedKeySet)

        for (item in allAssignedItems.take(80)) {
            val itemKey = item.id.toString()
            val previouslyKnownAssignee = knownAssignedMap[itemKey]
            val currentAssigneeSig = (item.assignedToName ?: resolvedEffectiveName ?: "me").trim().lowercase()
            knownAssignedMap[itemKey] = currentAssigneeSig

            val notifId = "assigned_${normOrg.lowercase()}_${item.id}"
            val existingNotif = existingNotificationsById[notifId]
            val changedIso = item.changedDate ?: item.createdDate.orEmpty()
            val epoch = parseIsoToEpochMs(changedIso).takeIf { it > 0L } ?: 0L

            val isRecentAssignment = epoch >= startOfCurrentMonthMs
            val isJustAssignedForPush = !isInitialAssignedBaseline && epoch >= recentPushCutoffMs

            if (previouslyKnownAssignee == null && existingNotif == null) {
                val isUnread = !isInitialAssignedBaseline && isRecentAssignment
                val notif = AppNotificationItem(
                    id = notifId,
                    organization = normOrg,
                    workItemId = item.id,
                    workItemTitle = item.title,
                    workItemType = item.workItemType,
                    workItemState = item.state,
                    teamProject = item.teamProject,
                    category = NotificationCategory.ASSIGNED,
                    actorName = item.assignedToName ?: resolvedEffectiveName,
                    messageSnippet = "Assigned to ${item.assignedToName ?: resolvedEffectiveName ?: "you"} • ${item.state}",
                    createdDateIso = changedIso,
                    formattedDate = commentsRepository.formatCommentTimestamp(changedIso),
                    epochMs = epoch,
                    commentId = null,
                    isRead = !isUnread
                )
                existingNotificationsById[notifId] = notif
                if (isUnread && isJustAssignedForPush && notifId !in notifiedSet) {
                    newlyTriggered.add(notif)
                }
                notifiedSet.add(notifId)
            } else if (existingNotif != null) {
                val updatedEpoch = if (epoch > 0L) epoch else existingNotif.epochMs
                val updatedIso = changedIso.ifBlank { existingNotif.createdDateIso }
                existingNotificationsById[notifId] = existingNotif.copy(
                    workItemTitle = item.title,
                    workItemType = item.workItemType,
                    workItemState = item.state,
                    teamProject = item.teamProject,
                    createdDateIso = updatedIso,
                    formattedDate = commentsRepository.formatCommentTimestamp(updatedIso),
                    epochMs = updatedEpoch,
                    messageSnippet = "Assigned to ${item.assignedToName ?: resolvedEffectiveName ?: "you"} • ${item.state}"
                )
            }
        }

        val candidateWorkItemIds = linkedSetOf<Int>()
        mentionServerIds.forEach { candidateWorkItemIds.add(it) }
        assignedToMeServerIds.forEach { candidateWorkItemIds.add(it) }
        itemsById.values
            .filter { !it.teamProject.isNullOrBlank() && !AzureDevOpsRepository.isExcludedNonWorkItemType(it.workItemType) }
            .sortedByDescending { it.changedDate ?: "" }
            .map { it.id }
            .forEach { candidateWorkItemIds.add(it) }

        val candidateItemsList = candidateWorkItemIds.mapNotNull { itemsById[it] }
            .filter { !it.teamProject.isNullOrBlank() && !AzureDevOpsRepository.isExcludedNonWorkItemType(it.workItemType) }

        val isInitialMentionsBaseline = !currentState.baselineMentionsInitialized
        val lastCheckedIso = currentState.lastCheckedChangedDateIso
        var maxObservedChangedIso = lastCheckedIso

        fun currentThisMonthMentionCount(): Int = existingNotificationsById.values.count {
            it.category == NotificationCategory.MENTIONED && it.epochMs >= startOfCurrentMonthMs
        }

        if (!resolvedEffectiveName.isNullOrBlank() || !detectedEmail.isNullOrBlank()) {
            val candidateChunks = candidateItemsList.take(75).chunked(15)
            for (chunk in candidateChunks) {
                val commentResults = coroutineScope {
                    chunk.map { workItem ->
                        async {
                            val proj = workItem.teamProject?.trim().orEmpty()
                            val comments = commentsRepository.fetchWorkItemComments(
                                organization = normOrg,
                                pat = cleanPat,
                                project = proj,
                                workItemId = workItem.id
                            ).getOrDefault(emptyList())
                            workItem to comments
                        }
                    }.awaitAll()
                }

                for ((workItem, comments) in commentResults) {
                    val itemChanged = workItem.changedDate?.trim()
                    if (!itemChanged.isNullOrEmpty() && (maxObservedChangedIso == null || itemChanged > maxObservedChangedIso)) {
                        maxObservedChangedIso = itemChanged
                    }

                    for (comment in comments) {
                        if (commentMentionsIdentity(comment.text, resolvedEffectiveName, detectedEmail, detectedUserId)) {
                            val notifId = "mention_${normOrg.lowercase()}_${workItem.id}_${comment.id}"

                            val existingMention = existingNotificationsById[notifId]
                            if (existingMention == null) {
                                val epoch = parseIsoToEpochMs(comment.createdDate).takeIf { it > 0L } ?: 0L
                                val isRecentMention = epoch >= startOfCurrentMonthMs
                                val isJustMentionedForPush = (!isInitialMentionsBaseline || epoch >= System.currentTimeMillis() - 15 * 60 * 1000L) && epoch >= recentPushCutoffMs
                                val shouldMarkUnread = (!isInitialMentionsBaseline || epoch >= System.currentTimeMillis() - 15 * 60 * 1000L) && isRecentMention
                                val notifItem = AppNotificationItem(
                                    id = notifId,
                                    organization = normOrg,
                                    workItemId = workItem.id,
                                    workItemTitle = workItem.title,
                                    workItemType = workItem.workItemType,
                                    workItemState = workItem.state,
                                    teamProject = workItem.teamProject,
                                    category = NotificationCategory.MENTIONED,
                                    actorName = comment.authorName,
                                    messageSnippet = comment.text,
                                    createdDateIso = comment.createdDate,
                                    formattedDate = comment.formattedDate.ifBlank { commentsRepository.formatCommentTimestamp(comment.createdDate) },
                                    epochMs = epoch,
                                    commentId = comment.id,
                                    isRead = !shouldMarkUnread
                                )
                                existingNotificationsById[notifId] = notifItem
                                if (isJustMentionedForPush && notifId !in notifiedSet) {
                                    newlyTriggered.add(notifItem)
                                }
                                notifiedSet.add(notifId)
                            } else {
                                existingNotificationsById[notifId] = existingMention.copy(
                                    workItemTitle = workItem.title,
                                    workItemType = workItem.workItemType,
                                    workItemState = workItem.state,
                                    teamProject = workItem.teamProject
                                )
                            }
                        }
                    }
                }

                if (currentThisMonthMentionCount() >= 15 || existingNotificationsById.values.count { it.category == NotificationCategory.MENTIONED } >= 30) {
                    break
                }
            }

            if (maxObservedChangedIso == null) {
                maxObservedChangedIso = itemsById.values
                    .mapNotNull { it.changedDate?.trim() }
                    .filter { it.isNotEmpty() }
                    .maxOrNull()
            }
        }

        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val sortedNotifications = existingNotificationsById.values
            .sortedWith(
                compareByDescending<AppNotificationItem> { it.epochMs }
                    .thenByDescending { it.workItemId }
            )
            .take(150)

        val recentNewlyTriggered = newlyTriggered
            .sortedByDescending { it.epochMs }
            .take(5)

        currentState = currentState.copy(
            organization = normOrg,
            detectedDisplayName = detectedName ?: currentState.detectedDisplayName,
            detectedEmail = detectedEmail ?: currentState.detectedEmail,
            detectedUserId = detectedUserId ?: currentState.detectedUserId,
            notifications = sortedNotifications,
            notifiedNotificationIds = notifiedSet.toList().takeLast(500),
            baselineAssignedInitialized = true,
            baselineMentionsInitialized = true,
            knownAssignedItemStates = knownAssignedMap,
            lastCheckedChangedDateIso = listOfNotNull(maxObservedChangedIso, currentState.lastCheckedChangedDateIso, nowIso).maxOrNull()
        )

        localStore.saveNotificationsState(currentState)

        NotificationSyncOutcome(
            state = currentState,
            newlyTriggeredNotifications = recentNewlyTriggered,
            newlyHydratedWorkItems = newlyHydratedItems
        )
    }
}
