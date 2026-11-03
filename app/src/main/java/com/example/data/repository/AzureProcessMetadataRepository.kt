package com.example.data.repository

import android.content.Context
import com.example.data.api.AzureDevOpsClient
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.CachedOrganizationMetadata
import com.example.data.model.ProcessBehaviorDefinition
import com.example.data.model.ProcessWorkItemType
import com.example.data.model.WorkItem
import com.example.ui.theme.registerAzureStateColor
import com.example.ui.theme.registerAzureTypeColor
import com.example.ui.theme.registerAzureTypeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ProjectBacklogMetadata(
    val categoryMap: Map<String, String>,
    val orderedBacklogLevels: List<List<String>>
)

/**
 * Domain repository responsible for state categorization, process hierarchy detection,
 * work item type styling, and backlog hierarchy levels resolution.
 */
class AzureProcessMetadataRepository(
    context: Context,
    private val localStore: WorkItemLocalStore = WorkItemLocalStore(context.applicationContext ?: context)
) {

    companion object {
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

    fun getCachedOrganizationMetadataSync(organization: String): CachedOrganizationMetadata? {
        val normOrg = AzureAccountRepository.extractOrganization(organization)
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
        val normOrg = AzureAccountRepository.extractOrganization(organization)
        if (normOrg.isBlank()) return
        localStore.saveMetadata(
            CachedOrganizationMetadata(
                organization = normOrg,
                processHierarchyByProject = processHierarchyByProject,
                categoryMapByProject = categoryMapByProject,
                backlogLevelsByProject = backlogLevelsByProject,
                areasByProject = areasByProject,
                iterationsByProject = iterationsByProject,
                typeColors = com.example.ui.theme.getRegisteredTypeColorsHex(),
                typeIcons = com.example.ui.theme.getRegisteredTypeIcons(),
                stateColors = com.example.ui.theme.getRegisteredStateColorsHex(),
                stateCategoryMap = stateCategoryCache.toMap(),
                schemaVersion = CachedOrganizationMetadata.CURRENT_SCHEMA_VERSION
            )
        )
    }

    suspend fun fetchProjects(organization: String, pat: String): List<String> =
        withContext(Dispatchers.IO) {
            val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun fetchProjectBacklogMetadata(
        organization: String,
        project: String,
        pat: String,
        activeTypesHint: List<String> = emptyList()
    ): ProjectBacklogMetadata = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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
                    emptyList<ProcessWorkItemType>() to
                        emptyList<ProcessBehaviorDefinition>()
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
                        !AzureDevOpsRepository.isExcludedNonWorkItemType(name)
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
                            !AzureDevOpsRepository.isExcludedNonWorkItemType(name)
                        ) {
                            enabledCanonicalByLower.putIfAbsent(name.lowercase(), name)
                        }
                    }
                }
                for (hint in activeTypesHint) {
                    val name = hint.trim()
                    if (name.isNotBlank() &&
                        name.lowercase() !in disabledTypeNamesLower &&
                        !AzureDevOpsRepository.isExcludedNonWorkItemType(name)
                    ) {
                        enabledCanonicalByLower.putIfAbsent(name.lowercase(), name)
                    }
                }

                fun resolveEnabledTypeName(rawName: String?): String? {
                    val clean = rawName?.trim()?.takeIf { it.isNotBlank() } ?: return null
                    val lower = clean.lowercase()
                    if (lower in disabledTypeNamesLower || AzureDevOpsRepository.isExcludedNonWorkItemType(clean)) {
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

                val portfolioBucketsByKey = linkedMapOf<String, Pair<Int, MutableList<String>>>()
                val requirementTypes = mutableListOf<String>()
                val iterationTypes = mutableListOf<String>()

                var isIterationBacklogExplicitlyHidden = false

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

                val bugsBehavior = backlogConfig?.bugsBehavior?.trim()?.lowercase()
                if (bugTypesFromCategory.isNotEmpty()) {
                    when {
                        bugsBehavior == "asrequirements" -> {
                            requirementTypes.addAll(bugTypesFromCategory)
                        }
                        bugsBehavior == "off" -> {
                        }
                        iterationTypes.isNotEmpty() || bugsBehavior == "astasks" -> {
                            if (!isIterationBacklogExplicitlyHidden && iterationTypes.isNotEmpty()) {
                                iterationTypes.addAll(bugTypesFromCategory)
                            }
                        }
                    }
                }

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
        } catch (_: Exception) {
            ProjectBacklogMetadata(emptyMap(), emptyList())
        }
    }

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

    fun resolveOrderedBacklogLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        explicitBacklogLevels: List<List<String>> = emptyList(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): List<List<String>> {
        val cleanedActive = activeTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val activeByLower = cleanedActive.associateBy { it.lowercase() }

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
                }
            }

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
        return levelIdx < 0 || levelIdx >= backlogLevels.lastIndex
    }

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

    suspend fun fetchProjectProcessHierarchy(
        organization: String,
        project: String,
        pat: String
    ): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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

                val projectDetail = projectDetailResp?.body()
                val projectGuid = projectDetail?.id?.trim()?.takeIf { it.isNotBlank() }

                val processIdFromProject = projectDetail
                    ?.capabilities
                    ?.processTemplate
                    ?.templateTypeId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val processIdFromList = processesResp?.body()?.value.orEmpty()
                    .firstOrNull { proc ->
                        proc.projects.orEmpty().any { pRef ->
                            pRef.name?.equals(project, ignoreCase = true) == true ||
                                pRef.id?.equals(project, ignoreCase = true) == true ||
                                (!projectGuid.isNullOrBlank() && pRef.id?.equals(projectGuid, ignoreCase = true) == true)
                        }
                    }
                    ?.typeId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val resolvedProcessId = processIdFromProject ?: processIdFromList

                val processWorkItemTypes = if (!resolvedProcessId.isNullOrBlank()) {
                    val expandedBoth = runCatching {
                        service.getProcessWorkItemTypes(
                            organization = normalizedOrg,
                            processId = resolvedProcessId,
                            expand = "states,behaviors"
                        ).body()?.value.orEmpty()
                    }.getOrDefault(emptyList())
                    if (expandedBoth.isNotEmpty()) {
                        expandedBoth
                    } else {
                        val expandedStates = runCatching {
                            service.getProcessWorkItemTypes(
                                organization = normalizedOrg,
                                processId = resolvedProcessId,
                                expand = "states"
                            ).body()?.value.orEmpty()
                        }.getOrDefault(emptyList())
                        expandedStates.ifEmpty {
                            runCatching {
                                service.getProcessWorkItemTypes(
                                    organization = normalizedOrg,
                                    processId = resolvedProcessId,
                                    expand = "none"
                                ).body()?.value.orEmpty()
                            }.getOrDefault(emptyList())
                        }
                    }
                } else {
                    emptyList()
                }

                val projectWorkItemTypes = witListResp?.body()?.value.orEmpty()

                val disabledNamesLower = buildSet {
                    processWorkItemTypes.filter { it.isDisabled == true }
                        .mapNotNull { it.name?.trim()?.lowercase() }
                        .forEach { add(it) }
                    projectWorkItemTypes.filter { it.isDisabled == true }
                        .mapNotNull { it.name?.trim()?.lowercase() }
                        .forEach { add(it) }
                }

                val candidateTypes = if (processWorkItemTypes.isNotEmpty()) {
                    processWorkItemTypes
                } else {
                    projectWorkItemTypes.map {
                        ProcessWorkItemType(
                            referenceName = it.referenceName ?: it.name,
                            name = it.name,
                            color = it.color,
                            icon = it.icon?.id,
                            isDisabled = it.isDisabled
                        )
                    }
                }.filter { pType ->
                    val clean = pType.name?.trim().orEmpty()
                    clean.isNotBlank() &&
                        pType.isDisabled != true &&
                        clean.lowercase() !in disabledNamesLower &&
                        !AzureDevOpsRepository.isExcludedNonWorkItemType(clean)
                }

                val perTypeResults = candidateTypes.map { candidate ->
                    async {
                        val typeName = candidate.name?.trim().orEmpty()
                        val matchingProjectWit = projectWorkItemTypes.firstOrNull { w ->
                            w.name?.trim()?.equals(typeName, ignoreCase = true) == true ||
                                (!candidate.referenceName.isNullOrBlank() &&
                                    w.referenceName?.trim()?.equals(candidate.referenceName.trim(), ignoreCase = true) == true)
                        }
                        val typeRef = candidate.referenceName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: matchingProjectWit?.referenceName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: typeName

                        registerAzureTypeColor(typeName, candidate.color ?: matchingProjectWit?.color)
                        registerAzureTypeIcon(typeName, candidate.icon ?: matchingProjectWit?.icon?.id)

                        val endpointProcessStatesDeferred = async {
                            if (!resolvedProcessId.isNullOrBlank() && typeRef.isNotBlank()) {
                                val primary = runCatching {
                                    service.getProcessWorkItemTypeStates(
                                        organization = normalizedOrg,
                                        processId = resolvedProcessId,
                                        witRefName = typeRef
                                    ).body()?.value.orEmpty()
                                }.getOrDefault(emptyList())
                                if (primary.isNotEmpty()) {
                                    primary
                                } else {
                                    val altRef = matchingProjectWit?.referenceName?.trim().orEmpty()
                                    if (altRef.isNotBlank() && !altRef.equals(typeRef, ignoreCase = true)) {
                                        runCatching {
                                            service.getProcessWorkItemTypeStates(
                                                organization = normalizedOrg,
                                                processId = resolvedProcessId,
                                                witRefName = altRef
                                            ).body()?.value.orEmpty()
                                        }.getOrDefault(emptyList())
                                    } else {
                                        emptyList()
                                    }
                                }
                            } else {
                                emptyList()
                            }
                        }

                        val endpointWitStatesDeferred = async {
                            runCatching {
                                service.getProjectWorkItemTypeStates(
                                    organization = normalizedOrg,
                                    project = project,
                                    type = typeName
                                ).body()?.value.orEmpty()
                            }.getOrDefault(emptyList())
                        }

                        val endpointProcessStates = endpointProcessStatesDeferred.await()
                        val inlineProcessStates = candidate.states.orEmpty()
                        val endpointWitStates = endpointWitStatesDeferred.await()
                        val inlineWitStates = matchingProjectWit?.states.orEmpty()

                        val hiddenStateIdsLower = buildSet {
                            endpointProcessStates.filter { it.hidden == true || it.isDisabled == true }
                                .mapNotNull { it.id?.trim()?.lowercase() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }
                            inlineProcessStates.filter { it.hidden == true || it.isDisabled == true }
                                .mapNotNull { it.id?.trim()?.lowercase() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }
                        }

                        val hiddenStateNamesLower = buildSet {
                            endpointProcessStates.filter {
                                it.hidden == true ||
                                    it.isDisabled == true ||
                                    (it.id?.trim()?.lowercase() in hiddenStateIdsLower)
                            }.mapNotNull { it.name?.trim()?.lowercase() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }

                            inlineProcessStates.filter {
                                it.hidden == true ||
                                    it.isDisabled == true ||
                                    (it.id?.trim()?.lowercase() in hiddenStateIdsLower)
                            }.mapNotNull { it.name?.trim()?.lowercase() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }

                            endpointWitStates.filter { it.hidden == true || it.isDisabled == true }
                                .mapNotNull { it.name?.trim()?.lowercase() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }

                            inlineWitStates.filter { it.hidden == true || it.isDisabled == true }
                                .mapNotNull { it.name?.trim()?.lowercase() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }
                        }

                        val transitionTargetsLower = matchingProjectWit?.transitions?.values.orEmpty()
                            .flatten()
                            .mapNotNull { it.to?.trim()?.lowercase() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        (endpointProcessStates + inlineProcessStates).forEach { s ->
                            val cleanName = s.name?.trim().orEmpty()
                            if (cleanName.isNotEmpty() &&
                                s.hidden != true &&
                                s.isDisabled != true &&
                                cleanName.lowercase() !in hiddenStateNamesLower
                            ) {
                                registerAzureStateColor(cleanName, s.color)
                                registerStateCategory(cleanName, s.stateCategory, project, typeName)
                            }
                        }

                        (endpointWitStates + inlineWitStates).forEach { s ->
                            val cleanName = s.name?.trim().orEmpty()
                            if (cleanName.isNotEmpty() &&
                                s.hidden != true &&
                                s.isDisabled != true &&
                                cleanName.lowercase() !in hiddenStateNamesLower
                            ) {
                                registerAzureStateColor(cleanName, s.color)
                                registerStateCategory(cleanName, s.category, project, typeName)
                            }
                        }

                        val enabledFromEndpointProcess = endpointProcessStates
                            .filter { it.hidden != true && it.isDisabled != true }
                            .sortedBy { it.order ?: Int.MAX_VALUE }
                            .mapNotNull { it.name?.trim() }
                            .filter { it.isNotEmpty() && it.lowercase() !in hiddenStateNamesLower }
                            .distinctBy { it.lowercase() }

                        val enabledFromInlineProcess = inlineProcessStates
                            .filter { it.hidden != true && it.isDisabled != true }
                            .sortedBy { it.order ?: Int.MAX_VALUE }
                            .mapNotNull { it.name?.trim() }
                            .filter { it.isNotEmpty() && it.lowercase() !in hiddenStateNamesLower }
                            .distinctBy { it.lowercase() }

                        val enabledFromProcess = when {
                            enabledFromEndpointProcess.isNotEmpty() && enabledFromInlineProcess.isNotEmpty() -> {
                                val inlineLowerSet = enabledFromInlineProcess.map { it.lowercase() }.toSet()
                                val intersected = enabledFromEndpointProcess.filter { it.lowercase() in inlineLowerSet }
                                intersected.ifEmpty { enabledFromEndpointProcess }
                            }
                            enabledFromEndpointProcess.isNotEmpty() -> enabledFromEndpointProcess
                            enabledFromInlineProcess.isNotEmpty() -> enabledFromInlineProcess
                            else -> emptyList()
                        }

                        val enabledFromEndpointWit = endpointWitStates
                            .filter { it.hidden != true && it.isDisabled != true }
                            .mapNotNull { it.name?.trim() }
                            .filter { it.isNotEmpty() && it.lowercase() !in hiddenStateNamesLower }
                            .distinctBy { it.lowercase() }

                        val enabledFromInlineWit = inlineWitStates
                            .filter { it.hidden != true && it.isDisabled != true }
                            .mapNotNull { it.name?.trim() }
                            .filter { it.isNotEmpty() && it.lowercase() !in hiddenStateNamesLower }
                            .distinctBy { it.lowercase() }

                        val enabledFromWit = when {
                            enabledFromEndpointWit.isNotEmpty() && enabledFromInlineWit.isNotEmpty() -> {
                                val inlineWitLowerSet = enabledFromInlineWit.map { it.lowercase() }.toSet()
                                val intersected = enabledFromEndpointWit.filter { it.lowercase() in inlineWitLowerSet }
                                intersected.ifEmpty { enabledFromEndpointWit }
                            }
                            enabledFromEndpointWit.isNotEmpty() -> enabledFromEndpointWit
                            enabledFromInlineWit.isNotEmpty() -> enabledFromInlineWit
                            else -> emptyList()
                        }

                        val baseFinalStates = when {
                            enabledFromProcess.isNotEmpty() && enabledFromWit.isNotEmpty() -> {
                                val witLowerSet = enabledFromWit.map { it.lowercase() }.toSet()
                                val intersected = enabledFromProcess.filter { it.lowercase() in witLowerSet }
                                intersected.ifEmpty { enabledFromProcess }
                            }
                            enabledFromProcess.isNotEmpty() -> enabledFromProcess
                            enabledFromWit.isNotEmpty() -> enabledFromWit
                            else -> emptyList()
                        }

                        val finalStates = if (transitionTargetsLower.isNotEmpty() && baseFinalStates.isNotEmpty()) {
                            val transitionFiltered = baseFinalStates.filter {
                                it.lowercase() in transitionTargetsLower && it.lowercase() !in hiddenStateNamesLower
                            }
                            transitionFiltered.ifEmpty { baseFinalStates }
                        } else {
                            baseFinalStates
                        }

                        typeName to finalStates
                    }
                }.awaitAll()

                val unorderedMap = linkedMapOf<String, List<String>>()
                for ((typeName, states) in perTypeResults) {
                    if (typeName.isNotEmpty() && states.isNotEmpty()) {
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
        } catch (_: Exception) {
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
}
