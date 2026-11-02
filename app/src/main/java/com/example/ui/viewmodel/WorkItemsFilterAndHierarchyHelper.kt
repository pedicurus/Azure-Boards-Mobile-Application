package com.example.ui.viewmodel

import com.example.data.model.WorkItem

/**
 * Pure helper functions for classification path normalization, hierarchy expansion,
 * process metadata lookups, and bounded top-K work item bucketing.
 */
object WorkItemsFilterAndHierarchyHelper {

    fun projectScopedKey(org: String?, project: String): String {
        val cleanOrg = org?.trim()?.lowercase().orEmpty()
        val cleanProj = project.trim().lowercase()
        return if (cleanOrg.isNotEmpty()) "$cleanOrg|$cleanProj" else cleanProj
    }

    fun findProcessHierarchy(
        org: String?,
        project: String,
        map: Map<String, Map<String, List<String>>>
    ): Map<String, List<String>>? {
        val scopedKey = projectScopedKey(org, project)
        map.entries.firstOrNull { it.key.equals(scopedKey, ignoreCase = true) }?.value?.let { return it }
        return map.entries.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value
    }

    fun findCategoryMap(
        org: String?,
        project: String,
        map: Map<String, Map<String, String>>
    ): Map<String, String>? {
        val scopedKey = projectScopedKey(org, project)
        map.entries.firstOrNull { it.key.equals(scopedKey, ignoreCase = true) }?.value?.let { return it }
        return map.entries.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value
    }

    fun findBacklogLevels(
        org: String?,
        project: String,
        map: Map<String, List<List<String>>>
    ): List<List<String>>? {
        val scopedKey = projectScopedKey(org, project)
        map.entries.firstOrNull { it.key.equals(scopedKey, ignoreCase = true) }?.value?.let { return it }
        return map.entries.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value
    }

    fun findProjectAreas(
        org: String?,
        project: String,
        map: Map<String, List<String>>
    ): List<String>? {
        val scopedKey = projectScopedKey(org, project)
        map.entries.firstOrNull { it.key.equals(scopedKey, ignoreCase = true) }?.value?.let { return it }
        return map.entries.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value
    }

    fun findProjectIterations(
        org: String?,
        project: String,
        map: Map<String, List<String>>
    ): List<String>? {
        val scopedKey = projectScopedKey(org, project)
        map.entries.firstOrNull { it.key.equals(scopedKey, ignoreCase = true) }?.value?.let { return it }
        return map.entries.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value
    }

    fun normalizeClassificationPath(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return path.trim()
            .replace('/', '\\')
            .split('\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\\")
    }

    fun matchesClassificationHierarchyFast(
        itemNormPathLower: String,
        selectedNormPathLower: String,
        selectedPrefixLower: String
    ): Boolean {
        if (selectedNormPathLower.isEmpty()) return true
        if (itemNormPathLower.isEmpty()) return false
        return itemNormPathLower == selectedNormPathLower ||
            itemNormPathLower.startsWith(selectedPrefixLower)
    }

    fun expandAndSortHierarchyPaths(project: String, rawPaths: List<String>): List<String> {
        val cleanProject = project.trim()
        val expanded = LinkedHashMap<String, String>()
        if (cleanProject.isNotEmpty()) {
            expanded[cleanProject.lowercase()] = cleanProject
        }
        for (raw in rawPaths) {
            val normalized = normalizeClassificationPath(raw)
            if (normalized.isEmpty()) continue
            val segments = normalized.split('\\')
            var prefix = ""
            for (seg in segments) {
                prefix = if (prefix.isEmpty()) seg else "$prefix\\$seg"
                val key = prefix.lowercase()
                if (!expanded.containsKey(key)) {
                    expanded[key] = prefix
                }
            }
        }
        return expanded.values.sortedWith(
            compareBy<String>(
                { !it.equals(cleanProject, ignoreCase = true) },
                { it.lowercase() }
            )
        )
    }

    fun insertBoundedTopK(
        bucket: MutableList<WorkItem>,
        candidate: WorkItem,
        maxSize: Int,
        comparator: Comparator<WorkItem>
    ) {
        var insertIdx = bucket.binarySearch(candidate, comparator)
        if (insertIdx < 0) {
            insertIdx = -insertIdx - 1
        }
        if (insertIdx < maxSize) {
            bucket.add(insertIdx, candidate)
            if (bucket.size > maxSize) {
                bucket.removeAt(bucket.lastIndex)
            }
        }
    }
}
