package com.example.data.repository

import com.example.data.api.AzureDevOpsClient
import com.example.data.model.JsonPatchOperation
import com.example.data.model.WorkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Domain repository responsible for creating, updating, and linking work items on Azure DevOps.
 */
class AzureWorkItemCrudRepository {

    /**
     * Persists work item changes (State, AssignedTo, Description, Tags, Area, Iteration) to Azure DevOps
     * using JSON Patch (application/json-patch+json) on /_apis/wit/workitems/{id}.
     */
    suspend fun updateWorkItemOnServer(
        organization: String,
        pat: String,
        previousItem: WorkItem?,
        updatedItem: WorkItem,
        resolvedAssigneeIdentity: String? = null
    ): Result<WorkItem> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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
     * using System.LinkTypes.Hierarchy-Reverse.
     */
    suspend fun linkExistingChildWorkItemOnServer(
        organization: String,
        pat: String,
        parentId: Int,
        childId: Int
    ): Result<WorkItem> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
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

            val parsedChild = AzureWorkItemParser.parseAndLinkRawWorkItems(listOf(existingRaw)).firstOrNull()
                ?: return@withContext Result.failure(Exception("Failed to parse work item #$childId."))

            Result.success(parsedChild.copy(parentId = parentId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes the parent-child hierarchy relation between [parentId] and [childId] in Azure DevOps.
     */
    suspend fun unlinkChildWorkItemOnServer(
        organization: String,
        pat: String,
        parentId: Int,
        childId: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Azure DevOps credentials."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)

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
}
