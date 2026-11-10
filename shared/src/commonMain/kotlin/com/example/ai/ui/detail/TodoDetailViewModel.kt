package com.example.ai.ui.detail

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DetailError { SaveFailed, DeleteFailed }

data class TodoDetailUiState(
    /** `true` when the screen was opened to create a task (only affects the title). */
    val isNew: Boolean,
    val isLoading: Boolean,
    /** Only an existing todo can be deleted. */
    val canDelete: Boolean,
    val showDeleteConfirmation: Boolean = false,
    val error: DetailError? = null,
    /** One-shot-by-state: the UI pops the screen exactly once when this turns `true`. */
    val closeRequested: Boolean = false,
)

/**
 * Edits one todo. Changes are written when the user leaves the screen ([onBackClick]).
 *
 * A **new** todo is only ever created by [onBackClick]. It is deliberately not created when the app
 * goes to the background: the insert finishes asynchronously, after Android has already snapshotted
 * the saved state, so its id could not be remembered across process death, and a half-written task
 * would also appear in the list while the user is just switching apps.
 *
 * @param todoId id of the todo to edit, or `null` to create a new one.
 */
class TodoDetailViewModel(
    todoId: Long?,
    private val repository: TodoRepository,
) : ViewModel() {

    // TextFieldState (not StateFlow<String>) so the IME/cursor never fights an async state round-trip.
    val titleState = TextFieldState()
    val descriptionState = TextFieldState()

    private val _uiState = MutableStateFlow(
        TodoDetailUiState(isNew = todoId == null, isLoading = todoId != null, canDelete = todoId != null),
    )
    val uiState: StateFlow<TodoDetailUiState> = _uiState.asStateFlow()

    private data class Content(val title: String, val description: String)

    /** What is currently in the database, used to skip redundant writes. */
    private var lastSaved: Content? = null

    /** Id of the persisted row: [todoId] when editing, set after the first insert when creating. */
    private var currentId: Long? = todoId

    /** Serialises saves so a todo can never be inserted twice. */
    private val saveMutex = Mutex()

    /** Set once the screen is leaving (back, discard, delete). Blocks any further saving or closing. */
    private var isClosing = false

    init {
        if (todoId != null) load(todoId)
    }

    private fun load(id: Long) {
        viewModelScope.launch {
            runSuspendCatching { repository.getTodo(id) }
                .onSuccess { todo ->
                    if (todo == null) {
                        close()
                    } else {
                        titleState.setTextAndPlaceCursorAtEnd(todo.title)
                        descriptionState.setTextAndPlaceCursorAtEnd(todo.description)
                        lastSaved = Content(todo.title, todo.description)
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                // Nothing to edit if we cannot read it, leave instead of showing an empty form
                // that a later save could use to overwrite the real content.
                .onFailure { close() }
        }
    }

    /** Back arrow, system back and (via the UI) back gestures. Saves, then asks the UI to close. */
    fun onBackClick() {
        if (isClosing) return
        isClosing = true
        viewModelScope.launch {
            // While still loading the fields are blank, saving now would wipe the stored description.
            val saved = _uiState.value.isLoading || save()
            if (saved) {
                close()
            } else {
                // Stay on the screen so the user's input is not lost; they may retry or discard.
                isClosing = false
                _uiState.update { it.copy(error = DetailError.SaveFailed) }
            }
        }
    }

    /**
     * Safety net for process death: persists edits of an *existing* todo when the app goes to the
     * background. Does nothing for a todo that has not been created yet (see class documentation).
     */
    fun onScreenStopped() {
        if (isClosing || _uiState.value.isLoading || currentId == null) return
        viewModelScope.launch { save() }
    }

    fun onDiscardChanges() {
        isClosing = true
        close()
    }

    fun onDeleteClick() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun onDeleteDismissed() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun onDeleteConfirmed() {
        if (isClosing) return
        isClosing = true // also disables autosave so the deleted row is not updated afterwards
        _uiState.update { it.copy(showDeleteConfirmation = false) }
        val id = currentId
        if (id == null) {
            close()
            return
        }
        viewModelScope.launch {
            runSuspendCatching { repository.deleteTodo(id) }
                .onSuccess { close() }
                .onFailure {
                    isClosing = false
                    _uiState.update { state -> state.copy(error = DetailError.DeleteFailed) }
                }
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    private fun close() {
        isClosing = true
        _uiState.update { it.copy(closeRequested = true) }
    }

    /** @return `false` only if writing to the database failed. */
    private suspend fun save(): Boolean = saveMutex.withLock {
        val typedTitle = titleState.text.toString().trim()
        val description = descriptionState.text.toString()

        // An empty title never overwrites an existing title; a new todo without title is not created.
        val title = typedTitle.ifEmpty { lastSaved?.title ?: return@withLock true }
        val content = Content(title, description)
        if (content == lastSaved) return@withLock true

        val id = currentId
        runSuspendCatching {
            if (id == null) {
                currentId = repository.createTodo(title, description)
            } else {
                repository.updateTodo(id, title, description)
            }
        }.onSuccess {
            lastSaved = content
        }.isSuccess
    }
}
