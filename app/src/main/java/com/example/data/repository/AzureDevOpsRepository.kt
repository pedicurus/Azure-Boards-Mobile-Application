package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.data.api.AzureDevOpsClient
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.CachedOrganizationMetadata
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.JsonPatchOperation
import com.example.data.model.OrganizationAccount
import com.example.data.model.WiqlQueryRequest
import com.example.data.model.WorkItem
import androidx.core.text.HtmlCompat
import com.example.ui.theme.registerAzureStateColor
import com.example.ui.theme.registerAzureTypeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DeltaSyncResult(
    val updatedOrNewItems: List<WorkItem>,
    val deletedItemIds: Set<Int>,
    val totalServerCount: Int
)

class AzureDevOpsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("azure_boards_prefs", Context.MODE_PRIVATE)

    private val localStore = WorkItemLocalStore(context.applicationContext ?: context)

    private val hydratedIdsByOrg = ConcurrentHashMap<String, MutableSet<Int>>()
    private val unloadedProposedIdsByOrg = ConcurrentHashMap<String, MutableList<Int>>()
    private val unloadedOtherIdsByOrg = ConcurrentHashMap<String, MutableList<Int>>()

    companion object {
        private const val KEY_DEFAULT_ORG = "pref_azure_user_default_organization"
        private const val KEY_DEFAULT_PROJECT = "pref_azure_user_default_project"
        private const val KEY_DEFAULT_PROJECT_PREFIX = "pref_azure_user_default_project_"
        private const val KEY_CACHED_PROJECTS_PREFIX = "pref_azure_user_cached_projects_"
        private const val KEY_SAVED_ORGS = "pref_azure_user_saved_organizations"

        private const val INITIAL_FAST_HYDRATE_LIMIT = 60
        const val STREAM_BATCH_SIZE = 50

        const val CATEGORY_IN_PROGRESS = "InProgress"
        const val CATEGORY_PROPOSED = "Proposed"
        const val CATEGORY_RESOLVED = "Resolved"
        const val CATEGORY_COMPLETED = "Completed"
        const val CATEGORY_REMOVED = "Removed"

        private val stateCategoryCache = ConcurrentHashMap<String, String>()

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

        private fun normalizeCategoryName(rawCategory: String?): String? {
            return when (rawCategory?.trim()?.lowercase()) {
                "inprogress", "in progress" -> CATEGORY_IN_PROGRESS
                "proposed" -> CATEGORY_PROPOSED
                "resolved" -> CATEGORY_RESOLVED
                "completed" -> CATEGORY_COMPLETED
                "removed" -> CATEGORY_REMOVED
                else -> null
            }
        }

        fun registerStateCategory(
            stateName: String?,
            rawCategory: String?,
            project: String? = null,
            workItemType: String? = null
        ) {
            val cleanState = stateName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
            val normalizedCat = normalizeCategoryName(rawCategory) ?: return
            stateCategoryCache[cleanState] = normalizedCat
            if (!project.isNullOrBlank() && !workItemType.isNullOrBlank()) {
                val scopedKey = "${project.trim().lowercase()}|${workItemType.trim().lowercase()}|$cleanState"
                stateCategoryCache[scopedKey] = normalizedCat
            }
        }
    }

    /**
     * Synchronously loads the cached work item snapshot for [organization] from local device storage
     * and restores the progressive hydration queues.
     */
    fun getCachedOrganizationSnapshotSync(organization: String): CachedOrganizationSnapshot? {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return null
        val snapshot = localStore.loadSnapshotSync(normOrg) ?: return null
        val orgKey = normOrg.lowercase()
        val hydratedSet = Collections.synchronizedSet(snapshot.workItems.map { it.id }.toMutableSet())
        hydratedIdsByOrg[orgKey] = hydratedSet
        unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(
            snapshot.unloadedProposedIds.filter { it !in hydratedSet }.toMutableList()
        )
        unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(
            snapshot.unloadedOtherIds.filter { it !in hydratedSet }.toMutableList()
        )
        return snapshot
    }

    suspend fun getCachedOrganizationSnapshot(organization: String): CachedOrganizationSnapshot? =
        withContext(Dispatchers.IO) {
            getCachedOrganizationSnapshotSync(organization)
        }

    /**
     * Saves the current work items and unloaded queues for [organization] to local device storage.
     */
    suspend fun saveCachedOrganizationSnapshot(
        organization: String,
        workItems: List<WorkItem>
    ) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        val orgKey = normOrg.lowercase()
        val propRemaining = unloadedProposedIdsByOrg[orgKey]?.let { queue ->
            synchronized(queue) { queue.toList() }
        }.orEmpty()
        val otherRemaining = unloadedOtherIdsByOrg[orgKey]?.let { queue ->
            synchronized(queue) { queue.toList() }
        }.orEmpty()
        val maxChangedDate = workItems.mapNotNull { it.changedDate?.trim() }
            .filter { it.isNotEmpty() }
            .maxOrNull()

        localStore.saveSnapshot(
            CachedOrganizationSnapshot(
                organization = normOrg,
                workItems = workItems,
                unloadedProposedIds = propRemaining,
                unloadedOtherIds = otherRemaining,
                lastSyncedChangedDate = maxChangedDate,
                savedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    fun getCachedOrganizationMetadataSync(organization: String): CachedOrganizationMetadata? {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return null
        val meta = localStore.loadMetadataSync(normOrg) ?: return null
        meta.stateCategoryMap.forEach { (k, v) ->
            stateCategoryCache[k] = v
        }
        return meta
    }

    suspend fun saveCachedOrganizationMetadata(
        organization: String,
        processHierarchyByProject: Map<String, Map<String, List<String>>>,
        categoryMapByProject: Map<String, Map<String, String>>
    ) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        localStore.saveMetadata(
            CachedOrganizationMetadata(
                organization = normOrg,
                processHierarchyByProject = processHierarchyByProject,
                categoryMapByProject = categoryMapByProject,
                stateCategoryMap = stateCategoryCache.toMap()
            )
        )
    }

    fun getRemainingUnloadedCount(organization: String): Int {
        val orgKey = extractOrganization(organization).lowercase()
        if (orgKey.isBlank()) return 0
        val propSize = unloadedProposedIdsByOrg[orgKey]?.let { synchronized(it) { it.size } } ?: 0
        val otherSize = unloadedOtherIdsByOrg[orgKey]?.let { synchronized(it) { it.size } } ?: 0
        return propSize + otherSize
    }

    fun resolveStateCategory(
        state: String?,
        project: String? = null,
        workItemType: String? = null
    ): String {
        val cleanState = state?.trim()?.lowercase().orEmpty()
        if (cleanState.isEmpty()) return CATEGORY_PROPOSED

        if (!project.isNullOrBlank() && !workItemType.isNullOrBlank()) {
            val scopedKey = "${project.trim().lowercase()}|${workItemType.trim().lowercase()}|$cleanState"
            stateCategoryCache[scopedKey]?.let { return it }
        }
        stateCategoryCache[cleanState]?.let { return it }

        return when {
            cleanState in setOf(
                "in progress", "inprogress", "active", "doing", "committed",
                "در راه", "امروز", "در حال انجام", "فعال"
            ) -> CATEGORY_IN_PROGRESS
            cleanState in setOf(
                "new", "to do", "todo", "proposed", "approved", "design", "open", "requested",
                "اهداف", "کارها", "پیشنهادی", "جدید"
            ) -> CATEGORY_PROPOSED
            cleanState in setOf(
                "resolved", "ready", "حل شده", "آماده"
            ) -> CATEGORY_RESOLVED
            cleanState in setOf(
                "done", "closed", "completed", "تکمیل", "انجام شده", "بسته"
            ) -> CATEGORY_COMPLETED
            cleanState in setOf(
                "removed", "cut", "منتفی", "حذف شده", "لغو"
            ) -> CATEGORY_REMOVED
            else -> CATEGORY_PROPOSED
        }
    }

    fun isTerminalOrResolvedCategory(
        state: String?,
        project: String? = null,
        workItemType: String? = null
    ): Boolean {
        val cat = resolveStateCategory(state, project, workItemType)
        return cat == CATEGORY_COMPLETED || cat == CATEGORY_RESOLVED || cat == CATEGORY_REMOVED
    }

    fun getStatePriorityTier(
        state: String?,
        project: String? = null,
        workItemType: String? = null
    ): Int {
        return when (resolveStateCategory(state, project, workItemType)) {
            CATEGORY_IN_PROGRESS -> 0
            CATEGORY_PROPOSED -> 1
            CATEGORY_RESOLVED, CATEGORY_COMPLETED, CATEGORY_REMOVED -> 2
            else -> 1
        }
    }

    private fun encodeField(raw: String): String {
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeField(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            encoded
        }
    }

    private fun persistOrganizationsList(accounts: List<OrganizationAccount>) {
        val remembered = accounts.filter {
            it.isRemembered && it.organization.isNotBlank() && it.pat.isNotBlank()
        }.distinctBy { it.organization.lowercase() }

        val serialized = remembered.joinToString("|") { acc ->
            "${encodeField(extractOrganization(acc.organization))}:${encodeField(acc.pat.trim())}"
        }
        prefs.edit().putString(KEY_SAVED_ORGS, serialized).commit()
    }

    fun getSavedOrganizations(): List<OrganizationAccount> {
        val raw = prefs.getString(KEY_SAVED_ORGS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val org = extractOrganization(decodeField(parts[0]))
                val pat = decodeField(parts[1]).trim()
                if (org.isNotBlank() && pat.isNotBlank()) {
                    OrganizationAccount(organization = org, pat = pat, isRemembered = true)
                } else {
                    null
                }
            } else {
                null
            }
        }.distinctBy { it.organization.lowercase() }
    }

    fun saveOrganizationAccount(account: OrganizationAccount) {
        val normOrg = extractOrganization(account.organization)
        val cleanPat = account.pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) return

        val existing = getSavedOrganizations().toMutableList()
        val existingIdx = existing.indexOfFirst { it.organization.equals(normOrg, ignoreCase = true) }

        if (account.isRemembered) {
            val normalizedAccount = OrganizationAccount(normOrg, cleanPat, isRemembered = true)
            if (existingIdx >= 0) {
                existing[existingIdx] = normalizedAccount
            } else {
                existing.add(normalizedAccount)
            }
            persistOrganizationsList(existing)
            if (getDefaultOrganization().isNullOrBlank() || existing.size == 1) {
                saveDefaultOrganization(normOrg)
            }
        } else {
            if (existingIdx >= 0) {
                existing.removeAt(existingIdx)
                persistOrganizationsList(existing)
            }
        }
    }

    fun removeOrganizationAccount(organization: String) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        localStore.clearOrganizationCache(normOrg)
        val remaining = getSavedOrganizations().filterNot {
            it.organization.equals(normOrg, ignoreCase = true)
        }
        persistOrganizationsList(remaining)
        val currentDefaultOrg = getDefaultOrganization()
        if (currentDefaultOrg != null && currentDefaultOrg.equals(normOrg, ignoreCase = true)) {
            saveDefaultOrganization(remaining.firstOrNull()?.organization)
        }
    }

    fun getDefaultOrganization(): String? {
        val savedDefault = prefs.getString(KEY_DEFAULT_ORG, null)?.trim()?.takeIf { it.isNotBlank() }
        val savedOrgs = getSavedOrganizations()
        if (savedDefault != null) {
            val matched = savedOrgs.firstOrNull { it.organization.equals(savedDefault, ignoreCase = true) }
            if (matched != null) return matched.organization
        }
        return savedOrgs.firstOrNull()?.organization
    }

    fun saveDefaultOrganization(organization: String?) {
        val normOrg = organization?.let { extractOrganization(it) }?.takeIf { it.isNotBlank() }
        if (normOrg != null) {
            prefs.edit().putString(KEY_DEFAULT_ORG, normOrg).commit()
        } else {
            prefs.edit().remove(KEY_DEFAULT_ORG).commit()
        }
    }

    /**
     * Resolves the configured Azure DevOps URL/Organization from saved accounts.
     */
    fun getConfiguredUrl(): String {
        val defaultOrg = getDefaultOrganization()
        if (!defaultOrg.isNullOrBlank()) return defaultOrg

        return getSavedOrganizations().firstOrNull()?.organization.orEmpty()
    }

    /**
     * Resolves the configured Azure DevOps PAT from saved accounts.
     */
    fun getConfiguredPat(): String {
        val defaultOrg = getDefaultOrganization()
        val savedOrgs = getSavedOrganizations()
        if (!defaultOrg.isNullOrBlank()) {
            val matched = savedOrgs.firstOrNull { it.organization.equals(defaultOrg, ignoreCase = true) }
            if (matched != null) return matched.pat
        }
        return savedOrgs.firstOrNull()?.pat.orEmpty()
    }

    fun getDefaultProject(organization: String? = null): String? {
        val explicitOrg = organization?.let { extractOrganization(it) }?.takeIf { it.isNotBlank() }
        val defOrg = getDefaultOrganization()
        val targetOrg = explicitOrg ?: defOrg
        if (!targetOrg.isNullOrBlank()) {
            val perOrg = prefs.getString(KEY_DEFAULT_PROJECT_PREFIX + targetOrg.lowercase(), null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (perOrg != null) return perOrg
            // Do not fall back to another organization's default project
            if (explicitOrg != null && !explicitOrg.equals(defOrg, ignoreCase = true)) {
                return null
            }
        }
        return prefs.getString(KEY_DEFAULT_PROJECT, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun saveDefaultProject(project: String, organization: String? = null) {
        val trimmed = project.trim()
        if (trimmed.isEmpty()) return
        val explicitOrg = organization?.let { extractOrganization(it) }?.takeIf { it.isNotBlank() }
        val defOrg = getDefaultOrganization()
        val targetOrg = explicitOrg ?: defOrg
        val editor = prefs.edit()
        if (!targetOrg.isNullOrBlank()) {
            editor.putString(KEY_DEFAULT_PROJECT_PREFIX + targetOrg.lowercase(), trimmed)
            if (defOrg == null || targetOrg.equals(defOrg, ignoreCase = true)) {
                editor.putString(KEY_DEFAULT_PROJECT, trimmed)
            }
        } else {
            editor.putString(KEY_DEFAULT_PROJECT, trimmed)
        }
        editor.commit()
    }

    fun getCachedProjectsForOrg(organization: String): List<String> {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return emptyList()
        val raw = prefs.getString(KEY_CACHED_PROJECTS_PREFIX + normOrg.lowercase(), null)
            ?: return emptyList()
        return raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }

    fun saveCachedProjectsForOrg(organization: String, projects: List<String>) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        val cleaned = projects.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (cleaned.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_CACHED_PROJECTS_PREFIX + normOrg.lowercase(), cleaned.joinToString("|"))
                .commit()
        }
    }

    fun clearCredentials() {
        localStore.clearAllCaches()
        prefs.edit()
            .remove(KEY_SAVED_ORGS)
            .remove(KEY_DEFAULT_ORG)
            .commit()
    }

    /**
     * Normalizes inputs like:
     * - "https://dev.azure.com/organization/" -> "organization"
     * - "dev.azure.com/organization" -> "organization"
     * - "https://organization.visualstudio.com/" -> "organization"
     * - "organization" -> "organization"
     */
    fun extractOrganization(input: String): String {
        var clean = input.trim()
        clean = clean.removePrefix("https://").removePrefix("http://")
        clean = clean.trimEnd('/')

        if (clean.contains("dev.azure.com/")) {
            val parts = clean.split("dev.azure.com/")
            if (parts.size > 1) {
                return parts[1].split("/").firstOrNull()?.trim() ?: clean
            }
        }
        if (clean.contains(".visualstudio.com")) {
            val parts = clean.split(".visualstudio.com")
            if (parts.isNotEmpty()) {
                return parts[0].substringAfterLast("/").trim()
            }
        }
        return clean.substringAfterLast("/").trim()
    }

    /**
     * Performs a fast delta check against Azure DevOps when cached items already exist on device.
     * Hydrates any newly created or modified items and queues any remaining uncached server IDs
     * for non-blocking background streaming.
     */
    suspend fun syncDeltaAndQueueRemaining(
        organization: String,
        pat: String,
        cachedItems: List<WorkItem>,
        lastSyncedChangedDate: String? = null
    ): Result<DeltaSyncResult> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val orgKey = normalizedOrg.lowercase()

            // 1. Fetch ordered list of all valid work item IDs in the organization
            val allWiqlResp = service.queryWorkItems(
                organization = normalizedOrg,
                request = WiqlQueryRequest(
                    query = "SELECT [System.Id] FROM WorkItems ORDER BY [System.ChangedDate] DESC"
                )
            )
            if (!allWiqlResp.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Azure DevOps sync error (${allWiqlResp.code()}): ${allWiqlResp.message()}")
                )
            }

            val allServerRefs = allWiqlResp.body()?.workItems.orEmpty()
            val allServerIds = allServerRefs.map { it.id }
            val allServerIdSet = allServerIds.toSet()

            val cachedIdSet = cachedItems.map { it.id }.toSet()
            val deletedIds = cachedIdSet.filter { it !in allServerIdSet }.toSet()

            // 2. Determine which items were updated since our last sync or are brand new at the top of the feed
            val watermarkDate = lastSyncedChangedDate
                ?: cachedItems.mapNotNull { it.changedDate?.trim() }.filter { it.isNotEmpty() }.maxOrNull()
            val datePrefix = watermarkDate?.take(10) // yyyy-MM-dd safe for all Azure DevOps WIQL servers

            val changedIdsFromServer = if (!datePrefix.isNullOrBlank()) {
                runCatching {
                    service.queryWorkItems(
                        organization = normalizedOrg,
                        request = WiqlQueryRequest(
                            query = "SELECT [System.Id] FROM WorkItems WHERE [System.ChangedDate] >= '$datePrefix' ORDER BY [System.ChangedDate] DESC"
                        )
                    ).body()?.workItems.orEmpty().map { it.id }
                }.getOrDefault(emptyList())
            } else {
                allServerIds.take(40)
            }

            // Always check top 35 most recently changed IDs + any new IDs in changedIdsFromServer
            val deltaCandidateIds = LinkedHashSet<Int>()
            allServerIds.take(35).forEach { deltaCandidateIds.add(it) }
            changedIdsFromServer.take(60).forEach { deltaCandidateIds.add(it) }

            val deltaIdsToHydrate = deltaCandidateIds.take(INITIAL_FAST_HYDRATE_LIMIT)
            val rawDeltaItems = if (deltaIdsToHydrate.isNotEmpty()) {
                hydrateRawItemsByIds(service, normalizedOrg, deltaIdsToHydrate)
            } else {
                emptyList()
            }
            val parsedDeltaItems = parseAndLinkRawWorkItems(rawDeltaItems)

            val nowHydratedSet = Collections.synchronizedSet(mutableSetOf<Int>())
            nowHydratedSet.addAll(cachedIdSet - deletedIds)
            parsedDeltaItems.forEach { nowHydratedSet.add(it.id) }
            hydratedIdsByOrg[orgKey] = nowHydratedSet

            // 3. Any server IDs still not in our local cache are queued for iterative background streaming
            val remainingUncachedIds = allServerIds.filter { it !in nowHydratedSet }
            if (remainingUncachedIds.isEmpty()) {
                unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
            } else {
                val (inProgressRefs, proposedRefs) = queryPriorityCategoryIds(service, normalizedOrg)
                val priorityProposedAndActive = (inProgressRefs + proposedRefs).filter { it !in nowHydratedSet }.distinct()
                val prioritySet = priorityProposedAndActive.toSet()
                val remainingOther = remainingUncachedIds.filter { it !in prioritySet }

                unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(priorityProposedAndActive.toMutableList())
                unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(remainingOther.toMutableList())
            }

            Result.success(
                DeltaSyncResult(
                    updatedOrNewItems = parsedDeltaItems,
                    deletedItemIds = deletedIds,
                    totalServerCount = allServerIds.size
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun queryPriorityCategoryIds(
        service: com.example.data.api.AzureDevOpsService,
        normalizedOrg: String
    ): Pair<List<Int>, List<Int>> = coroutineScope {
        val inProgressDef = async {
            runCatching {
                service.queryWorkItems(
                    organization = normalizedOrg,
                    request = WiqlQueryRequest(
                        query = "SELECT [System.Id] FROM WorkItems WHERE [System.State] IN ('Active', 'In Progress', 'Doing', 'Committed', 'در راه', 'امروز', 'در حال انجام', 'فعال') ORDER BY [System.ChangedDate] DESC"
                    )
                ).body()?.workItems.orEmpty().map { it.id }
            }.getOrDefault(emptyList())
        }
        val proposedDef = async {
            runCatching {
                service.queryWorkItems(
                    organization = normalizedOrg,
                    request = WiqlQueryRequest(
                        query = "SELECT [System.Id] FROM WorkItems WHERE [System.State] IN ('New', 'To Do', 'Proposed', 'Approved', 'Design', 'Open', 'اهداف', 'کارها', 'پیشنهادی', 'جدید') ORDER BY [System.ChangedDate] DESC"
                    )
                ).body()?.workItems.orEmpty().map { it.id }
            }.getOrDefault(emptyList())
        }
        inProgressDef.await() to proposedDef.await()
    }

    suspend fun fetchWorkItems(organization: String, pat: String): Result<List<WorkItem>> =
        withContext(Dispatchers.IO) {
            val normalizedOrg = extractOrganization(organization)
            if (normalizedOrg.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please provide a valid Azure DevOps Organization URL or Name."))
            }
            if (pat.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please provide your Personal Access Token (PAT)."))
            }

            try {
                val service = AzureDevOpsClient.createService(pat)

                // 1. Run WIQL Query to get work items ordered by change date
                val wiql = WiqlQueryRequest(
                    query = "SELECT [System.Id] FROM WorkItems ORDER BY [System.ChangedDate] DESC"
                )
                val wiqlResponse = service.queryWorkItems(
                    organization = normalizedOrg,
                    request = wiql
                )

                if (!wiqlResponse.isSuccessful) {
                    val code = wiqlResponse.code()
                    val errorMsg = when (code) {
                        401 -> "Unauthorized: Personal Access Token is invalid or expired. Check PAT permissions (Work Items: Read)."
                        404 -> "Organization '$normalizedOrg' not found. Verify your organization name or URL."
                        403 -> "Forbidden: You don't have permission to access work items in this organization."
                        else -> "Azure DevOps API error ($code): ${wiqlResponse.message()}"
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val refs = wiqlResponse.body()?.workItems ?: emptyList()
                val orgKey = normalizedOrg.lowercase()
                val hydratedSet = Collections.synchronizedSet(mutableSetOf<Int>())
                hydratedIdsByOrg[orgKey] = hydratedSet

                if (refs.isEmpty()) {
                    unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                    unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                    return@withContext Result.success(emptyList())
                }

                // Hydrate a fast initial wave (up to INITIAL_FAST_HYDRATE_LIMIT = 60 items) so the first screen
                // renders immediately without lag, and queue all remaining items for automatic background streaming.
                val initialIdsToHydrate: List<Int>
                if (refs.size <= INITIAL_FAST_HYDRATE_LIMIT) {
                    initialIdsToHydrate = refs.map { it.id }
                    unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                    unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                } else {
                    val (inProgressRefs, proposedRefs) = queryPriorityCategoryIds(service, normalizedOrg)

                    val prioritizedSet = LinkedHashSet<Int>()
                    // Priority 1: InProgress items first
                    inProgressRefs.take(INITIAL_FAST_HYDRATE_LIMIT).forEach { prioritizedSet.add(it) }
                    // Priority 2: Top Proposed items
                    for (id in proposedRefs) {
                        if (prioritizedSet.size >= INITIAL_FAST_HYDRATE_LIMIT) break
                        prioritizedSet.add(id)
                    }
                    // Priority 3: Fill up to INITIAL_FAST_HYDRATE_LIMIT with most recently changed items
                    for (ref in refs) {
                        if (prioritizedSet.size >= INITIAL_FAST_HYDRATE_LIMIT) break
                        prioritizedSet.add(ref.id)
                    }

                    initialIdsToHydrate = prioritizedSet.take(INITIAL_FAST_HYDRATE_LIMIT)
                    val initialSet = initialIdsToHydrate.toSet()

                    // Queue remaining InProgress + Proposed IDs first so background streamer hydrates them next
                    val remainingHighPriority = (inProgressRefs + proposedRefs)
                        .filter { it !in initialSet }
                        .distinct()
                    val remainingHighPrioritySet = remainingHighPriority.toSet()
                    val remainingOther = refs.map { it.id }
                        .filter { it !in initialSet && it !in remainingHighPrioritySet }

                    unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(remainingHighPriority.toMutableList())
                    unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(remainingOther.toMutableList())
                }

                val rawItems = hydrateRawItemsByIds(service, normalizedOrg, initialIdsToHydrate)
                val workItems = parseAndLinkRawWorkItems(rawItems)
                workItems.forEach { hydratedSet.add(it.id) }
                unloadedProposedIdsByOrg[orgKey]?.removeAll(hydratedSet)
                unloadedOtherIdsByOrg[orgKey]?.removeAll(hydratedSet)

                Result.success(workItems)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun hasUnloadedServerItems(organization: String): Boolean {
        val orgKey = extractOrganization(organization).lowercase()
        if (orgKey.isBlank()) return false
        val propRemaining = unloadedProposedIdsByOrg[orgKey]?.isNotEmpty() == true
        val otherRemaining = unloadedOtherIdsByOrg[orgKey]?.isNotEmpty() == true
        return propRemaining || otherRemaining
    }

    /**
     * Hydrates the next batch of unloaded work items for continuous background streaming,
     * progressive scroll, or when a user selects a specific state chip.
     */
    suspend fun fetchNextWorkItemBatch(
        organization: String,
        pat: String,
        project: String? = null,
        workItemType: String? = null,
        state: String? = null,
        alreadyLoadedIds: Set<Int> = emptySet(),
        batchSize: Int = STREAM_BATCH_SIZE
    ): Result<List<WorkItem>> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val orgKey = normalizedOrg.lowercase()
            val hydratedSet = hydratedIdsByOrg.getOrPut(orgKey) {
                Collections.synchronizedSet(mutableSetOf())
            }
            hydratedSet.addAll(alreadyLoadedIds)

            val candidateIds = mutableListOf<Int>()

            // If filtering by a specific state (or project + type + state), run a targeted WIQL query
            // so items of that specific state beyond the initial 5-item cap are fetched immediately.
            if (!state.isNullOrBlank() && !state.equals("All", ignoreCase = true)) {
                val escapedState = state.trim().replace("'", "''")
                val clauses = mutableListOf("[System.State] = '$escapedState'")
                if (!project.isNullOrBlank()) {
                    clauses.add("[System.TeamProject] = '${project.trim().replace("'", "''")}'")
                }
                if (!workItemType.isNullOrBlank()) {
                    clauses.add("[System.WorkItemType] = '${workItemType.trim().replace("'", "''")}'")
                }
                val wiqlQuery = "SELECT [System.Id] FROM WorkItems WHERE ${clauses.joinToString(" AND ")} ORDER BY [System.ChangedDate] DESC"
                val targetedIds = runCatching {
                    service.queryWorkItems(
                        organization = normalizedOrg,
                        request = WiqlQueryRequest(query = wiqlQuery)
                    ).body()?.workItems.orEmpty().map { it.id }
                }.getOrDefault(emptyList())

                for (id in targetedIds) {
                    if (id !in hydratedSet) {
                        candidateIds.add(id)
                        if (candidateIds.size >= batchSize) break
                    }
                }
            }

            // Pull from queued Proposed/InProgress IDs first, then remaining IDs
            if (candidateIds.size < batchSize) {
                val propQueue = unloadedProposedIdsByOrg[orgKey]
                if (propQueue != null) {
                    synchronized(propQueue) {
                        val iterator = propQueue.iterator()
                        while (iterator.hasNext() && candidateIds.size < batchSize) {
                            val nextId = iterator.next()
                            iterator.remove()
                            if (nextId !in hydratedSet && nextId !in candidateIds) {
                                candidateIds.add(nextId)
                            }
                        }
                    }
                }
            }

            if (candidateIds.size < batchSize) {
                val otherQueue = unloadedOtherIdsByOrg[orgKey]
                if (otherQueue != null) {
                    synchronized(otherQueue) {
                        val iterator = otherQueue.iterator()
                        while (iterator.hasNext() && candidateIds.size < batchSize) {
                            val nextId = iterator.next()
                            iterator.remove()
                            if (nextId !in hydratedSet && nextId !in candidateIds) {
                                candidateIds.add(nextId)
                            }
                        }
                    }
                }
            }

            if (candidateIds.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val rawItems = hydrateRawItemsByIds(service, normalizedOrg, candidateIds)
            val parsed = parseAndLinkRawWorkItems(rawItems)
            parsed.forEach { hydratedSet.add(it.id) }
            unloadedProposedIdsByOrg[orgKey]?.removeAll(hydratedSet)
            unloadedOtherIdsByOrg[orgKey]?.removeAll(hydratedSet)

            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes a resilient server-side search across all types, states, and assignees (scoped to
     * [project] with organization-wide fallback + direct ID lookup) so any matching item in Azure DevOps
     * is guaranteed to be found, hydrated, and merged into the Home screen search results.
     */
    suspend fun searchWorkItemsOnServer(
        organization: String,
        pat: String,
        query: String,
        project: String? = null,
        alreadyLoadedIds: Set<Int> = emptySet()
    ): Result<List<WorkItem>> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        val cleanQuery = query.trim()
        if (normalizedOrg.isBlank() || pat.isBlank() || cleanQuery.isBlank()) {
            return@withContext Result.success(emptyList())
        }

        try {
            val service = AzureDevOpsClient.createService(pat)
            val orgKey = normalizedOrg.lowercase()
            val hydratedSet = hydratedIdsByOrg.getOrPut(orgKey) {
                Collections.synchronizedSet(mutableSetOf())
            }
            hydratedSet.addAll(alreadyLoadedIds)

            val escaped = cleanQuery.replace("'", "''")
            val numericId = cleanQuery.removePrefix("#").trim().toIntOrNull()
            val escapedProject = project?.trim()?.takeIf { it.isNotEmpty() }?.replace("'", "''")
            val projectPrefix = if (escapedProject != null) {
                "[System.TeamProject] = '$escapedProject' AND "
            } else {
                ""
            }

            // Run direct ID lookup, Title/Tags WIQL, and Description full-text WIQL in parallel
            val (directIdRaw, titleAndTagsIds, descIds) = coroutineScope {
                val directDef = async {
                    if (numericId != null && numericId !in hydratedSet) {
                        runCatching {
                            hydrateRawItemsByIds(service, normalizedOrg, listOf(numericId))
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }

                val titleTagsDef = async {
                    val whereClause = if (numericId != null) {
                        "${projectPrefix}([System.Id] = $numericId OR [System.Title] CONTAINS '$escaped' OR [System.Tags] CONTAINS '$escaped')"
                    } else {
                        "${projectPrefix}([System.Title] CONTAINS '$escaped' OR [System.Tags] CONTAINS '$escaped')"
                    }
                    val scopedIds = runCatching {
                        service.queryWorkItems(
                            organization = normalizedOrg,
                            request = WiqlQueryRequest(
                                query = "SELECT [System.Id] FROM WorkItems WHERE $whereClause ORDER BY [System.ChangedDate] DESC"
                            )
                        ).body()?.workItems.orEmpty().map { it.id }
                    }.getOrDefault(emptyList())

                    // If project-scoped query returned nothing, also check organization-wide
                    if (scopedIds.isEmpty() && projectPrefix.isNotEmpty()) {
                        val unscopedWhere = if (numericId != null) {
                            "[System.Id] = $numericId OR [System.Title] CONTAINS '$escaped' OR [System.Tags] CONTAINS '$escaped'"
                        } else {
                            "[System.Title] CONTAINS '$escaped' OR [System.Tags] CONTAINS '$escaped'"
                        }
                        runCatching {
                            service.queryWorkItems(
                                organization = normalizedOrg,
                                request = WiqlQueryRequest(
                                    query = "SELECT [System.Id] FROM WorkItems WHERE $unscopedWhere ORDER BY [System.ChangedDate] DESC"
                                )
                            ).body()?.workItems.orEmpty().map { it.id }
                        }.getOrDefault(emptyList())
                    } else {
                        scopedIds
                    }
                }

                val descDef = async {
                    // Azure DevOps LongText HTML fields use CONTAINS WORDS (with fallback to CONTAINS)
                    val wordsQuery = "SELECT [System.Id] FROM WorkItems WHERE ${projectPrefix}[System.Description] CONTAINS WORDS '$escaped' ORDER BY [System.ChangedDate] DESC"
                    val wordsResp = runCatching {
                        service.queryWorkItems(
                            organization = normalizedOrg,
                            request = WiqlQueryRequest(query = wordsQuery)
                        )
                    }.getOrNull()

                    if (wordsResp?.isSuccessful == true) {
                        wordsResp.body()?.workItems.orEmpty().map { it.id }
                    } else {
                        val containsQuery = "SELECT [System.Id] FROM WorkItems WHERE ${projectPrefix}[System.Description] CONTAINS '$escaped' ORDER BY [System.ChangedDate] DESC"
                        runCatching {
                            service.queryWorkItems(
                                organization = normalizedOrg,
                                request = WiqlQueryRequest(query = containsQuery)
                            ).body()?.workItems.orEmpty().map { it.id }
                        }.getOrDefault(emptyList())
                    }
                }

                Triple(directDef.await(), titleTagsDef.await(), descDef.await())
            }

            val directIds = directIdRaw.map { it.id }.toSet()
            val unloadedMatchedIds = (titleAndTagsIds + descIds)
                .distinct()
                .filter { it !in hydratedSet && it !in directIds }
                .take(100)

            val hydratedFromWiql = if (unloadedMatchedIds.isNotEmpty()) {
                hydrateRawItemsByIds(service, normalizedOrg, unloadedMatchedIds)
            } else {
                emptyList()
            }

            val combinedRaw = (directIdRaw + hydratedFromWiql).distinctBy { it.id }
            if (combinedRaw.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val parsed = parseAndLinkRawWorkItems(combinedRaw)
            parsed.forEach { hydratedSet.add(it.id) }
            unloadedProposedIdsByOrg[orgKey]?.removeAll(hydratedSet)
            unloadedOtherIdsByOrg[orgKey]?.removeAll(hydratedSet)

            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun hydrateRawItemsByIds(
        service: com.example.data.api.AzureDevOpsService,
        normalizedOrg: String,
        idsToHydrate: List<Int>
    ): List<com.example.data.model.RawWorkItem> {
        if (idsToHydrate.isEmpty()) return emptyList()
        val rawItems = mutableListOf<com.example.data.model.RawWorkItem>()
        coroutineScope {
            val chunkResults = idsToHydrate.distinct().chunked(200).map { chunk ->
                async {
                    val ids = chunk.joinToString(",")
                    var batchResponse = service.getWorkItems(
                        organization = normalizedOrg,
                        ids = ids,
                        fields = null,
                        expand = "relations"
                    )
                    if (!batchResponse.isSuccessful) {
                        batchResponse = service.getWorkItems(
                            organization = normalizedOrg,
                            ids = ids,
                            fields = null,
                            expand = null
                        )
                    }
                    if (batchResponse.isSuccessful) batchResponse.body()?.value ?: emptyList() else emptyList()
                }
            }.awaitAll()
            chunkResults.forEach { rawItems.addAll(it) }
        }

        val fetchedIds = rawItems.map { it.id }.toSet()
        val missingChildIds = rawItems.flatMap { raw ->
            raw.relations.orEmpty()
                .filter { it.rel.equals("System.LinkTypes.Hierarchy-Forward", ignoreCase = true) }
                .mapNotNull { it.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() }
        }.distinct().filter { it !in fetchedIds }

        if (missingChildIds.isNotEmpty()) {
            coroutineScope {
                val extraResults = missingChildIds.chunked(200).map { chunk ->
                    async {
                        val ids = chunk.joinToString(",")
                        try {
                            val extraResp = service.getWorkItems(
                                organization = normalizedOrg,
                                ids = ids,
                                fields = null,
                                expand = "relations"
                            )
                            if (extraResp.isSuccessful) extraResp.body()?.value ?: emptyList() else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
                extraResults.forEach { rawItems.addAll(it) }
            }
        }
        return rawItems
    }

    private fun parseAndLinkRawWorkItems(rawItems: List<com.example.data.model.RawWorkItem>): List<WorkItem> {
        val parsedItems = rawItems.distinctBy { it.id }.map { raw ->
            val f = raw.fields
            val id = raw.id
            val title = (f["System.Title"] as? String)?.trim() ?: "Work Item #$id"
            val type = (f["System.WorkItemType"] as? String)?.trim() ?: "Task"
            val state = (f["System.State"] as? String)?.trim() ?: "New"
            val teamProject = (f["System.TeamProject"] as? String)
                ?: (f["System.AreaPath"] as? String)?.substringBefore("\\")
                ?: "General"

            val assignedMap = f["System.AssignedTo"] as? Map<*, *>
            val assignedName = assignedMap?.get("displayName") as? String
            val assignedEmail = assignedMap?.get("uniqueName") as? String
            val avatarUrl = (assignedMap?.get("_links") as? Map<*, *>)
                ?.let { links -> (links["avatar"] as? Map<*, *>)?.get("href") as? String }

            val createdDate = f["System.CreatedDate"] as? String
            val changedDate = f["System.ChangedDate"] as? String
            val description = (f["System.Description"] as? String)
                ?: (f["Microsoft.VSTS.Common.AcceptanceCriteria"] as? String)
            val areaPath = f["System.AreaPath"] as? String
            val iterationPath = f["System.IterationPath"] as? String
            val priority = (f["Microsoft.VSTS.Common.Priority"] as? Number)?.toInt()

            val boardOrder = (f["Microsoft.VSTS.Common.StackRank"] as? Number)?.toDouble()
                ?: (f["Microsoft.VSTS.Common.BacklogPriority"] as? Number)?.toDouble()
                ?: f.entries.firstOrNull { (k, v) ->
                    v is Number && (
                        k.endsWith(".StackRank", ignoreCase = true) ||
                        k.endsWith(".BacklogPriority", ignoreCase = true)
                    )
                }?.let { (it.value as? Number)?.toDouble() }

            val parentIdFromField = (f["System.Parent"] as? Number)?.toInt()
                ?: (f["System.Parent"] as? String)?.trim()?.toIntOrNull()
            val parentIdFromRel = raw.relations.orEmpty()
                .firstOrNull { it.rel.equals("System.LinkTypes.Hierarchy-Reverse", ignoreCase = true) }
                ?.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull()
            val parentId = parentIdFromField ?: parentIdFromRel

            val childIdsFromRel = raw.relations.orEmpty()
                .filter { it.rel.equals("System.LinkTypes.Hierarchy-Forward", ignoreCase = true) }
                .mapNotNull { it.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() }
                .distinct()

            val rawTags = f["System.Tags"] as? String
            val tagList = rawTags?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()

            WorkItem(
                id = id,
                title = title,
                workItemType = type,
                state = state,
                teamProject = teamProject,
                assignedToName = assignedName,
                assignedToEmail = assignedEmail,
                assignedToAvatar = avatarUrl,
                createdDate = createdDate,
                changedDate = changedDate,
                description = cleanHtml(description),
                areaPath = areaPath,
                iterationPath = iterationPath,
                priority = priority,
                boardOrder = boardOrder,
                parentId = parentId,
                childIds = childIdsFromRel,
                tags = tagList
            )
        }

        val parentToChildren = mutableMapOf<Int, MutableSet<Int>>()
        val childToParent = mutableMapOf<Int, Int>()
        for (item in parsedItems) {
            if (item.parentId != null) {
                childToParent[item.id] = item.parentId
                parentToChildren.getOrPut(item.parentId) { mutableSetOf() }.add(item.id)
            }
            for (cId in item.childIds) {
                parentToChildren.getOrPut(item.id) { mutableSetOf() }.add(cId)
                if (!childToParent.containsKey(cId)) {
                    childToParent[cId] = item.id
                }
            }
        }

        return parsedItems.map { item ->
            item.copy(
                parentId = item.parentId ?: childToParent[item.id],
                childIds = parentToChildren[item.id]?.toList() ?: item.childIds
            )
        }.sortedWith(
            compareBy<WorkItem>(
                { it.boardOrder ?: Double.MAX_VALUE },
                { it.id }
            )
        )
    }

    /**
     * Persists work item changes (State, AssignedTo, Description, Tags) to Azure DevOps
     * using JSON Patch (application/json-patch+json) on /_apis/wit/workitems/{id}.
     */
    suspend fun updateWorkItemOnServer(
        organization: String,
        pat: String,
        previousItem: WorkItem?,
        updatedItem: WorkItem,
        resolvedAssigneeIdentity: String? = null
    ): Result<WorkItem> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }

        try {
            val operations = mutableListOf<JsonPatchOperation>()

            // 0. Title (/fields/System.Title)
            if (previousItem == null || previousItem.title != updatedItem.title) {
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.Title",
                        value = updatedItem.title.trim()
                    )
                )
            }

            // 1. State (/fields/System.State)
            if (previousItem == null || previousItem.state != updatedItem.state) {
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.State",
                        value = updatedItem.state
                    )
                )
            }

            // 2. Assignee (/fields/System.AssignedTo)
            val prevAssignee = previousItem?.assignedToName?.trim()?.takeUnless {
                it.isEmpty() || it.equals("Unassigned", ignoreCase = true)
            }
            val newAssignee = updatedItem.assignedToName?.trim()?.takeUnless {
                it.isEmpty() || it.equals("Unassigned", ignoreCase = true)
            }
            if (previousItem == null || prevAssignee != newAssignee) {
                val assigneeValue = if (newAssignee == null) {
                    ""
                } else {
                    resolvedAssigneeIdentity?.takeIf { it.isNotBlank() } ?: newAssignee
                }
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.AssignedTo",
                        value = assigneeValue
                    )
                )
            }

            // 3. Description (/fields/System.Description)
            val prevDesc = previousItem?.description ?: ""
            val newDesc = updatedItem.description ?: ""
            if (previousItem == null || prevDesc != newDesc) {
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.Description",
                        value = newDesc
                    )
                )
            }

            // 4. Tags (/fields/System.Tags)
            val prevTags = previousItem?.tags?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val newTags = updatedItem.tags.map { it.trim() }.filter { it.isNotBlank() }
            if (previousItem == null || prevTags != newTags) {
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.Tags",
                        value = newTags.joinToString("; ")
                    )
                )
            }

            if (operations.isEmpty()) {
                return@withContext Result.success(updatedItem)
            }

            val service = AzureDevOpsClient.createService(pat)
            val patchBody = AzureDevOpsClient.createPatchRequestBody(operations)
            val response = service.updateWorkItem(
                organization = normalizedOrg,
                id = updatedItem.id,
                patchBody = patchBody
            )

            if (response.isSuccessful) {
                Result.success(updatedItem)
            } else {
                // If System.AssignedTo with email failed or vice versa, retry with displayName
                if (newAssignee != null && resolvedAssigneeIdentity != null && resolvedAssigneeIdentity != newAssignee) {
                    val fallbackOps = operations.map { op ->
                        if (op.path == "/fields/System.AssignedTo") {
                            op.copy(value = newAssignee)
                        } else {
                            op
                        }
                    }
                    val retryResp = service.updateWorkItem(
                        organization = normalizedOrg,
                        id = updatedItem.id,
                        patchBody = AzureDevOpsClient.createPatchRequestBody(fallbackOps)
                    )
                    if (retryResp.isSuccessful) {
                        return@withContext Result.success(updatedItem)
                    }
                }
                Result.failure(
                    Exception("Failed to update work item #${updatedItem.id} (${response.code()}): ${response.message()}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a new top-level or standalone work item in Azure DevOps with all user-specified fields.
     */
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
        tags: List<String> = emptyList()
    ): Result<WorkItem> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank() || project.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials or project."))
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Work item title is required."))
        }

        try {
            val service = AzureDevOpsClient.createService(pat)
            val cleanAssignee = assignedToName?.trim()?.takeUnless {
                it.isEmpty() || it.equals("Unassigned", ignoreCase = true)
            }
            val cleanDesc = description?.trim().orEmpty()
            val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }

            val createOps = mutableListOf(
                JsonPatchOperation(
                    op = "add",
                    path = "/fields/System.Title",
                    value = cleanTitle
                )
            )
            if (cleanDesc.isNotEmpty()) {
                createOps.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.Description",
                        value = cleanDesc
                    )
                )
            }
            if (cleanTags.isNotEmpty()) {
                createOps.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.Tags",
                        value = cleanTags.joinToString("; ")
                    )
                )
            }
            if (cleanAssignee != null) {
                val assigneeVal = resolvedAssigneeIdentity?.takeIf { it.isNotBlank() } ?: cleanAssignee
                createOps.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.AssignedTo",
                        value = assigneeVal
                    )
                )
            }

            var response = service.createWorkItem(
                organization = normalizedOrg,
                project = project,
                type = workItemType,
                patchBody = AzureDevOpsClient.createPatchRequestBody(createOps)
            )

            // Fallback if assignee identity format caused a 400 error on initial creation
            if (!response.isSuccessful && cleanAssignee != null) {
                val fallbackOps = if (resolvedAssigneeIdentity != null && resolvedAssigneeIdentity != cleanAssignee) {
                    createOps.map { op ->
                        if (op.path == "/fields/System.AssignedTo") op.copy(value = cleanAssignee) else op
                    }
                } else {
                    createOps.filterNot { it.path == "/fields/System.AssignedTo" }
                }
                val retryResp = service.createWorkItem(
                    organization = normalizedOrg,
                    project = project,
                    type = workItemType,
                    patchBody = AzureDevOpsClient.createPatchRequestBody(fallbackOps)
                )
                if (retryResp.isSuccessful) {
                    response = retryResp
                }
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to create work item (${response.code()}): ${response.message()}")
                )
            }

            val createdRaw = response.body()
                ?: return@withContext Result.failure(Exception("Empty response when creating work item."))

            val createdId = createdRaw.id
            var currentRawState = (createdRaw.fields["System.State"] as? String)?.trim()
                ?: desiredState?.trim()?.takeIf { it.isNotEmpty() }
                ?: getStandardProcessStates(workItemType).firstOrNull()
                ?: "New"

            if (!desiredState.isNullOrBlank() &&
                !desiredState.equals("All", ignoreCase = true) &&
                !currentRawState.equals(desiredState.trim(), ignoreCase = true)
            ) {
                val statePatchOps = listOf(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.State",
                        value = desiredState.trim()
                    )
                )
                val stateResp = try {
                    service.updateWorkItem(
                        organization = normalizedOrg,
                        id = createdId,
                        patchBody = AzureDevOpsClient.createPatchRequestBody(statePatchOps)
                    )
                } catch (_: Exception) {
                    null
                }
                if (stateResp?.isSuccessful == true) {
                    currentRawState = desiredState.trim()
                }
            }

            val f = createdRaw.fields
            val boardOrder = (f["Microsoft.VSTS.Common.StackRank"] as? Number)?.toDouble()
                ?: (f["Microsoft.VSTS.Common.BacklogPriority"] as? Number)?.toDouble()

            Result.success(
                WorkItem(
                    id = createdId,
                    title = (f["System.Title"] as? String)?.trim() ?: cleanTitle,
                    workItemType = (f["System.WorkItemType"] as? String)?.trim() ?: workItemType,
                    state = currentRawState,
                    teamProject = (f["System.TeamProject"] as? String) ?: project,
                    assignedToName = cleanAssignee,
                    assignedToEmail = resolvedAssigneeIdentity,
                    createdDate = f["System.CreatedDate"] as? String,
                    changedDate = f["System.ChangedDate"] as? String,
                    description = cleanDesc.ifEmpty { null },
                    areaPath = (f["System.AreaPath"] as? String) ?: project,
                    iterationPath = (f["System.IterationPath"] as? String) ?: project,
                    boardOrder = boardOrder,
                    tags = cleanTags
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a new child work item in Azure DevOps and links it to [parentId]
     * using System.LinkTypes.Hierarchy-Reverse.
     */
    suspend fun createChildWorkItemOnServer(
        organization: String,
        pat: String,
        project: String,
        parentId: Int,
        title: String,
        childType: String,
        desiredState: String? = null
    ): Result<WorkItem> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank() || project.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }

        try {
            val service = AzureDevOpsClient.createService(pat)
            val parentUrl = "https://dev.azure.com/$normalizedOrg/_apis/wit/workItems/$parentId"
            val createOps = listOf(
                JsonPatchOperation(
                    op = "add",
                    path = "/fields/System.Title",
                    value = title.trim()
                ),
                JsonPatchOperation(
                    op = "add",
                    path = "/relations/-",
                    value = mapOf(
                        "rel" to "System.LinkTypes.Hierarchy-Reverse",
                        "url" to parentUrl
                    )
                )
            )

            val response = service.createWorkItem(
                organization = normalizedOrg,
                project = project,
                type = childType,
                patchBody = AzureDevOpsClient.createPatchRequestBody(createOps)
            )

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to create child work item (${response.code()}): ${response.message()}")
                )
            }

            val createdRaw = response.body()
                ?: return@withContext Result.failure(Exception("Empty response when creating child work item."))

            val createdId = createdRaw.id
            var currentRawState = (createdRaw.fields["System.State"] as? String)?.trim()
                ?: desiredState?.trim()?.takeIf { it.isNotEmpty() }
                ?: getStandardProcessStates(childType).firstOrNull()
                ?: "New"

            if (!desiredState.isNullOrBlank() && !currentRawState.equals(desiredState.trim(), ignoreCase = true)) {
                val statePatchOps = listOf(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.State",
                        value = desiredState.trim()
                    )
                )
                val stateResp = try {
                    service.updateWorkItem(
                        organization = normalizedOrg,
                        id = createdId,
                        patchBody = AzureDevOpsClient.createPatchRequestBody(statePatchOps)
                    )
                } catch (e: Exception) {
                    null
                }
                if (stateResp?.isSuccessful == true) {
                    currentRawState = desiredState.trim()
                }
            }

            val f = createdRaw.fields
            val boardOrder = (f["Microsoft.VSTS.Common.StackRank"] as? Number)?.toDouble()
                ?: (f["Microsoft.VSTS.Common.BacklogPriority"] as? Number)?.toDouble()

            Result.success(
                WorkItem(
                    id = createdId,
                    title = (f["System.Title"] as? String)?.trim() ?: title.trim(),
                    workItemType = (f["System.WorkItemType"] as? String)?.trim() ?: childType,
                    state = currentRawState,
                    teamProject = (f["System.TeamProject"] as? String) ?: project,
                    createdDate = f["System.CreatedDate"] as? String,
                    changedDate = f["System.ChangedDate"] as? String,
                    areaPath = f["System.AreaPath"] as? String,
                    iterationPath = f["System.IterationPath"] as? String,
                    boardOrder = boardOrder,
                    parentId = parentId
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches work item type -> category reference name (e.g. Microsoft.EpicCategory,
     * Microsoft.FeatureCategory, Microsoft.RequirementCategory, Microsoft.TaskCategory)
     * for a project so hierarchy levels can be determined accurately.
     */
    suspend fun fetchProjectCategoryMap(
        organization: String,
        project: String,
        pat: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || project.isBlank() || pat.isBlank()) return@withContext emptyMap()
        try {
            val service = AzureDevOpsClient.createService(pat)
            coroutineScope {
                val categoriesDeferred = async {
                    runCatching {
                        service.getWorkItemTypeCategories(
                            organization = normalizedOrg,
                            project = project
                        )
                    }.getOrNull()
                }
                val backlogsDeferred = async {
                    runCatching {
                        service.getProjectBacklogs(
                            organization = normalizedOrg,
                            project = project
                        )
                    }.getOrNull()
                }

                val categoriesResp = categoriesDeferred.await()
                val backlogsResp = backlogsDeferred.await()
                val result = mutableMapOf<String, String>()

                val relevantCategories = listOf(
                    "Microsoft.BugCategory",
                    "Microsoft.TaskCategory",
                    "Microsoft.RequirementCategory",
                    "Microsoft.FeatureCategory",
                    "Microsoft.EpicCategory"
                )
                if (categoriesResp?.isSuccessful == true) {
                    val categories = categoriesResp.body()?.value.orEmpty()
                    // Iterate from lowest priority to highest so higher-tier categories win
                    for (targetCat in relevantCategories) {
                        val matchedCat = categories.firstOrNull {
                            it.referenceName?.trim()?.equals(targetCat, ignoreCase = true) == true
                        } ?: continue
                        for (witRef in matchedCat.workItemTypes.orEmpty()) {
                            val witName = witRef.name?.trim().orEmpty()
                            if (witName.isNotBlank()) {
                                result[witName] = targetCat
                            }
                        }
                    }
                }

                // Also incorporate explicit project Backlog Level ranks (highest rank = biggest portfolio tier)
                if (backlogsResp?.isSuccessful == true) {
                    val backlogs = backlogsResp.body()?.value.orEmpty()
                        .sortedByDescending { it.rank ?: 0 }
                    if (backlogs.isNotEmpty()) {
                        val portfolioBacklogs = backlogs.filter { it.type.equals("portfolio", ignoreCase = true) }
                            .sortedByDescending { it.rank ?: 0 }
                        for ((idx, bLevel) in portfolioBacklogs.withIndex()) {
                            val mappedCat = if (idx == 0) "Microsoft.EpicCategory" else "Microsoft.FeatureCategory"
                            for (witRef in bLevel.workItemTypes.orEmpty()) {
                                val witName = witRef.name?.trim().orEmpty()
                                if (witName.isNotBlank() && !result.containsKey(witName)) {
                                    result[witName] = mappedCat
                                }
                            }
                        }
                        for (bLevel in backlogs) {
                            val bType = bLevel.type?.trim()?.lowercase()
                            val mappedCat = when (bType) {
                                "requirement" -> "Microsoft.RequirementCategory"
                                "task" -> "Microsoft.TaskCategory"
                                else -> null
                            } ?: continue
                            for (witRef in bLevel.workItemTypes.orEmpty()) {
                                val witName = witRef.name?.trim().orEmpty()
                                if (witName.isNotBlank() && !result.containsKey(witName)) {
                                    result[witName] = mappedCat
                                }
                            }
                        }
                    }
                }
                result
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun baseHierarchyScore(
        type: String,
        categoryRef: String?,
        projectWorkItems: List<WorkItem> = emptyList()
    ): Int {
        val n = type.lowercase().trim()
        // Check explicit name-based hierarchy first for fine-grained ordering within/across categories
        val nameScore = when {
            n.contains("initiative") || n.contains("theme") -> 0
            n.contains("epic") || n.contains("هدف") || n.contains("اهداف") || n.contains("حماسه") ||
                n.contains("goal") || n.contains("objective") || n.contains("کلان") -> 2
            n.contains("feature") || n.contains("ویژگی") || n.contains("قابلیت") ||
                n.contains("امکانات") || n.contains("deliverable") -> 10
            n.contains("user story") || n.contains("story") || n.contains("product backlog item") ||
                n.contains("backlog item") || n.contains("requirement") || n.contains("داستان") ||
                n.contains("نیازمندی") || n.contains("پروژه") || n.contains("خواسته") -> 20
            n.contains("issue") || n.contains("مسئله") || n.contains("impediment") || n.contains("مانع") -> 22
            n.contains("subtask") || n.contains("sub-task") || n.contains("زیرکار") -> 35
            n.contains("task") || n.contains("کار") || n.contains("وظیفه") || n.contains("اقدام") -> 30
            n.contains("bug") || n.contains("باگ") || n.contains("خطا") || n.contains("ایراد") -> 32
            else -> null
        }
        val catScore = when {
            categoryRef.equals("Microsoft.EpicCategory", ignoreCase = true) -> 2
            categoryRef.equals("Microsoft.FeatureCategory", ignoreCase = true) -> 10
            categoryRef.equals("Microsoft.RequirementCategory", ignoreCase = true) -> 20
            categoryRef.equals("Microsoft.TaskCategory", ignoreCase = true) -> 30
            categoryRef.equals("Microsoft.BugCategory", ignoreCase = true) -> 32
            else -> null
        }
        if (catScore != null && nameScore != null) {
            return if (kotlin.math.abs(catScore - nameScore) <= 4) nameScore else catScore
        }
        if (catScore != null) return catScore
        if (nameScore != null) return nameScore

        // For custom types not matched by category or name, inspect whether items of this type act as parents or leaves
        val itemsOfType = projectWorkItems.filter { it.workItemType.equals(type, ignoreCase = true) }
        if (itemsOfType.isNotEmpty()) {
            val hasChildren = itemsOfType.any { it.childIds.isNotEmpty() }
            val hasParents = itemsOfType.any { it.parentId != null }
            return when {
                hasChildren && !hasParents -> 5
                hasChildren && hasParents -> 15
                !hasChildren && hasParents -> 28
                else -> 15
            }
        }
        return 15
    }

    /**
     * Resolves the ordered hierarchy levels of active work item types for a project,
     * from biggest / top level (index 0) down to smallest / leaf level (lastIndex).
     */
    fun resolveOrderedHierarchyLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): List<List<String>> {
        val cleanedTypes = activeTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanedTypes.isEmpty()) return emptyList()

        val tierScores = mutableMapOf<String, Int>()
        val isKnownTier = mutableMapOf<String, Boolean>()
        for (type in cleanedTypes) {
            val catRef = categoryMap.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value
            tierScores[type] = baseHierarchyScore(type, catRef, projectWorkItems)
            isKnownTier[type] = catRef != null || baseHierarchyScore(type, null) != 15
        }

        // Refine tiers using observed parent -> child relationships in projectWorkItems
        if (projectWorkItems.isNotEmpty()) {
            val byId = projectWorkItems.associateBy { it.id }
            val edgeCounts = mutableMapOf<Pair<String, String>, Int>()
            for (item in projectWorkItems) {
                val pId = item.parentId ?: continue
                val parent = byId[pId] ?: continue
                val pType = cleanedTypes.firstOrNull { it.equals(parent.workItemType, ignoreCase = true) } ?: continue
                val cType = cleanedTypes.firstOrNull { it.equals(item.workItemType, ignoreCase = true) } ?: continue
                if (!pType.equals(cType, ignoreCase = true)) {
                    val key = pType to cType
                    edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
                }
            }
            // Keep dominant direction if bidirectional links exist
            val directedEdges = edgeCounts.keys.filter { (p, c) ->
                val forward = edgeCounts[p to c] ?: 0
                val backward = edgeCounts[c to p] ?: 0
                forward >= backward
            }
            repeat(cleanedTypes.size) {
                for ((pType, cType) in directedEdges) {
                    val pScore = tierScores[pType] ?: 15
                    val cScore = tierScores[cType] ?: 15
                    if (pScore >= cScore) {
                        if (isKnownTier[cType] == true && isKnownTier[pType] != true) {
                            tierScores[pType] = cScore - 5
                        } else {
                            tierScores[cType] = pScore + 5
                        }
                    }
                }
            }
        }

        return cleanedTypes
            .groupBy { tierScores[it] ?: 15 }
            .toSortedMap()
            .values
            .map { levelList ->
                levelList.distinct().sortedBy { type ->
                    val catRef = categoryMap.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value
                    baseHierarchyScore(type, catRef, projectWorkItems)
                }
            }
    }

    /**
     * Orders work item types from biggest (highest in hierarchy, e.g. Epic / هدف)
     * to smallest (lowest in hierarchy, e.g. Task / کار / Bug).
     */
    fun orderWorkItemTypesBiggestToSmallest(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): List<String> {
        return resolveOrderedHierarchyLevels(
            activeTypes = activeTypes,
            categoryMap = categoryMap,
            projectWorkItems = projectWorkItems
        ).flatten()
    }

    suspend fun fetchProjects(organization: String, pat: String): List<String> =
        withContext(Dispatchers.IO) {
            val normalizedOrg = extractOrganization(organization)
            if (normalizedOrg.isBlank() || pat.isBlank()) return@withContext emptyList()
            try {
                val service = AzureDevOpsClient.createService(pat)
                val response = service.getProjects(organization = normalizedOrg)
                if (response.isSuccessful) {
                    val list = response.body()?.value
                        .orEmpty()
                        .mapNotNull { it.name?.trim() }
                        .filter { it.isNotBlank() }
                    list.distinctBy { it.lowercase() }.sorted()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun stateCategoryRank(category: String?): Int = when (category?.trim()?.lowercase()) {
        "proposed" -> 0
        "inprogress" -> 1
        "resolved" -> 2
        "completed" -> 3
        "removed" -> 4
        else -> 2
    }

    /**
     * Resolves active work item types and ONLY their enabled (non-hidden, non-disabled) states
     * for [project] under [organization].
     */
    suspend fun fetchProjectProcessHierarchy(
        organization: String,
        project: String,
        pat: String
    ): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || project.isBlank() || pat.isBlank()) {
            return@withContext emptyMap()
        }
        try {
            val service = AzureDevOpsClient.createService(pat)

            coroutineScope {
                val projectDetailDeferred = async {
                    runCatching {
                        service.getProjectDetail(
                            organization = normalizedOrg,
                            project = project,
                            includeCapabilities = true
                        )
                    }.getOrNull()
                }
                val processesDeferred = async {
                    runCatching {
                        service.getProcesses(organization = normalizedOrg)
                    }.getOrNull()
                }
                val witListDeferred = async {
                    runCatching {
                        service.getProjectWorkItemTypes(
                            organization = normalizedOrg,
                            project = project
                        )
                    }.getOrNull()
                }

                val projectDetailResp = projectDetailDeferred.await()
                val processesResp = processesDeferred.await()
                val witListResp = witListDeferred.await()

                val processIdFromProject = projectDetailResp?.body()
                    ?.capabilities
                    ?.processTemplate
                    ?.templateTypeId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val processIdFromList = processesResp?.body()?.value.orEmpty()
                    .firstOrNull { proc ->
                        proc.projects.orEmpty().any { pRef ->
                            pRef.name?.equals(project, ignoreCase = true) == true ||
                                pRef.id?.equals(project, ignoreCase = true) == true
                        }
                    }
                    ?.typeId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val resolvedProcessId = processIdFromProject ?: processIdFromList

                val processWorkItemTypes = if (!resolvedProcessId.isNullOrBlank()) {
                    runCatching {
                        service.getProcessWorkItemTypes(
                            organization = normalizedOrg,
                            processId = resolvedProcessId,
                            expand = "states"
                        )
                    }.getOrNull()?.body()?.value.orEmpty()
                } else {
                    emptyList()
                }

                val projectWorkItemTypes = witListResp?.body()?.value.orEmpty()

                val disabledTypeNames = buildSet {
                    processWorkItemTypes.filter { it.isDisabled == true }
                        .mapNotNull { it.name?.trim()?.lowercase() }
                        .forEach { add(it) }
                    projectWorkItemTypes.filter { it.isDisabled == true }
                        .mapNotNull { it.name?.trim()?.lowercase() }
                        .forEach { add(it) }
                }

                // Candidate active work item types from Process API and Project WIT API
                data class TypeCandidate(
                    val name: String,
                    val referenceName: String?,
                    val color: String?,
                    val expandedProcessStates: List<com.example.data.model.ProcessStateDefinition>,
                    val inlineWitStates: List<com.example.data.model.WorkItemStateDefinition>
                )

                val candidateByLowerName = linkedMapOf<String, TypeCandidate>()

                for (pWit in processWorkItemTypes) {
                    val name = pWit.name?.trim().orEmpty()
                    if (name.isBlank() ||
                        pWit.isDisabled == true ||
                        name.lowercase() in disabledTypeNames ||
                        isExcludedNonWorkItemType(name)
                    ) {
                        continue
                    }
                    candidateByLowerName[name.lowercase()] = TypeCandidate(
                        name = name,
                        referenceName = pWit.referenceName?.trim()?.takeIf { it.isNotBlank() },
                        color = pWit.color,
                        expandedProcessStates = pWit.states.orEmpty(),
                        inlineWitStates = emptyList()
                    )
                }

                for (wWit in projectWorkItemTypes) {
                    val name = wWit.name?.trim().orEmpty()
                    if (name.isBlank() ||
                        wWit.isDisabled == true ||
                        name.lowercase() in disabledTypeNames ||
                        isExcludedNonWorkItemType(name)
                    ) {
                        continue
                    }
                    val key = name.lowercase()
                    val existing = candidateByLowerName[key]
                    if (existing != null) {
                        candidateByLowerName[key] = existing.copy(
                            referenceName = existing.referenceName ?: wWit.referenceName?.trim()?.takeIf { it.isNotBlank() },
                            color = existing.color ?: wWit.color,
                            inlineWitStates = wWit.states.orEmpty()
                        )
                    } else if (processWorkItemTypes.isEmpty()) {
                        // Only add types not in processWorkItemTypes if Process API wasn't available
                        candidateByLowerName[key] = TypeCandidate(
                            name = name,
                            referenceName = wWit.referenceName?.trim()?.takeIf { it.isNotBlank() },
                            color = wWit.color,
                            expandedProcessStates = emptyList(),
                            inlineWitStates = wWit.states.orEmpty()
                        )
                    }
                }

                if (candidateByLowerName.isEmpty()) {
                    return@coroutineScope emptyMap()
                }

                // Fetch exact per-type states in parallel from both Process States API (which includes hidden=true)
                // and WIT WorkItemType States API (which returns runtime enabled states for the type)
                val perTypeResults = candidateByLowerName.values.map { candidate ->
                    async {
                        registerAzureTypeColor(candidate.name, candidate.color)

                        val processStatesFromEndpoint = if (!resolvedProcessId.isNullOrBlank() && !candidate.referenceName.isNullOrBlank()) {
                            runCatching {
                                service.getProcessWorkItemTypeStates(
                                    organization = normalizedOrg,
                                    processId = resolvedProcessId,
                                    witRefName = candidate.referenceName
                                )
                            }.getOrNull()?.body()?.value.orEmpty()
                        } else {
                            emptyList()
                        }

                        val witStatesFromEndpoint = runCatching {
                            service.getProjectWorkItemTypeStates(
                                organization = normalizedOrg,
                                project = project,
                                type = candidate.name
                            )
                        }.getOrNull()?.body()?.value.orEmpty()

                        val allProcessStates = (processStatesFromEndpoint + candidate.expandedProcessStates)
                        val allWitStates = witStatesFromEndpoint.ifEmpty { candidate.inlineWitStates }

                        // Any state marked hidden == true in Process States or WIT States is disabled/hidden
                        val hiddenStateNames = buildSet {
                            allProcessStates.filter { it.hidden == true }
                                .mapNotNull { it.name?.trim()?.lowercase() }
                                .forEach { add(it) }
                            allWitStates.filter { it.hidden == true }
                                .mapNotNull { it.name?.trim()?.lowercase() }
                                .forEach { add(it) }
                        }

                        val enabledFromProcess = (processStatesFromEndpoint.ifEmpty { candidate.expandedProcessStates })
                            .filter { stateDef ->
                                val sName = stateDef.name?.trim().orEmpty()
                                sName.isNotEmpty() &&
                                    stateDef.hidden != true &&
                                    sName.lowercase() !in hiddenStateNames
                            }
                            .sortedWith(
                                compareBy<com.example.data.model.ProcessStateDefinition>(
                                    { stateCategoryRank(it.stateCategory) },
                                    { it.order ?: 999 }
                                )
                            )
                            .mapNotNull { stateDef ->
                                val sName = stateDef.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                registerAzureStateColor(sName, stateDef.color)
                                registerStateCategory(sName, stateDef.stateCategory, project, candidate.name)
                                sName
                            }
                            .distinctBy { it.lowercase() }

                        val enabledFromWit = allWitStates
                            .filter { stateDef ->
                                val sName = stateDef.name?.trim().orEmpty()
                                sName.isNotEmpty() &&
                                    stateDef.hidden != true &&
                                    sName.lowercase() !in hiddenStateNames
                            }
                            .sortedBy { stateCategoryRank(it.category) }
                            .mapNotNull { stateDef ->
                                val sName = stateDef.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                registerAzureStateColor(sName, stateDef.color)
                                registerStateCategory(sName, stateDef.category, project, candidate.name)
                                sName
                            }
                            .distinctBy { it.lowercase() }

                        val finalStates = when {
                            enabledFromProcess.isNotEmpty() && witStatesFromEndpoint.isNotEmpty() -> {
                                // Keep Process API order & hidden filtering, and also intersect with WIT active states
                                val witLowerSet = enabledFromWit.map { it.lowercase() }.toSet()
                                val intersected = enabledFromProcess.filter { it.lowercase() in witLowerSet }
                                intersected.ifEmpty { enabledFromProcess }
                            }
                            enabledFromProcess.isNotEmpty() -> enabledFromProcess
                            enabledFromWit.isNotEmpty() -> enabledFromWit
                            else -> emptyList()
                        }

                        candidate.name to finalStates
                    }
                }.awaitAll()

                val unorderedMap = linkedMapOf<String, List<String>>()
                for ((typeName, states) in perTypeResults) {
                    if (states.isNotEmpty()) {
                        unorderedMap[typeName] = states
                    }
                }
                val orderedKeys = orderWorkItemTypesBiggestToSmallest(unorderedMap.keys.toList())
                val resultMap = linkedMapOf<String, List<String>>()
                for (key in orderedKeys) {
                    unorderedMap[key]?.let { resultMap[key] = it }
                }
                resultMap
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getKnownProjectProcessHierarchy(project: String): Map<String, List<String>> {
        return mapOf(
            "Epic" to listOf("New", "Active", "Resolved", "Closed"),
            "Feature" to listOf("New", "Active", "Resolved", "Closed"),
            "User Story" to listOf("New", "Active", "Resolved", "Closed"),
            "Task" to listOf("To Do", "In Progress", "Done"),
            "Bug" to listOf("New", "Active", "Resolved", "Closed")
        )
    }

    fun getStandardProcessStates(type: String): List<String> {
        val clean = type.lowercase().trim()
        return when {
            clean.contains("bug") || clean.contains("باگ") -> listOf("New", "Active", "Resolved", "Closed")
            clean.contains("task") || clean.contains("وظیفه") -> listOf("To Do", "In Progress", "Done")
            clean.contains("کار") -> listOf("کارها", "امروز", "تکمیل", "منتفی")
            clean.contains("story") || clean.contains("داستان") -> listOf("New", "Active", "Resolved", "Closed")
            clean.contains("feature") || clean.contains("ویژگی") -> listOf("New", "Active", "Resolved", "Closed")
            clean.contains("epic") || clean.contains("هدف") -> listOf("New", "Active", "Resolved", "Closed")
            clean.contains("issue") -> listOf("To Do", "Doing", "Done")
            else -> listOf("New", "Active", "Closed")
        }
    }

    private fun cleanHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }
}
