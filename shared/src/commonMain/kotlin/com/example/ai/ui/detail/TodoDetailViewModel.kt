package com.example.ai.ui.detail

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.domain.TodoDraft
import com.example.ai.domain.TodoDraftParser
import com.example.ai.domain.TodoRepository
import com.example.ai.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * What the screen shows once, as a snackbar. [AiUnavailable] is not a failure of the user's action:
 * the draft is there (their own words), the screen just says why it is not tidier.
 */
enum class DetailError { SaveFailed, DeleteFailed, AiUnavailable }

/**
 * Koin parameter for a task that starts from dictation. Its own type on purpose: Koin matches
 * parameters by type, and a second plain `String?` next to the todo id would be mixed up with it.
 */
data class Dictation(val text: String)

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
    /**
     * When the todo was last changed, for the "Edited ..." footer. `null` for a new todo and while loading.
     * Read once at load: it is not live, because the screen is closed right after saving anyway.
     */
    val updatedAt: Instant? = null,
    /**
     * The task comes from dictation and is only a proposal: it is stored by an explicit Save, never
     * by going back. Stays `true` until the screen closes.
     */
    val isDraft: Boolean = false,
    /** A draft is waiting for the AI; the fields show a "Filling…" label while they are empty. */
    val isFilling: Boolean = false,
    /** Back was pressed on a draft: asking whether to throw it away. */
    val showDiscardDraftConfirmation: Boolean = false,
)

/**
 * Edits one todo. Changes are written when the user leaves the screen ([onBackClick]).
 *
 * **A draft is the exception.** A task opened with [dictatedText] is an AI proposal: the screen
 * asks the [draftParser] to fill the fields, and nothing is stored until the user presses Save
 * ([onSaveClick]). Going back asks "Discard draft?" and, if confirmed, stores nothing. This is the
 * human-in-the-loop rule of the voice feature; every other screen keeps save-on-back.
 *
 * A **new** todo is only ever created by [onBackClick] (or [onSaveClick] for a draft). It is deliberately not created when the app
 * goes to the background: the insert finishes asynchronously, after Android has already snapshotted
 * the saved state, so its id could not be remembered across process death, and a half-written task
 * would also appear in the list while the user is just switching apps.
 *
 * @param todoId id of the todo to edit, or `null` to create a new one.
 * @param dictatedText what the user said, to create a new todo as a draft; needs `todoId == null`
 *   and a [draftParser].
 */
class TodoDetailViewModel(
    todoId: String?,
    private val repository: TodoRepository,
    dictatedText: String? = null,
    private val draftParser: TodoDraftParser? = null,
) : ViewModel() {

    // TextFieldState (not StateFlow<String>) so the IME/cursor never fights an async state round-trip.
    val titleState = TextFieldState()
    val descriptionState = TextFieldState()

    // Blank dictation is no dictation (the list never sends it): there is nothing to turn into a draft.
    private val isDraft = !dictatedText.isNullOrBlank()
    private val _uiState = MutableStateFlow(
        TodoDetailUiState(
            isNew = todoId == null,
            isLoading = todoId != null,
            canDelete = todoId != null,
            isDraft = isDraft,
            isFilling = isDraft,
        ),
    )
    val uiState: StateFlow<TodoDetailUiState> = _uiState.asStateFlow()

    private data class Content(val title: String, val description: String)

    /**
     * What the AI put into the fields, `null` when it filled nothing (not a draft, still waiting, the
     * AI was unreachable, or the user had typed in both fields first). Snapshot state, so the hint
     * below re-reads it in composition.
     */
    private var aiFilled by mutableStateOf<Content?>(null)

    /**
     * The "Filled by AI, check before saving" hint: shown while the fields still hold exactly what the
     * AI wrote, gone as soon as the user edits one of them. (Saving closes the screen.)
     */
    val showAiHint: Boolean
        get() {
            val filled = aiFilled ?: return false
            return titleState.text.toString() == filled.title && descriptionState.text.toString() == filled.description
        }

    /** The wait for the AI, cancelled when the user saves or leaves so a late answer cannot land. */
    private var fillJob: Job? = null

    /** What is currently in the database, used to skip redundant writes. */
    private var lastSaved: Content? = null

    /** Id of the persisted row: [todoId] when editing, set after the first insert when creating. */
    private var currentId: String? = todoId

    /** Serialises saves so a todo can never be inserted twice. */
    private val saveMutex = Mutex()

    /** Set once the screen is leaving (back, discard, delete). Blocks any further saving or closing. */
    private var isClosing = false

    init {
        require(!isDraft || (todoId == null && draftParser != null)) {
            "a dictated draft is a new todo and needs a draft parser"
        }
        if (todoId != null) load(todoId)
        if (isDraft) fill(checkNotNull(dictatedText))
    }

    /**
     * Asks the AI for the draft and puts it into the fields. If that fails for any reason (offline, a
     * server error, a timeout) the user's own words become the title instead, with a snackbar that
     * says so: after dictating, an empty form would be worse.
     */
    private fun fill(transcript: String) {
        val parser = checkNotNull(draftParser)
        fillJob = viewModelScope.launch {
            val result = runSuspendCatching { parser.parse(transcript) }
            // The user may have saved or left while we waited; then the answer has nowhere to go.
            if (isClosing) return@launch
            result
                .onSuccess { applyDraft(it, filledByAi = true) }
                .onFailure {
                    applyDraft(TodoDraft.fallbackFor(transcript), filledByAi = false)
                    _uiState.update { state -> state.copy(error = DetailError.AiUnavailable) }
                }
        }
    }

    /**
     * Fills a field only if it is still empty: whatever the user typed while waiting is never
     * overwritten. (A field the user typed in and then cleared counts as empty again; tracking that
     * would need to observe every keystroke for little gain.)
     */
    private fun applyDraft(draft: TodoDraft, filledByAi: Boolean) {
        var filledAnything = false
        if (titleState.text.isEmpty() && draft.title.isNotEmpty()) {
            titleState.setTextAndPlaceCursorAtEnd(draft.title)
            filledAnything = true
        }
        if (descriptionState.text.isEmpty() && draft.description.isNotEmpty()) {
            descriptionState.setTextAndPlaceCursorAtEnd(draft.description)
            filledAnything = true
        }
        aiFilled = if (filledByAi && filledAnything) {
            Content(titleState.text.toString(), descriptionState.text.toString())
        } else {
            null
        }
        _uiState.update { it.copy(isFilling = false) }
    }

    private fun stopWaitingForAi() {
        fillJob?.cancel()
        _uiState.update { it.copy(isFilling = false) }
    }

    private fun load(id: String) {
        viewModelScope.launch {
            runSuspendCatching { repository.getTodo(id) }
                .onSuccess { todo ->
                    if (todo == null) {
                        close()
                    } else {
                        titleState.setTextAndPlaceCursorAtEnd(todo.title)
                        descriptionState.setTextAndPlaceCursorAtEnd(todo.description)
                        lastSaved = Content(todo.title, todo.description)
                        _uiState.update { it.copy(isLoading = false, updatedAt = todo.updatedAt) }
                    }
                }
                // Nothing to edit if we cannot read it, leave instead of showing an empty form
                // that a later save could use to overwrite the real content.
                .onFailure { close() }
        }
    }

    /**
     * Back arrow, system back and (via the UI) back gestures. Saves, then asks the UI to close.
     * On a draft it saves nothing: it asks whether to discard it ([onDiscardDraftConfirmed]).
     */
    fun onBackClick() {
        if (isClosing) return
        if (isDraft) {
            _uiState.update { it.copy(showDiscardDraftConfirmation = true) }
            return
        }
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

    /**
     * The Save action of a draft: the one way a dictated task gets stored. It stores the fields as they
     * are now (so an edited draft is stored edited), then closes. Does nothing without a title.
     */
    fun onSaveClick() {
        if (isClosing || !isDraft || titleState.text.isBlank()) return
        isClosing = true
        stopWaitingForAi()
        _uiState.update { it.copy(showDiscardDraftConfirmation = false) }
        viewModelScope.launch {
            if (save()) {
                close()
            } else {
                // Stay on the screen so the draft is not lost; they may retry Save or discard.
                isClosing = false
                _uiState.update { it.copy(error = DetailError.SaveFailed) }
            }
        }
    }

    /** "Keep editing" in the discard dialog (also tapping outside it). */
    fun onDiscardDraftDismissed() {
        _uiState.update { it.copy(showDiscardDraftConfirmation = false) }
    }

    /** "Discard" in the discard dialog: leaves, storing nothing. */
    fun onDiscardDraftConfirmed() {
        _uiState.update { it.copy(showDiscardDraftConfirmation = false) }
        onDiscardChanges()
    }

    fun onDiscardChanges() {
        isClosing = true
        stopWaitingForAi()
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
