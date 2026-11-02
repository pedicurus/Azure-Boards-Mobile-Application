package com.example.ui.viewmodel.delegates

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delegate managing progressive background streaming, sync indicators, loading flags,
 * and status messaging for WorkItemsViewModel.
 */
class WorkItemStreamDelegate {

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
}
