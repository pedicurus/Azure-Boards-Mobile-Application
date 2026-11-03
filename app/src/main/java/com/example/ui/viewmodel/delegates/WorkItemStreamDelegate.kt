package com.example.ui.viewmodel.delegates

import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.repository.AzureDevOpsRepository
import com.example.di.SessionManager
import com.example.ui.ConnectionStatus
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate.Companion.PROPOSED_INITIAL_BATCH_SIZE
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate.Companion.PROPOSED_PAGE_STEP
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate.Companion.STATE_INITIAL_BATCH_SIZE
import com.example.ui.viewmodel.delegates.WorkItemFilterDelegate.Companion.STATE_PAGE_STEP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Delegate managing progressive background streaming, delta synchronization, server search,
 * account switching, local cache restoration/persistence, and loading indicators.
 */
class WorkItemStreamDelegate(
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val repository: AzureDevOpsRepository,
    private val allWorkItems: MutableStateFlow<List<WorkItem>>,
    private val workItemsOrg: MutableStateFlow<String?>,
    private val filterDelegate: WorkItemFilterDelegate,
    private val processDelegate: WorkItemProcessDelegate,
    private val crudDelegate: WorkItemCrudDelegate,
    private val hierarchyDelegate: WorkItemHierarchyDelegate
) {

    companion object {
        private const val BACKGROUND_STREAM_UI_THROTTLE_MS = 250L
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isSearchingServer = MutableStateFlow(false)
    val isSearchingServer: StateFlow<Boolean> = _isSearchingServer.asStateFlow()

    private val _isBackgroundSyncing = MutableStateFlow(false)
    val isBackgroundSyncing: StateFlow<Boolean> = _isBackgroundSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<Float?>(null)
    val syncProgress: StateFlow<Float?> = _syncProgress.asStateFlow()

    private val _isPullRefreshing = MutableStateFlow(false)
    val isPullRefreshing: StateFlow<Boolean> = _isPullRefreshing.asStateFlow()

    private val _syncStatusText = MutableStateFlow<String?>(null)
    val syncStatusText: StateFlow<String?> = _syncStatusText.asStateFlow()

    private var backgroundStreamJob: Job? = null
    private var activeConnectJob: Job? = null
    private var snapshotPersistJob: Job? = null

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setLoadingMore(loadingMore: Boolean) {
        _isLoadingMore.value = loadingMore
    }

    fun setSearchingServer(searching: Boolean) {
        _isSearchingServer.value = searching
    }

    fun setBackgroundSyncing(syncing: Boolean) {
        _isBackgroundSyncing.value = syncing
    }

    fun setSyncProgress(progress: Float?) {
        _syncProgress.value = progress
    }

    fun setPullRefreshing(refreshing: Boolean) {
        _isPullRefreshing.value = refreshing
    }

    fun setSyncStatusText(text: String?) {
        _syncStatusText.value = text
    }

    fun reset() {
        _isLoading.value = false
        _isLoadingMore.value = false
        _isSearchingServer.value = false
        _isBackgroundSyncing.value = false
        _syncProgress.value = null
        _isPullRefreshing.value = false
        _syncStatusText.value = null
    }

    fun ensureProjectProcessLoaded(project: String) {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isNotBlank() && pat.isNotBlank()) {
            scope.launch {
                processDelegate.fetchAndStoreProjectMetadata(org, project, pat)
            }
        }
    }

    fun initializeSavedAccountsAndAutoConnect() {
        val savedAccounts = repository.getSavedOrganizations()
        sessionManager.updateOrganizations(savedAccounts)
        val savedDefaultOrg = repository.getDefaultOrganization() ?: savedAccounts.firstOrNull()?.organization
        sessionManager.updateDefaultOrganization(savedDefaultOrg)

        val initialProjectsMap = mutableMapOf<String, List<String>>()
        val initialDefaultsMap = mutableMapOf<String, String>()
        savedAccounts.forEach { acc ->
            val cachedProjs = repository.getCachedProjectsForOrg(acc.organization)
            if (cachedProjs.isNotEmpty()) {
                initialProjectsMap[acc.organization] = cachedProjs
            }
            val savedDefProj = repository.getDefaultProject(acc.organization)
                ?.takeIf { def -> cachedProjs.isEmpty() || cachedProjs.any { it.equals(def, ignoreCase = true) } }
                ?: cachedProjs.firstOrNull()
            if (savedDefProj != null) {
                initialDefaultsMap[acc.organization] = savedDefProj
                repository.saveDefaultProject(savedDefProj, acc.organization)
            }
        }
        if (initialProjectsMap.isNotEmpty()) {
            sessionManager.updateProjectsByOrg(initialProjectsMap)
        }
        if (initialDefaultsMap.isNotEmpty()) {
            sessionManager.updateDefaultProjectsByOrg(initialDefaultsMap)
        }

        val initialDefProj = savedDefaultOrg?.let { defOrg ->
            initialDefaultsMap.entries.firstOrNull { it.key.equals(defOrg, ignoreCase = true) }?.value
                ?: repository.getDefaultProject(defOrg)
        } ?: repository.getDefaultProject()
        sessionManager.updateDefaultProject(initialDefProj)
        if (!initialDefProj.isNullOrBlank()) {
            filterDelegate.setProject(initialDefProj)
        }

        if (!savedDefaultOrg.isNullOrBlank()) {
            restoreOrganizationFromLocalCache(savedDefaultOrg)
        }

        val activeAccount = sessionManager.getActiveAccount()
        if (activeAccount != null && activeAccount.organization.isNotBlank() && activeAccount.pat.isNotBlank()) {
            connect(
                urlOrOrg = activeAccount.organization,
                pat = activeAccount.pat,
                rememberCredentials = activeAccount.isRemembered
            )
        } else {
            val savedUrl = repository.getConfiguredUrl()
            val savedPat = repository.getConfiguredPat()
            if (savedUrl.isNotBlank() && savedPat.isNotBlank()) {
                connect(savedUrl, savedPat, rememberCredentials = true)
            }
        }
    }

    fun observeOnDemandServerSearch() {
        scope.launch {
            filterDelegate.searchQuery.collectLatest { rawQuery ->
                val cleanQuery = rawQuery.trim()
                if (cleanQuery.length < 2) {
                    setSearchingServer(false)
                    return@collectLatest
                }
                delay(280L)
                val acc = sessionManager.getActiveAccount()
                val org = acc?.organization ?: repository.getConfiguredUrl()
                val pat = acc?.pat ?: repository.getConfiguredPat()
                if (org.isBlank() || pat.isBlank()) return@collectLatest

                val existingIds = allWorkItems.value.map { it.id }.toSet()
                val numericId = cleanQuery.removePrefix("#").trim().toIntOrNull()
                if (numericId != null && numericId in existingIds) {
                    return@collectLatest
                }

                setSearchingServer(true)
                val searchResult = repository.searchWorkItemsOnServer(
                    organization = org,
                    pat = pat,
                    query = cleanQuery,
                    project = filterDelegate.selectedProject.value,
                    alreadyLoadedIds = existingIds
                )
                searchResult.onSuccess { fetchedSearchItems ->
                    if (fetchedSearchItems.isNotEmpty()) {
                        mergeHydratedWorkItemsSuspend(fetchedSearchItems)
                    }
                }
                setSearchingServer(false)
            }
        }
    }

    fun restoreOrganizationFromLocalCache(organization: String): CachedOrganizationSnapshot? {
        val normOrg = repository.extractOrganization(organization)
        if (normOrg.isBlank()) return null

        val cachedMeta = repository.getCachedOrganizationMetadataSync(normOrg)
        if (cachedMeta != null) {
            val validProcessMap = if (cachedMeta.schemaVersion >= com.example.data.model.CachedOrganizationMetadata.CURRENT_SCHEMA_VERSION) {
                processDelegate.projectProcessMap.value + cachedMeta.processHierarchyByProject
            } else {
                processDelegate.projectProcessMap.value
            }
            processDelegate.updateMaps(
                processMap = validProcessMap,
                categoryMap = processDelegate.projectCategoryMap.value + cachedMeta.categoryMapByProject,
                backlogLevelsMap = processDelegate.projectBacklogLevelsMap.value + cachedMeta.backlogLevelsByProject,
                areasMap = processDelegate.projectAreasMap.value + cachedMeta.areasByProject,
                iterationsMap = processDelegate.projectIterationsMap.value + cachedMeta.iterationsByProject
            )
        }

        val cachedNotifs = repository.getCachedNotificationsStateSync(normOrg)
        sessionManager.updateNotificationsState(cachedNotifs)

        val snapshot = repository.getCachedOrganizationSnapshotSync(normOrg)
        if (snapshot != null && snapshot.workItems.isNotEmpty()) {
            crudDelegate.clearAll()
            crudDelegate.recordServerSyncedItems(snapshot.workItems)
            workItemsOrg.value = normOrg
            allWorkItems.value = snapshot.workItems
            filterDelegate.proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, snapshot.workItems.size)
            filterDelegate.stateDisplayLimits.value = emptyMap()
            filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)
            setLoading(false)
            sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normOrg))
        }
        return snapshot
    }

    fun startIterativeBackgroundStream(organization: String, pat: String) {
        val normOrg = repository.extractOrganization(organization)
        val cleanPat = pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) return

        backgroundStreamJob?.cancel()
        backgroundStreamJob = scope.launch {
            val initialUnloaded = repository.getRemainingUnloadedCount(normOrg)
            if (initialUnloaded <= 0) {
                filterDelegate.hasMoreServerItems.value = false
                setBackgroundSyncing(false)
                setSyncProgress(null)
                setSyncStatusText(null)
                return@launch
            }

            setBackgroundSyncing(true)
            filterDelegate.hasMoreServerItems.value = true

            val pendingStreamBuffer = ArrayList<WorkItem>()
            var lastUiEmitMs = 0L

            suspend fun flushBufferedStreamItems(forcePersist: Boolean) {
                if (pendingStreamBuffer.isNotEmpty()) {
                    val toMerge = ArrayList(pendingStreamBuffer)
                    pendingStreamBuffer.clear()
                    mergeHydratedWorkItemsSuspend(toMerge)
                    filterDelegate.proposedDisplayLimit.value =
                        maxOf(filterDelegate.proposedDisplayLimit.value, allWorkItems.value.size)
                    lastUiEmitMs = System.currentTimeMillis()
                }
                if (forcePersist) {
                    repository.saveCachedOrganizationSnapshot(normOrg, allWorkItems.value)
                }
            }

            while (repository.hasUnloadedServerItems(normOrg)) {
                val activeOrg = (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
                    ?: sessionManager.defaultOrganization.value
                if (activeOrg != null && !activeOrg.equals(normOrg, ignoreCase = true)) {
                    break
                }

                val loadedCount = allWorkItems.value.size + pendingStreamBuffer.size
                val remainingCount = repository.getRemainingUnloadedCount(normOrg)
                val totalEstimate = (loadedCount + remainingCount).coerceAtLeast(1)
                setSyncProgress((loadedCount.toFloat() / totalEstimate.toFloat()).coerceIn(0.05f, 0.99f))
                setSyncStatusText("Syncing $loadedCount / $totalEstimate")

                val alreadyIds = allWorkItems.value.map { it.id }.toHashSet().apply {
                    pendingStreamBuffer.forEach { add(it.id) }
                }
                val batchResult = repository.fetchNextWorkItemBatch(
                    organization = normOrg,
                    pat = cleanPat,
                    alreadyLoadedIds = alreadyIds,
                    batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
                )

                val fetchedBatch = batchResult.getOrNull()
                if (fetchedBatch.isNullOrEmpty()) {
                    if (batchResult.isFailure || !repository.hasUnloadedServerItems(normOrg)) {
                        break
                    }
                } else {
                    pendingStreamBuffer.addAll(fetchedBatch)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastUiEmitMs >= BACKGROUND_STREAM_UI_THROTTLE_MS) {
                        flushBufferedStreamItems(forcePersist = false)
                    }
                }
                delay(45L)
            }

            flushBufferedStreamItems(forcePersist = true)
            filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normOrg)
            setBackgroundSyncing(false)
            setSyncProgress(null)
            setSyncStatusText(null)
        }
    }

    fun persistCurrentItemsInBackground(organization: String? = null) {
        val targetOrg = organization
            ?: workItemsOrg.value
            ?: (sessionManager.connectionStatus.value as? ConnectionStatus.Connected)?.organization
            ?: sessionManager.defaultOrganization.value
            ?: return
        if (targetOrg.isBlank()) return
        val itemsSnapshot = allWorkItems.value
        if (itemsSnapshot.isEmpty()) return

        snapshotPersistJob?.cancel()
        snapshotPersistJob = scope.launch(Dispatchers.IO) {
            delay(200L)
            repository.saveCachedOrganizationSnapshot(targetOrg, itemsSnapshot)
        }
    }

    fun connect(
        urlOrOrg: String,
        pat: String,
        rememberCredentials: Boolean = true
    ) {
        val normalizedOrg = repository.extractOrganization(urlOrOrg)
        val cleanPat = pat.trim()

        if (normalizedOrg.isBlank()) {
            sessionManager.updateErrorMessage("Please enter a valid Azure DevOps organization name or URL.")
            sessionManager.updateConnectionStatus(ConnectionStatus.Error("Invalid organization"))
            return
        }
        if (cleanPat.isBlank()) {
            sessionManager.updateErrorMessage("Please enter your Personal Access Token (PAT).")
            sessionManager.updateConnectionStatus(ConnectionStatus.Error("Missing PAT"))
            return
        }

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = scope.launch {
            sessionManager.updateErrorMessage(null)

            val currentItemsBelongToTargetOrg = workItemsOrg.value.equals(normalizedOrg, ignoreCase = true)
            if (!currentItemsBelongToTargetOrg) {
                allWorkItems.value = emptyList()
                restoreOrganizationFromLocalCache(normalizedOrg)
            }

            val hasCachedItemsForOrg = workItemsOrg.value.equals(normalizedOrg, ignoreCase = true) &&
                allWorkItems.value.isNotEmpty()

            if (!hasCachedItemsForOrg) {
                setLoading(true)
                if (sessionManager.organizations.value.isEmpty()) {
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connecting)
                }
            } else {
                setLoading(false)
                sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                setBackgroundSyncing(true)
                setSyncProgress(null)
                setSyncStatusText("Checking for updates...")
            }

            val knownCachedProjs = repository.getCachedProjectsForOrg(normalizedOrg)
            val preResolvedProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, knownCachedProjs)

            if (hasCachedItemsForOrg) {
                val (deltaResult, fetchedProjs) = coroutineScope {
                    val deltaDef = async {
                        repository.syncDeltaAndQueueRemaining(
                            organization = normalizedOrg,
                            pat = cleanPat,
                            cachedItems = allWorkItems.value
                        )
                    }
                    val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                    val metaDef = async {
                        if (!preResolvedProj.isNullOrBlank()) {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, preResolvedProj, cleanPat)
                        }
                    }
                    metaDef.await()
                    deltaDef.await() to projsDef.await()
                }

                deltaResult.fold(
                    onSuccess = { delta ->
                        saveAccountIntoMemoryAndPrefs(normalizedOrg, cleanPat, rememberCredentials)

                        if (delta.deletedItemIds.isNotEmpty()) {
                            delta.deletedItemIds.forEach { crudDelegate.clearPendingDirty(it) }
                            allWorkItems.value = allWorkItems.value.filterNot { it.id in delta.deletedItemIds }
                        }
                        if (delta.updatedOrNewItems.isNotEmpty()) {
                            mergeHydratedWorkItemsSuspend(delta.updatedOrNewItems)
                        }
                        workItemsOrg.value = normalizedOrg
                        filterDelegate.proposedDisplayLimit.value =
                            maxOf(filterDelegate.proposedDisplayLimit.value, allWorkItems.value.size)
                        filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                        repository.saveCachedOrganizationSnapshot(normalizedOrg, allWorkItems.value)

                        val fromItemsProjects = allWorkItems.value
                            .mapNotNull { it.teamProject?.trim() }
                            .filter { it.isNotBlank() }
                        val mergedProjects = (fetchedProjs + fromItemsProjects + knownCachedProjs)
                            .distinctBy { it.lowercase() }
                            .sorted()
                        if (mergedProjects.isNotEmpty()) {
                            repository.saveCachedProjectsForOrg(normalizedOrg, mergedProjects)
                            sessionManager.putProjectsForOrg(normalizedOrg, mergedProjects)
                        }

                        val resolvedDefProj = sessionManager.resolveAndPersistDefaultProjectForOrg(
                            normalizedOrg,
                            mergedProjects
                        )
                        val validSelectedProj = filterDelegate.selectedProject.value?.takeIf { current ->
                            mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                        } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                        if (validSelectedProj != null && !filterDelegate.selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                            filterDelegate.userManuallySelectedProject = false
                            filterDelegate.userManuallySelectedType = false
                            filterDelegate.setTypeFilter(null)
                            filterDelegate.setStateFilter(null)
                            filterDelegate.setProject(validSelectedProj)
                        }

                        val targetProj = filterDelegate.selectedProject.value ?: validSelectedProj
                        if (!targetProj.isNullOrBlank()) {
                            launch {
                                processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, targetProj, cleanPat)
                            }
                        }

                        val otherProjs = mergedProjects.filterNot { it.equals(targetProj, ignoreCase = true) }
                        if (otherProjs.isNotEmpty()) {
                            launch {
                                otherProjs.map { proj ->
                                    async {
                                        processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                    }
                                }.awaitAll()
                            }
                        }

                        setLoading(false)
                        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, cleanPat)
                        } else {
                            setBackgroundSyncing(false)
                            setSyncProgress(null)
                            setSyncStatusText(null)
                        }
                        sessionManager.onSyncNotificationsRequested?.invoke(true)
                    },
                    onFailure = { error ->
                        setLoading(false)
                        setBackgroundSyncing(false)
                        setSyncProgress(null)
                        setSyncStatusText(null)
                        sessionManager.updateErrorMessage(error.message ?: "Failed to connect to Azure DevOps.")
                    }
                )
                return@launch
            }

            val (itemsResult, fetchedProjs) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, cleanPat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                itemsDef.await() to projsDef.await()
            }

            itemsResult.fold(
                onSuccess = { items ->
                    saveAccountIntoMemoryAndPrefs(normalizedOrg, cleanPat, rememberCredentials)

                    crudDelegate.clearAll()
                    crudDelegate.recordServerSyncedItems(items)
                    allWorkItems.value = items
                    workItemsOrg.value = normalizedOrg
                    filterDelegate.proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    filterDelegate.stateDisplayLimits.value = emptyMap()
                    filterDelegate.exhaustedStateQueries.clear()
                    filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val orgProjects = (fetchedProjs + fromItemsProjs + knownCachedProjs)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (orgProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, orgProjects)
                        sessionManager.putProjectsForOrg(normalizedOrg, orgProjects)
                    }

                    val chosenProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    filterDelegate.userManuallySelectedProject = false
                    filterDelegate.userManuallySelectedType = false
                    filterDelegate.resetFilters()
                    hierarchyDelegate.setSelectedWorkItem(null)
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    filterDelegate.setProject(chosenProj)

                    if (chosenProj != null) {
                        launch {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, cleanPat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                }
                            }.awaitAll()
                        }
                    }

                    setLoading(false)
                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        setBackgroundSyncing(false)
                        setSyncProgress(null)
                        setSyncStatusText(null)
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)
                },
                onFailure = { error ->
                    setLoading(false)
                    setBackgroundSyncing(false)
                    setSyncProgress(null)
                    setSyncStatusText(null)
                    sessionManager.updateErrorMessage(error.message ?: "Failed to connect to Azure DevOps.")
                    sessionManager.updateConnectionStatus(ConnectionStatus.Error(error.message ?: "Connection failed"))
                }
            )
        }
    }

    private fun saveAccountIntoMemoryAndPrefs(
        normalizedOrg: String,
        cleanPat: String,
        rememberCredentials: Boolean
    ) {
        val newAccount = OrganizationAccount(
            organization = normalizedOrg,
            pat = cleanPat,
            isRemembered = rememberCredentials
        )
        val currentAccounts = sessionManager.organizations.value.toMutableList()
        val existingIndex = currentAccounts.indexOfFirst { it.organization.equals(normalizedOrg, ignoreCase = true) }
        if (existingIndex >= 0) {
            currentAccounts[existingIndex] = newAccount
        } else {
            currentAccounts.add(newAccount)
        }
        val updatedOrgs = currentAccounts.distinctBy { it.organization.lowercase() }
        sessionManager.updateOrganizations(updatedOrgs)
        repository.saveOrganizationAccount(newAccount)

        if (sessionManager.defaultOrganization.value.isNullOrBlank() || updatedOrgs.size == 1) {
            sessionManager.updateDefaultOrganization(normalizedOrg)
            if (rememberCredentials) {
                repository.saveDefaultOrganization(normalizedOrg)
            }
        }
    }

    fun switchOrganization(organizationName: String) {
        val normOrg = repository.extractOrganization(organizationName)
        val account = sessionManager.organizations.value.firstOrNull {
            it.organization.equals(normOrg, ignoreCase = true)
        } ?: return

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()
        setBackgroundSyncing(false)
        setSyncProgress(null)
        setSyncStatusText(null)

        filterDelegate.userManuallySelectedProject = false
        filterDelegate.userManuallySelectedType = false
        filterDelegate.resetFilters()
        hierarchyDelegate.setSelectedWorkItem(null)
        workItemsOrg.value = account.organization
        allWorkItems.value = emptyList()

        val knownProjectsForOrg = (sessionManager.projectsByOrg.value.entries
            .firstOrNull { it.key.equals(account.organization, ignoreCase = true) }?.value.orEmpty() +
            repository.getCachedProjectsForOrg(account.organization))
            .distinctBy { it.lowercase() }
            .sorted()

        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(account.organization))
        filterDelegate.setProject(
            sessionManager.resolveAndPersistDefaultProjectForOrg(
                account.organization,
                knownProjectsForOrg
            ) ?: knownProjectsForOrg.firstOrNull()
        )

        val restored = restoreOrganizationFromLocalCache(account.organization)
        setLoading(restored == null || restored.workItems.isEmpty())

        scope.launch {
            val dirtyList = crudDelegate.getPendingDirtyItems().values.toList()
            crudDelegate.clearAll()
            coroutineScope {
                dirtyList.map { dirtyItem ->
                    async { crudDelegate.syncWorkItemToServer(dirtyItem) }
                }.awaitAll()
            }

            connect(account.organization, account.pat, rememberCredentials = account.isRemembered)
        }
    }

    fun saveOrganizationFromSettings(
        previousOrgName: String?,
        orgInput: String,
        patInput: String,
        isRemembered: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        val normalizedOrg = repository.extractOrganization(orgInput)
        val cleanPat = patInput.trim()
        if (normalizedOrg.isBlank()) {
            onResult(false, "Please enter a valid organization name or URL.")
            return
        }
        if (cleanPat.isBlank()) {
            onResult(false, "Please enter a valid Personal Access Token (PAT).")
            return
        }

        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = scope.launch {
            val (workItemsResult, fetchedProjs) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, cleanPat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, cleanPat) }
                itemsDef.await() to projsDef.await()
            }
            workItemsResult.fold(
                onSuccess = { items ->
                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                    val orgProjects = (fetchedProjs + fromItemsProjs + cachedForOrg)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (orgProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, orgProjects)
                        sessionManager.putProjectsForOrg(normalizedOrg, orgProjects)
                    }

                    val newAccount = OrganizationAccount(
                        organization = normalizedOrg,
                        pat = cleanPat,
                        isRemembered = isRemembered
                    )

                    val currentList = sessionManager.organizations.value.toMutableList()
                    if (!previousOrgName.isNullOrBlank() && !previousOrgName.equals(normalizedOrg, ignoreCase = true)) {
                        repository.removeOrganizationAccount(previousOrgName)
                        val prevIdx = currentList.indexOfFirst { it.organization.equals(previousOrgName, ignoreCase = true) }
                        if (prevIdx >= 0) {
                            currentList[prevIdx] = newAccount
                        } else {
                            currentList.add(newAccount)
                        }
                    } else {
                        val existingIdx = currentList.indexOfFirst { it.organization.equals(normalizedOrg, ignoreCase = true) }
                        if (existingIdx >= 0) {
                            currentList[existingIdx] = newAccount
                        } else {
                            currentList.add(newAccount)
                        }
                    }
                    val distinctList = currentList.distinctBy { it.organization.lowercase() }
                    sessionManager.updateOrganizations(distinctList)
                    repository.saveOrganizationAccount(newAccount)

                    val wasPreviousDefault = !previousOrgName.isNullOrBlank() &&
                        previousOrgName.equals(sessionManager.defaultOrganization.value, ignoreCase = true)
                    if (distinctList.size == 1 || sessionManager.defaultOrganization.value.isNullOrBlank() || wasPreviousDefault) {
                        sessionManager.updateDefaultOrganization(normalizedOrg)
                        if (isRemembered) {
                            repository.saveDefaultOrganization(normalizedOrg)
                        }
                    }

                    val chosenProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    filterDelegate.userManuallySelectedProject = false
                    filterDelegate.userManuallySelectedType = false
                    filterDelegate.resetFilters()
                    hierarchyDelegate.setSelectedWorkItem(null)
                    filterDelegate.stateDisplayLimits.value = emptyMap()
                    filterDelegate.exhaustedStateQueries.clear()

                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    workItemsOrg.value = normalizedOrg
                    filterDelegate.setProject(chosenProj)

                    crudDelegate.clearAll()
                    crudDelegate.recordServerSyncedItems(items)
                    allWorkItems.value = items
                    filterDelegate.proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    if (chosenProj != null) {
                        launch {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, cleanPat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, cleanPat)
                                }
                            }.awaitAll()
                        }
                    }

                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, cleanPat)
                    } else {
                        setBackgroundSyncing(false)
                        setSyncProgress(null)
                        setSyncStatusText(null)
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)

                    onResult(true, null)
                },
                onFailure = { err ->
                    onResult(false, err.message ?: "Failed to verify organization and PAT.")
                }
            )
        }
    }

    fun refreshWorkItems() {
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val normalizedOrg = repository.extractOrganization(org)
        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = scope.launch {
            val itemsBelongToTargetOrg = workItemsOrg.value.equals(normalizedOrg, ignoreCase = true)
            val hasExisting = itemsBelongToTargetOrg && allWorkItems.value.isNotEmpty()
            setPullRefreshing(true)
            if (!hasExisting) {
                setLoading(true)
            } else {
                setBackgroundSyncing(true)
                setSyncProgress(null)
                setSyncStatusText("Refreshing...")
            }
            sessionManager.updateErrorMessage(null)

            val dirtyList = crudDelegate.getPendingDirtyItems().values.toList()
            crudDelegate.clearAll()
            coroutineScope {
                dirtyList.map { dirtyItem ->
                    async { crudDelegate.syncWorkItemToServer(dirtyItem) }
                }.awaitAll()
            }

            if (hasExisting) {
                val (deltaRes, fetchedProjs) = coroutineScope {
                    val deltaDef = async {
                        repository.syncDeltaAndQueueRemaining(
                            organization = normalizedOrg,
                            pat = pat,
                            cachedItems = allWorkItems.value
                        )
                    }
                    val projsDef = async { repository.fetchProjects(normalizedOrg, pat) }
                    deltaDef.await() to projsDef.await()
                }
                deltaRes.fold(
                    onSuccess = { delta ->
                        if (delta.deletedItemIds.isNotEmpty()) {
                            delta.deletedItemIds.forEach { crudDelegate.clearPendingDirty(it) }
                            allWorkItems.value = allWorkItems.value.filterNot { it.id in delta.deletedItemIds }
                        }
                        if (delta.updatedOrNewItems.isNotEmpty()) {
                            mergeHydratedWorkItemsSuspend(delta.updatedOrNewItems)
                        }
                        workItemsOrg.value = normalizedOrg
                        filterDelegate.proposedDisplayLimit.value =
                            maxOf(filterDelegate.proposedDisplayLimit.value, allWorkItems.value.size)
                        filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)
                        repository.saveCachedOrganizationSnapshot(normalizedOrg, allWorkItems.value)

                        val fromItemsProjects = allWorkItems.value.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                        val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                        val mergedProjects = (fetchedProjs + fromItemsProjects + cachedForOrg)
                            .distinctBy { it.lowercase() }
                            .sorted()
                        if (mergedProjects.isNotEmpty()) {
                            repository.saveCachedProjectsForOrg(normalizedOrg, mergedProjects)
                            sessionManager.putProjectsForOrg(normalizedOrg, mergedProjects)
                        }

                        val resolvedDefProj = sessionManager.resolveAndPersistDefaultProjectForOrg(
                            normalizedOrg,
                            mergedProjects
                        )
                        val validSelectedProj = filterDelegate.selectedProject.value?.takeIf { current ->
                            mergedProjects.isEmpty() || mergedProjects.any { it.equals(current, ignoreCase = true) }
                        } ?: resolvedDefProj ?: mergedProjects.firstOrNull()

                        if (validSelectedProj != null && !filterDelegate.selectedProject.value.equals(validSelectedProj, ignoreCase = true)) {
                            filterDelegate.userManuallySelectedProject = false
                            filterDelegate.userManuallySelectedType = false
                            filterDelegate.setTypeFilter(null)
                            filterDelegate.setStateFilter(null)
                            filterDelegate.setProject(validSelectedProj)
                        }

                        val targetProj = filterDelegate.selectedProject.value ?: validSelectedProj
                        if (!targetProj.isNullOrBlank()) {
                            launch {
                                processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, targetProj, pat)
                            }
                        }
                        setLoading(false)
                        setPullRefreshing(false)
                        sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))

                        if (repository.hasUnloadedServerItems(normalizedOrg)) {
                            startIterativeBackgroundStream(normalizedOrg, pat)
                        } else {
                            setBackgroundSyncing(false)
                            setSyncProgress(null)
                            setSyncStatusText(null)
                        }
                        sessionManager.onSyncNotificationsRequested?.invoke(true)
                    },
                    onFailure = { error ->
                        setLoading(false)
                        setPullRefreshing(false)
                        setBackgroundSyncing(false)
                        setSyncProgress(null)
                        setSyncStatusText(null)
                        sessionManager.updateErrorMessage(error.message ?: "Failed to refresh work items.")
                    }
                )
                return@launch
            }

            val (result, fetchedProjs) = coroutineScope {
                val itemsDef = async { repository.fetchWorkItems(normalizedOrg, pat) }
                val projsDef = async { repository.fetchProjects(normalizedOrg, pat) }
                itemsDef.await() to projsDef.await()
            }

            result.fold(
                onSuccess = { items ->
                    crudDelegate.clearAll()
                    crudDelegate.recordServerSyncedItems(items)
                    allWorkItems.value = items
                    workItemsOrg.value = normalizedOrg
                    filterDelegate.proposedDisplayLimit.value = maxOf(PROPOSED_INITIAL_BATCH_SIZE, items.size)
                    filterDelegate.stateDisplayLimits.value = emptyMap()
                    filterDelegate.exhaustedStateQueries.clear()
                    filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(normalizedOrg)

                    repository.saveCachedOrganizationSnapshot(normalizedOrg, items)

                    val fromItemsProjs = items.mapNotNull { it.teamProject?.trim() }.filter { it.isNotBlank() }
                    val cachedForOrg = repository.getCachedProjectsForOrg(normalizedOrg)
                    val orgProjects = (fetchedProjs + fromItemsProjs + cachedForOrg)
                        .distinctBy { it.lowercase() }
                        .sorted()

                    if (orgProjects.isNotEmpty()) {
                        repository.saveCachedProjectsForOrg(normalizedOrg, orgProjects)
                        sessionManager.putProjectsForOrg(normalizedOrg, orgProjects)
                    }

                    val chosenProj = sessionManager.resolveAndPersistDefaultProjectForOrg(normalizedOrg, orgProjects)
                        ?: orgProjects.firstOrNull()

                    filterDelegate.userManuallySelectedProject = false
                    filterDelegate.userManuallySelectedType = false
                    filterDelegate.resetFilters()
                    hierarchyDelegate.setSelectedWorkItem(null)
                    sessionManager.updateConnectionStatus(ConnectionStatus.Connected(normalizedOrg))
                    filterDelegate.setProject(chosenProj)

                    if (chosenProj != null) {
                        launch {
                            processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, chosenProj, pat)
                        }
                    }

                    val otherProjs = orgProjects.filterNot { it.equals(chosenProj, ignoreCase = true) }
                    if (otherProjs.isNotEmpty()) {
                        launch {
                            otherProjs.map { proj ->
                                async {
                                    processDelegate.fetchAndStoreProjectMetadata(normalizedOrg, proj, pat)
                                }
                            }.awaitAll()
                        }
                    }

                    setLoading(false)
                    setPullRefreshing(false)
                    if (repository.hasUnloadedServerItems(normalizedOrg)) {
                        startIterativeBackgroundStream(normalizedOrg, pat)
                    } else {
                        setBackgroundSyncing(false)
                        setSyncProgress(null)
                        setSyncStatusText(null)
                    }
                    sessionManager.onSyncNotificationsRequested?.invoke(true)
                },
                onFailure = { error ->
                    setLoading(false)
                    setPullRefreshing(false)
                    setBackgroundSyncing(false)
                    setSyncProgress(null)
                    setSyncStatusText(null)
                    sessionManager.updateErrorMessage(error.message ?: "Failed to refresh work items.")
                }
            )
        }
    }

    suspend fun mergeHydratedWorkItemsSuspend(newItems: List<WorkItem>) {
        if (newItems.isEmpty()) return
        val current = allWorkItems.value
        val pendingDirty = crudDelegate.getPendingDirtyItems()
        val map = current.associateBy { it.id }.toMutableMap()
        for (item in newItems) {
            crudDelegate.recordServerSyncedItem(item)
            if (!pendingDirty.containsKey(item.id)) {
                map[item.id] = item
            }
        }
        allWorkItems.value = map.values.toList()
        hierarchyDelegate.selectedWorkItem.value?.let { currentSelected ->
            if (map.containsKey(currentSelected.id) && !pendingDirty.containsKey(currentSelected.id)) {
                hierarchyDelegate.setSelectedWorkItem(map[currentSelected.id])
            }
        }
    }

    fun loadMoreWorkItems() {
        val currentState = filterDelegate.stateFilter.value
        val currentProject = filterDelegate.selectedProject.value
        val currentType = filterDelegate.typeFilter.value

        if (currentState == null || currentState.equals("All", ignoreCase = true)) {
            filterDelegate.proposedDisplayLimit.value += PROPOSED_PAGE_STEP
        } else {
            val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${currentState.trim().lowercase()}"
            val updated = filterDelegate.stateDisplayLimits.value.toMutableMap()
            val prevLimit = updated[stateKey] ?: STATE_INITIAL_BATCH_SIZE
            updated[stateKey] = prevLimit + STATE_PAGE_STEP
            filterDelegate.stateDisplayLimits.value = updated
        }

        if (_isLoadingMore.value) return
        val acc = sessionManager.getActiveAccount()
        val org = acc?.organization ?: repository.getConfiguredUrl()
        val pat = acc?.pat ?: repository.getConfiguredPat()
        if (org.isBlank() || pat.isBlank()) return

        val isSpecificState = !currentState.isNullOrBlank() && !currentState.equals("All", ignoreCase = true)
        val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${currentState.orEmpty().trim().lowercase()}"
        val shouldQueryServer = if (isSpecificState) {
            stateKey !in filterDelegate.exhaustedStateQueries || repository.hasUnloadedServerItems(org)
        } else {
            repository.hasUnloadedServerItems(org)
        }

        if (!shouldQueryServer) return

        scope.launch {
            setLoadingMore(true)
            val existingIds = allWorkItems.value.map { it.id }.toSet()
            val batchResult = repository.fetchNextWorkItemBatch(
                organization = org,
                pat = pat,
                project = currentProject,
                workItemType = currentType,
                state = if (isSpecificState) currentState else null,
                alreadyLoadedIds = existingIds,
                batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
            )
            batchResult.onSuccess { fetchedBatch ->
                if (fetchedBatch.isNotEmpty()) {
                    mergeHydratedWorkItemsSuspend(fetchedBatch)
                    persistCurrentItemsInBackground(org)
                } else if (isSpecificState) {
                    filterDelegate.exhaustedStateQueries.add(stateKey)
                }
                filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(org)
            }
            setLoadingMore(false)
        }
    }

    fun onStateFilterSelected(trimmedState: String) {
        if (trimmedState.equals("All", ignoreCase = true)) return
        val currentProject = filterDelegate.selectedProject.value
        val currentType = filterDelegate.typeFilter.value
        val stateKey = "${currentProject.orEmpty().lowercase()}|${currentType.orEmpty().lowercase()}|${trimmedState.lowercase()}"
        if (!filterDelegate.stateDisplayLimits.value.containsKey(stateKey)) {
            val updated = filterDelegate.stateDisplayLimits.value.toMutableMap()
            updated[stateKey] = maxOf(STATE_INITIAL_BATCH_SIZE, allWorkItems.value.size)
            filterDelegate.stateDisplayLimits.value = updated
        }

        val cat = repository.resolveStateCategory(trimmedState, currentProject, currentType)
        if (cat != AzureDevOpsRepository.CATEGORY_IN_PROGRESS && stateKey !in filterDelegate.exhaustedStateQueries) {
            val inMemoryForState = allWorkItems.value.count { item ->
                (currentProject == null || item.teamProject?.equals(currentProject, ignoreCase = true) == true) &&
                    (currentType == null || item.workItemType.equals(currentType, ignoreCase = true)) &&
                    item.state.equals(trimmedState, ignoreCase = true)
            }
            val targetLimit = filterDelegate.stateDisplayLimits.value[stateKey] ?: STATE_INITIAL_BATCH_SIZE
            if (inMemoryForState < targetLimit && !_isLoadingMore.value) {
                val acc = sessionManager.getActiveAccount()
                val org = acc?.organization ?: repository.getConfiguredUrl()
                val pat = acc?.pat ?: repository.getConfiguredPat()
                if (org.isNotBlank() && pat.isNotBlank()) {
                    scope.launch {
                        setLoadingMore(true)
                        val existingIds = allWorkItems.value.map { it.id }.toSet()
                        val batchResult = repository.fetchNextWorkItemBatch(
                            organization = org,
                            pat = pat,
                            project = currentProject,
                            workItemType = currentType,
                            state = trimmedState,
                            alreadyLoadedIds = existingIds,
                            batchSize = AzureDevOpsRepository.STREAM_BATCH_SIZE
                        )
                        batchResult.onSuccess { fetchedBatch ->
                            if (fetchedBatch.isNotEmpty()) {
                                mergeHydratedWorkItemsSuspend(fetchedBatch)
                                persistCurrentItemsInBackground(org)
                            } else {
                                filterDelegate.exhaustedStateQueries.add(stateKey)
                            }
                            filterDelegate.hasMoreServerItems.value = repository.hasUnloadedServerItems(org)
                        }
                        setLoadingMore(false)
                    }
                }
            }
        }
    }

    fun disconnect() {
        backgroundStreamJob?.cancel()
        activeConnectJob?.cancel()
        reset()
        repository.clearCredentials()
        sessionManager.updateOrganizations(emptyList())
        sessionManager.updateDefaultOrganization(null)
        workItemsOrg.value = null
        crudDelegate.clearAll()
        allWorkItems.value = emptyList()
        filterDelegate.clearAll()
        hierarchyDelegate.reset()
        sessionManager.updateErrorMessage(null)
        sessionManager.updateConnectionStatus(ConnectionStatus.Disconnected)
    }
}
