package com.example.ai.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.example.ai.domain.Todo
import com.example.ai.testing.FakeClock
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.runAppComposeUiTest
import com.example.ai.ui.nav.SharedElements
import com.example.ai.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detail screen on its own, with the shared-element modifiers replaced by a registry of which ones
 * are currently applied: the actual morph needs a NavHost (see `AppFlowTest`), what matters here is
 * *when* the screen offers itself to it. A modifier has no semantics of its own, so it is observed
 * through the composition (a `DisposableEffect` per applied modifier) instead of through the UI tree.
 */
@OptIn(ExperimentalTestApi::class)
class TodoDetailScreenTest {

    private val milk = Todo("1", "Buy milk", "Two litres", isDone = false)

    private class RecordingSharedElements : SharedElements {
        /** Keys of the modifiers that are in the composition right now. */
        val applied = linkedSetOf<String>()

        @Composable
        private fun track(key: String): Modifier {
            DisposableEffect(key) {
                applied += key
                onDispose { applied -= key }
            }
            return Modifier
        }

        @Composable
        override fun container(key: String): Modifier = track(key)

        @Composable
        override fun title(key: String): Modifier = track(key)
    }

    @Test
    fun whileEditing_theContainerAndTheTitleTakePartInTheSharedTransition() = runAppComposeUiTest {
        val sharedElements = RecordingSharedElements()
        val viewModel = TodoDetailViewModel("1", FakeTodoRepository(listOf(milk)))
        setContent {
            AppTheme {
                TodoDetailScreen(
                    todoId = "1",
                    onClose = {},
                    viewModel = viewModel,
                    sharedElements = sharedElements,
                    clock = FakeClock(),
                )
            }
        }
        waitForIdle()

        assertEquals(setOf("todo-container-1", "todo-title-1"), sharedElements.applied)
    }

    /**
     * Why: a morph back into the list would land on the FAB for a new task and on a card that no longer
     * matches the edited title for an existing one. Leaving the shared transition makes the way out the
     * plain fade-through of the NavHost. The modifiers must be gone *before* `onClose` is called, because
     * `onClose` is what starts the pop.
     */
    @Test
    fun onceTheSaveHasFinished_theScreenIsNoLongerASharedElementWhenItAsksToClose() = runAppComposeUiTest {
        val repository = FakeTodoRepository(listOf(milk))
        val sharedElements = RecordingSharedElements()
        val viewModel = TodoDetailViewModel("1", repository)
        val appliedWhenClosing = mutableListOf<Set<String>>()
        setContent {
            AppTheme {
                TodoDetailScreen(
                    todoId = "1",
                    // Nothing pops the screen here, so it stays composed and its state can be inspected afterwards.
                    onClose = { appliedWhenClosing += sharedElements.applied.toSet() },
                    viewModel = viewModel,
                    sharedElements = sharedElements,
                    clock = FakeClock(),
                )
            }
        }
        waitForIdle()

        onNodeWithText("Buy milk").performTextReplacement("Buy oat milk")
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        assertEquals(listOf(FakeTodoRepository.Updated("1", "Buy oat milk", "Two litres")), repository.updated)
        assertEquals(listOf(emptySet<String>()), appliedWhenClosing, "onClose is called once, with no shared modifier left")
        assertEquals(emptySet(), sharedElements.applied)
    }

    @Test
    fun aNewTaskLeavesTheSharedTransitionTheSameWay() = runAppComposeUiTest {
        val sharedElements = RecordingSharedElements()
        val viewModel = TodoDetailViewModel(null, FakeTodoRepository())
        val appliedWhenClosing = mutableListOf<Set<String>>()
        setContent {
            AppTheme {
                TodoDetailScreen(
                    todoId = null,
                    onClose = { appliedWhenClosing += sharedElements.applied.toSet() },
                    viewModel = viewModel,
                    sharedElements = sharedElements,
                    clock = FakeClock(),
                )
            }
        }
        waitForIdle()
        assertEquals(setOf("todo-container-new", "todo-title-new"), sharedElements.applied)

        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        assertEquals(listOf(emptySet<String>()), appliedWhenClosing)
    }
}
