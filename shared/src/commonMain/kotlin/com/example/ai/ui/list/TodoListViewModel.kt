package com.example.ai.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.domain.Todo
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.util.runSuspendCatching
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TodoListUiState {
    /** Before the first emission from the database. Distinct from [Empty] to avoid a flash of the empty screen. */
    data object Loading : TodoListUiState
    data object Empty : TodoListUiState
    data class Content(val items: List<Todo>) : TodoListUiState
    data object Error : TodoListUiState
}

class TodoListViewModel(private val repository: TodoRepository) : ViewModel() {

    private val retryTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TodoListUiState> = retryTrigger
        .flatMapLatest {
            repository.observeTodos()
                .map<List<Todo>, TodoListUiState> { todos ->
                    if (todos.isEmpty()) TodoListUiState.Empty else TodoListUiState.Content(todos)
                }
                .catch { emit(TodoListUiState.Error) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodoListUiState.Loading)

    private val _writeFailed = MutableStateFlow(false)

    /** `true` after a failed write; the UI shows a message and calls [onWriteErrorShown]. */
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    fun onToggleDone(id: Long, isDone: Boolean) {
        viewModelScope.launch {
            // The database is the single source of truth: the checkbox updates when the Flow re-emits.
            runSuspendCatching { repository.setDone(id, isDone) }
                .onFailure { _writeFailed.value = true }
        }
    }

    fun onRetry() {
        retryTrigger.value++
    }

    fun onWriteErrorShown() {
        _writeFailed.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
