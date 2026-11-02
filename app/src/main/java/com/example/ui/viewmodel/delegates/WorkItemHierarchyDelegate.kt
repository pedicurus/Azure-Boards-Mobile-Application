package com.example.ui.viewmodel.delegates

import com.example.data.model.WorkItem
import com.example.data.model.WorkItemComment
import com.example.data.repository.AzureDevOpsRepository
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findBacklogLevels
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findCategoryMap
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProcessHierarchy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delegate managing detail selection, child work item state, discussion comments,
 * and hierarchy type resolution rules.
 */
class WorkItemHierarchyDelegate {

    private val _selectedWorkItem = MutableStateFlow<WorkItem?>(null)
    val selectedWorkItem: StateFlow<WorkItem?> = _selectedWorkItem.asStateFlow()

    private val _selectedWorkItemComments = MutableStateFlow<List<WorkItemComment>>(emptyList())
    val selectedWorkItemComments: StateFlow<List<WorkItemComment>> = _selectedWorkItemComments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow(false)
    val isLoadingComments: StateFlow<Boolean> = _isLoadingComments.asStateFlow()

    fun setSelectedWorkItem(item: WorkItem?) {
        _selectedWorkItem.value = item
        if (item == null) {
            _selectedWorkItemComments.value = emptyList()
            _isLoadingComments.value = false
        }
    }

    fun setSelectedWorkItemComments(comments: List<WorkItemComment>) {
        _selectedWorkItemComments.value = comments
    }

    fun addComment(comment: WorkItemComment) {
        val current = _selectedWorkItemComments.value
        _selectedWorkItemComments.value = listOf(comment) + current.filterNot { it.id == comment.id }
    }

    fun setLoadingComments(loading: Boolean) {
        _isLoadingComments.value = loading
    }

    fun getStatesForType(
        project: String?,
        workItemType: String,
        currentState: String? = null,
        activeOrg: String?,
        allItems: List<WorkItem>,
        projectProcessMap: Map<String, Map<String, List<String>>>,
        repository: AzureDevOpsRepository
    ): List<String> {
        val resolvedProj = project.orEmpty()
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProj, projectProcessMap)
        val fromProcess = processMapForProj?.entries
            ?.firstOrNull { it.key.equals(workItemType, ignoreCase = true) }?.value
        val baseStates = if (!fromProcess.isNullOrEmpty()) {
            fromProcess
        } else {
            val fromItems = allItems.filter {
                it.teamProject?.equals(resolvedProj, ignoreCase = true) == true &&
                    it.workItemType.equals(workItemType, ignoreCase = true)
            }.map { it.state }.distinct()
            if (fromItems.isNotEmpty()) fromItems else repository.getStandardProcessStates(workItemType)
        }
        return if (!currentState.isNullOrBlank()) {
            (baseStates + currentState).distinctBy { it.lowercase() }
        } else {
            baseStates.distinctBy { it.lowercase() }
        }
    }

    fun getAllowedChildTypesForType(
        project: String?,
        workItemType: String,
        activeOrg: String?,
        allItems: List<WorkItem>,
        projectProcessMap: Map<String, Map<String, List<String>>>,
        projectCategoryMap: Map<String, Map<String, String>>,
        projectBacklogLevelsMap: Map<String, List<List<String>>>,
        repository: AzureDevOpsRepository
    ): List<String> {
        val cleanType = workItemType.trim()
        if (cleanType.isBlank()) return emptyList()

        val resolvedProject = project.orEmpty()
        val processMapForProj = findProcessHierarchy(activeOrg, resolvedProject, projectProcessMap)
        val activeTypesFromProcess = processMapForProj?.keys
            ?.filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            ?.toList()
            .orEmpty()

        val projectItems = allItems.filter {
            resolvedProject.isBlank() || it.teamProject?.equals(resolvedProject, ignoreCase = true) == true
        }
        val activeTypesFromItems = projectItems.map { it.workItemType }
            .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
            .distinct()

        val candidateActiveTypes = (activeTypesFromProcess + activeTypesFromItems).distinctBy { it.lowercase() }
        if (candidateActiveTypes.isEmpty()) return emptyList()

        val categoryMapForProj = findCategoryMap(activeOrg, resolvedProject, projectCategoryMap).orEmpty()
        val backlogLevelsForProj = findBacklogLevels(activeOrg, resolvedProject, projectBacklogLevelsMap).orEmpty()

        val orderedBacklogLevels = repository.resolveOrderedBacklogLevels(
            activeTypes = candidateActiveTypes,
            categoryMap = categoryMapForProj,
            explicitBacklogLevels = backlogLevelsForProj,
            projectWorkItems = projectItems
        )
        if (orderedBacklogLevels.size <= 1) return emptyList()

        val parentLevelIdx = orderedBacklogLevels.indexOfFirst { level ->
            level.any { it.equals(cleanType, ignoreCase = true) }
        }
        if (parentLevelIdx < 0 || parentLevelIdx >= orderedBacklogLevels.lastIndex) {
            return emptyList()
        }

        val nextLevelTypes = orderedBacklogLevels[parentLevelIdx + 1]
        return nextLevelTypes.filterNot { it.equals(cleanType, ignoreCase = true) }
    }

    fun reset() {
        _selectedWorkItem.value = null
        _selectedWorkItemComments.value = emptyList()
        _isLoadingComments.value = false
    }
}
