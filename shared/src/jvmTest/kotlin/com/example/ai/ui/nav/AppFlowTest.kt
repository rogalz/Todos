package com.example.ai.ui.nav

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.ai.domain.Todo
import com.example.ai.testing.FakeAppEnvironment
import com.example.ai.testing.FakeClock
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.runAppComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Whole-app flows: the real `AppNavHost` (including the shared-element transitions, which run to
 * completion in these tests) and the real ViewModels from the production Koin module, over fakes.
 */
@OptIn(ExperimentalTestApi::class)
class AppFlowTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val clock = FakeClock(now.toEpochMilliseconds())

    private val milk = Todo("1", "Buy milk", "Two litres", isDone = false, updatedAt = now - 5.minutes)

    private fun ComposeUiTest.showApp(repository: FakeTodoRepository) {
        setContent { FakeAppEnvironment(repository, clock = clock) { AppNavHost() } }
        waitForIdle()
    }

    // ---- creating ------------------------------------------------------------------------------

    @Test
    fun newTaskFlow_fabThenTypeATitleThenBack_theTodoIsInTheList() = runAppComposeUiTest {
        val repository = FakeTodoRepository()
        showApp(repository)
        onNodeWithText("No tasks yet").assertExists()

        onNodeWithContentDescription("New task").performClick()
        waitForIdle()
        onNodeWithText("Title").performTextInput("Water the plants")
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Water the plants").assertExists()
        onNodeWithText("No tasks yet").assertDoesNotExist()
        assertEquals(listOf(FakeTodoRepository.Created("Water the plants", "")), repository.created)
    }

    @Test
    fun theDescriptionTypedOnTheNewTaskScreenIsSavedToo() = runAppComposeUiTest {
        val repository = FakeTodoRepository()
        showApp(repository)

        onNodeWithContentDescription("New task").performClick()
        waitForIdle()
        onNodeWithText("Title").performTextInput("Water the plants")
        onNodeWithText("Description").performTextInput("Twice a week")
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        assertEquals(listOf(FakeTodoRepository.Created("Water the plants", "Twice a week")), repository.created)
        onNodeWithText("Twice a week").assertExists()
    }

    @Test
    fun goingBackFromANewTaskWithoutATitleCreatesNothing() = runAppComposeUiTest {
        val repository = FakeTodoRepository()
        showApp(repository)

        onNodeWithContentDescription("New task").performClick()
        waitForIdle()
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("No tasks yet").assertExists()
        assertEquals(0, repository.writeCount)
    }

    // ---- opening and editing -------------------------------------------------------------------

    @Test
    fun tappingACardOpensItWithItsContentAndWhenItWasEdited() = runAppComposeUiTest {
        showApp(FakeTodoRepository(listOf(milk)))

        onNodeWithText("Buy milk").performClick()
        waitForIdle()

        onNodeWithText("Edit task").assertExists()
        onNodeWithText("Buy milk").assertExists() // the title field
        onNodeWithText("Two litres").assertExists() // the description field
        onNodeWithText("Edited 5 min ago").assertExists()
    }

    @Test
    fun theFooterSaysJustNowForSomethingChangedSecondsAgo() = runAppComposeUiTest {
        showApp(FakeTodoRepository(listOf(milk.copy(updatedAt = now))))

        onNodeWithText("Buy milk").performClick()
        waitForIdle()

        onNodeWithText("Edited just now").assertExists()
    }

    @Test
    fun theFooterSaysYesterdayForSomethingChangedADayAgo() = runAppComposeUiTest {
        // 24 h before noon UTC is the previous calendar day in every time zone, so this holds wherever it runs.
        showApp(FakeTodoRepository(listOf(milk.copy(updatedAt = now - 1.days))))

        onNodeWithText("Buy milk").performClick()
        waitForIdle()

        onNodeWithText("Edited yesterday").assertExists()
    }

    @Test
    fun aNewTaskHasNoEditedFooter() = runAppComposeUiTest {
        showApp(FakeTodoRepository(listOf(milk)))

        onNodeWithContentDescription("New task").performClick()
        waitForIdle()

        onNodeWithText("New task").assertExists() // the screen title
        onNodeWithText("Edited just now").assertDoesNotExist()
        onNodeWithText("Edited 5 min ago").assertDoesNotExist()
    }

    @Test
    fun editingATitleAndGoingBackSavesItAndShowsItInTheList() = runAppComposeUiTest {
        val repository = FakeTodoRepository(listOf(milk))
        showApp(repository)

        onNodeWithText("Buy milk").performClick()
        waitForIdle()
        onNodeWithText("Buy milk").performTextReplacement("Buy oat milk")
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Buy oat milk").assertExists()
        assertEquals(listOf(FakeTodoRepository.Updated("1", "Buy oat milk", "Two litres")), repository.updated)
    }

    @Test
    fun goingBackWithoutChangesWritesNothing() = runAppComposeUiTest {
        val repository = FakeTodoRepository(listOf(milk))
        showApp(repository)

        onNodeWithText("Buy milk").performClick()
        waitForIdle()
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Buy milk").assertExists()
        assertEquals(0, repository.writeCount)
    }

    // ---- failures and deleting (existing behaviour that the restyle must not change) --------------

    @Test
    fun whenSavingFailsYouStayOnTheScreenAndMayDiscard() = runAppComposeUiTest {
        val repository = FakeTodoRepository().apply { failWrites = true }
        showApp(repository)

        onNodeWithContentDescription("New task").performClick()
        waitForIdle()
        onNodeWithText("Title").performTextInput("Water the plants")
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Couldn't save your changes.").assertExists()
        onNodeWithText("New task").assertExists() // still on the editor, the input is not lost

        onNodeWithText("Discard").performClick()
        waitForIdle()
        onNodeWithText("No tasks yet").assertExists()
    }

    @Test
    fun deletingATodoAsksFirstAndThenRemovesItFromTheList() = runAppComposeUiTest {
        val repository = FakeTodoRepository(listOf(milk))
        showApp(repository)

        onNodeWithText("Buy milk").performClick()
        waitForIdle()
        onNodeWithContentDescription("Delete task").performClick()
        onNodeWithText("Delete task?").assertExists()
        onNodeWithText("Delete").performClick()
        waitForIdle()

        onNodeWithText("No tasks yet").assertExists()
        assertEquals(listOf("1"), repository.deleted)
    }

    @Test
    fun cancellingTheDeleteDialogKeepsTheTodo() = runAppComposeUiTest {
        val repository = FakeTodoRepository(listOf(milk))
        showApp(repository)

        onNodeWithText("Buy milk").performClick()
        waitForIdle()
        onNodeWithContentDescription("Delete task").performClick()
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        onNodeWithText("Edit task").assertExists()
        assertEquals(emptyList(), repository.deleted)
    }
}
