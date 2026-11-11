package com.example.ai.ui.detail

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.example.ai.domain.Todo
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TodoDetailViewModelTest : MainDispatcherTest() {

    private val existing = Todo(id = "5", title = "Old title", description = "Old description", isDone = true)

    private fun newTodoViewModel(repository: FakeTodoRepository) =
        TodoDetailViewModel(todoId = null, repository = repository)

    private fun editViewModel(repository: FakeTodoRepository, id: String = existing.id) =
        TodoDetailViewModel(todoId = id, repository = repository)

    private fun TodoDetailViewModel.type(title: String? = null, description: String? = null) {
        title?.let(titleState::setTextAndPlaceCursorAtEnd)
        description?.let(descriptionState::setTextAndPlaceCursorAtEnd)
    }

    // ---- "Edited ..." footer data --------------------------------------------------------------

    @Test
    fun editingExposesWhenTheTodoWasLastChanged() {
        val changedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val repository = FakeTodoRepository(listOf(existing.copy(updatedAt = changedAt)))

        val viewModel = editViewModel(repository)

        assertEquals(changedAt, viewModel.uiState.value.updatedAt)
    }

    @Test
    fun updatedAtIsUnknownWhileLoading() {
        val repository = FakeTodoRepository(listOf(existing))
        repository.getTodoGate = CompletableDeferred()

        val viewModel = editViewModel(repository)

        assertNull(viewModel.uiState.value.updatedAt)
    }

    @Test
    fun aNewTodoHasNoUpdatedAt() {
        assertNull(newTodoViewModel(FakeTodoRepository()).uiState.value.updatedAt)
    }

    // ---- new todo -------------------------------------------------------------------------

    @Test
    fun newTodoStartsReadyAndWithoutDeleteAction() {
        val state = newTodoViewModel(FakeTodoRepository()).uiState.value

        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertFalse(state.canDelete)
        assertFalse(state.closeRequested)
    }

    @Test
    fun backWithBlankTitleDiscardsNewTodo() {
        val repository = FakeTodoRepository()
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "   ", description = "orphan description")

        viewModel.onBackClick()

        assertEquals(0, repository.writeCount)
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun backWithTitleCreatesTodoOnceWithTrimmedTitle() {
        val repository = FakeTodoRepository()
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "  Buy milk  ", description = "2 liters")

        viewModel.onBackClick()

        assertEquals(listOf(FakeTodoRepository.Created("Buy milk", "2 liters")), repository.created)
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun repeatedBackSavesAndClosesOnlyOnce() {
        val repository = FakeTodoRepository()
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "Once")

        viewModel.onBackClick()
        viewModel.onBackClick()
        viewModel.onBackClick()

        assertEquals(1, repository.created.size)
        assertEquals(1, repository.writeCount)
    }

    // ---- autosave on ON_STOP --------------------------------------------------------------

    @Test
    fun stopNeverCreatesANewTodo() {
        val repository = FakeTodoRepository()
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "Half written", description = "still typing")

        viewModel.onScreenStopped()

        assertEquals(0, repository.writeCount, "a draft must not appear in the list just because the app was backgrounded")
        assertFalse(viewModel.uiState.value.canDelete)
        assertFalse(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun newTodoIsStillCreatedOnceByBackAfterAStop() {
        val repository = FakeTodoRepository()
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "Draft")
        viewModel.onScreenStopped()

        viewModel.onBackClick()

        assertEquals(listOf(FakeTodoRepository.Created("Draft", "")), repository.created)
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun stopPersistsEditsOfAnExistingTodo() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "Edited in background")

        viewModel.onScreenStopped()

        assertEquals(listOf(FakeTodoRepository.Updated("5", "Edited in background", "Old description")), repository.updated)
        assertFalse(viewModel.uiState.value.closeRequested, "stopping must not close the screen")
    }

    @Test
    fun backAfterStopOnlyWritesWhatChangedSince() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "First edit")
        viewModel.onScreenStopped()

        viewModel.onBackClick()
        assertEquals(1, repository.updated.size, "nothing changed since the autosave")
    }

    @Test
    fun editAfterStopIsSavedByBack() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "First edit")
        viewModel.onScreenStopped()
        viewModel.type(title = "Second edit")

        viewModel.onBackClick()

        assertEquals(
            listOf(
                FakeTodoRepository.Updated("5", "First edit", "Old description"),
                FakeTodoRepository.Updated("5", "Second edit", "Old description"),
            ),
            repository.updated,
        )
    }

    @Test
    fun stopWithBlankNewTodoWritesNothing() {
        val repository = FakeTodoRepository()
        val viewModel = newTodoViewModel(repository)

        viewModel.onScreenStopped()

        assertEquals(0, repository.writeCount)
        assertFalse(viewModel.uiState.value.closeRequested)
    }

    // ---- editing an existing todo ---------------------------------------------------------

    @Test
    fun existingTodoIsLoadedIntoTheFields() {
        val viewModel = editViewModel(FakeTodoRepository(listOf(existing)))

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isNew)
        assertTrue(viewModel.uiState.value.canDelete)
        assertEquals("Old title", viewModel.titleState.text.toString())
        assertEquals("Old description", viewModel.descriptionState.text.toString())
    }

    @Test
    fun backWithoutChangesDoesNotWrite() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)

        viewModel.onBackClick()

        assertEquals(0, repository.writeCount)
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun backWithChangesUpdatesAndKeepsDoneState() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "New title", description = "New description")

        viewModel.onBackClick()

        assertEquals(listOf(FakeTodoRepository.Updated("5", "New title", "New description")), repository.updated)
        assertTrue(repository.todos.value.single().isDone, "editing text must not change the checkbox")
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun blankTitleNeverOverwritesExistingTitleButDescriptionIsStillSaved() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "  ", description = "Edited description")

        viewModel.onBackClick()

        assertEquals(listOf(FakeTodoRepository.Updated("5", "Old title", "Edited description")), repository.updated)
    }

    @Test
    fun blankTitleWithoutOtherChangesWritesNothing() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "")

        viewModel.onBackClick()

        assertEquals(0, repository.writeCount)
    }

    // ---- loading / not found --------------------------------------------------------------

    @Test
    fun missingTodoClosesTheScreen() {
        val viewModel = editViewModel(FakeTodoRepository(), id = "99")

        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun loadFailureClosesTheScreenInsteadOfShowingAnEmptyForm() {
        val repository = FakeTodoRepository(listOf(existing)).apply { failReads = true }
        val viewModel = editViewModel(repository)

        assertTrue(viewModel.uiState.value.closeRequested)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun backWhileStillLoadingDoesNotWipeStoredData() {
        val repository = FakeTodoRepository(listOf(existing))
        repository.getTodoGate = CompletableDeferred()
        val viewModel = editViewModel(repository)
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.onBackClick()

        assertEquals(0, repository.writeCount, "blank, not-yet-loaded fields must never be saved")
        assertEquals(existing, repository.todos.value.single())
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun stopWhileStillLoadingDoesNotSave() {
        val repository = FakeTodoRepository(listOf(existing))
        repository.getTodoGate = CompletableDeferred()
        val viewModel = editViewModel(repository)

        viewModel.onScreenStopped()

        assertEquals(0, repository.writeCount)
    }

    // ---- delete ---------------------------------------------------------------------------

    @Test
    fun deleteDialogCanBeShownAndDismissed() {
        val viewModel = editViewModel(FakeTodoRepository(listOf(existing)))

        viewModel.onDeleteClick()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)

        viewModel.onDeleteDismissed()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun confirmedDeleteRemovesTodoClosesAndSkipsAutosave() {
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = editViewModel(repository)
        viewModel.type(title = "Edited but about to be deleted")
        viewModel.onDeleteClick()

        viewModel.onDeleteConfirmed()
        viewModel.onScreenStopped() // e.g. the screen stopping while it is being popped
        viewModel.onBackClick()

        assertEquals(listOf("5"), repository.deleted)
        assertEquals(0, repository.updated.size, "a deleted row must not be updated or re-created")
        assertEquals(0, repository.created.size)
        assertTrue(repository.todos.value.isEmpty())
        assertTrue(viewModel.uiState.value.closeRequested)
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun deleteFailureKeepsTheScreenOpenAndReportsError() {
        val repository = FakeTodoRepository(listOf(existing)).apply { failWrites = true }
        val viewModel = editViewModel(repository)

        viewModel.onDeleteConfirmed()

        assertEquals(DetailError.DeleteFailed, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.closeRequested)

        // Not stuck: the user can still leave (and edits are saved again).
        repository.failWrites = false
        viewModel.onBackClick()
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    // ---- save failure ---------------------------------------------------------------------

    @Test
    fun saveFailureKeepsTheScreenOpenSoInputIsNotLost() {
        val repository = FakeTodoRepository().apply { failWrites = true }
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "Important", description = "Do not lose me")

        viewModel.onBackClick()

        assertEquals(DetailError.SaveFailed, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.closeRequested)
        assertEquals("Important", viewModel.titleState.text.toString())
        assertEquals("Do not lose me", viewModel.descriptionState.text.toString())
    }

    @Test
    fun backCanBeRetriedAfterSaveFailure() {
        val repository = FakeTodoRepository().apply { failWrites = true }
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "Retry me")
        viewModel.onBackClick()
        viewModel.onErrorShown()
        assertNull(viewModel.uiState.value.error)

        repository.failWrites = false
        viewModel.onBackClick()

        assertEquals(listOf(FakeTodoRepository.Created("Retry me", "")), repository.created)
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun discardLeavesWithoutSavingAfterSaveFailure() {
        val repository = FakeTodoRepository().apply { failWrites = true }
        val viewModel = newTodoViewModel(repository)
        viewModel.type(title = "Give up")
        viewModel.onBackClick()

        repository.failWrites = false
        viewModel.onDiscardChanges()
        viewModel.onScreenStopped()

        assertTrue(viewModel.uiState.value.closeRequested)
        assertEquals(0, repository.writeCount)
    }
}
