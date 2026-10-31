package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Clean domain model for an Azure DevOps Work Item.
 */
@JsonClass(generateAdapter = true)
data class WorkItem(
    val id: Int,
    val title: String,
    val workItemType: String,
    val state: String,
    val teamProject: String? = null,
    val assignedToName: String? = null,
    val assignedToEmail: String? = null,
    val assignedToAvatar: String? = null,
    val createdDate: String? = null,
    val changedDate: String? = null,
    val description: String? = null,
    val areaPath: String? = null,
    val iterationPath: String? = null,
    val priority: Int? = null,
    val boardOrder: Double? = null,
    val parentId: Int? = null,
    val childIds: List<Int> = emptyList(),
    val tags: List<String> = emptyList()
)

/**
 * On-device persisted snapshot of work items and progressive sync state for an organization.
 */
@JsonClass(generateAdapter = true)
data class CachedOrganizationSnapshot(
    val organization: String,
    val workItems: List<WorkItem> = emptyList(),
    val unloadedProposedIds: List<Int> = emptyList(),
    val unloadedOtherIds: List<Int> = emptyList(),
    val lastSyncedChangedDate: String? = null,
    val savedAtEpochMs: Long = 0L
)

/**
 * On-device persisted snapshot of project process hierarchies, category maps, and state categories.
 */
@JsonClass(generateAdapter = true)
data class CachedOrganizationMetadata(
    val organization: String,
    val processHierarchyByProject: Map<String, Map<String, List<String>>> = emptyMap(),
    val categoryMapByProject: Map<String, Map<String, String>> = emptyMap(),
    val backlogLevelsByProject: Map<String, List<List<String>>> = emptyMap(),
    val areasByProject: Map<String, List<String>> = emptyMap(),
    val iterationsByProject: Map<String, List<String>> = emptyMap(),
    val stateCategoryMap: Map<String, String> = emptyMap(),
    val typeColors: Map<String, String> = emptyMap(),
    val typeIcons: Map<String, String> = emptyMap(),
    val stateColors: Map<String, String> = emptyMap()
)

/**
 * Represents a configured Azure DevOps organization account and its Personal Access Token.
 */
@JsonClass(generateAdapter = true)
data class OrganizationAccount(
    val organization: String,
    val pat: String,
    val isRemembered: Boolean = true
)


@JsonClass(generateAdapter = true)
data class JsonPatchOperation(
    @param:Json(name = "op") val op: String = "add",
    @param:Json(name = "path") val path: String,
    @param:Json(name = "value") val value: Any?
)

@JsonClass(generateAdapter = true)
data class WiqlQueryRequest(
    @param:Json(name = "query") val query: String
)

@JsonClass(generateAdapter = true)
data class WiqlQueryResult(
    @param:Json(name = "workItems") val workItems: List<WiqlWorkItemReference> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WiqlWorkItemReference(
    @param:Json(name = "id") val id: Int,
    @param:Json(name = "url") val url: String? = null
)

@JsonClass(generateAdapter = true)
data class WorkItemsBatchResponse(
    @param:Json(name = "count") val count: Int = 0,
    @param:Json(name = "value") val value: List<RawWorkItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RawWorkItemRelation(
    @param:Json(name = "rel") val rel: String? = null,
    @param:Json(name = "url") val url: String? = null
)

@JsonClass(generateAdapter = true)
data class RawWorkItem(
    @param:Json(name = "id") val id: Int,
    @param:Json(name = "url") val url: String? = null,
    @param:Json(name = "fields") val fields: Map<String, Any?> = emptyMap(),
    @param:Json(name = "relations") val relations: List<RawWorkItemRelation>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProjectsResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<ProjectItem>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProjectItem(
    @param:Json(name = "id") val id: String? = null,
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "description") val description: String? = null,
    @param:Json(name = "capabilities") val capabilities: ProjectCapabilities? = null
)

@JsonClass(generateAdapter = true)
data class ProjectCapabilities(
    @param:Json(name = "processTemplate") val processTemplate: ProcessTemplateCapability? = null
)

@JsonClass(generateAdapter = true)
data class ProcessTemplateCapability(
    @param:Json(name = "templateName") val templateName: String? = null,
    @param:Json(name = "templateTypeId") val templateTypeId: String? = null
)

@JsonClass(generateAdapter = true)
data class ProjectWorkItemTypesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<ProjectWorkItemTypeDefinition>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProjectWorkItemTypeDefinition(
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "referenceName") val referenceName: String? = null,
    @param:Json(name = "color") val color: String? = null,
    @param:Json(name = "icon") val icon: WorkItemTypeIcon? = null,
    @param:Json(name = "isDisabled") val isDisabled: Boolean? = false,
    @param:Json(name = "states") val states: List<WorkItemStateDefinition>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorkItemTypeIcon(
    @param:Json(name = "id") val id: String? = null,
    @param:Json(name = "url") val url: String? = null
)

@JsonClass(generateAdapter = true)
data class WorkItemStatesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<WorkItemStateDefinition>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorkItemStateDefinition(
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "color") val color: String? = null,
    @param:Json(name = "category") val category: String? = null,
    @param:Json(name = "hidden") val hidden: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class ProcessesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<ProcessDefinition>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProcessDefinition(
    @param:Json(name = "typeId") val typeId: String? = "",
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "isEnabled") val isEnabled: Boolean? = true,
    @param:Json(name = "projects") val projects: List<ProcessProjectRef>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProcessProjectRef(
    @param:Json(name = "id") val id: String? = null,
    @param:Json(name = "name") val name: String? = ""
)

@JsonClass(generateAdapter = true)
data class ProcessWorkItemTypesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<ProcessWorkItemType>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProcessWorkItemType(
    @param:Json(name = "referenceName") val referenceName: String? = null,
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "isDisabled") val isDisabled: Boolean? = false,
    @param:Json(name = "customization") val customization: String? = null,
    @param:Json(name = "color") val color: String? = null,
    @param:Json(name = "icon") val icon: String? = null,
    @param:Json(name = "states") val states: List<ProcessStateDefinition>? = emptyList(),
    @param:Json(name = "behaviors") val behaviors: List<ProcessWorkItemTypeBehavior>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProcessWorkItemTypeBehavior(
    @param:Json(name = "behavior") val behavior: ProcessBehaviorRef? = null,
    @param:Json(name = "isDefault") val isDefault: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class ProcessBehaviorRef(
    @param:Json(name = "id") val id: String? = null
)

@JsonClass(generateAdapter = true)
data class ProcessBehaviorsResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<ProcessBehaviorDefinition>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProcessBehaviorDefinition(
    @param:Json(name = "referenceName") val referenceName: String? = null,
    @param:Json(name = "name") val name: String? = null,
    @param:Json(name = "rank") val rank: Int? = null,
    @param:Json(name = "inherits") val inherits: ProcessBehaviorInheritsRef? = null
)

@JsonClass(generateAdapter = true)
data class ProcessBehaviorInheritsRef(
    @param:Json(name = "behaviorRefName") val behaviorRefName: String? = null,
    @param:Json(name = "id") val id: String? = null
)

@JsonClass(generateAdapter = true)
data class ProcessStatesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<ProcessStateDefinition>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProcessStateDefinition(
    @param:Json(name = "id") val id: String? = null,
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "color") val color: String? = null,
    @param:Json(name = "stateCategory") val stateCategory: String? = null,
    @param:Json(name = "order") val order: Int? = null,
    @param:Json(name = "hidden") val hidden: Boolean? = false,
    @param:Json(name = "customizationType") val customizationType: String? = null
)

@JsonClass(generateAdapter = true)
data class WorkItemTypeCategoriesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<WorkItemTypeCategory>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorkItemTypeCategory(
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "referenceName") val referenceName: String? = "",
    @param:Json(name = "workItemTypes") val workItemTypes: List<CategoryWorkItemTypeRef>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class CategoryWorkItemTypeRef(
    @param:Json(name = "name") val name: String? = ""
)

@JsonClass(generateAdapter = true)
data class BacklogsResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<BacklogLevelConfiguration>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class BacklogLevelConfiguration(
    @param:Json(name = "id") val id: String? = "",
    @param:Json(name = "name") val name: String? = "",
    @param:Json(name = "rank") val rank: Int? = null,
    @param:Json(name = "type") val type: String? = null,
    @param:Json(name = "workItemTypes") val workItemTypes: List<CategoryWorkItemTypeRef>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class BacklogConfigurationResponse(
    @param:Json(name = "portfolioBacklogs") val portfolioBacklogs: List<BacklogLevelConfiguration>? = emptyList(),
    @param:Json(name = "requirementBacklog") val requirementBacklog: BacklogLevelConfiguration? = null,
    @param:Json(name = "taskBacklog") val taskBacklog: BacklogLevelConfiguration? = null,
    @param:Json(name = "hiddenBacklogs") val hiddenBacklogs: List<String>? = emptyList(),
    @param:Json(name = "bugsBehavior") val bugsBehavior: String? = null
)

/**
 * Clean domain model for a Work Item Discussion comment.
 */
@JsonClass(generateAdapter = true)
data class WorkItemComment(
    val id: Int,
    val workItemId: Int,
    val text: String,
    val authorName: String,
    val authorEmail: String? = null,
    val authorAvatar: String? = null,
    val createdDate: String = "",
    val formattedDate: String = ""
)

@JsonClass(generateAdapter = true)
data class WorkItemCommentsResponse(
    @param:Json(name = "totalCount") val totalCount: Int? = 0,
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "comments") val comments: List<RawWorkItemComment>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class RawWorkItemComment(
    @param:Json(name = "id") val id: Int? = null,
    @param:Json(name = "workItemId") val workItemId: Int? = null,
    @param:Json(name = "text") val text: String? = null,
    @param:Json(name = "createdBy") val createdBy: RawCommentIdentity? = null,
    @param:Json(name = "createdDate") val createdDate: String? = null,
    @param:Json(name = "modifiedDate") val modifiedDate: String? = null,
    @param:Json(name = "isDeleted") val isDeleted: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class RawCommentIdentity(
    @param:Json(name = "id") val id: String? = null,
    @param:Json(name = "displayName") val displayName: String? = null,
    @param:Json(name = "uniqueName") val uniqueName: String? = null,
    @param:Json(name = "imageUrl") val imageUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class AddWorkItemCommentRequest(
    @param:Json(name = "text") val text: String
)

@JsonClass(generateAdapter = true)
data class ClassificationNodesResponse(
    @param:Json(name = "count") val count: Int? = 0,
    @param:Json(name = "value") val value: List<RawClassificationNode>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class RawClassificationNode(
    @param:Json(name = "id") val id: Int? = null,
    @param:Json(name = "identifier") val identifier: String? = null,
    @param:Json(name = "name") val name: String? = null,
    @param:Json(name = "structureType") val structureType: String? = null,
    @param:Json(name = "hasChildren") val hasChildren: Boolean? = false,
    @param:Json(name = "path") val path: String? = null,
    @param:Json(name = "children") val children: List<RawClassificationNode>? = emptyList()
)



