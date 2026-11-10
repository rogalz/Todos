package com.example.ai.ui.list

import com.example.ai.domain.Todo
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.MainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TodoListViewModelTest : MainDispatcherTest() {

    private val first = Todo("1", "First", "", isDone = false)
    private val second = Todo("2", "Second", "desc", isDone = true)

    /** `uiState` is `WhileSubscribed`, so something has to collect it. */
    private fun TestScope.keepCollecting(viewModel: TodoListViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun startsInLoadingNotEmpty() {
        val viewModel = TodoListViewModel(FakeTodoRepository())

        assertEquals(TodoListUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun emptyRepositoryShowsEmptyState() = runTest {
        val viewModel = TodoListViewModel(FakeTodoRepository())
        keepCollecting(viewModel)

        assertEquals(TodoListUiState.Empty, viewModel.uiState.value)
    }

    @Test
    fun todosAreExposedAsContent() = runTest {
        val viewModel = TodoListViewModel(FakeTodoRepository(listOf(first, second)))
        keepCollecting(viewModel)

        assertEquals(TodoListUiState.Content(listOf(first, second)), viewModel.uiState.value)
    }

    @Test
    fun stateFollowsRepositoryChanges() = runTest {
        val repository = FakeTodoRepository()
        val viewModel = TodoListViewModel(repository)
        keepCollecting(viewModel)
        assertEquals(TodoListUiState.Empty, viewModel.uiState.value)

        val id = repository.createTodo("New", "")
        assertTrue(viewModel.uiState.value is TodoListUiState.Content)

        repository.deleteTodo(id)
        assertEquals(TodoListUiState.Empty, viewModel.uiState.value)
    }

    @Test
    fun toggleWritesToRepositoryAndStateReflectsIt() = runTest {
        val repository = FakeTodoRepository(listOf(first))
        val viewModel = TodoListViewModel(repository)
        keepCollecting(viewModel)

        viewModel.onToggleDone(id = "1", isDone = true)

        assertEquals(listOf("1" to true), repository.doneChanges)
        assertEquals(
            TodoListUiState.Content(listOf(first.copy(isDone = true))),
            viewModel.uiState.value,
        )
    }

    @Test
    fun readFailureShowsErrorAndRetryRecovers() = runTest {
        val repository = FakeTodoRepository(listOf(first)).apply { failReads = true }
        val viewModel = TodoListViewModel(repository)
        keepCollecting(viewModel)
        assertEquals(TodoListUiState.Error, viewModel.uiState.value)

        repository.failReads = false
        viewModel.onRetry()

        assertEquals(TodoListUiState.Content(listOf(first)), viewModel.uiState.value)
    }

    @Test
    fun writeFailureIsReportedOnceAndCanBeAcknowledged() = runTest {
        val repository = FakeTodoRepository(listOf(first)).apply { failWrites = true }
        val viewModel = TodoListViewModel(repository)
        keepCollecting(viewModel)
        assertFalse(viewModel.writeFailed.value)

        viewModel.onToggleDone(id = "1", isDone = true)
        assertTrue(viewModel.writeFailed.value)

        viewModel.onWriteErrorShown()
        assertFalse(viewModel.writeFailed.value)
        // Failed write must not leak into the displayed state.
        assertEquals(TodoListUiState.Content(listOf(first)), viewModel.uiState.value)
    }
}
