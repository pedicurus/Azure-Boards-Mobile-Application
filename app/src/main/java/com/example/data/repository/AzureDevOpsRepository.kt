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
import com.example.ui.theme.getRegisteredStateColorsHex
import com.example.ui.theme.getRegisteredTypeColorsHex
import com.example.ui.theme.getRegisteredTypeIcons
import com.example.ui.theme.registerAzureStateColor
import com.example.ui.theme.registerAzureTypeColor
import com.example.ui.theme.registerAzureTypeIcon
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
    private val cachedProjectsByOrgMemory = ConcurrentHashMap<String, List<String>>()

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

        private val IN_PROGRESS_STATE_NAMES = setOf(
            "in progress", "inprogress", "active", "doing", "committed",
            "در راه", "امروز", "در حال انجام", "فعال"
        )
        private val PROPOSED_STATE_NAMES = setOf(
            "new", "to do", "todo", "proposed", "approved", "design", "open", "requested",
            "اهداف", "کارها", "پیشنهادی", "جدید"
        )
        private val RESOLVED_STATE_NAMES = setOf(
            "resolved", "ready", "حل شده", "آماده"
        )
        private val COMPLETED_STATE_NAMES = setOf(
            "done", "closed", "completed", "تکمیل", "انجام شده", "بسته"
        )
        private val REMOVED_STATE_NAMES = setOf(
            "removed", "cut", "منتفی", "حذف شده", "لغو"
        )

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
        meta.typeColors.forEach { (k, v) ->
            registerAzureTypeColor(k, v)
        }
        meta.typeIcons.forEach { (k, v) ->
            registerAzureTypeIcon(k, v)
        }
        meta.stateColors.forEach { (k, v) ->
            registerAzureStateColor(k, v)
        }
        return meta
    }

    suspend fun saveCachedOrganizationMetadata(
        organization: String,
        processHierarchyByProject: Map<String, Map<String, List<String>>>,
        categoryMapByProject: Map<String, Map<String, String>>,
        backlogLevelsByProject: Map<String, List<List<String>>> = emptyMap(),
        areasByProject: Map<String, List<String>> = emptyMap(),
        iterationsByProject: Map<String, List<String>> = emptyMap()
    ) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        localStore.saveMetadata(
            CachedOrganizationMetadata(
                organization = normOrg,
                processHierarchyByProject = processHierarchyByProject,
                categoryMapByProject = categoryMapByProject,
                backlogLevelsByProject = backlogLevelsByProject,
                areasByProject = areasByProject,
                iterationsByProject = iterationsByProject,
                stateCategoryMap = stateCategoryCache.toMap(),
                typeColors = getRegisteredTypeColorsHex(),
                typeIcons = getRegisteredTypeIcons(),
                stateColors = getRegisteredStateColorsHex()
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

    fun resolveStateCategoryForWorkItem(item: WorkItem): String {
        val cleanState = item.normStateLower
        if (cleanState.isEmpty()) return CATEGORY_PROPOSED

        val projLower = item.normTeamProjectLower
        val typeLower = item.normTypeLower
        if (projLower.isNotEmpty() && typeLower.isNotEmpty()) {
            val scopedKey = "$projLower|$typeLower|$cleanState"
            stateCategoryCache[scopedKey]?.let { return it }
        }
        stateCategoryCache[cleanState]?.let { return it }

        val resolved = when (cleanState) {
            in IN_PROGRESS_STATE_NAMES -> CATEGORY_IN_PROGRESS
            in PROPOSED_STATE_NAMES -> CATEGORY_PROPOSED
            in RESOLVED_STATE_NAMES -> CATEGORY_RESOLVED
            in COMPLETED_STATE_NAMES -> CATEGORY_COMPLETED
            in REMOVED_STATE_NAMES -> CATEGORY_REMOVED
            else -> CATEGORY_PROPOSED
        }
        stateCategoryCache[cleanState] = resolved
        return resolved
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

        val resolved = when (cleanState) {
            in IN_PROGRESS_STATE_NAMES -> CATEGORY_IN_PROGRESS
            in PROPOSED_STATE_NAMES -> CATEGORY_PROPOSED
            in RESOLVED_STATE_NAMES -> CATEGORY_RESOLVED
            in COMPLETED_STATE_NAMES -> CATEGORY_COMPLETED
            in REMOVED_STATE_NAMES -> CATEGORY_REMOVED
            else -> CATEGORY_PROPOSED
        }
        stateCategoryCache[cleanState] = resolved
        return resolved
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
        val orgKey = normOrg.lowercase()
        cachedProjectsByOrgMemory[orgKey]?.let { return it }

        val raw = prefs.getString(KEY_CACHED_PROJECTS_PREFIX + orgKey, null)
            ?: return emptyList()
        val parsed = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        cachedProjectsByOrgMemory[orgKey] = parsed
        return parsed
    }

    fun saveCachedProjectsForOrg(organization: String, projects: List<String>) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        val orgKey = normOrg.lowercase()
        val cleaned = projects.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (cleaned.isNotEmpty()) {
            cachedProjectsByOrgMemory[orgKey] = cleaned
            prefs.edit()
                .putString(KEY_CACHED_PROJECTS_PREFIX + orgKey, cleaned.joinToString("|"))
                .apply()
        }
    }

    fun clearCredentials() {
        cachedProjectsByOrgMemory.clear()
        localStore.clearAllCaches()
        prefs.edit()
            .remove(KEY_SAVED_ORGS)
            .remove(KEY_DEFAULT_ORG)
            .apply()
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

            // 5. Area Path (/fields/System.AreaPath)
            val prevArea = previousItem?.areaPath?.trim()?.takeIf { it.isNotBlank() }
            val newArea = updatedItem.areaPath?.trim()?.takeIf { it.isNotBlank() }
            if (newArea != null && (previousItem == null || !newArea.equals(prevArea, ignoreCase = false))) {
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.AreaPath",
                        value = newArea
                    )
                )
            }

            // 6. Iteration Path (/fields/System.IterationPath)
            val prevIteration = previousItem?.iterationPath?.trim()?.takeIf { it.isNotBlank() }
            val newIteration = updatedItem.iterationPath?.trim()?.takeIf { it.isNotBlank() }
            if (newIteration != null && (previousItem == null || !newIteration.equals(prevIteration, ignoreCase = false))) {
                operations.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.IterationPath",
                        value = newIteration
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
        tags: List<String> = emptyList(),
        areaPath: String? = null,
        iterationPath: String? = null
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
            val cleanAreaPath = areaPath?.trim()?.takeIf { it.isNotBlank() }
            val cleanIterationPath = iterationPath?.trim()?.takeIf { it.isNotBlank() }

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
            if (cleanAreaPath != null) {
                createOps.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.AreaPath",
                        value = cleanAreaPath
                    )
                )
            }
            if (cleanIterationPath != null) {
                createOps.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/fields/System.IterationPath",
                        value = cleanIterationPath
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
                    areaPath = (f["System.AreaPath"] as? String) ?: cleanAreaPath ?: project,
                    iterationPath = (f["System.IterationPath"] as? String) ?: cleanIterationPath ?: project,
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
     * Links an existing work item [childId] in Azure DevOps as a child of [parentId]
     * using System.LinkTypes.Hierarchy-Reverse (replacing any previous parent relation if present).
     */
    suspend fun linkExistingChildWorkItemOnServer(
        organization: String,
        pat: String,
        parentId: Int,
        childId: Int
    ): Result<WorkItem> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }
        if (childId <= 0) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid work item ID."))
        }
        if (childId == parentId) {
            return@withContext Result.failure(IllegalArgumentException("A work item cannot be a child of itself."))
        }

        try {
            val service = AzureDevOpsClient.createService(pat)
            val fetchResp = service.getWorkItems(
                organization = normalizedOrg,
                ids = childId.toString(),
                fields = null,
                expand = "relations"
            )
            val existingRaw = fetchResp.body()?.value?.firstOrNull { it.id == childId }
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Work item #$childId was not found in Azure DevOps.")
                )

            val existingRelations = existingRaw.relations.orEmpty()
            val forwardTargetIds = existingRelations
                .filter { it.rel.equals("System.LinkTypes.Hierarchy-Forward", ignoreCase = true) }
                .mapNotNull { it.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() }
            if (parentId in forwardTargetIds) {
                return@withContext Result.failure(
                    IllegalArgumentException("Work item #$childId is the parent of #$parentId and cannot be added as its child.")
                )
            }

            val existingParentRelIndex = existingRelations.indexOfFirst {
                it.rel.equals("System.LinkTypes.Hierarchy-Reverse", ignoreCase = true)
            }
            val currentParentIdOnServer = if (existingParentRelIndex >= 0) {
                existingRelations[existingParentRelIndex].url
                    ?.trimEnd('/')
                    ?.substringAfterLast('/')
                    ?.toIntOrNull()
            } else {
                null
            }

            if (currentParentIdOnServer != parentId) {
                val parentUrl = "https://dev.azure.com/$normalizedOrg/_apis/wit/workItems/$parentId"
                val patchOps = mutableListOf<JsonPatchOperation>()
                if (existingParentRelIndex >= 0) {
                    patchOps.add(
                        JsonPatchOperation(
                            op = "remove",
                            path = "/relations/$existingParentRelIndex",
                            value = null
                        )
                    )
                }
                patchOps.add(
                    JsonPatchOperation(
                        op = "add",
                        path = "/relations/-",
                        value = mapOf(
                            "rel" to "System.LinkTypes.Hierarchy-Reverse",
                            "url" to parentUrl
                        )
                    )
                )

                val patchResp = service.updateWorkItem(
                    organization = normalizedOrg,
                    id = childId,
                    patchBody = AzureDevOpsClient.createPatchRequestBody(patchOps)
                )

                if (!patchResp.isSuccessful) {
                    // Fallback: try adding Hierarchy-Forward relation on the parent work item
                    val childUrl = "https://dev.azure.com/$normalizedOrg/_apis/wit/workItems/$childId"
                    val parentPatchOps = listOf(
                        JsonPatchOperation(
                            op = "add",
                            path = "/relations/-",
                            value = mapOf(
                                "rel" to "System.LinkTypes.Hierarchy-Forward",
                                "url" to childUrl
                            )
                        )
                    )
                    val fallbackResp = service.updateWorkItem(
                        organization = normalizedOrg,
                        id = parentId,
                        patchBody = AzureDevOpsClient.createPatchRequestBody(parentPatchOps)
                    )
                    if (!fallbackResp.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Failed to link work item #$childId as child (${patchResp.code()}): ${patchResp.message()}")
                        )
                    }
                }
            }

            val parsedChild = parseAndLinkRawWorkItems(listOf(existingRaw)).firstOrNull()
                ?: return@withContext Result.failure(Exception("Failed to parse work item #$childId."))

            Result.success(parsedChild.copy(parentId = parentId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes the parent-child hierarchy relation between [parentId] and [childId] in Azure DevOps
     * without deleting either work item.
     */
    suspend fun unlinkChildWorkItemOnServer(
        organization: String,
        pat: String,
        parentId: Int,
        childId: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)

            // 1. Try removing System.LinkTypes.Hierarchy-Reverse from the child work item
            val childResp = service.getWorkItems(
                organization = normalizedOrg,
                ids = childId.toString(),
                fields = null,
                expand = "relations"
            )
            val childRaw = childResp.body()?.value?.firstOrNull { it.id == childId }
            val childRelations = childRaw?.relations.orEmpty()
            val reverseIdx = childRelations.indexOfFirst { rel ->
                rel.rel.equals("System.LinkTypes.Hierarchy-Reverse", ignoreCase = true) &&
                    (rel.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() == parentId)
            }.takeIf { it >= 0 } ?: childRelations.indexOfFirst { rel ->
                rel.rel.equals("System.LinkTypes.Hierarchy-Reverse", ignoreCase = true)
            }

            if (reverseIdx >= 0) {
                val removeOps = listOf(
                    JsonPatchOperation(
                        op = "remove",
                        path = "/relations/$reverseIdx",
                        value = null
                    )
                )
                val patchResp = service.updateWorkItem(
                    organization = normalizedOrg,
                    id = childId,
                    patchBody = AzureDevOpsClient.createPatchRequestBody(removeOps)
                )
                if (patchResp.isSuccessful) {
                    return@withContext Result.success(Unit)
                }
            }

            // 2. Fallback: try removing System.LinkTypes.Hierarchy-Forward from the parent work item
            val parentResp = service.getWorkItems(
                organization = normalizedOrg,
                ids = parentId.toString(),
                fields = null,
                expand = "relations"
            )
            val parentRaw = parentResp.body()?.value?.firstOrNull { it.id == parentId }
            val parentRelations = parentRaw?.relations.orEmpty()
            val forwardIdx = parentRelations.indexOfFirst { rel ->
                rel.rel.equals("System.LinkTypes.Hierarchy-Forward", ignoreCase = true) &&
                    (rel.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() == childId)
            }

            if (forwardIdx >= 0) {
                val removeOps = listOf(
                    JsonPatchOperation(
                        op = "remove",
                        path = "/relations/$forwardIdx",
                        value = null
                    )
                )
                val patchResp = service.updateWorkItem(
                    organization = normalizedOrg,
                    id = parentId,
                    patchBody = AzureDevOpsClient.createPatchRequestBody(removeOps)
                )
                if (patchResp.isSuccessful) {
                    return@withContext Result.success(Unit)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class ProjectBacklogMetadata(
        val categoryMap: Map<String, String>,
        val orderedBacklogLevels: List<List<String>>
    )

    /**
     * Resolves the authoritative Azure Boards Backlog Levels (Portfolio Backlogs -> Requirement Backlog ->
     * Iteration Backlog) and work item type category mappings for [project].
     *
     * Handles:
     * 1. Inherited & completely customized work item types via Process Work Item Type Behaviors
     *    (`_apis/work/processes/{processId}/workitemtypes?$expand=states,behaviors`) and Process Behaviors
     *    (`_apis/work/processes/{processId}/behaviors`).
     * 2. Project Backlog Configuration (`_apis/work/backlogconfiguration` & `_apis/work/backlogs`),
     *    including `hiddenBacklogs` and `bugsBehavior`.
     * 3. Disabled work item types (`isDisabled == true`): when an organization disables all work item
     *    types in the Iteration Backlog (or hides the Iteration Backlog), the Iteration Backlog tier
     *    becomes empty and is omitted, automatically making Requirement Backlog the last active level.
     */
    suspend fun fetchProjectBacklogMetadata(
        organization: String,
        project: String,
        pat: String,
        activeTypesHint: List<String> = emptyList()
    ): ProjectBacklogMetadata = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || project.isBlank() || pat.isBlank()) {
            return@withContext ProjectBacklogMetadata(emptyMap(), emptyList())
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
                val backlogConfigDeferred = async {
                    runCatching {
                        service.getProjectBacklogConfiguration(
                            organization = normalizedOrg,
                            project = project
                        )
                    }.getOrNull()
                }

                val projectDetailResp = projectDetailDeferred.await()
                val processesResp = processesDeferred.await()
                val witListResp = witListDeferred.await()
                val categoriesResp = categoriesDeferred.await()
                val backlogsResp = backlogsDeferred.await()
                val backlogConfigResp = backlogConfigDeferred.await()

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

                val (processWorkItemTypes, processBehaviors) = if (!resolvedProcessId.isNullOrBlank()) {
                    val pWitsDef = async {
                        runCatching {
                            service.getProcessWorkItemTypes(
                                organization = normalizedOrg,
                                processId = resolvedProcessId,
                                expand = "states,behaviors"
                            )
                        }.getOrNull()?.body()?.value.orEmpty()
                    }
                    val pBehaviorsDef = async {
                        runCatching {
                            service.getProcessBehaviors(
                                organization = normalizedOrg,
                                processId = resolvedProcessId
                            )
                        }.getOrNull()?.body()?.value.orEmpty()
                    }
                    pWitsDef.await() to pBehaviorsDef.await()
                } else {
                    emptyList<com.example.data.model.ProcessWorkItemType>() to
                        emptyList<com.example.data.model.ProcessBehaviorDefinition>()
                }

                val projectWorkItemTypes = witListResp?.body()?.value.orEmpty()

                val disabledTypeNamesLower = buildSet {
                    processWorkItemTypes.filter { it.isDisabled == true }
                        .mapNotNull { it.name?.trim()?.lowercase() }
                        .forEach { add(it) }
                    projectWorkItemTypes.filter { it.isDisabled == true }
                        .mapNotNull { it.name?.trim()?.lowercase() }
                        .forEach { add(it) }
                }

                val enabledCanonicalByLower = linkedMapOf<String, String>()
                for (pWit in processWorkItemTypes) {
                    val name = pWit.name?.trim().orEmpty()
                    if (name.isNotBlank()) {
                        registerAzureTypeColor(name, pWit.color)
                        registerAzureTypeIcon(name, pWit.icon)
                    }
                    if (name.isNotBlank() &&
                        pWit.isDisabled != true &&
                        name.lowercase() !in disabledTypeNamesLower &&
                        !isExcludedNonWorkItemType(name)
                    ) {
                        enabledCanonicalByLower[name.lowercase()] = name
                    }
                }
                for (wWit in projectWorkItemTypes) {
                    val name = wWit.name?.trim().orEmpty()
                    if (name.isNotBlank()) {
                        registerAzureTypeColor(name, wWit.color)
                        registerAzureTypeIcon(name, wWit.icon?.id)
                    }
                }
                if (enabledCanonicalByLower.isEmpty()) {
                    for (wWit in projectWorkItemTypes) {
                        val name = wWit.name?.trim().orEmpty()
                        if (name.isNotBlank() &&
                            wWit.isDisabled != true &&
                            name.lowercase() !in disabledTypeNamesLower &&
                            !isExcludedNonWorkItemType(name)
                        ) {
                            enabledCanonicalByLower.putIfAbsent(name.lowercase(), name)
                        }
                    }
                }
                for (hint in activeTypesHint) {
                    val name = hint.trim()
                    if (name.isNotBlank() &&
                        name.lowercase() !in disabledTypeNamesLower &&
                        !isExcludedNonWorkItemType(name)
                    ) {
                        enabledCanonicalByLower.putIfAbsent(name.lowercase(), name)
                    }
                }

                fun resolveEnabledTypeName(rawName: String?): String? {
                    val clean = rawName?.trim()?.takeIf { it.isNotBlank() } ?: return null
                    val lower = clean.lowercase()
                    if (lower in disabledTypeNamesLower || isExcludedNonWorkItemType(clean)) {
                        return null
                    }
                    return if (enabledCanonicalByLower.isNotEmpty()) {
                        enabledCanonicalByLower[lower]
                    } else {
                        clean
                    }
                }

                val backlogConfig = backlogConfigResp?.body()
                val hiddenBacklogSet = backlogConfig?.hiddenBacklogs.orEmpty()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()

                fun isBacklogHidden(id: String?, name: String?): Boolean {
                    val idLow = id?.trim()?.lowercase()
                    val nameLow = name?.trim()?.lowercase()
                    return (idLow != null && idLow in hiddenBacklogSet) ||
                        (nameLow != null && nameLow in hiddenBacklogSet)
                }

                // 1. Collect Portfolio Backlog buckets (rank -> list of enabled type names)
                val portfolioBucketsByKey = linkedMapOf<String, Pair<Int, MutableList<String>>>()
                val requirementTypes = mutableListOf<String>()
                val iterationTypes = mutableListOf<String>()

                var isIterationBacklogExplicitlyHidden = false

                // Source A: Process Behaviors + Process Work Item Types (authoritative for customized/inherited processes)
                if (processWorkItemTypes.isNotEmpty()) {
                    val behaviorById = processBehaviors.associateBy {
                        it.referenceName?.trim()?.lowercase().orEmpty()
                    }
                    for (pWit in processWorkItemTypes) {
                        if (pWit.isDisabled == true) continue
                        val resolvedName = resolveEnabledTypeName(pWit.name) ?: continue
                        val witBehaviors = pWit.behaviors.orEmpty()
                        for (witBehavior in witBehaviors) {
                            val bId = witBehavior.behavior?.id?.trim()?.takeIf { it.isNotBlank() } ?: continue
                            val bIdLower = bId.lowercase()
                            val bDef = behaviorById[bIdLower]
                            val parentRefLower = (bDef?.inherits?.behaviorRefName ?: bDef?.inherits?.id)
                                ?.trim()?.lowercase().orEmpty()
                            val rank = bDef?.rank

                            when {
                                bIdLower == "system.taskbacklogbehavior" ||
                                    parentRefLower == "system.taskbacklogbehavior" ||
                                    bIdLower.endsWith(".taskbacklogbehavior") -> {
                                    if (!isBacklogHidden(bId, bDef?.name)) {
                                        iterationTypes.add(resolvedName)
                                    } else {
                                        isIterationBacklogExplicitlyHidden = true
                                    }
                                }
                                bIdLower == "system.requirementbacklogbehavior" ||
                                    parentRefLower == "system.requirementbacklogbehavior" ||
                                    bIdLower.endsWith(".requirementbacklogbehavior") -> {
                                    if (!isBacklogHidden(bId, bDef?.name)) {
                                        requirementTypes.add(resolvedName)
                                    }
                                }
                                bIdLower == "system.portfoliobacklogbehavior" ||
                                    parentRefLower == "system.portfoliobacklogbehavior" ||
                                    bIdLower.contains("portfolio") ||
                                    (rank != null && rank > 100) -> {
                                    if (!isBacklogHidden(bId, bDef?.name)) {
                                        val effectiveRank = rank ?: 200
                                        val bucket = portfolioBucketsByKey.getOrPut(bIdLower) {
                                            effectiveRank to mutableListOf()
                                        }
                                        bucket.second.add(resolvedName)
                                    }
                                }
                            }
                        }
                    }
                }

                // Source B: Project Backlog Configuration (_apis/work/backlogconfiguration)
                if (backlogConfig != null) {
                    val configPortfolios = backlogConfig.portfolioBacklogs.orEmpty()
                        .sortedByDescending { it.rank ?: 0 }
                    for ((idx, pLevel) in configPortfolios.withIndex()) {
                        if (isBacklogHidden(pLevel.id, pLevel.name)) continue
                        val key = pLevel.id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                            ?: "portfolio_cfg_$idx"
                        val effectiveRank = pLevel.rank ?: (500 - idx * 50)
                        val bucket = portfolioBucketsByKey.getOrPut(key) {
                            effectiveRank to mutableListOf()
                        }
                        for (witRef in pLevel.workItemTypes.orEmpty()) {
                            val resolved = resolveEnabledTypeName(witRef.name) ?: continue
                            bucket.second.add(resolved)
                        }
                    }

                    val reqLevel = backlogConfig.requirementBacklog
                    if (reqLevel != null && !isBacklogHidden(reqLevel.id, reqLevel.name)) {
                        for (witRef in reqLevel.workItemTypes.orEmpty()) {
                            val resolved = resolveEnabledTypeName(witRef.name) ?: continue
                            requirementTypes.add(resolved)
                        }
                    }

                    val taskLevel = backlogConfig.taskBacklog
                    if (taskLevel != null) {
                        if (isBacklogHidden(taskLevel.id, taskLevel.name)) {
                            isIterationBacklogExplicitlyHidden = true
                        } else {
                            for (witRef in taskLevel.workItemTypes.orEmpty()) {
                                val resolved = resolveEnabledTypeName(witRef.name) ?: continue
                                iterationTypes.add(resolved)
                            }
                        }
                    }
                }

                // Source C: Project Backlogs (_apis/work/backlogs)
                if (backlogsResp?.isSuccessful == true) {
                    val backlogs = backlogsResp.body()?.value.orEmpty()
                        .sortedByDescending { it.rank ?: 0 }
                    val portfolioList = backlogs.filter { it.type.equals("portfolio", ignoreCase = true) }
                        .sortedByDescending { it.rank ?: 0 }
                    for ((idx, bLevel) in portfolioList.withIndex()) {
                        if (isBacklogHidden(bLevel.id, bLevel.name)) continue
                        val key = bLevel.id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                            ?: "portfolio_bl_$idx"
                        val effectiveRank = bLevel.rank ?: (500 - idx * 50)
                        val bucket = portfolioBucketsByKey.getOrPut(key) {
                            effectiveRank to mutableListOf()
                        }
                        for (witRef in bLevel.workItemTypes.orEmpty()) {
                            val resolved = resolveEnabledTypeName(witRef.name) ?: continue
                            bucket.second.add(resolved)
                        }
                    }
                    for (bLevel in backlogs) {
                        if (isBacklogHidden(bLevel.id, bLevel.name)) continue
                        when (bLevel.type?.trim()?.lowercase()) {
                            "requirement" -> {
                                for (witRef in bLevel.workItemTypes.orEmpty()) {
                                    val resolved = resolveEnabledTypeName(witRef.name) ?: continue
                                    requirementTypes.add(resolved)
                                }
                            }
                            "task" -> {
                                if (!isIterationBacklogExplicitlyHidden) {
                                    for (witRef in bLevel.workItemTypes.orEmpty()) {
                                        val resolved = resolveEnabledTypeName(witRef.name) ?: continue
                                        iterationTypes.add(resolved)
                                    }
                                }
                            }
                        }
                    }
                }

                // Source D: Work Item Type Categories (_apis/wit/workitemtypecategories) filtered by enabled types
                val bugTypesFromCategory = mutableListOf<String>()
                if (categoriesResp?.isSuccessful == true) {
                    val categories = categoriesResp.body()?.value.orEmpty()
                    for (cat in categories) {
                        val refName = cat.referenceName?.trim().orEmpty()
                        val refLower = refName.lowercase()
                        val enabledInCat = cat.workItemTypes.orEmpty()
                            .mapNotNull { resolveEnabledTypeName(it.name) }
                        if (enabledInCat.isEmpty()) continue

                        when {
                            refLower == "microsoft.epiccategory" -> {
                                if (portfolioBucketsByKey.none { (_, pair) ->
                                        pair.second.any { existing ->
                                            enabledInCat.any { it.equals(existing, ignoreCase = true) }
                                        }
                                    }
                                ) {
                                    val bucket = portfolioBucketsByKey.getOrPut("microsoft.epiccategory") {
                                        300 to mutableListOf()
                                    }
                                    bucket.second.addAll(enabledInCat)
                                }
                            }
                            refLower == "microsoft.featurecategory" -> {
                                if (portfolioBucketsByKey.none { (_, pair) ->
                                        pair.second.any { existing ->
                                            enabledInCat.any { it.equals(existing, ignoreCase = true) }
                                        }
                                    }
                                ) {
                                    val bucket = portfolioBucketsByKey.getOrPut("microsoft.featurecategory") {
                                        200 to mutableListOf()
                                    }
                                    bucket.second.addAll(enabledInCat)
                                }
                            }
                            refLower == "microsoft.requirementcategory" -> {
                                requirementTypes.addAll(enabledInCat)
                            }
                            refLower == "microsoft.taskcategory" -> {
                                if (!isIterationBacklogExplicitlyHidden) {
                                    iterationTypes.addAll(enabledInCat)
                                }
                            }
                            refLower == "microsoft.bugcategory" -> {
                                bugTypesFromCategory.addAll(enabledInCat)
                            }
                        }
                    }
                }

                // Handle Bugs behavior according to Azure Boards configuration:
                // - "asRequirements": Bug is part of Requirement Backlog
                // - "asTasks" (or default when Iteration Backlog has active task types): Bug is part of Iteration Backlog
                // - "off" or when Iteration Backlog is disabled: Bug is not added as a lower Iteration level
                val bugsBehavior = backlogConfig?.bugsBehavior?.trim()?.lowercase()
                if (bugTypesFromCategory.isNotEmpty()) {
                    when {
                        bugsBehavior == "asrequirements" -> {
                            requirementTypes.addAll(bugTypesFromCategory)
                        }
                        bugsBehavior == "off" -> {
                            // Managed off backlogs -> treated as unmapped
                        }
                        iterationTypes.isNotEmpty() || bugsBehavior == "astasks" -> {
                            if (!isIterationBacklogExplicitlyHidden && iterationTypes.isNotEmpty()) {
                                iterationTypes.addAll(bugTypesFromCategory)
                            }
                        }
                    }
                }

                // 2. Assemble ordered active Backlog Levels from highest Portfolio down to Requirement and Iteration
                val orderedBacklogLevels = mutableListOf<List<String>>()
                val assignedLower = mutableSetOf<String>()
                val categoryMap = linkedMapOf<String, String>()

                val sortedPortfolioBuckets = portfolioBucketsByKey.values
                    .sortedByDescending { it.first }

                var portfolioTierIndex = 0
                for ((_, typesInBucket) in sortedPortfolioBuckets) {
                    val distinctLevelTypes = typesInBucket
                        .filter { assignedLower.add(it.lowercase()) }
                        .distinctBy { it.lowercase() }
                    if (distinctLevelTypes.isNotEmpty()) {
                        orderedBacklogLevels.add(distinctLevelTypes)
                        for (t in distinctLevelTypes) {
                            categoryMap[t] = "Portfolio:$portfolioTierIndex"
                        }
                        portfolioTierIndex++
                    }
                }

                val distinctRequirementTypes = requirementTypes
                    .filter { assignedLower.add(it.lowercase()) }
                    .distinctBy { it.lowercase() }
                if (distinctRequirementTypes.isNotEmpty()) {
                    orderedBacklogLevels.add(distinctRequirementTypes)
                    for (t in distinctRequirementTypes) {
                        categoryMap[t] = "Microsoft.RequirementCategory"
                    }
                }

                val distinctIterationTypes = iterationTypes
                    .filter { assignedLower.add(it.lowercase()) }
                    .distinctBy { it.lowercase() }
                if (distinctIterationTypes.isNotEmpty()) {
                    orderedBacklogLevels.add(distinctIterationTypes)
                    for (t in distinctIterationTypes) {
                        categoryMap[t] = "Microsoft.TaskCategory"
                    }
                }

                // Mark any remaining enabled work item types that are not on any Backlog level as "Unmapped"
                for (enabledType in enabledCanonicalByLower.values) {
                    if (enabledType.lowercase() !in assignedLower) {
                        categoryMap[enabledType] = "Unmapped"
                    }
                }

                ProjectBacklogMetadata(
                    categoryMap = categoryMap,
                    orderedBacklogLevels = orderedBacklogLevels
                )
            }
        } catch (e: Exception) {
            ProjectBacklogMetadata(emptyMap(), emptyList())
        }
    }

    /**
     * Fetches work item type -> category reference name for a project.
     */
    suspend fun fetchProjectCategoryMap(
        organization: String,
        project: String,
        pat: String
    ): Map<String, String> {
        return fetchProjectBacklogMetadata(organization, project, pat).categoryMap
    }

    private fun isSubtaskTypeName(type: String): Boolean {
        val n = type.lowercase().trim()
        return n.contains("subtask") || n.contains("sub-task") || n.contains("زیرکار")
    }

    private fun hasExactWordOrPersianToken(text: String, token: String): Boolean {
        val cleanText = text.lowercase().trim()
        val cleanToken = token.lowercase().trim()
        if (cleanText == cleanToken) return true
        val words = cleanText.split(Regex("[\\s\\-_/.(),]+")).filter { it.isNotEmpty() }
        return cleanToken in words
    }

    /**
     * Classifies a work item type into a fallback backlog tier score when server Backlog metadata
     * has not been loaded yet:
     * - 0..20 : Portfolio Backlog tiers (Initiative / Epic / Feature)
     * - 30    : Requirement Backlog tier (User Story / PBI / Requirement / داستان کاربر / پروژه)
     * - 40    : Iteration Backlog tier (Task / کار / وظیفه / اقدام)
     * - null  : Unmapped / custom type not recognizable by standard name alone
     */
    private fun fallbackBacklogTierScoreByName(type: String): Int? {
        val n = type.lowercase().trim()
        return when {
            n.contains("initiative") || n.contains("theme") -> 0
            n.contains("epic") || hasExactWordOrPersianToken(n, "هدف") || hasExactWordOrPersianToken(n, "اهداف") ||
                n.contains("حماسه") || hasExactWordOrPersianToken(n, "goal") ||
                hasExactWordOrPersianToken(n, "objective") || hasExactWordOrPersianToken(n, "کلان") -> 10
            n.contains("feature") || n.contains("ویژگی") || n.contains("قابلیت") ||
                n.contains("امکانات") || n.contains("deliverable") -> 20
            n.contains("user story") || n.contains("story") || n.contains("product backlog item") ||
                n.contains("backlog item") || n.contains("requirement") || n.contains("داستان کاربر") ||
                n.contains("داستان") || n.contains("نیازمندی") || hasExactWordOrPersianToken(n, "پروژه") ||
                n.contains("خواسته") -> 30
            isSubtaskTypeName(n) -> 50
            hasExactWordOrPersianToken(n, "task") || hasExactWordOrPersianToken(n, "tasks") ||
                hasExactWordOrPersianToken(n, "کار") || hasExactWordOrPersianToken(n, "کارها") ||
                hasExactWordOrPersianToken(n, "وظیفه") || hasExactWordOrPersianToken(n, "اقدام") -> 40
            else -> null
        }
    }

    private fun typeDisplayTieBreakerWithinLevel(type: String): Int {
        val tier = fallbackBacklogTierScoreByName(type)
        val n = type.lowercase().trim()
        return when {
            tier == 30 -> 0
            tier == 40 -> 0
            n.contains("bug") || n.contains("باگ") || n.contains("خطا") || n.contains("ایراد") -> 1
            n.contains("issue") || n.contains("مسئله") || n.contains("impediment") || n.contains("مانع") -> 1
            else -> 2
        }
    }

    /**
     * Resolves the ordered active Backlog Levels (from highest Portfolio Backlog down to
     * Requirement Backlog and Iteration Backlog) for a project.
     *
     * Rules:
     * 1. Only enabled/active work item types in [activeTypes] are included in each level.
     * 2. If the Iteration Backlog is disabled (or has zero active types), the Requirement Backlog
     *    is automatically the last level (`lastIndex`) in the returned list.
     * 3. Unmapped work item types (types not assigned to any Portfolio, Requirement, or Iteration
     *    backlog level) are excluded from the returned backlog levels so they are treated as
     *    last-level (leaf) items that cannot have children.
     */
    fun resolveOrderedBacklogLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        explicitBacklogLevels: List<List<String>> = emptyList(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): List<List<String>> {
        val cleanedActive = activeTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val activeByLower = cleanedActive.associateBy { it.lowercase() }

        // 1. Authoritative explicit Backlog Levels from Azure Boards Process Behaviors & Backlog Configuration
        if (explicitBacklogLevels.isNotEmpty()) {
            val filteredLevels = explicitBacklogLevels.mapNotNull { level ->
                val activeInLevel = level.mapNotNull { rawName ->
                    val clean = rawName.trim()
                    if (clean.isEmpty()) null
                    else if (activeByLower.isNotEmpty()) activeByLower[clean.lowercase()]
                    else clean
                }.distinctBy { it.lowercase() }
                activeInLevel.takeIf { it.isNotEmpty() }
            }
            if (filteredLevels.isNotEmpty()) {
                return filteredLevels
            }
        }

        // 2. Category Map resolution (Portfolio:0..N / Epic / Feature -> Requirement -> Task/Iteration)
        if (categoryMap.isNotEmpty()) {
            val candidateTypes = if (cleanedActive.isNotEmpty()) {
                cleanedActive
            } else {
                categoryMap.keys.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            }

            val portfolioTiers = sortedMapOf<Int, MutableList<String>>()
            val requirementTier = mutableListOf<String>()
            val taskTier = mutableListOf<String>()
            val bugTierCandidates = mutableListOf<String>()

            for (type in candidateTypes) {
                val catRef = categoryMap.entries
                    .firstOrNull { it.key.equals(type, ignoreCase = true) }
                    ?.value
                    ?.trim()
                    ?: continue
                val catLower = catRef.lowercase()
                when {
                    catLower.startsWith("portfolio:") -> {
                        val idx = catLower.substringAfter("portfolio:").toIntOrNull() ?: 0
                        portfolioTiers.getOrPut(idx) { mutableListOf() }.add(type)
                    }
                    catLower == "microsoft.epiccategory" -> {
                        portfolioTiers.getOrPut(0) { mutableListOf() }.add(type)
                    }
                    catLower == "microsoft.featurecategory" -> {
                        portfolioTiers.getOrPut(1) { mutableListOf() }.add(type)
                    }
                    catLower == "microsoft.requirementcategory" -> {
                        requirementTier.add(type)
                    }
                    catLower == "microsoft.taskcategory" -> {
                        taskTier.add(type)
                    }
                    catLower == "microsoft.bugcategory" -> {
                        bugTierCandidates.add(type)
                    }
                    // "unmapped" or non-backlog categories are intentionally omitted
                }
            }

            // Include Bug in Iteration tier only when Iteration Backlog is active (has Task types)
            if (taskTier.isNotEmpty() && bugTierCandidates.isNotEmpty()) {
                taskTier.addAll(bugTierCandidates)
            }

            val levels = mutableListOf<List<String>>()
            for ((_, pList) in portfolioTiers) {
                val distinct = pList.distinctBy { it.lowercase() }
                if (distinct.isNotEmpty()) levels.add(distinct)
            }
            if (requirementTier.isNotEmpty()) {
                levels.add(requirementTier.distinctBy { it.lowercase() })
            }
            if (taskTier.isNotEmpty()) {
                levels.add(taskTier.distinctBy { it.lowercase() })
            }
            if (levels.isNotEmpty()) {
                return levels
            }
        }

        // 3. Fallback resolution before server metadata arrives:
        //    Combine observed parent -> child links in projectWorkItems with exact-token backlog name tiers.
        if (cleanedActive.isEmpty()) return emptyList()

        val tierScores = mutableMapOf<String, Int>()
        for (type in cleanedActive) {
            val scoreByName = fallbackBacklogTierScoreByName(type)
            if (scoreByName != null) {
                tierScores[type] = scoreByName
            }
        }

        if (projectWorkItems.isNotEmpty()) {
            val byId = projectWorkItems.associateBy { it.id }
            val edgeCounts = mutableMapOf<Pair<String, String>, Int>()
            for (item in projectWorkItems) {
                val pId = item.parentId ?: continue
                val parent = byId[pId] ?: continue
                val pType = cleanedActive.firstOrNull { it.equals(parent.workItemType, ignoreCase = true) } ?: continue
                val cType = cleanedActive.firstOrNull { it.equals(item.workItemType, ignoreCase = true) } ?: continue
                if (!pType.equals(cType, ignoreCase = true)) {
                    val key = pType to cType
                    edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
                }
            }
            val directedEdges = edgeCounts.keys.filter { (p, c) ->
                val forward = edgeCounts[p to c] ?: 0
                val backward = edgeCounts[c to p] ?: 0
                forward >= backward
            }
            for ((pType, cType) in directedEdges) {
                if (!tierScores.containsKey(pType) && !tierScores.containsKey(cType)) {
                    tierScores[pType] = 20
                    tierScores[cType] = 30
                } else if (tierScores.containsKey(pType) && !tierScores.containsKey(cType)) {
                    tierScores[cType] = (tierScores[pType] ?: 20) + 10
                } else if (!tierScores.containsKey(pType) && tierScores.containsKey(cType)) {
                    tierScores[pType] = (tierScores[cType] ?: 30) - 10
                }
            }
            repeat(cleanedActive.size) {
                for ((pType, cType) in directedEdges) {
                    val pScore = tierScores[pType] ?: continue
                    val cScore = tierScores[cType] ?: continue
                    if (pScore >= cScore) {
                        tierScores[cType] = pScore + 10
                    }
                }
            }
        }

        // Only include types that belong to a recognized backlog tier or observed hierarchy chain
        val mappedTypes = cleanedActive.filter { tierScores.containsKey(it) }
        if (mappedTypes.isEmpty()) return emptyList()

        return mappedTypes
            .groupBy { tierScores[it] ?: 30 }
            .toSortedMap()
            .values
            .map { levelList ->
                levelList.distinctBy { it.lowercase() }.sortedWith(
                    compareBy<String> { typeDisplayTieBreakerWithinLevel(it) }
                        .thenBy { it.lowercase() }
                )
            }
    }

    /**
     * Returns true if [type] is at the last active Backlog Level (e.g., Iteration Backlog by default,
     * or Requirement Backlog when Iteration Backlog is disabled) OR is an unmapped work item type
     * not assigned to any Backlog Level, and therefore cannot have children.
     */
    fun isLeafWorkItemType(
        type: String,
        categoryMap: Map<String, String> = emptyMap(),
        activeTypes: List<String> = emptyList(),
        explicitBacklogLevels: List<List<String>> = emptyList(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): Boolean {
        val cleanType = type.trim()
        if (cleanType.isBlank()) return true
        if (isSubtaskTypeName(cleanType)) return true

        val backlogLevels = resolveOrderedBacklogLevels(
            activeTypes = activeTypes,
            categoryMap = categoryMap,
            explicitBacklogLevels = explicitBacklogLevels,
            projectWorkItems = projectWorkItems
        )
        if (backlogLevels.size <= 1) return true

        val levelIdx = backlogLevels.indexOfFirst { level ->
            level.any { it.equals(cleanType, ignoreCase = true) }
        }
        // Unmapped types (levelIdx < 0) and last-level backlog types (levelIdx >= backlogLevels.lastIndex) are leaf items
        return levelIdx < 0 || levelIdx >= backlogLevels.lastIndex
    }

    /**
     * Resolves all active work item types grouped by hierarchy level for display ordering,
     * placing ordered Backlog Levels first (Portfolio -> Requirement -> Iteration) followed
     * by any unmapped active work item types at the end.
     */
    fun resolveOrderedHierarchyLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList(),
        explicitBacklogLevels: List<List<String>> = emptyList()
    ): List<List<String>> {
        val cleanedTypes = activeTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (cleanedTypes.isEmpty()) return emptyList()

        val backlogLevels = resolveOrderedBacklogLevels(
            activeTypes = cleanedTypes,
            categoryMap = categoryMap,
            explicitBacklogLevels = explicitBacklogLevels,
            projectWorkItems = projectWorkItems
        )
        val inBacklogLower = backlogLevels.flatten().map { it.lowercase() }.toSet()
        val unmappedTypes = cleanedTypes.filter { it.lowercase() !in inBacklogLower }
            .sortedWith(
                compareBy<String> { typeDisplayTieBreakerWithinLevel(it) }
                    .thenBy { it.lowercase() }
            )

        return if (unmappedTypes.isNotEmpty()) {
            backlogLevels + listOf(unmappedTypes)
        } else {
            backlogLevels
        }
    }

    /**
     * Orders work item types from biggest (highest Portfolio Backlog, e.g. Epic / هدف)
     * to smallest (Iteration Backlog, e.g. Task / کار, followed by any unmapped types).
     */
    fun orderWorkItemTypesBiggestToSmallest(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList(),
        explicitBacklogLevels: List<List<String>> = emptyList()
    ): List<String> {
        return resolveOrderedHierarchyLevels(
            activeTypes = activeTypes,
            categoryMap = categoryMap,
            projectWorkItems = projectWorkItems,
            explicitBacklogLevels = explicitBacklogLevels
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
                    val icon: String?,
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
                        icon = pWit.icon,
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
                            icon = existing.icon ?: wWit.icon?.id,
                            inlineWitStates = wWit.states.orEmpty()
                        )
                    } else if (processWorkItemTypes.isEmpty()) {
                        // Only add types not in processWorkItemTypes if Process API wasn't available
                        candidateByLowerName[key] = TypeCandidate(
                            name = name,
                            referenceName = wWit.referenceName?.trim()?.takeIf { it.isNotBlank() },
                            color = wWit.color,
                            icon = wWit.icon?.id,
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
                        registerAzureTypeIcon(candidate.name, candidate.icon)

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

    fun formatCommentTimestamp(isoDate: String?): String {
        val clean = isoDate?.trim().orEmpty()
        if (clean.length < 10) return ""
        val datePart = clean.take(10)
        val timePart = clean.substringAfter('T', "").take(5)
        return if (timePart.length == 5) "$datePart · $timePart" else datePart
    }

    private fun mapRawCommentToDomain(
        raw: com.example.data.model.RawWorkItemComment,
        fallbackWorkItemId: Int
    ): com.example.data.model.WorkItemComment? {
        if (raw.isDeleted == true) return null
        val cleanedText = cleanHtml(raw.text)?.trim().orEmpty()
        if (cleanedText.isBlank()) return null
        val rawDate = raw.createdDate?.trim().orEmpty()
        val authorName = raw.createdBy?.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: raw.createdBy?.uniqueName?.trim()?.takeIf { it.isNotBlank() }
            ?: "User"
        return com.example.data.model.WorkItemComment(
            id = raw.id ?: rawDate.hashCode(),
            workItemId = raw.workItemId ?: fallbackWorkItemId,
            text = cleanedText,
            authorName = authorName,
            authorEmail = raw.createdBy?.uniqueName?.trim()?.takeIf { it.isNotBlank() },
            authorAvatar = raw.createdBy?.imageUrl?.trim()?.takeIf { it.isNotBlank() },
            createdDate = rawDate,
            formattedDate = formatCommentTimestamp(rawDate)
        )
    }

    /**
     * Fetches discussion comments for [workItemId] ordered newest-first.
     */
    suspend fun fetchWorkItemComments(
        organization: String,
        pat: String,
        project: String,
        workItemId: Int
    ): Result<List<com.example.data.model.WorkItemComment>> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank() || project.isBlank() || workItemId <= 0) {
            return@withContext Result.success(emptyList())
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val response = service.getWorkItemComments(
                organization = normalizedOrg,
                project = project.trim(),
                workItemId = workItemId,
                top = 100,
                order = "desc"
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to load comments (${response.code()}): ${response.message()}")
                )
            }
            val comments = response.body()?.comments.orEmpty()
                .mapNotNull { mapRawCommentToDomain(it, workItemId) }
                .sortedWith(
                    compareByDescending<com.example.data.model.WorkItemComment> { it.createdDate }
                        .thenByDescending { it.id }
                )
            Result.success(comments)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Posts a new comment on [workItemId] in Azure Boards (with fallback to System.History patch).
     */
    suspend fun addWorkItemCommentOnServer(
        organization: String,
        pat: String,
        project: String,
        workItemId: Int,
        commentText: String
    ): Result<com.example.data.model.WorkItemComment> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        val trimmedText = commentText.trim()
        if (normalizedOrg.isBlank() || pat.isBlank() || workItemId <= 0 || trimmedText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Comment text cannot be empty."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val htmlBody = trimmedText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>")

            if (project.isNotBlank()) {
                val commentResp = runCatching {
                    service.addWorkItemComment(
                        organization = normalizedOrg,
                        project = project.trim(),
                        workItemId = workItemId,
                        request = com.example.data.model.AddWorkItemCommentRequest(text = htmlBody)
                    )
                }.getOrNull()

                if (commentResp?.isSuccessful == true && commentResp.body() != null) {
                    val mapped = mapRawCommentToDomain(commentResp.body()!!, workItemId)
                    if (mapped != null) {
                        return@withContext Result.success(mapped)
                    }
                }
            }

            // Fallback: PATCH /fields/System.History on the work item
            val patchOps = listOf(
                JsonPatchOperation(
                    op = "add",
                    path = "/fields/System.History",
                    value = htmlBody
                )
            )
            val patchResp = service.updateWorkItem(
                organization = normalizedOrg,
                id = workItemId,
                patchBody = AzureDevOpsClient.createPatchRequestBody(patchOps)
            )
            if (!patchResp.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to post comment (${patchResp.code()}): ${patchResp.message()}")
                )
            }

            val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            Result.success(
                com.example.data.model.WorkItemComment(
                    id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                    workItemId = workItemId,
                    text = trimmedText,
                    authorName = "You",
                    createdDate = nowIso,
                    formattedDate = formatCommentTimestamp(nowIso)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches the authoritative Area and Iteration classification trees for [project] from
     * Azure DevOps (`_apis/wit/classificationnodes?$depth=10`) and returns `(areaPaths, iterationPaths)`
     * as canonical backslash-separated paths (e.g. `Project`, `Project\Team A`, `Project\Team A\Backend`).
     */
    suspend fun fetchProjectClassificationPaths(
        organization: String,
        project: String,
        pat: String
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val normalizedOrg = extractOrganization(organization)
        val cleanProject = project.trim()
        if (normalizedOrg.isBlank() || cleanProject.isBlank() || pat.isBlank()) {
            return@withContext emptyList<String>() to emptyList()
        }

        val areaPaths = linkedSetOf<String>()
        val iterationPaths = linkedSetOf<String>()

        fun collectFromTree(
            node: com.example.data.model.RawClassificationNode?,
            parentPath: String?,
            isRootStructureNode: Boolean,
            targetSet: MutableSet<String>
        ) {
            if (node == null) return
            val nodeName = node.name?.trim().orEmpty()
            val currentPath = when {
                parentPath == null || isRootStructureNode -> cleanProject
                nodeName.isNotBlank() -> "$parentPath\\$nodeName"
                else -> parentPath
            }
            if (currentPath.isNotBlank()) {
                targetSet.add(currentPath)
            }
            node.children.orEmpty().forEach { child ->
                collectFromTree(
                    node = child,
                    parentPath = currentPath,
                    isRootStructureNode = false,
                    targetSet = targetSet
                )
            }
        }

        try {
            val service = AzureDevOpsClient.createService(pat)
            coroutineScope {
                val combinedDeferred = async {
                    runCatching {
                        service.getProjectClassificationNodes(
                            organization = normalizedOrg,
                            project = cleanProject,
                            depth = 10
                        )
                    }.getOrNull()
                }
                val combinedResp = combinedDeferred.await()
                val rootNodes = combinedResp?.body()?.value.orEmpty()

                if (rootNodes.isNotEmpty()) {
                    for (root in rootNodes) {
                        val structureType = root.structureType?.trim()?.lowercase().orEmpty()
                        when {
                            structureType.contains("area") ->
                                collectFromTree(root, null, isRootStructureNode = true, targetSet = areaPaths)
                            structureType.contains("iteration") ->
                                collectFromTree(root, null, isRootStructureNode = true, targetSet = iterationPaths)
                        }
                    }
                }

                if (areaPaths.isEmpty() || iterationPaths.isEmpty()) {
                    val areasDef = async {
                        if (areaPaths.isEmpty()) {
                            runCatching {
                                service.getProjectClassificationTreeByGroup(
                                    organization = normalizedOrg,
                                    project = cleanProject,
                                    structureGroup = "areas",
                                    depth = 10
                                )
                            }.getOrNull()?.body()
                        } else null
                    }
                    val iterationsDef = async {
                        if (iterationPaths.isEmpty()) {
                            runCatching {
                                service.getProjectClassificationTreeByGroup(
                                    organization = normalizedOrg,
                                    project = cleanProject,
                                    structureGroup = "iterations",
                                    depth = 10
                                )
                            }.getOrNull()?.body()
                        } else null
                    }
                    areasDef.await()?.let { areaRoot ->
                        collectFromTree(areaRoot, null, isRootStructureNode = true, targetSet = areaPaths)
                    }
                    iterationsDef.await()?.let { iterationRoot ->
                        collectFromTree(iterationRoot, null, isRootStructureNode = true, targetSet = iterationPaths)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore network errors; fallback paths from work items will still populate the hierarchy
        }

        areaPaths.toList() to iterationPaths.toList()
    }

    data class NotificationSyncOutcome(
        val state: com.example.data.model.CachedNotificationsState,
        val newlyTriggeredNotifications: List<com.example.data.model.AppNotificationItem>,
        val newlyHydratedWorkItems: List<WorkItem> = emptyList()
    )

    fun getCachedNotificationsStateSync(organization: String): com.example.data.model.CachedNotificationsState {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) {
            return com.example.data.model.CachedNotificationsState(organization = "")
        }
        return localStore.loadNotificationsStateSync(normOrg)
            ?: com.example.data.model.CachedNotificationsState(organization = normOrg)
    }

    suspend fun getCachedNotificationsState(organization: String): com.example.data.model.CachedNotificationsState =
        withContext(Dispatchers.IO) {
            getCachedNotificationsStateSync(organization)
        }

    suspend fun setManualIdentityOverride(
        organization: String,
        identityOverride: String?
    ): com.example.data.model.CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = extractOrganization(organization)
        val current = getCachedNotificationsStateSync(normOrg)
        val cleanOverride = identityOverride?.trim()?.takeIf { it.isNotEmpty() }
        // Reset baselines when the user switches identity override so their Assigned & Mentioned tabs rebuild for that identity
        val identityChanged = !current.manualIdentityOverride.equals(cleanOverride, ignoreCase = true)
        val updated = if (identityChanged) {
            current.copy(
                manualIdentityOverride = cleanOverride,
                baselineAssignedInitialized = false,
                baselineMentionsInitialized = false,
                knownAssignedItemStates = emptyMap()
            )
        } else {
            current.copy(manualIdentityOverride = cleanOverride)
        }
        localStore.saveNotificationsState(updated)
        updated
    }

    suspend fun markNotificationRead(
        organization: String,
        notificationId: String
    ): com.example.data.model.CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = extractOrganization(organization)
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
    ): com.example.data.model.CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = extractOrganization(organization)
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
    ): com.example.data.model.CachedNotificationsState = withContext(Dispatchers.IO) {
        val normOrg = extractOrganization(organization)
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
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(clean.take(24))
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun matchesUserIdentity(
        candidateName: String?,
        candidateEmail: String?,
        effectiveDisplayName: String?,
        detectedEmail: String?
    ): Boolean {
        val cName = candidateName?.trim()?.lowercase().orEmpty()
        val cEmail = candidateEmail?.trim()?.lowercase().orEmpty()
        val targetName = effectiveDisplayName?.trim()?.lowercase().orEmpty()
        val targetEmail = detectedEmail?.trim()?.lowercase().orEmpty()

        if (targetName.isNotEmpty() && cName.isNotEmpty()) {
            if (cName == targetName || cName.contains(targetName) || targetName.contains(cName)) {
                return true
            }
        }
        if (targetEmail.isNotEmpty()) {
            if (cEmail.isNotEmpty() && cEmail == targetEmail) return true
            if (cName.isNotEmpty() && cName == targetEmail) return true
            val emailPrefix = targetEmail.substringBefore('@')
            if (emailPrefix.length >= 3 && cEmail.startsWith("$emailPrefix@")) return true
        }
        return false
    }

    private fun commentMentionsIdentity(
        commentText: String,
        effectiveDisplayName: String?,
        detectedEmail: String?,
        detectedUserId: String?
    ): Boolean {
        val lowerText = commentText.lowercase()
        val targetName = effectiveDisplayName?.trim()?.lowercase().orEmpty()
        val targetEmail = detectedEmail?.trim()?.lowercase().orEmpty()
        val targetId = detectedUserId?.trim()?.lowercase().orEmpty()

        if (targetName.isNotEmpty()) {
            if (lowerText.contains("@$targetName")) return true
            // Also check first name token if @FirstName is used and is at least 3 chars
            val firstToken = targetName.substringBefore(' ').trim()
            if (firstToken.length >= 3 && Regex("(?<![\\w@])@${Regex.escape(firstToken)}(?![\\w])").containsMatchIn(lowerText)) {
                return true
            }
        }
        if (targetEmail.isNotEmpty()) {
            if (lowerText.contains("@$targetEmail")) return true
            val emailPrefix = targetEmail.substringBefore('@').trim()
            if (emailPrefix.length >= 3 && Regex("(?<![\\w@])@${Regex.escape(emailPrefix)}(?![\\w])").containsMatchIn(lowerText)) {
                return true
            }
        }
        if (targetId.isNotEmpty() && lowerText.contains(targetId)) {
            return true
        }
        return false
    }

    /**
     * Evaluates freshly loaded or posted comments on a single [workItem] for `@mentions` of the current user.
     */
    suspend fun evaluateWorkItemCommentsForMentions(
        organization: String,
        workItem: WorkItem,
        comments: List<com.example.data.model.WorkItemComment>,
        allowSystemNotification: Boolean = true
    ): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val normOrg = extractOrganization(organization)
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
        val newlyTriggered = mutableListOf<com.example.data.model.AppNotificationItem>()

        for (comment in comments) {
            if (commentMentionsIdentity(comment.text, effectiveName, detectedEmail, detectedUserId)) {
                val notifId = "mention_${normOrg.lowercase()}_${workItem.id}_${comment.id}"
                val isAuthorSelf = matchesUserIdentity(
                    candidateName = comment.authorName,
                    candidateEmail = comment.authorEmail,
                    effectiveDisplayName = effectiveName,
                    detectedEmail = detectedEmail
                ) || comment.authorName.equals("You", ignoreCase = true)

                val existing = existingById[notifId]
                if (existing == null) {
                    val epoch = parseIsoToEpochMs(comment.createdDate).takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    val shouldMarkUnread = allowSystemNotification && !isAuthorSelf
                    val notifItem = com.example.data.model.AppNotificationItem(
                        id = notifId,
                        organization = normOrg,
                        workItemId = workItem.id,
                        workItemTitle = workItem.title,
                        workItemType = workItem.workItemType,
                        workItemState = workItem.state,
                        teamProject = workItem.teamProject,
                        category = com.example.data.model.NotificationCategory.MENTIONED,
                        actorName = comment.authorName,
                        messageSnippet = comment.text,
                        createdDateIso = comment.createdDate,
                        formattedDate = comment.formattedDate.ifBlank { formatCommentTimestamp(comment.createdDate) },
                        epochMs = epoch,
                        commentId = comment.id,
                        isRead = !shouldMarkUnread
                    )
                    existingById[notifId] = notifItem
                    if (shouldMarkUnread && notifId !in notifiedSet) {
                        newlyTriggered.add(notifItem)
                    }
                    notifiedSet.add(notifId)
                }
            }
        }

        val sortedNotifications = existingById.values
            .sortedWith(compareByDescending<com.example.data.model.AppNotificationItem> { it.epochMs }.thenByDescending { it.workItemId })
            .take(150)

        val updatedState = currentState.copy(
            notifications = sortedNotifications,
            notifiedNotificationIds = notifiedSet.toList().takeLast(400)
        )
        localStore.saveNotificationsState(updatedState)
        NotificationSyncOutcome(updatedState, newlyTriggered)
    }

    /**
     * Synchronizes Assigned and Mentioned notifications for [organization]:
     * 1. Auto-detects the authenticated user's display name & email via `_apis/connectionData` and `@Me` WIQL.
     * 2. Detects work items newly assigned to the user (`Assigned` section).
     * 3. Scans recently updated work items' discussions for `@mentions` of the user (`Mentioned` section).
     */
    suspend fun syncOrganizationNotifications(
        organization: String,
        pat: String,
        knownWorkItems: List<WorkItem> = emptyList()
    ): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val normOrg = extractOrganization(organization)
        val cleanPat = pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) {
            return@withContext NotificationSyncOutcome(
                com.example.data.model.CachedNotificationsState(organization = normOrg),
                emptyList()
            )
        }

        var currentState = getCachedNotificationsStateSync(normOrg)
        val service = AzureDevOpsClient.createService(cleanPat)

        // 1. Resolve Authenticated User Identity from _apis/connectionData if not yet detected
        var detectedName = currentState.detectedDisplayName
        var detectedEmail = currentState.detectedEmail
        var detectedUserId = currentState.detectedUserId

        if (detectedName.isNullOrBlank()) {
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

        // 2. Query @Me Assigned Work Items on server (authoritative for PAT owner)
        val manualOverride = currentState.manualIdentityOverride?.trim()?.takeIf { it.isNotEmpty() }
        val newlyHydratedItems = mutableListOf<WorkItem>()
        val itemsById = knownWorkItems.associateBy { it.id }.toMutableMap()

        val assignedToMeServerIds = if (manualOverride == null) {
            runCatching {
                val wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.AssignedTo] = @Me ORDER BY [System.ChangedDate] DESC"
                service.queryWorkItems(
                    organization = normOrg,
                    request = WiqlQueryRequest(query = wiql)
                ).body()?.workItems.orEmpty().map { it.id }.take(40)
            }.getOrDefault(emptyList())
        } else {
            val escapedOverride = manualOverride.replace("'", "''")
            runCatching {
                val wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.AssignedTo] CONTAINS '$escapedOverride' ORDER BY [System.ChangedDate] DESC"
                service.queryWorkItems(
                    organization = normOrg,
                    request = WiqlQueryRequest(query = wiql)
                ).body()?.workItems.orEmpty().map { it.id }.take(40)
            }.getOrDefault(emptyList())
        }

        val missingAssignedIds = assignedToMeServerIds.filter { it !in itemsById }.take(25)
        if (missingAssignedIds.isNotEmpty()) {
            runCatching {
                val rawBatch = hydrateRawItemsByIds(service, normOrg, missingAssignedIds)
                val parsedBatch = parseAndLinkRawWorkItems(rawBatch)
                parsedBatch.forEach { item ->
                    itemsById[item.id] = item
                    newlyHydratedItems.add(item)
                }
            }
        }

        // Fallback: if connectionData didn't return a display name, infer it from @Me assigned items
        if (detectedName.isNullOrBlank() && manualOverride == null && assignedToMeServerIds.isNotEmpty()) {
            val firstMeItem = assignedToMeServerIds.mapNotNull { itemsById[it] }
                .firstOrNull { !it.assignedToName.isNullOrBlank() }
            if (firstMeItem != null) {
                detectedName = firstMeItem.assignedToName?.trim()
                if (detectedEmail.isNullOrBlank()) {
                    detectedEmail = firstMeItem.assignedToEmail?.trim()
                }
            }
        }

        val effectiveIdentityName = manualOverride ?: detectedName
        val assignedServerIdSet = assignedToMeServerIds.toSet()

        // Collect all items assigned to the effective user identity
        val allAssignedItems = itemsById.values.filter { item ->
            if (isExcludedNonWorkItemType(item.workItemType)) return@filter false
            if (manualOverride == null && item.id in assignedServerIdSet) {
                true
            } else if (!effectiveIdentityName.isNullOrBlank() || !detectedEmail.isNullOrBlank()) {
                matchesUserIdentity(
                    candidateName = item.assignedToName,
                    candidateEmail = item.assignedToEmail,
                    effectiveDisplayName = effectiveIdentityName,
                    detectedEmail = if (manualOverride == null) detectedEmail else null
                )
            } else {
                false
            }
        }.sortedWith(
            compareByDescending<WorkItem> { it.changedDate ?: "" }.thenByDescending { it.id }
        )

        val existingNotificationsById = currentState.notifications.associateBy { it.id }.toMutableMap()
        val notifiedSet = currentState.notifiedNotificationIds.toMutableSet()
        val knownAssignedMap = currentState.knownAssignedItemStates.toMutableMap()
        val isInitialAssignedBaseline = !currentState.baselineAssignedInitialized
        val newlyTriggered = mutableListOf<com.example.data.model.AppNotificationItem>()

        // Track currently assigned item IDs so if an item is unassigned and later re-assigned to the user, we notify again
        val currentAssignedKeySet = allAssignedItems.map { it.id.toString() }.toSet()
        knownAssignedMap.keys.retainAll(currentAssignedKeySet)

        for (item in allAssignedItems.take(35)) {
            val itemKey = item.id.toString()
            val previouslyKnownAssignee = knownAssignedMap[itemKey]
            val currentAssigneeSig = (item.assignedToName ?: effectiveIdentityName ?: "me").trim().lowercase()
            knownAssignedMap[itemKey] = currentAssigneeSig

            val notifId = "assigned_${normOrg.lowercase()}_${item.id}"
            val existingNotif = existingNotificationsById[notifId]
            val changedIso = item.changedDate ?: item.createdDate.orEmpty()
            val epoch = parseIsoToEpochMs(changedIso).takeIf { it > 0L } ?: System.currentTimeMillis()

            if (previouslyKnownAssignee == null) {
                // Newly discovered assignment to this user!
                val isUnread = !isInitialAssignedBaseline
                val notif = com.example.data.model.AppNotificationItem(
                    id = notifId,
                    organization = normOrg,
                    workItemId = item.id,
                    workItemTitle = item.title,
                    workItemType = item.workItemType,
                    workItemState = item.state,
                    teamProject = item.teamProject,
                    category = com.example.data.model.NotificationCategory.ASSIGNED,
                    actorName = item.assignedToName ?: effectiveIdentityName,
                    messageSnippet = "Assigned to ${item.assignedToName ?: effectiveIdentityName ?: "you"} • ${item.state}",
                    createdDateIso = changedIso,
                    formattedDate = formatCommentTimestamp(changedIso),
                    epochMs = epoch,
                    commentId = null,
                    isRead = !isUnread
                )
                existingNotificationsById[notifId] = notif
                if (isUnread && notifId !in notifiedSet) {
                    newlyTriggered.add(notif)
                }
                notifiedSet.add(notifId)
            } else if (existingNotif != null) {
                // Keep work item title, state, and type fresh in the Assigned feed
                existingNotificationsById[notifId] = existingNotif.copy(
                    workItemTitle = item.title,
                    workItemType = item.workItemType,
                    workItemState = item.state,
                    teamProject = item.teamProject,
                    messageSnippet = "Assigned to ${item.assignedToName ?: effectiveIdentityName ?: "you"} • ${item.state}"
                )
            }
        }

        // 3. Scan recently changed work items for Discussion @mentions
        val isInitialMentionsBaseline = !currentState.baselineMentionsInitialized
        val lastCheckedIso = currentState.lastCheckedChangedDateIso
        var maxObservedChangedIso = lastCheckedIso

        if (!effectiveIdentityName.isNullOrBlank() || !detectedEmail.isNullOrBlank()) {
            val candidateItemsForComments = itemsById.values
                .filter { !it.teamProject.isNullOrBlank() && !isExcludedNonWorkItemType(it.workItemType) }
                .sortedByDescending { it.changedDate ?: "" }
                .let { sorted ->
                    if (isInitialMentionsBaseline || lastCheckedIso.isNullOrBlank()) {
                        sorted.take(10)
                    } else {
                        sorted.filter { (it.changedDate ?: "") > lastCheckedIso }.take(10)
                    }
                }

            if (candidateItemsForComments.isNotEmpty()) {
                val commentResults = coroutineScope {
                    candidateItemsForComments.map { workItem ->
                        async {
                            val proj = workItem.teamProject?.trim().orEmpty()
                            val comments = fetchWorkItemComments(
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
                        if (commentMentionsIdentity(comment.text, effectiveIdentityName, detectedEmail, detectedUserId)) {
                            val notifId = "mention_${normOrg.lowercase()}_${workItem.id}_${comment.id}"
                            val isAuthorSelf = matchesUserIdentity(
                                candidateName = comment.authorName,
                                candidateEmail = comment.authorEmail,
                                effectiveDisplayName = effectiveIdentityName,
                                detectedEmail = detectedEmail
                            ) || comment.authorName.equals("You", ignoreCase = true)

                            if (!existingNotificationsById.containsKey(notifId)) {
                                val epoch = parseIsoToEpochMs(comment.createdDate).takeIf { it > 0L }
                                    ?: System.currentTimeMillis()
                                val shouldNotify = !isInitialMentionsBaseline && !isAuthorSelf
                                val notifItem = com.example.data.model.AppNotificationItem(
                                    id = notifId,
                                    organization = normOrg,
                                    workItemId = workItem.id,
                                    workItemTitle = workItem.title,
                                    workItemType = workItem.workItemType,
                                    workItemState = workItem.state,
                                    teamProject = workItem.teamProject,
                                    category = com.example.data.model.NotificationCategory.MENTIONED,
                                    actorName = comment.authorName,
                                    messageSnippet = comment.text,
                                    createdDateIso = comment.createdDate,
                                    formattedDate = comment.formattedDate.ifBlank { formatCommentTimestamp(comment.createdDate) },
                                    epochMs = epoch,
                                    commentId = comment.id,
                                    isRead = !shouldNotify
                                )
                                existingNotificationsById[notifId] = notifItem
                                if (shouldNotify && notifId !in notifiedSet) {
                                    newlyTriggered.add(notifItem)
                                }
                                notifiedSet.add(notifId)
                            }
                        }
                    }
                }
            }

            if (maxObservedChangedIso == null) {
                maxObservedChangedIso = itemsById.values
                    .mapNotNull { it.changedDate?.trim() }
                    .filter { it.isNotEmpty() }
                    .maxOrNull()
            }
        }

        val sortedNotifications = existingNotificationsById.values
            .sortedWith(
                compareByDescending<com.example.data.model.AppNotificationItem> { it.epochMs }
                    .thenByDescending { it.workItemId }
            )
            .take(150)

        currentState = currentState.copy(
            organization = normOrg,
            detectedDisplayName = detectedName ?: currentState.detectedDisplayName,
            detectedEmail = detectedEmail ?: currentState.detectedEmail,
            detectedUserId = detectedUserId ?: currentState.detectedUserId,
            notifications = sortedNotifications,
            notifiedNotificationIds = notifiedSet.toList().takeLast(400),
            baselineAssignedInitialized = true,
            baselineMentionsInitialized = true,
            knownAssignedItemStates = knownAssignedMap,
            lastCheckedChangedDateIso = maxObservedChangedIso ?: currentState.lastCheckedChangedDateIso
        )

        localStore.saveNotificationsState(currentState)

        NotificationSyncOutcome(
            state = currentState,
            newlyTriggeredNotifications = newlyTriggered,
            newlyHydratedWorkItems = newlyHydratedItems
        )
    }

    private fun cleanHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }
}

