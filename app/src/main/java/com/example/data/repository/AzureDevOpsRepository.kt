package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.data.api.AzureDevOpsClient
import com.example.data.model.JsonPatchOperation
import com.example.data.model.OrganizationAccount
import com.example.data.model.WiqlQueryRequest
import com.example.data.model.WorkItem
import com.example.ui.theme.registerAzureStateColor
import com.example.ui.theme.registerAzureTypeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class AzureDevOpsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("azure_boards_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DEFAULT_ORG = "pref_azure_user_default_organization"
        private const val KEY_DEFAULT_PROJECT = "pref_azure_user_default_project"
        private const val KEY_DEFAULT_PROJECT_PREFIX = "pref_azure_user_default_project_"
        private const val KEY_CACHED_PROJECTS_PREFIX = "pref_azure_user_cached_projects_"
        private const val KEY_SAVED_ORGS = "pref_azure_user_saved_organizations"

        private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val P_CLOSE_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
        private val LI_CLOSE_REGEX = Regex("</li>", RegexOption.IGNORE_CASE)
        private val LI_OPEN_REGEX = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]+>")

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
                if (refs.isEmpty()) {
                    return@withContext Result.success(emptyList())
                }

                // Batch fetch work item fields (up to 200 per batch) with all fields and relations so StackRank / BacklogPriority and parent-child links are included
                val rawItems = mutableListOf<com.example.data.model.RawWorkItem>()
                val chunks = refs.take(400).chunked(200)
                for (chunk in chunks) {
                    val ids = chunk.joinToString(",") { it.id.toString() }
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

                    if (!batchResponse.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Failed to fetch work item details (${batchResponse.code()}): ${batchResponse.message()}")
                        )
                    }
                    rawItems.addAll(batchResponse.body()?.value ?: emptyList())
                }

                // Fetch any child work items referenced via Hierarchy-Forward relations that weren't in the initial batch
                val fetchedIds = rawItems.map { it.id }.toSet()
                val missingChildIds = rawItems.flatMap { raw ->
                    raw.relations.orEmpty()
                        .filter { it.rel.equals("System.LinkTypes.Hierarchy-Forward", ignoreCase = true) }
                        .mapNotNull { it.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() }
                }.distinct().filter { it !in fetchedIds }

                if (missingChildIds.isNotEmpty()) {
                    for (chunk in missingChildIds.chunked(200)) {
                        val ids = chunk.joinToString(",")
                        val extraResp = try {
                            service.getWorkItems(
                                organization = normalizedOrg,
                                ids = ids,
                                fields = null,
                                expand = "relations"
                            )
                        } catch (e: Exception) {
                            null
                        }
                        if (extraResp?.isSuccessful == true) {
                            rawItems.addAll(extraResp.body()?.value ?: emptyList())
                        }
                    }
                }

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

                    // Azure Boards orders items within each state using StackRank or BacklogPriority
                    val boardOrder = (f["Microsoft.VSTS.Common.StackRank"] as? Number)?.toDouble()
                        ?: (f["Microsoft.VSTS.Common.BacklogPriority"] as? Number)?.toDouble()
                        ?: f.entries.firstOrNull { (k, v) ->
                            v is Number && (
                                k.endsWith(".StackRank", ignoreCase = true) ||
                                k.endsWith(".BacklogPriority", ignoreCase = true)
                            )
                        }?.let { (it.value as? Number)?.toDouble() }

                    // Parent ID from System.Parent field or System.LinkTypes.Hierarchy-Reverse relation
                    val parentIdFromField = (f["System.Parent"] as? Number)?.toInt()
                        ?: (f["System.Parent"] as? String)?.trim()?.toIntOrNull()
                    val parentIdFromRel = raw.relations.orEmpty()
                        .firstOrNull { it.rel.equals("System.LinkTypes.Hierarchy-Reverse", ignoreCase = true) }
                        ?.url?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull()
                    val parentId = parentIdFromField ?: parentIdFromRel

                    // Child IDs from System.LinkTypes.Hierarchy-Forward relations
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

                // Cross-link parent and child IDs bidirectionally so every parent knows all its children
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

                val workItems = parsedItems.map { item ->
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

                Result.success(workItems)
            } catch (e: Exception) {
                Result.failure(e)
            }
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
            val response = service.getWorkItemTypeCategories(
                organization = normalizedOrg,
                project = project
            )
            if (response.isSuccessful) {
                val categories = response.body()?.value.orEmpty()
                val result = mutableMapOf<String, String>()
                val relevantCategories = listOf(
                    "Microsoft.EpicCategory",
                    "Microsoft.FeatureCategory",
                    "Microsoft.RequirementCategory",
                    "Microsoft.TaskCategory"
                )
                for (cat in categories) {
                    val catRefName = cat.referenceName?.trim().orEmpty()
                    if (relevantCategories.any { it.equals(catRefName, ignoreCase = true) }) {
                        for (witRef in cat.workItemTypes.orEmpty()) {
                            val witName = witRef.name?.trim().orEmpty()
                            if (witName.isNotBlank()) {
                                result[witName] = catRefName
                            }
                        }
                    }
                }
                result
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Resolves the ordered hierarchy levels of active work item types for a project,
     * from top level (index 0) down to the last/leaf level (lastIndex).
     */
    fun resolveOrderedHierarchyLevels(
        activeTypes: List<String>,
        categoryMap: Map<String, String> = emptyMap(),
        projectWorkItems: List<WorkItem> = emptyList()
    ): List<List<String>> {
        val cleanedTypes = activeTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanedTypes.isEmpty()) return emptyList()

        val tierScores = mutableMapOf<String, Int>()
        for (type in cleanedTypes) {
            val catRef = categoryMap.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value
            val n = type.lowercase().trim()
            val score = when {
                catRef.equals("Microsoft.EpicCategory", ignoreCase = true) -> 0
                catRef.equals("Microsoft.FeatureCategory", ignoreCase = true) -> 10
                catRef.equals("Microsoft.RequirementCategory", ignoreCase = true) -> 20
                catRef.equals("Microsoft.TaskCategory", ignoreCase = true) -> 30
                n.contains("epic") || n.contains("هدف") || n.contains("goal") || n.contains("initiative") -> 0
                n.contains("feature") || n.contains("ویژگی") -> 10
                n.contains("story") || n.contains("requirement") || n.contains("backlog item") || n.contains("issue") || n.contains("داستان") -> 20
                n.contains("task") || n.contains("کار") || n.contains("وظیفه") || n.contains("subtask") || n.contains("sub-task") || n.contains("bug") || n.contains("باگ") -> 30
                else -> 15
            }
            tierScores[type] = score
        }

        // Refine tiers using any observed parent -> child relationships in projectWorkItems
        if (projectWorkItems.isNotEmpty()) {
            val byId = projectWorkItems.associateBy { it.id }
            for (item in projectWorkItems) {
                val pId = item.parentId ?: continue
                val parent = byId[pId] ?: continue
                val pType = cleanedTypes.firstOrNull { it.equals(parent.workItemType, ignoreCase = true) } ?: continue
                val cType = cleanedTypes.firstOrNull { it.equals(item.workItemType, ignoreCase = true) } ?: continue
                if (!pType.equals(cType, ignoreCase = true)) {
                    val pScore = tierScores[pType] ?: 15
                    val cScore = tierScores[cType] ?: 15
                    if (pScore >= cScore) {
                        tierScores[cType] = pScore + 5
                    }
                }
            }
        }

        return cleanedTypes
            .groupBy { tierScores[it] ?: 15 }
            .toSortedMap()
            .values
            .map { it.distinct() }
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

                val resultMap = linkedMapOf<String, List<String>>()
                for ((typeName, states) in perTypeResults) {
                    if (states.isNotEmpty()) {
                        resultMap[typeName] = states
                    }
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
        return html
            .replace(BR_REGEX, "\n")
            .replace(P_CLOSE_REGEX, "\n\n")
            .replace(LI_CLOSE_REGEX, "\n")
            .replace(LI_OPEN_REGEX, "• ")
            .replace(HTML_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
    }
}
