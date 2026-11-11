package com.example.ai.ui.list

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.example.ai.domain.SyncOverview
import com.example.ai.domain.Todo
import com.example.ai.testing.FakeSyncStatusRepository
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.runAppComposeUiTest
import com.example.ai.ui.sync.SyncStatusViewModel
import com.example.ai.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The list screen with the real ViewModels over fake repositories. Things are found by their text and
 * semantics (what a user, or a screen reader, perceives), not by test tags.
 */
@OptIn(ExperimentalTestApi::class)
class TodoListScreenTest {

    private val open = Todo("1", "Buy milk", "Two litres", isDone = false)
    private val done = Todo("2", "Call the dentist", "", isDone = true)

    private class Screen(
        val todos: FakeTodoRepository,
        val sync: FakeSyncStatusRepository,
    ) {
        val added = mutableListOf<Unit>()
        val opened = mutableListOf<String>()
    }

    private fun ComposeUiTest.showList(
        initial: List<Todo>,
        overview: SyncOverview = SyncOverview.Synced,
    ): Screen {
        val screen = Screen(FakeTodoRepository(initial), FakeSyncStatusRepository(overview))
        // The ViewModels are created outside setContent: inside it they would be rebuilt on every recomposition.
        val listViewModel = TodoListViewModel(screen.todos)
        val syncViewModel = SyncStatusViewModel(screen.sync)
        setContent {
            AppTheme {
                TodoListScreen(
                    onAddClick = { screen.added += Unit },
                    onTodoClick = { screen.opened += it },
                    viewModel = listViewModel,
                    syncStatusViewModel = syncViewModel,
                )
            }
        }
        return screen
    }

    // ---- the list ------------------------------------------------------------------------------

    @Test
    fun showsTheTodosWithTheirTitlesAndDescriptions() = runAppComposeUiTest {
        showList(listOf(open, done))

        onNodeWithText("Buy milk").assertExists()
        onNodeWithText("Two litres").assertExists()
        onNodeWithText("Call the dentist").assertExists()
    }

    @Test
    fun aDoneTodoIsMarkedAsDoneInSemanticsAndAnOpenOneIsNot() = runAppComposeUiTest {
        showList(listOf(open, done))

        onNode(isToggleable() and hasContentDescription("Call the dentist")).assertIsOn()
        onNode(isToggleable() and hasContentDescription("Buy milk")).assertIsOff()
    }

    @Test
    fun theCheckboxStateIsAlsoSpokenAsText() = runAppComposeUiTest {
        // Not conveyed by the tick (or the colour) alone: a screen reader gets the words too.
        showList(listOf(open, done))

        onNodeWithTextState("Done", forTodo = "Call the dentist")
        onNodeWithTextState("Not done", forTodo = "Buy milk")
    }

    /** The state as words, via the semantics a screen reader uses. */
    private fun ComposeUiTest.onNodeWithTextState(state: String, forTodo: String) {
        onNode(
            isToggleable() and hasContentDescription(forTodo) and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state),
        ).assertExists()
    }

    @Test
    fun theEmptyStateIsShownWhenThereAreNoTodos() = runAppComposeUiTest {
        showList(emptyList())

        onNodeWithText("No tasks yet").assertExists()
        onNodeWithText("Tap “New task” to add your first one.").assertExists()
    }

    @Test
    fun theEmptyStateDisappearsOnceATodoExists() = runAppComposeUiTest {
        val screen = showList(emptyList())
        onNodeWithText("No tasks yet").assertExists()

        screen.todos.todos.value = listOf(open)
        waitForIdle()

        onNodeWithText("No tasks yet").assertDoesNotExist()
        onNodeWithText("Buy milk").assertExists()
    }

    @Test
    fun theNewTaskButtonIsThereInEveryStateAndOpensTheEditor() = runAppComposeUiTest {
        val screen = showList(emptyList())

        onNodeWithContentDescription("New task").performClick()

        assertEquals(1, screen.added.size)
    }

    @Test
    fun tappingACardOpensThatTodo() = runAppComposeUiTest {
        val screen = showList(listOf(open, done))

        onNodeWithText("Call the dentist").performClick()

        assertEquals(listOf("2"), screen.opened)
    }

    // ---- ticking -------------------------------------------------------------------------------

    @Test
    fun tappingTheCheckboxTogglesTheTodoInTheRepositoryAndTheScreenFollows() = runAppComposeUiTest {
        val screen = showList(listOf(open))

        onNode(isToggleable() and hasContentDescription("Buy milk")).performClick()
        waitForIdle()

        assertEquals(listOf("1" to true), screen.todos.doneChanges)
        onNode(isToggleable() and hasContentDescription("Buy milk")).assertIsOn()
    }

    @Test
    fun tappingTheCheckboxDoesNotOpenTheTodo() = runAppComposeUiTest {
        val screen = showList(listOf(open))

        onNode(isToggleable() and hasContentDescription("Buy milk")).performClick()

        assertEquals(emptyList(), screen.opened)
    }

    @Test
    fun aDoneTodoCanBeUnticked() = runAppComposeUiTest {
        val screen = showList(listOf(done))

        onNode(isToggleable() and hasContentDescription("Call the dentist")).performClick()
        waitForIdle()

        assertEquals(listOf("2" to false), screen.todos.doneChanges)
        onNode(isToggleable() and hasContentDescription("Call the dentist")).assertIsOff()
    }

    // ---- "not synced yet" ----------------------------------------------------------------------

    @Test
    fun aPendingTodoShowsTheNotSyncedIconAndASyncedOneDoesNot() = runAppComposeUiTest {
        val pending = Todo("3", "Water the plants", "", isDone = false, isSynced = false)
        showList(listOf(open, pending))

        onNode(hasText("Water the plants") and hasContentDescription("Not synced yet")).assertExists()
        onNode(hasText("Buy milk") and hasContentDescription("Not synced yet")).assertDoesNotExist()
        onAllNodesWithContentDescription("Not synced yet").assertCountEquals(1)
    }

    @Test
    fun theIconGoesAwayOnceTheTodoIsSynced() = runAppComposeUiTest {
        val pending = Todo("3", "Water the plants", "", isDone = false, isSynced = false)
        val screen = showList(listOf(pending))
        onAllNodesWithContentDescription("Not synced yet").assertCountEquals(1)

        screen.todos.todos.value = listOf(pending.copy(isSynced = true))
        waitForIdle()

        onAllNodesWithContentDescription("Not synced yet").assertCountEquals(0)
    }

    // ---- the status chip -----------------------------------------------------------------------

    @Test
    fun theChipSaysSynced() = runAppComposeUiTest {
        showList(listOf(open), SyncOverview.Synced)

        onNodeWithText("Synced").assertExists()
    }

    @Test
    fun theChipSaysOfflineAndHowManyChangesAreWaiting() = runAppComposeUiTest {
        showList(listOf(open), SyncOverview.Offline(pendingCount = 2))

        onNodeWithText("Offline · 2 waiting").assertExists()
    }

    @Test
    fun theWaitingCountUpdatesInPlace() = runAppComposeUiTest {
        val screen = showList(listOf(open), SyncOverview.Offline(2))

        screen.sync.overview.value = SyncOverview.Offline(3)
        waitForIdle()

        onNodeWithText("Offline · 3 waiting").assertExists()
        onNodeWithText("Offline · 2 waiting").assertDoesNotExist()
    }

    @Test
    fun offlineWithNothingWaitingLeavesTheNumberOut() = runAppComposeUiTest {
        showList(listOf(open), SyncOverview.Offline(0))

        onNodeWithText("Offline").assertExists()
        onAllNodesWithText("Offline · 0 waiting").assertCountEquals(0)
    }

    @Test
    fun aSyncOf100MillisecondsNeverShowsSyncing() = runAppComposeUiTest {
        val screen = showList(listOf(open))

        screen.sync.overview.value = SyncOverview.Syncing
        mainClock.advanceTimeBy(100)
        onNodeWithText("Syncing…").assertDoesNotExist()
        screen.sync.overview.value = SyncOverview.Synced
        mainClock.advanceTimeBy(1_000)

        onNodeWithText("Syncing…").assertDoesNotExist()
        onNodeWithText("Synced").assertExists()
    }

    @Test
    fun aSyncOfOneSecondShowsSyncingAndThenSyncedAgain() = runAppComposeUiTest {
        val screen = showList(listOf(open))

        screen.sync.overview.value = SyncOverview.Syncing
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Syncing…").assertExists()

        screen.sync.overview.value = SyncOverview.Synced
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Syncing…").assertDoesNotExist()
        onNodeWithText("Synced").assertExists()
    }

    @Test
    fun theChipDescribesItselfToAScreenReaderIncludingTheCount() = runAppComposeUiTest {
        val screen = showList(listOf(open), SyncOverview.Offline(1))
        onNodeWithContentDescription("Offline. 1 change is waiting to be uploaded.").assertExists()

        screen.sync.overview.value = SyncOverview.Offline(2)
        waitForIdle()
        onNodeWithContentDescription("Offline. 2 changes are waiting to be uploaded.").assertExists()

        screen.sync.overview.value = SyncOverview.Offline(0)
        waitForIdle()
        onNodeWithContentDescription("Offline. The server cannot be reached.").assertExists()

        screen.sync.overview.value = SyncOverview.Synced
        waitForIdle()
        onNodeWithContentDescription("Synced. All changes have reached the server.").assertExists()
    }

    // ---- touch targets -------------------------------------------------------------------------

    /** The area that reacts to a tap, which Compose may make larger than what is drawn. */
    private fun ComposeUiTest.assertTouchTargetIsAtLeast48Dp(name: String, node: SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val minimum = with(density) { 48.dp.toPx() }
        assertTrue(bounds.width >= minimum && bounds.height >= minimum, "$name touch target is ${bounds.width} x ${bounds.height} px, needs $minimum")
    }

    @Test
    fun everythingYouCanTapIsAtLeast48DpLargeInBothDirections() = runAppComposeUiTest {
        showList(listOf(open))

        assertTouchTargetIsAtLeast48Dp("checkbox", onNode(isToggleable() and hasContentDescription("Buy milk")))
        assertTouchTargetIsAtLeast48Dp("new task button", onNodeWithContentDescription("New task"))
        assertTouchTargetIsAtLeast48Dp("card", onNodeWithText("Buy milk"))
    }
}
