package com.example.data.repository

import androidx.core.text.HtmlCompat
import com.example.data.api.AzureDevOpsService
import com.example.data.model.RawWorkItem
import com.example.data.model.WorkItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Parser and hydration coordinator for converting Azure DevOps [RawWorkItem] objects into domain [WorkItem] entities,
 * resolving hierarchy links, assigned identities, and stripping HTML.
 */
object AzureWorkItemParser {

    fun cleanHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }

    suspend fun hydrateRawItemsByIds(
        service: AzureDevOpsService,
        normalizedOrg: String,
        idsToHydrate: List<Int>
    ): List<RawWorkItem> {
        if (idsToHydrate.isEmpty()) return emptyList()
        val rawItems = mutableListOf<RawWorkItem>()
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

    fun parseAndLinkRawWorkItems(rawItems: List<RawWorkItem>): List<WorkItem> {
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
            val assignedId = (assignedMap?.get("id") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val assignedName = assignedMap?.get("displayName") as? String
            val assignedEmail = assignedMap?.get("uniqueName") as? String
            val avatarUrl = (assignedMap?.get("_links") as? Map<*, *>)
                ?.let { links -> (links["avatar"] as? Map<*, *>)?.get("href") as? String }
                ?: (assignedMap?.get("imageUrl") as? String)

            // Index all identity fields present on the work item for @<member> mention resolution
            val identityFieldKeys = arrayOf(
                "System.AssignedTo",
                "System.CreatedBy",
                "System.ChangedBy",
                "Microsoft.VSTS.Common.ActivatedBy",
                "Microsoft.VSTS.Common.ResolvedBy",
                "Microsoft.VSTS.Common.ClosedBy"
            )
            for (fieldKey in identityFieldKeys) {
                val idMap = f[fieldKey] as? Map<*, *> ?: continue
                val uId = (idMap["id"] as? String)?.trim()
                val uName = (idMap["displayName"] as? String)?.trim()
                val uEmail = (idMap["uniqueName"] as? String)?.trim()
                val uAvatar = (idMap["_links"] as? Map<*, *>)
                    ?.let { links -> (links["avatar"] as? Map<*, *>)?.get("href") as? String }
                    ?: (idMap["imageUrl"] as? String)
                AzureIdentityRegistry.registerKnownIdentity(id = uId, displayName = uName, email = uEmail, avatarUrl = uAvatar)
            }

            val createdDate = f["System.CreatedDate"] as? String
            val changedDate = f["System.ChangedDate"] as? String
            val activatedDate = (f["Microsoft.VSTS.Common.ActivatedDate"] as? String)
                ?: (f["Microsoft.VSTS.Common.StateChangeDate"] as? String)
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
            val commentCount = (f["System.CommentCount"] as? Number)?.toInt()
                ?: (f["System.CommentCount"] as? String)?.trim()?.toIntOrNull()

            WorkItem(
                id = id,
                title = title,
                workItemType = type,
                state = state,
                teamProject = teamProject,
                assignedToId = assignedId,
                assignedToName = assignedName,
                assignedToEmail = assignedEmail,
                assignedToAvatar = avatarUrl,
                createdDate = createdDate,
                changedDate = changedDate ?: activatedDate ?: createdDate,
                description = cleanHtml(description),
                areaPath = areaPath,
                iterationPath = iterationPath,
                priority = priority,
                boardOrder = boardOrder,
                parentId = parentId,
                childIds = childIdsFromRel,
                tags = tagList,
                commentCount = commentCount
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
}
