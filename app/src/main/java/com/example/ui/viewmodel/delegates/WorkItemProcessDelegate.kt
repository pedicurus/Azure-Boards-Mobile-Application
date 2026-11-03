package com.example.ui.viewmodel.delegates

import com.example.data.repository.AzureDevOpsRepository
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.expandAndSortHierarchyPaths
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectAreas
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectIterations
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.projectScopedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Delegate managing cached project process hierarchies, category maps, backlog levels,
 * and Area/Iteration classification trees.
 */
class WorkItemProcessDelegate(
    private val repository: AzureDevOpsRepository
) {
    private val _projectProcessMap = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    val projectProcessMap: StateFlow<Map<String, Map<String, List<String>>>> = _projectProcessMap.asStateFlow()

    private val _projectCategoryMap = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val projectCategoryMap: StateFlow<Map<String, Map<String, String>>> = _projectCategoryMap.asStateFlow()

    private val _projectBacklogLevelsMap = MutableStateFlow<Map<String, List<List<String>>>>(emptyMap())
    val projectBacklogLevelsMap: StateFlow<Map<String, List<List<String>>>> = _projectBacklogLevelsMap.asStateFlow()

    private val _projectAreasMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val projectAreasMap: StateFlow<Map<String, List<String>>> = _projectAreasMap.asStateFlow()

    private val _projectIterationsMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val projectIterationsMap: StateFlow<Map<String, List<String>>> = _projectIterationsMap.asStateFlow()

    fun updateMaps(
        processMap: Map<String, Map<String, List<String>>>,
        categoryMap: Map<String, Map<String, String>>,
        backlogLevelsMap: Map<String, List<List<String>>>,
        areasMap: Map<String, List<String>>,
        iterationsMap: Map<String, List<String>>
    ) {
        _projectProcessMap.value = processMap
        _projectCategoryMap.value = categoryMap
        _projectBacklogLevelsMap.value = backlogLevelsMap
        _projectAreasMap.value = areasMap
        _projectIterationsMap.value = iterationsMap
    }

    suspend fun fetchAndStoreProjectMetadata(
        normOrg: String,
        project: String,
        pat: String
    ) = withContext(Dispatchers.IO) {
        val cleanProject = project.trim()
        if (normOrg.isBlank() || cleanProject.isBlank() || pat.isBlank()) return@withContext
        val scopedKey = projectScopedKey(normOrg, cleanProject)

        coroutineScope {
            val processDeferred = async { repository.fetchProjectProcessHierarchy(normOrg, cleanProject, pat) }
            val classificationDeferred = async { repository.fetchProjectClassificationPaths(normOrg, cleanProject, pat) }

            val processHierarchy = processDeferred.await()
            val activeTypes = processHierarchy.keys
                .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
                .toList()
            val backlogMeta = repository.fetchProjectBacklogMetadata(
                organization = normOrg,
                project = cleanProject,
                pat = pat,
                activeTypesHint = activeTypes
            )
            val (areas, iterations) = classificationDeferred.await()

            val categoryMap = backlogMeta.categoryMap
            val backlogLevels = backlogMeta.orderedBacklogLevels

            if (processHierarchy.isNotEmpty()) {
                val cleanedMap = _projectProcessMap.value.filterKeys { k ->
                    !k.equals(scopedKey, ignoreCase = true) && !k.equals(cleanProject, ignoreCase = true)
                }
                _projectProcessMap.value = cleanedMap + (scopedKey to processHierarchy)
            }
            if (categoryMap.isNotEmpty()) {
                val cleanedCat = _projectCategoryMap.value.filterKeys { k ->
                    !k.equals(scopedKey, ignoreCase = true) && !k.equals(cleanProject, ignoreCase = true)
                }
                _projectCategoryMap.value = cleanedCat + (scopedKey to categoryMap)
            }
            if (backlogLevels.isNotEmpty()) {
                val cleanedLevels = _projectBacklogLevelsMap.value.filterKeys { k ->
                    !k.equals(scopedKey, ignoreCase = true) && !k.equals(cleanProject, ignoreCase = true)
                }
                _projectBacklogLevelsMap.value = cleanedLevels + (scopedKey to backlogLevels)
            }
            if (areas.isNotEmpty()) {
                _projectAreasMap.value = _projectAreasMap.value + (scopedKey to areas)
            }
            if (iterations.isNotEmpty()) {
                _projectIterationsMap.value = _projectIterationsMap.value + (scopedKey to iterations)
            }

            repository.saveCachedOrganizationMetadata(
                organization = normOrg,
                processHierarchyByProject = _projectProcessMap.value,
                categoryMapByProject = _projectCategoryMap.value,
                backlogLevelsByProject = _projectBacklogLevelsMap.value,
                areasByProject = _projectAreasMap.value,
                iterationsByProject = _projectIterationsMap.value
            )
        }
    }

    fun getAreasForProject(activeOrg: String?, project: String?): List<String> {
        val clean = project?.trim().orEmpty()
        if (clean.isEmpty()) return emptyList()
        val found = findProjectAreas(activeOrg, clean, _projectAreasMap.value)
        return if (!found.isNullOrEmpty()) {
            expandAndSortHierarchyPaths(clean, found)
        } else {
            emptyList()
        }
    }

    fun getIterationsForProject(activeOrg: String?, project: String?): List<String> {
        val clean = project?.trim().orEmpty()
        if (clean.isEmpty()) return emptyList()
        val found = findProjectIterations(activeOrg, clean, _projectIterationsMap.value)
        return if (!found.isNullOrEmpty()) {
            expandAndSortHierarchyPaths(clean, found)
        } else {
            emptyList()
        }
    }

    fun clearAll() {
        _projectProcessMap.value = emptyMap()
        _projectCategoryMap.value = emptyMap()
        _projectBacklogLevelsMap.value = emptyMap()
        _projectAreasMap.value = emptyMap()
        _projectIterationsMap.value = emptyMap()
    }
}
