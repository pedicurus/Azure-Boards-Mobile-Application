package com.example.ui.viewmodel.delegates

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delegate managing search queries, project selection, and attribute filters
 * (Work Item Type, State, Assignee, Area Path, Iteration Path) for WorkItemsViewModel.
 */
class WorkItemFilterDelegate {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    private val _stateFilter = MutableStateFlow<String?>(null)
    val stateFilter: StateFlow<String?> = _stateFilter.asStateFlow()

    private val _assigneeFilter = MutableStateFlow<String?>(null)
    val assigneeFilter: StateFlow<String?> = _assigneeFilter.asStateFlow()

    private val _areaFilter = MutableStateFlow<String?>(null)
    val areaFilter: StateFlow<String?> = _areaFilter.asStateFlow()

    private val _iterationFilter = MutableStateFlow<String?>(null)
    val iterationFilter: StateFlow<String?> = _iterationFilter.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProject(project: String?) {
        val trimmed = project?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        if (_selectedProject.value != trimmed) {
            _selectedProject.value = trimmed
            _areaFilter.value = null
            _iterationFilter.value = null
        }
    }

    fun setTypeFilter(type: String?) {
        val trimmed = type?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _typeFilter.value = trimmed
    }

    fun setStateFilter(state: String?) {
        val trimmed = state?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _stateFilter.value = trimmed
    }

    fun setAssigneeFilter(assignee: String?) {
        val trimmed = assignee?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _assigneeFilter.value = trimmed
    }

    fun setAreaFilter(areaPath: String?) {
        val trimmed = areaPath?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _areaFilter.value = trimmed
    }

    fun setIterationFilter(iterationPath: String?) {
        val trimmed = iterationPath?.trim()?.takeIf { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        _iterationFilter.value = trimmed
    }

    fun resetFilters() {
        _searchQuery.value = ""
        _typeFilter.value = null
        _stateFilter.value = null
        _assigneeFilter.value = null
        _areaFilter.value = null
        _iterationFilter.value = null
    }

    fun clearAll() {
        resetFilters()
        _selectedProject.value = null
    }
}
