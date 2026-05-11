package com.hearthappy.loggerx.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.core.DataQueryService
import com.hearthappy.loggerx.core.LogScopeProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PreviewLogUiState(
    val logs: List<Map<String, Any>> = emptyList(),
    val loading: Boolean = false,
    val progressPercent: Int = 0,
    val progressStage: String = "",
    val canCancel: Boolean = false,
    val keepInBackground: Boolean = true,
    val hasMore: Boolean = false
)

data class DistinctValuesUiState(
    val values: Map<FilterCategory, List<String>> = emptyMap(),
    val disabledCategories: Set<FilterCategory> = emptySet(),
    val loading: Boolean = false
)

class PreviewViewModel(private val scopeProxy: LogScopeProxy) : ViewModel() {
    private var currentQueryHandle: DataQueryService.QueryHandle? = null
    private var activeQueryId: String? = null
    private val _appliedState = MutableStateFlow(FilterState.EMPTY)
    val appliedState: StateFlow<FilterState> = _appliedState.asStateFlow()

    private val _draftState = MutableStateFlow(FilterState.EMPTY)
    val draftState: StateFlow<FilterState> = _draftState.asStateFlow()

    private val _activeCategory = MutableStateFlow(FilterCategory.ALL)
    val activeCategory: StateFlow<FilterCategory> = _activeCategory.asStateFlow()

    private val _distinctValues = MutableStateFlow(DistinctValuesUiState())
    val distinctValues: StateFlow<DistinctValuesUiState> = _distinctValues.asStateFlow()

    private val _logUiState = MutableStateFlow(PreviewLogUiState())
    val logUiState: StateFlow<PreviewLogUiState> = _logUiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private var nextAnchorTime: String? = null
    private var nextAnchorId: Int? = null

    fun loadInitialLogs() {
        queryWithState(_appliedState.value)
    }

    fun refreshAppliedLogs() {
        queryWithState(_appliedState.value)
    }

    fun loadMoreLogs() {
        if (_logUiState.value.loading || !_logUiState.value.hasMore) return
        val anchorTime = nextAnchorTime ?: return
        val anchorId = nextAnchorId ?: return
        queryWithState(_appliedState.value, isLoadMore = true, anchorTime = anchorTime, anchorId = anchorId)
    }

    fun startFilterEditing() {
        _draftState.value = _appliedState.value
        _activeCategory.value = FilterCategory.ALL
        refreshDistinctValues()
    }

    fun setActiveCategory(category: FilterCategory) {
        _activeCategory.value = category
    }

    fun toggleSelection(category: FilterCategory, value: String?) {
        if (_distinctValues.value.disabledCategories.contains(category)) return
        _draftState.update { it.toggle(category, value) }
        queryWithState(_draftState.value)
    }

    fun resetDraft() {
        _draftState.value = FilterState.EMPTY
        _activeCategory.value = FilterCategory.ALL
        queryWithState(_draftState.value)
    }

    fun confirmDraft() {
        _appliedState.value = _draftState.value
        queryWithState(_appliedState.value)
    }

    fun cancelDraft() {
        _draftState.value = _appliedState.value
        queryWithState(_appliedState.value)
    }

    fun clearDistinctCache() {
        _distinctValues.value = DistinctValuesUiState()
    }

    fun setBackgroundContinue(enabled: Boolean) {
        currentQueryHandle?.setBackgroundContinue(enabled)
        _logUiState.update { it.copy(keepInBackground = enabled) }
    }

    fun cancelCurrentQuery() {
        activeQueryId = null
        currentQueryHandle?.cancel()
        currentQueryHandle = null
        _logUiState.update {
            it.copy(
                loading = false,
                canCancel = false,
                progressPercent = 0,
                progressStage = "cancelled"
            )
        }
    }

    private fun refreshDistinctValues() {
        viewModelScope.launch {
            _distinctValues.update { it.copy(loading = true, values = emptyMap(), disabledCategories = emptySet()) }
            val values = mutableMapOf<FilterCategory, List<String>>()
            val disabled = mutableSetOf<FilterCategory>()
            var hasError = false
            withContext(Dispatchers.IO) {
                FilterCategory.filterable.forEach { category ->
                    try {
                        val result = if (category == FilterCategory.IMAGE) {
                            listOf(FilterCategory.IMAGE_ONLY_VALUE)
                        } else {
                            scopeProxy.getDistinctValues(category.columnName.orEmpty())
                        }
                        values[category] = result
                    } catch (_: Exception) {
                        hasError = true
                        disabled.add(category)
                    }
                }
            }
            _distinctValues.value = DistinctValuesUiState(
                values = values,
                disabledCategories = disabled,
                loading = false
            )
            if (hasError) {
                _toastEvent.tryEmit("获取筛选条件失败")
            }
        }
    }

    private fun queryWithState(state: FilterState, isLoadMore: Boolean = false, anchorTime: String? = null, anchorId: Int? = null) {
        activeQueryId = null
        currentQueryHandle?.cancel()
        if (!isLoadMore) {
            nextAnchorTime = null
            nextAnchorId = null
        }
        val keepInBackground = _logUiState.value.keepInBackground
        _logUiState.update {
            it.copy(
                loading = true,
                progressPercent = 0,
                progressStage = "queued",
                canCancel = true,
                keepInBackground = keepInBackground
            )
        }
        val params = FilterQueryHelper.buildQueryParams(state, page = 1, limit = 100)
        val requestPage = if (isLoadMore) 2 else 1
        val handle = scopeProxy.queryLogsAsync(
            time = params.time,
            tag = params.tag,
            level = params.level,
            method = params.method,
            isImage = params.isImage,
            keyword = null,
            isAsc = false,
            page = requestPage,
            limit = params.limit ?: 100,
            anchorTime = anchorTime,
            anchorId = anchorId,
            listener = object : DataQueryService.QueryListener {
                override fun onProgress(progress: DataQueryService.QueryProgress) {
                    if (progress.queryId != activeQueryId) return
                    viewModelScope.launch {
                        _logUiState.update {
                            it.copy(
                                loading = progress.percent < 100,
                                progressPercent = progress.percent,
                                progressStage = progress.stage,
                                canCancel = progress.percent < 100
                            )
                        }
                    }
                }

                override fun onSuccess(result: DataQueryService.QueryResult) {
                    if (result.queryId != activeQueryId) return
                    viewModelScope.launch {
                        val filtered = result.pageResult.rows.filter { FilterQueryHelper.matches(it, state) }
                        _logUiState.update { currentState ->
                            val newLogs = if (isLoadMore) {
                                (currentState.logs + filtered).distinctBy { row ->
                                    row[LoggerX.COLUMN_ID]?.toString().orEmpty()
                                }
                            } else {
                                filtered
                            }
                            currentState.copy(
                                logs = newLogs,
                                loading = false,
                                progressPercent = 100,
                                progressStage = if (result.fromCache) "cache-hit" else "done",
                                canCancel = false,
                                hasMore = result.pageResult.hasMore
                            )
                        }
                        nextAnchorTime = result.pageResult.nextAnchorTime
                        nextAnchorId = result.pageResult.nextAnchorId
                    }
                }

                override fun onError(queryId: String, throwable: Throwable) {
                    if (queryId != activeQueryId) return
                    viewModelScope.launch {
                        _logUiState.update {
                            it.copy(
                                loading = false,
                                canCancel = false,
                                progressStage = "error"
                            )
                        }
                        _toastEvent.tryEmit("日志查询失败: ${throwable.message.orEmpty()}")
                    }
                }

                override fun onCancelled(queryId: String) {
                    if (queryId != activeQueryId) return
                    viewModelScope.launch {
                        _logUiState.update {
                            it.copy(
                                loading = false,
                                canCancel = false,
                                progressPercent = 0,
                                progressStage = "cancelled"
                            )
                        }
                    }
                }
            }
        )
        currentQueryHandle = handle
        activeQueryId = handle.queryId
        handle.setBackgroundContinue(keepInBackground)
    }

    class Factory(private val scopeProxy: LogScopeProxy) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PreviewViewModel(scopeProxy) as T
        }
    }
}
