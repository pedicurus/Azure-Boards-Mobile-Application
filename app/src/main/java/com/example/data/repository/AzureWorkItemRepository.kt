package com.example.data.repository

import android.content.Context
import com.example.data.api.AzureDevOpsClient
import com.example.data.api.AzureDevOpsService
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.WiqlQueryRequest
import com.example.data.model.WorkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DeltaSyncResult(
    val updatedOrNewItems: List<WorkItem>,
    val deletedItemIds: Set<Int>,
    val totalServerCount: Int
)

/**
 * Domain repository responsible for progressive work item hydration, WIQL execution,
 * snapshot caching, delta sync, and server-side search.
 */
class AzureWorkItemRepository(
    context: Context,
    private val localStore: WorkItemLocalStore = WorkItemLocalStore(context.applicationContext ?: context)
) {
    private val hydratedIdsByOrg = ConcurrentHashMap<String, MutableSet<Int>>()
    private val unloadedProposedIdsByOrg = ConcurrentHashMap<String, MutableList<Int>>()
    private val unloadedOtherIdsByOrg = ConcurrentHashMap<String, MutableList<Int>>()

    companion object {
        private const val INITIAL_FAST_HYDRATE_LIMIT = 60
        const val STREAM_BATCH_SIZE = 50
    }

    fun getCachedOrganizationSnapshotSync(organization: String): CachedOrganizationSnapshot? {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        if (normOrg.isBlank()) return null
        val snapshot = localStore.loadSnapshotSync(normOrg) ?: return null
        val orgKey = normOrg.lowercase()
        snapshot.workItems.forEach { item ->
            AzureIdentityRegistry.registerKnownIdentity(
                id = item.assignedToId,
                displayName = item.assignedToName,
                email = item.assignedToEmail,
                avatarUrl = item.assignedToAvatar
            )
        }
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

    suspend fun saveCachedOrganizationSnapshot(
        organization: String,
        workItems: List<WorkItem>
    ) {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
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

    fun getRemainingUnloadedCount(organization: String): Int {
        val orgKey = AzureAccountRepository.extractOrganization(organization).lowercase()
        if (orgKey.isBlank()) return 0
        val propSize = unloadedProposedIdsByOrg[orgKey]?.let { synchronized(it) { it.size } } ?: 0
        val otherSize = unloadedOtherIdsByOrg[orgKey]?.let { synchronized(it) { it.size } } ?: 0
        return propSize + otherSize
    }

    fun hasUnloadedServerItems(organization: String): Boolean {
        val orgKey = AzureAccountRepository.extractOrganization(organization).lowercase()
        if (orgKey.isBlank()) return false
        val propRemaining = unloadedProposedIdsByOrg[orgKey]?.isNotEmpty() == true
        val otherRemaining = unloadedOtherIdsByOrg[orgKey]?.isNotEmpty() == true
        return propRemaining || otherRemaining
    }

    fun clearQueuesForOrg(organization: String) {
        val orgKey = AzureAccountRepository.extractOrganization(organization).lowercase()
        hydratedIdsByOrg.remove(orgKey)
        unloadedProposedIdsByOrg.remove(orgKey)
        unloadedOtherIdsByOrg.remove(orgKey)
    }

    suspend fun syncDeltaAndQueueRemaining(
        organization: String,
        pat: String,
        cachedItems: List<WorkItem>,
        lastSyncedChangedDate: String? = null
    ): Result<DeltaSyncResult> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val orgKey = normalizedOrg.lowercase()

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

            val watermarkDate = lastSyncedChangedDate
                ?: cachedItems.mapNotNull { it.changedDate?.trim() }.filter { it.isNotEmpty() }.maxOrNull()
            val datePrefix = watermarkDate?.take(10)

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

            val deltaCandidateIds = LinkedHashSet<Int>()
            allServerIds.take(35).forEach { deltaCandidateIds.add(it) }
            changedIdsFromServer.take(60).forEach { deltaCandidateIds.add(it) }

            val deltaIdsToHydrate = deltaCandidateIds.take(INITIAL_FAST_HYDRATE_LIMIT)
            val rawDeltaItems = if (deltaIdsToHydrate.isNotEmpty()) {
                AzureWorkItemParser.hydrateRawItemsByIds(service, normalizedOrg, deltaIdsToHydrate)
            } else {
                emptyList()
            }
            val parsedDeltaItems = AzureWorkItemParser.parseAndLinkRawWorkItems(rawDeltaItems)

            val nowHydratedSet = Collections.synchronizedSet(mutableSetOf<Int>())
            nowHydratedSet.addAll(cachedIdSet - deletedIds)
            parsedDeltaItems.forEach { nowHydratedSet.add(it.id) }
            hydratedIdsByOrg[orgKey] = nowHydratedSet

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
        service: AzureDevOpsService,
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
            val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
            if (normalizedOrg.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please provide a valid Azure DevOps Organization URL or Name."))
            }
            if (pat.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please provide your Personal Access Token (PAT)."))
            }

            try {
                val service = AzureDevOpsClient.createService(pat)

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

                val initialIdsToHydrate: List<Int>
                if (refs.size <= INITIAL_FAST_HYDRATE_LIMIT) {
                    initialIdsToHydrate = refs.map { it.id }
                    unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                    unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(mutableListOf())
                } else {
                    val (inProgressRefs, proposedRefs) = queryPriorityCategoryIds(service, normalizedOrg)

                    val prioritizedSet = LinkedHashSet<Int>()
                    inProgressRefs.take(INITIAL_FAST_HYDRATE_LIMIT).forEach { prioritizedSet.add(it) }
                    for (id in proposedRefs) {
                        if (prioritizedSet.size >= INITIAL_FAST_HYDRATE_LIMIT) break
                        prioritizedSet.add(id)
                    }
                    for (ref in refs) {
                        if (prioritizedSet.size >= INITIAL_FAST_HYDRATE_LIMIT) break
                        prioritizedSet.add(ref.id)
                    }

                    initialIdsToHydrate = prioritizedSet.take(INITIAL_FAST_HYDRATE_LIMIT)
                    val initialSet = initialIdsToHydrate.toSet()

                    val remainingHighPriority = (inProgressRefs + proposedRefs)
                        .filter { it !in initialSet }
                        .distinct()
                    val remainingHighPrioritySet = remainingHighPriority.toSet()
                    val remainingOther = refs.map { it.id }
                        .filter { it !in initialSet && it !in remainingHighPrioritySet }

                    unloadedProposedIdsByOrg[orgKey] = Collections.synchronizedList(remainingHighPriority.toMutableList())
                    unloadedOtherIdsByOrg[orgKey] = Collections.synchronizedList(remainingOther.toMutableList())
                }

                val rawItems = AzureWorkItemParser.hydrateRawItemsByIds(service, normalizedOrg, initialIdsToHydrate)
                val workItems = AzureWorkItemParser.parseAndLinkRawWorkItems(rawItems)
                workItems.forEach { hydratedSet.add(it.id) }
                unloadedProposedIdsByOrg[orgKey]?.removeAll(hydratedSet)
                unloadedOtherIdsByOrg[orgKey]?.removeAll(hydratedSet)

                Result.success(workItems)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchNextWorkItemBatch(
        organization: String,
        pat: String,
        project: String? = null,
        workItemType: String? = null,
        state: String? = null,
        alreadyLoadedIds: Set<Int> = emptySet(),
        batchSize: Int = STREAM_BATCH_SIZE
    ): Result<List<WorkItem>> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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

            val rawItems = AzureWorkItemParser.hydrateRawItemsByIds(service, normalizedOrg, candidateIds)
            val parsed = AzureWorkItemParser.parseAndLinkRawWorkItems(rawItems)
            parsed.forEach { hydratedSet.add(it.id) }
            unloadedProposedIdsByOrg[orgKey]?.removeAll(hydratedSet)
            unloadedOtherIdsByOrg[orgKey]?.removeAll(hydratedSet)

            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchWorkItemsOnServer(
        organization: String,
        pat: String,
        query: String,
        project: String? = null,
        alreadyLoadedIds: Set<Int> = emptySet()
    ): Result<List<WorkItem>> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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

            val (directIdRaw, titleAndTagsIds, descIds) = coroutineScope {
                val directDef = async {
                    if (numericId != null && numericId !in hydratedSet) {
                        runCatching {
                            AzureWorkItemParser.hydrateRawItemsByIds(service, normalizedOrg, listOf(numericId))
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
                AzureWorkItemParser.hydrateRawItemsByIds(service, normalizedOrg, unloadedMatchedIds)
            } else {
                emptyList()
            }

            val combinedRaw = (directIdRaw + hydratedFromWiql).distinctBy { it.id }
            if (combinedRaw.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val parsed = AzureWorkItemParser.parseAndLinkRawWorkItems(combinedRaw)
            parsed.forEach { hydratedSet.add(it.id) }
            unloadedProposedIdsByOrg[orgKey]?.removeAll(hydratedSet)
            unloadedOtherIdsByOrg[orgKey]?.removeAll(hydratedSet)

            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
