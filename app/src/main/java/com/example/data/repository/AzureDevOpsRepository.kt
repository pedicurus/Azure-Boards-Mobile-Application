package com.example.data.repository

import android.content.Context
import com.example.data.local.EncryptedTokenStore
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.CachedNotificationsState
import com.example.data.model.CachedOrganizationMetadata
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment

/**
 * Coordinating façade over specialized domain repositories:
 * - [AzureAccountRepository]: Organization accounts, encrypted PAT tokens, and cached projects
 * - [AzureWorkItemRepository]: Progressive hydration, WIQL streaming, delta sync, and server search
 * - [AzureWorkItemCrudRepository]: Work item creation, JSON Patch updates, and parent-child linking
 * - [AzureProcessMetadataRepository]: Process hierarchy, state categories, and backlog tiers
 * - [AzureClassificationRepository]: Area and Iteration classification trees
 * - [AzureCommentsRepository]: Work item discussions and Markdown/HTML comment posting
 * - [AzureNotificationsRepository]: Assignments, `@mention` evaluation, and identity confirmation
 */
class AzureDevOpsRepository(
    context: Context,
    private val tokenStore: EncryptedTokenStore = EncryptedTokenStore(context.applicationContext ?: context),
    private val localStore: WorkItemLocalStore = WorkItemLocalStore(context.applicationContext ?: context),
    val accountRepository: AzureAccountRepository = AzureAccountRepository(context, tokenStore, localStore),
    val classificationRepository: AzureClassificationRepository = AzureClassificationRepository(),
    val commentsRepository: AzureCommentsRepository = AzureCommentsRepository(),
    val crudRepository: AzureWorkItemCrudRepository = AzureWorkItemCrudRepository(),
    val processMetadataRepository: AzureProcessMetadataRepository = AzureProcessMetadataRepository(context, localStore),
    val notificationsRepository: AzureNotificationsRepository = AzureNotificationsRepository(context, localStore, commentsRepository),
    val workItemRepository: AzureWorkItemRepository = AzureWorkItemRepository(context, localStore)
) {

    companion object {
        const val STREAM_BATCH_SIZE = AzureWorkItemRepository.STREAM_BATCH_SIZE

        const val CATEGORY_IN_PROGRESS = AzureProcessMetadataRepository.CATEGORY_IN_PROGRESS
        const val CATEGORY_PROPOSED = AzureProcessMetadataRepository.CATEGORY_PROPOSED
        const val CATEGORY_RESOLVED = AzureProcessMetadataRepository.CATEGORY_RESOLVED
        const val CATEGORY_COMPLETED = AzureProcessMetadataRepository.CATEGORY_COMPLETED
        const val CATEGORY_REMOVED = AzureProcessMetadataRepository.CATEGORY_REMOVED

        val NON_WORK_ITEM_TYPES = setOf(
            "feedback response",
            "feedback request",
            "code review response",
            "code review request",
            "coder review request",
            "shared parameter",
            "shared steps",
            "test suite",
            "test plan",
            "test case"
        )

        fun isExcludedNonWorkItemType(typeName: String?): Boolean {
            if (typeName.isNullOrBlank()) return true
            val clean = typeName.trim().lowercase()
            return NON_WORK_ITEM_TYPES.any { clean == it || clean.startsWith(it) }
        }

        fun registerStateCategory(
            stateName: String?,
            rawCategory: String?,
            project: String? = null,
            workItemType: String? = null
        ) = AzureProcessMetadataRepository.registerStateCategory(
            stateName = stateName,
            rawCategory = rawCategory,
            project = project,
            workItemType = workItemType
        )

        fun registerKnownIdentity(
            id: String?,
            displayName: String?,
            email: String?,
            avatarUrl: String? = null
        ) = AzureIdentityRegistry.registerKnownIdentity(
            id = id,
            displayName = displayName,
            email = email,
            avatarUrl = avatarUrl
        )

        fun clearKnownIdentitiesForTesting() =
            AzureIdentityRegistry.clearKnownIdentitiesForTesting()

        fun resolveIdentityRecord(token: String): AzureIdentityRegistry.KnownIdentityRecord? =
            AzureIdentityRegistry.resolveIdentityRecord(token)

        fun serializeCommentForAzureMarkdown(rawComment: String): String =
            AzureIdentityRegistry.serializeCommentForAzureMarkdown(rawComment)

        fun serializeCommentForHtmlFallback(rawComment: String): String =
            AzureIdentityRegistry.serializeCommentForHtmlFallback(rawComment)

        fun normalizeCommentTextFromAzure(
            rawText: String?,
            renderedText: String? = null
        ): String? = AzureIdentityRegistry.normalizeCommentTextFromAzure(rawText, renderedText)
    }

    // -------------------------------------------------------------------------
    // Account & Organization Credentials Delegation
    // -------------------------------------------------------------------------

    fun getManuallySavedOrganizations(): List<OrganizationAccount> =
        accountRepository.getManuallySavedOrganizations()

    fun isExplicitlyDisconnected(): Boolean =
        accountRepository.isExplicitlyDisconnected()

    fun setExplicitlyDisconnected(disconnected: Boolean) =
        accountRepository.setExplicitlyDisconnected(disconnected)

    fun getSavedOrganizations(): List<OrganizationAccount> =
        accountRepository.getSavedOrganizations()

    fun saveOrganizationAccount(account: OrganizationAccount) =
        accountRepository.saveOrganizationAccount(account)

    fun removeOrganizationAccount(organization: String) {
        val normOrg = extractOrganization(organization)
        workItemRepository.clearQueuesForOrg(normOrg)
        accountRepository.removeOrganizationAccount(normOrg)
    }

    fun getDefaultOrganization(): String? =
        accountRepository.getDefaultOrganization()

    fun saveDefaultOrganization(organization: String?) =
        accountRepository.saveDefaultOrganization(organization)

    fun getConfiguredUrl(): String =
        accountRepository.getConfiguredUrl()

    fun getConfiguredPat(): String =
        accountRepository.getConfiguredPat()

    fun getDefaultProject(organization: String? = null): String? =
        accountRepository.getDefaultProject(organization)

    fun saveDefaultProject(project: String, organization: String? = null) =
        accountRepository.saveDefaultProject(project, organization)

    fun getCachedProjectsForOrg(organization: String): List<String> =
        accountRepository.getCachedProjectsForOrg(organization)

    fun saveCachedProjectsForOrg(organization: String, projects: List<String>) =
        accountRepository.saveCachedProjectsForOrg(organization, projects)

    fun clearCredentials() =
        accountRepository.clearCredentials()

    fun extractOrganization(input: String): String =
        accountRepository.extractOrganization(input)

    // -------------------------------------------------------------------------
    // Work Item Snapshot, Progressive Streaming & Search Delegation
    // -------------------------------------------------------------------------

    fun getCachedOrganizationSnapshotSync(organization: String): CachedOrganizationSnapshot? =
        workItemRepository.getCachedOrganizationSnapshotSync(organization)

    suspend fun getCachedOrganizationSnapshot(organization: String): CachedOrganizationSnapshot? =
        workItemRepository.getCachedOrganizationSnapshot(organization)

    suspend fun saveCachedOrganizationSnapshot(
        organization: String,
        workItems: List<WorkItem>
    ) = workItemRepository.saveCachedOrganizationSnapshot(organization, workItems)

    fun getRemainingUnloadedCount(organization: String): Int =
        workItemRepository.getRemainingUnloadedCount(organization)

    fun hasUnloadedServerItems(organization: String): Boolean =
        workItemRepository.hasUnloadedServerItems(organization)

    suspend fun syncDeltaAndQueueRemaining(
        organization: String,
        pat: String,
        cachedItems: List<WorkItem>,
        lastSyncedChangedDate: String? = null
    ): Result<DeltaSyncResult> = workItemRepository.syncDeltaAndQueueRemaining(
        organization = organization,
        pat = pat,
        cachedItems = cachedItems,
        lastSyncedChangedDate = lastSyncedChangedDate
    )

    suspend fun fetchWorkItems(organization: String, pat: String): Result<List<WorkItem>> =
        workItemRepository.fetchWorkItems(organization, pat)

    suspend fun fetchNextWorkItemBatch(
        organization: String,
        pat: String,
        project: String? = null,
        workItemType: String? = null,
        state: String? = null,
        alreadyLoadedIds: Set<Int> = emptySet(),
        batchSize: Int = STREAM_BATCH_SIZE
    ): Result<List<WorkItem>> = workItemRepository.fetchNextWorkItemBatch(
        organization = organization,
        pat = pat,
        project = project,
        workItemType = workItemType,
        state = state,
        alreadyLoadedIds = alreadyLoadedIds,
        batchSize = batchSize
    )

    suspend fun searchWorkItemsOnServer(
        organization: String,
        pat: String,
        query: String,
        project: String? = null,
        alreadyLoadedIds: Set<Int> = emptySet()
    ): Result<List<WorkItem>> = workItemRepository.searchWorkItemsOnServer(
        organization = organization,
        pat = pat,
        query = query,
        project = project,
        alreadyLoadedIds = alreadyLoadedIds
    )

    // -------------------------------------------------------------------------
    // Work Item CRUD & Hierarchy Linking Delegation
    // -------------------------------------------------------------------------

    suspend fun updateWorkItemOnServer(
        organization: String,
        pat: String,
        previousItem: WorkItem?,
        updatedItem: WorkItem,
        resolvedAssigneeIdentity: String? = null
    ): Result<WorkItem> = crudRepository.updateWorkItemOnServer(
        organization = organization,
        pat = pat,
        previousItem = previousItem,
        updatedItem = updatedItem,
        resolvedAssigneeIdentity = resolvedAssigneeIdentity
    )

    suspend fun createWorkItemOnServer(
        organization: String,
        pat: String,
        project: String,
        workItemType: String,
        title: String,
        desiredState: String? = null,
        assignedToName: String? = null,
        resolvedAssigneeIdentity: String? = null,
        description: String? = null,
        tags: List<String> = emptyList(),
        areaPath: String? = null,
        iterationPath: String? = null
    ): Result<WorkItem> = crudRepository.createWorkItemOnServer(
        organization = organization,
        pat = pat,
        project = project,
        workItemType = workItemType,
        title = title,
        desiredState = desiredState,
        assignedToName = assignedToName,
        resolvedAssigneeIdentity = resolvedAssigneeIdentity,
        description = description,
        tags = tags,
        areaPath = areaPath,
        iterationPath = iterationPath
    )

    suspend fun createChildWorkItemOnServer(
        organization: String,
        pat: String,
        project: String,
        parentId: Int,
        title: String,
        childType: String,
        desiredState: String? = null
    ): Result<WorkItem> = crudRepository.createChildWorkItemOnServer(
        organization = organization,
        pat = pat,
        project = project,
        parentId = parentId,
        title = title,
        childType = childType,
        desiredState = desiredState
    )

    suspend fun linkExistingChildWorkItemOnServer(
        organization: String,
        pat: String,
        parentId: Int,
        childId: Int
    ): Result<WorkItem> = crudRepository.linkExistingChildWorkItemOnServer(
        organization = organization,
        pat = pat,
        parentId = parentId,
        childId = childId
    )

    suspend fun createParentWorkItemOnServer(
        organization: String,
        pat: String,
        project: String,
        childId: Int,
        title: String,
        parentType: String,
        desiredState: String? = null
    ): Result<WorkItem> = crudRepository.createParentWorkItemOnServer(
        organization = organization,
        pat = pat,
        project = project,
        childId = childId,
        title = title,
        parentType = parentType,
        desiredState = desiredState
    )

    suspend fun linkExistingParentWorkItemOnServer(
        organization: String,
        pat: String,
        childId: Int,
        parentId: Int
    ): Result<WorkItem> = crudRepository.linkExistingParentWorkItemOnServer(
        organization = organization,
        pat = pat,
        childId = childId,
        parentId = parentId
    )

    suspend fun unlinkChildWorkItemOnServer(
        organization: String,
        pat: String,
        parentId: Int,
        childId: Int
    ): Result<Unit> = crudRepository.unlinkChildWorkItemOnServer(
        organization = organization,
        pat = pat,
        parentId = parentId,
        childId = childId
    )

    // -------------------------------------------------------------------------
    // Process Metadata, Categories & Classification Trees Delegation
    // -------------------------------------------------------------------------

    fun resolveStateCategoryForWorkItem(item: WorkItem): String =
        processMetadataRepository.resolveStateCategoryForWorkItem(item)

    fun resolveStateCategory(
        state: String?,
        project: String? = null,
        workItemType: String? = null
    ): String = processMetadataRepository.resolveStateCategory(state, project, workItemType)

    fun isTerminalOrResolvedCategory(
        state: String?,
        project: String? = null,
        workItemType: String? = null
    ): Boolean = processMetadataRepository.isTerminalOrResolvedCategory(state, project, workItemType)

    fun getStatePriorityTier(
        state: String?,
        project: String? = null,
        workItemType: String? = null
    ): Int = processMetadataRepository.getStatePriorityTier(state, project, workItemType)

    fun getCachedOrganizationMetadataSync(organization: String): CachedOrganizationMetadata? =
        processMetadataRepository.getCachedOrganizationMetadataSync(organization)

    suspend fun saveCachedOrganizationMetadata(
        organization: String,
        processHierarchyByProject: Map<String, Map<String, List<String>>>,
        categoryMapByProject: Map<String, Map<String, String>>,
        backlogLevelsByProject: Map<String, List<List<String>>> = emptyMap(),
        areasByProject: Map<String, List<String>> = emptyMap(),
        iterationsByProject: Map<String, List<String>> = emptyMap()
    ) = processMetadataRepository.saveCachedOrganizationMetadata(
        organization = organization,
        processHierarchyByProject = processHierarchyByProject,
        categoryMapByProject = categoryMapByProject,
        backlogLevelsByProject = backlogLevelsByProject,
        areasByProject = areasByProject,
        iterationsByProject = iterationsByProject
    )

    suspend fun fetchProjects(organization: String, pat: String): List<String> =
        processMetadataRepository.fetchProjects(organization, pat)

    suspend fun fetchProjectClassificationPaths(
        organization: String,
        project: String,
        pat: String
    ): Pair<List<String>, List<String>> =
        classificationRepository.fetchProjectClassificationPaths(organization, project, pat)

    suspend fun fetchProjectBacklogMetadata(
        organization: String,
        project: String,
        pat: String,
        activeTypesHint: List<String> = emptyList()
    ): ProjectBacklogMetadata = processMetadataRepository.fetchProjectBacklogMetadata(
        organization = organization,
        project = project,
        pat = pat,
        activeTypesHint = activeTypesHint
    )

    suspend fun fetchProjectCategoryMap(
        organization: String,
        project: String,
        pat: String
    ): Map<String, String> =
        processMetadataRepository.fetchProjectCategoryMap(organization, project, pat)

    fun resolveOrderedBacklogLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        explicitBacklogLevels: List<List<String>> = emptyList(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): List<List<String>> = processMetadataRepository.resolveOrderedBacklogLevels(
        activeTypes = activeTypes,
        categoryMap = categoryMap,
        explicitBacklogLevels = explicitBacklogLevels,
        projectWorkItems = projectWorkItems
    )

    fun isLeafWorkItemType(
        type: String,
        categoryMap: Map<String, String> = emptyMap(),
        activeTypes: List<String> = emptyList(),
        explicitBacklogLevels: List<List<String>> = emptyList(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): Boolean = processMetadataRepository.isLeafWorkItemType(
        type = type,
        categoryMap = categoryMap,
        activeTypes = activeTypes,
        explicitBacklogLevels = explicitBacklogLevels,
        projectWorkItems = projectWorkItems
    )

    fun resolveOrderedHierarchyLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList(),
        explicitBacklogLevels: List<List<String>> = emptyList()
    ): List<List<String>> = processMetadataRepository.resolveOrderedHierarchyLevels(
        activeTypes = activeTypes,
        categoryMap = categoryMap,
        projectWorkItems = projectWorkItems,
        explicitBacklogLevels = explicitBacklogLevels
    )

    fun orderWorkItemTypesBiggestToSmallest(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList(),
        explicitBacklogLevels: List<List<String>> = emptyList()
    ): List<String> = processMetadataRepository.orderWorkItemTypesBiggestToSmallest(
        activeTypes = activeTypes,
        categoryMap = categoryMap,
        projectWorkItems = projectWorkItems,
        explicitBacklogLevels = explicitBacklogLevels
    )

    suspend fun fetchProjectProcessHierarchy(
        organization: String,
        project: String,
        pat: String
    ): Map<String, List<String>> =
        processMetadataRepository.fetchProjectProcessHierarchy(organization, project, pat)

    fun getKnownProjectProcessHierarchy(project: String): Map<String, List<String>> =
        processMetadataRepository.getKnownProjectProcessHierarchy(project)

    fun getStandardProcessStates(type: String): List<String> =
        processMetadataRepository.getStandardProcessStates(type)

    // -------------------------------------------------------------------------
    // Work Item Discussions & Comments Delegation
    // -------------------------------------------------------------------------

    fun formatCommentTimestamp(isoDate: String?): String =
        commentsRepository.formatCommentTimestamp(isoDate)

    suspend fun fetchWorkItemComments(
        organization: String,
        pat: String,
        project: String,
        workItemId: Int
    ): Result<List<WorkItemComment>> = commentsRepository.fetchWorkItemComments(
        organization = organization,
        pat = pat,
        project = project,
        workItemId = workItemId
    )

    suspend fun addWorkItemCommentOnServer(
        organization: String,
        pat: String,
        project: String,
        workItemId: Int,
        commentText: String
    ): Result<WorkItemComment> = commentsRepository.addWorkItemCommentOnServer(
        organization = organization,
        pat = pat,
        project = project,
        workItemId = workItemId,
        commentText = commentText
    )

    // -------------------------------------------------------------------------
    // Notifications & Identity Detection Delegation
    // -------------------------------------------------------------------------

    fun getCachedNotificationsStateSync(organization: String): CachedNotificationsState =
        notificationsRepository.getCachedNotificationsStateSync(organization)

    suspend fun getCachedNotificationsState(organization: String): CachedNotificationsState =
        notificationsRepository.getCachedNotificationsState(organization)

    suspend fun saveNotificationsStateForTesting(state: CachedNotificationsState) =
        notificationsRepository.saveNotificationsStateForTesting(state)

    suspend fun markNotificationRead(
        organization: String,
        notificationId: String
    ): CachedNotificationsState =
        notificationsRepository.markNotificationRead(organization, notificationId)

    suspend fun markNotificationsReadForWorkItem(
        organization: String,
        workItemId: Int
    ): CachedNotificationsState =
        notificationsRepository.markNotificationsReadForWorkItem(organization, workItemId)

    suspend fun markAllNotificationsRead(
        organization: String,
        category: String? = null
    ): CachedNotificationsState =
        notificationsRepository.markAllNotificationsRead(organization, category)

    fun getStartOfCurrentMonthEpochMs(nowEpochMs: Long = System.currentTimeMillis()): Long =
        notificationsRepository.getStartOfCurrentMonthEpochMs(nowEpochMs)

    suspend fun removeAssignedNotificationForWorkItem(
        organization: String,
        workItemId: Int
    ): CachedNotificationsState =
        notificationsRepository.removeAssignedNotificationForWorkItem(organization, workItemId)

    fun matchesUserIdentity(
        candidateName: String?,
        candidateEmail: String?,
        effectiveDisplayName: String?,
        detectedEmail: String?
    ): Boolean = notificationsRepository.matchesUserIdentity(
        candidateName = candidateName,
        candidateEmail = candidateEmail,
        effectiveDisplayName = effectiveDisplayName,
        detectedEmail = detectedEmail
    )

    suspend fun recordAuthenticatedUserIdentity(
        organization: String,
        userId: String?,
        displayName: String?,
        email: String?
    ): CachedNotificationsState = notificationsRepository.recordAuthenticatedUserIdentity(
        organization = organization,
        userId = userId,
        displayName = displayName,
        email = email
    )

    fun commentMentionsIdentity(
        commentText: String,
        effectiveDisplayName: String?,
        detectedEmail: String?,
        detectedUserId: String? = null,
        mentionedIds: List<String> = emptyList()
    ): Boolean = notificationsRepository.commentMentionsIdentity(
        commentText = commentText,
        effectiveDisplayName = effectiveDisplayName,
        detectedEmail = detectedEmail,
        detectedUserId = detectedUserId,
        mentionedIds = mentionedIds
    )

    suspend fun evaluateWorkItemCommentsForMentions(
        organization: String,
        workItem: WorkItem,
        comments: List<WorkItemComment>,
        allowSystemNotification: Boolean = true,
        pat: String? = null
    ): AzureNotificationsRepository.NotificationSyncOutcome =
        notificationsRepository.evaluateWorkItemCommentsForMentions(
            organization = organization,
            workItem = workItem,
            comments = comments,
            allowSystemNotification = allowSystemNotification,
            pat = pat
        )

    suspend fun syncOrganizationNotifications(
        organization: String,
        pat: String,
        knownWorkItems: List<WorkItem> = emptyList()
    ): AzureNotificationsRepository.NotificationSyncOutcome =
        notificationsRepository.syncOrganizationNotifications(
            organization = organization,
            pat = pat,
            knownWorkItems = knownWorkItems
        )
}
