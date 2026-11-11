package com.example.ai.ui.detail

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.example.ai.domain.TodoDraft
import com.example.ai.testing.FakeTodoDraftParser
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A task that comes from dictation is an AI *draft*: the AI fills the fields, and nothing is stored
 * until the user presses Save. These tests are the contract of that rule.
 */
class TodoDetailDraftViewModelTest : MainDispatcherTest() {

    private val transcript = "uh, buy milk and bread, tomorrow morning before work"
    private val aiDraft = TodoDraft(title = "Buy milk and bread", description = "Tomorrow morning, before work")

    private val repository = FakeTodoRepository()

    /** An AI that answers at once. */
    private fun answeringParser(draft: TodoDraft = aiDraft) = FakeTodoDraftParser { draft }

    /** An AI that answers when the test says so (the "Filling…" phase). */
    private fun waitingParser(): Pair<FakeTodoDraftParser, CompletableDeferred<TodoDraft>> {
        val gate = CompletableDeferred<TodoDraft>()
        return FakeTodoDraftParser { gate.await() } to gate
    }

    private fun draftViewModel(parser: FakeTodoDraftParser, text: String = transcript) =
        TodoDetailViewModel(todoId = null, repository = repository, dictatedText = text, draftParser = parser)

    private fun TodoDetailViewModel.type(title: String? = null, description: String? = null) {
        title?.let(titleState::setTextAndPlaceCursorAtEnd)
        description?.let(descriptionState::setTextAndPlaceCursorAtEnd)
    }

    private val TodoDetailViewModel.title get() = titleState.text.toString()
    private val TodoDetailViewModel.description get() = descriptionState.text.toString()

    // ---- filling -------------------------------------------------------------------------------

    @Test
    fun itOpensAsADraftThatIsWaitingForTheAi() {
        val (parser, _) = waitingParser()

        val viewModel = draftViewModel(parser)

        val state = viewModel.uiState.value
        assertTrue(state.isDraft)
        assertTrue(state.isFilling)
        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertFalse(state.canDelete)
        assertEquals("", viewModel.title)
        assertEquals(listOf(transcript), parser.parsed)
    }

    @Test
    fun theDraftOfTheAiFillsBothFieldsAndShowsTheHint() {
        val (parser, gate) = waitingParser()
        val viewModel = draftViewModel(parser)

        gate.complete(aiDraft)

        assertEquals("Buy milk and bread", viewModel.title)
        assertEquals("Tomorrow morning, before work", viewModel.description)
        assertFalse(viewModel.uiState.value.isFilling)
        assertTrue(viewModel.showAiHint)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun fillingTheDraftStoresNothing() {
        draftViewModel(answeringParser())

        assertEquals(0, repository.writeCount)
    }

    @Test
    fun theHintGoesAwayAsSoonAsTheUserEditsAField() {
        val viewModel = draftViewModel(answeringParser())
        assertTrue(viewModel.showAiHint)

        viewModel.type(description = "Tomorrow morning, before work, oat milk")

        assertFalse(viewModel.showAiHint)
    }

    @Test
    fun anEmptyDescriptionFromTheAiLeavesTheFieldEmpty() {
        val viewModel = draftViewModel(answeringParser(TodoDraft("Buy milk", "")))

        assertEquals("Buy milk", viewModel.title)
        assertEquals("", viewModel.description)
        assertTrue(viewModel.showAiHint)
    }

    // ---- what the user typed while waiting -----------------------------------------------------

    @Test
    fun aTitleTypedBeforeTheAnswerIsNotOverwritten() {
        val (parser, gate) = waitingParser()
        val viewModel = draftViewModel(parser)
        viewModel.type(title = "My own title")

        gate.complete(aiDraft)

        assertEquals("My own title", viewModel.title)
        assertEquals("Tomorrow morning, before work", viewModel.description) // the other field is still filled
    }

    @Test
    fun aDescriptionTypedBeforeTheAnswerIsNotOverwritten() {
        val (parser, gate) = waitingParser()
        val viewModel = draftViewModel(parser)
        viewModel.type(description = "My own notes")

        gate.complete(aiDraft)

        assertEquals("Buy milk and bread", viewModel.title)
        assertEquals("My own notes", viewModel.description)
    }

    @Test
    fun ifTheUserTypedInBothFieldsTheAiChangesNothingAndThereIsNoHint() {
        val (parser, gate) = waitingParser()
        val viewModel = draftViewModel(parser)
        viewModel.type(title = "Mine", description = "Also mine")

        gate.complete(aiDraft)

        assertEquals("Mine", viewModel.title)
        assertEquals("Also mine", viewModel.description)
        assertFalse(viewModel.showAiHint)
        assertFalse(viewModel.uiState.value.isFilling)
    }

    // ---- the AI is unreachable -----------------------------------------------------------------

    @Test
    fun whenTheAiFailsTheUsersWordsBecomeTheTitleAndTheScreenSaysSo() {
        val viewModel = draftViewModel(FakeTodoDraftParser { throw IllegalStateException("HTTP 503") })

        assertEquals(transcript, viewModel.title)
        assertEquals("", viewModel.description)
        assertEquals(DetailError.AiUnavailable, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isFilling)
        assertFalse(viewModel.showAiHint, "the AI did not fill anything")
    }

    @Test
    fun theFallbackTitleIsCapped() {
        val long = "word ".repeat(60)

        val viewModel = draftViewModel(FakeTodoDraftParser { throw IllegalStateException() }, text = long)

        // 16 words fill the 80 characters exactly; the space the cut leaves at the end is dropped.
        assertEquals("word ".repeat(16).trimEnd(), viewModel.title)
        assertTrue(viewModel.title.length <= TodoDraft.MAX_TITLE_LENGTH)
    }

    @Test
    fun theFallbackDoesNotOverwriteATitleTheUserTypedWhileWaiting() {
        val gate = CompletableDeferred<TodoDraft>()
        val viewModel = draftViewModel(FakeTodoDraftParser { gate.await() })
        viewModel.type(title = "My own title")

        gate.completeExceptionally(IllegalStateException("timeout"))

        assertEquals("My own title", viewModel.title)
        assertEquals(DetailError.AiUnavailable, viewModel.uiState.value.error)
    }

    @Test
    fun theFallbackIsStillADraftThatNeedsSave() {
        val viewModel = draftViewModel(FakeTodoDraftParser { throw IllegalStateException() })

        viewModel.onBackClick()
        assertEquals(0, repository.writeCount)
        assertTrue(viewModel.uiState.value.showDiscardDraftConfirmation)

        viewModel.onDiscardDraftDismissed()
        viewModel.onSaveClick()
        assertEquals(listOf(FakeTodoRepository.Created(transcript, "")), repository.created)
    }

    // ---- approval: back saves nothing ----------------------------------------------------------

    @Test
    fun backOnAnAiDraftSavesNothingAndAsksToDiscard() {
        val viewModel = draftViewModel(answeringParser())

        viewModel.onBackClick()

        assertEquals(0, repository.writeCount)
        assertTrue(viewModel.uiState.value.showDiscardDraftConfirmation)
        assertFalse(viewModel.uiState.value.closeRequested, "back on a draft must not leave without an answer")
    }

    @Test
    fun confirmingTheDiscardLeavesAndStoresNothing() {
        val viewModel = draftViewModel(answeringParser())
        viewModel.onBackClick()

        viewModel.onDiscardDraftConfirmed()

        assertTrue(viewModel.uiState.value.closeRequested)
        assertFalse(viewModel.uiState.value.showDiscardDraftConfirmation)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun keepEditingClosesTheDialogAndStaysOnTheDraft() {
        val viewModel = draftViewModel(answeringParser())
        viewModel.onBackClick()

        viewModel.onDiscardDraftDismissed()

        assertFalse(viewModel.uiState.value.showDiscardDraftConfirmation)
        assertFalse(viewModel.uiState.value.closeRequested)
        assertEquals("Buy milk and bread", viewModel.title)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun backIsAskedAgainEveryTimeUntilTheUserSavesOrDiscards() {
        val viewModel = draftViewModel(answeringParser())

        viewModel.onBackClick()
        viewModel.onDiscardDraftDismissed()
        viewModel.onBackClick()

        assertTrue(viewModel.uiState.value.showDiscardDraftConfirmation)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun backWhileStillFillingAlsoAsksAndDiscardingDropsTheLateAnswer() {
        val (parser, gate) = waitingParser()
        val viewModel = draftViewModel(parser)

        viewModel.onBackClick()
        assertTrue(viewModel.uiState.value.showDiscardDraftConfirmation)
        viewModel.onDiscardDraftConfirmed()
        gate.complete(aiDraft)

        assertTrue(viewModel.uiState.value.closeRequested)
        assertEquals("", viewModel.title, "an answer that arrives after discarding has nowhere to go")
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun theAppGoingToTheBackgroundDoesNotStoreADraft() {
        // The safety net of `onScreenStopped` is for existing todos only; a draft needs an explicit Save.
        val viewModel = draftViewModel(answeringParser())

        viewModel.onScreenStopped()

        assertEquals(0, repository.writeCount)
    }

    // ---- approval: Save stores it --------------------------------------------------------------

    @Test
    fun saveStoresTheDraftAsTheAiWroteItAndCloses() {
        val viewModel = draftViewModel(answeringParser())

        viewModel.onSaveClick()

        assertEquals(
            listOf(FakeTodoRepository.Created("Buy milk and bread", "Tomorrow morning, before work")),
            repository.created,
        )
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun editingTheDraftAndThenSaveStoresTheEditedVersion() {
        val viewModel = draftViewModel(answeringParser())

        viewModel.type(title = "Buy oat milk", description = "Tomorrow, before work. Not the soy one.")
        viewModel.onSaveClick()

        assertEquals(
            listOf(FakeTodoRepository.Created("Buy oat milk", "Tomorrow, before work. Not the soy one.")),
            repository.created,
        )
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun saveStoresWhatIsInTheFieldsEvenIfTheDialogWasOpenedBefore() {
        val viewModel = draftViewModel(answeringParser())
        viewModel.onBackClick()
        viewModel.onDiscardDraftDismissed()
        viewModel.type(title = "Edited after keep editing")

        viewModel.onSaveClick()

        assertEquals(listOf(FakeTodoRepository.Created("Edited after keep editing", "Tomorrow morning, before work")), repository.created)
    }

    @Test
    fun savingTwiceStoresOnce() {
        val viewModel = draftViewModel(answeringParser())

        viewModel.onSaveClick()
        viewModel.onSaveClick()

        assertEquals(1, repository.created.size)
    }

    @Test
    fun saveDoesNothingWithoutATitle() {
        val viewModel = draftViewModel(answeringParser())
        viewModel.type(title = "   ")

        viewModel.onSaveClick()

        assertEquals(0, repository.writeCount)
        assertFalse(viewModel.uiState.value.closeRequested)
    }

    @Test
    fun saveWhileStillFillingStoresWhatWasTypedAndTheLateAnswerIsDropped() {
        val (parser, gate) = waitingParser()
        val viewModel = draftViewModel(parser)
        viewModel.type(title = "Typed while waiting")

        viewModel.onSaveClick()
        gate.complete(aiDraft)

        assertEquals(listOf(FakeTodoRepository.Created("Typed while waiting", "")), repository.created)
        assertEquals("", viewModel.description, "the late answer must not change a screen that is closing")
    }

    @Test
    fun aFailedSaveKeepsTheDraftOnScreenAndSaveCanBeRetried() = runTest {
        val viewModel = draftViewModel(answeringParser())
        repository.failWrites = true

        viewModel.onSaveClick()

        assertEquals(DetailError.SaveFailed, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.closeRequested)
        assertEquals("Buy milk and bread", viewModel.title)

        repository.failWrites = false
        viewModel.onErrorShown()
        viewModel.onSaveClick()

        assertEquals(1, repository.created.size)
        assertTrue(viewModel.uiState.value.closeRequested)
    }

    // ---- everything else stays as it was -------------------------------------------------------

    @Test
    fun aNormalNewTodoStillSavesOnBack() {
        val viewModel = TodoDetailViewModel(todoId = null, repository = repository)
        viewModel.type(title = "Water the plants")

        viewModel.onBackClick()

        assertFalse(viewModel.uiState.value.isDraft)
        assertEquals(listOf(FakeTodoRepository.Created("Water the plants", "")), repository.created)
        assertTrue(viewModel.uiState.value.closeRequested)
        assertFalse(viewModel.uiState.value.showDiscardDraftConfirmation)
    }

    @Test
    fun aNormalNewTodoHasNoAiHintAndDoesNotFill() {
        val viewModel = TodoDetailViewModel(todoId = null, repository = repository)

        assertFalse(viewModel.uiState.value.isFilling)
        assertFalse(viewModel.showAiHint)
    }

    @Test
    fun editingAnExistingTodoStillSavesOnBack() {
        val existing = com.example.ai.domain.Todo(id = "5", title = "Old", description = "Old d", isDone = false)
        val repository = FakeTodoRepository(listOf(existing))
        val viewModel = TodoDetailViewModel(todoId = "5", repository = repository)

        viewModel.type(title = "New")
        viewModel.onBackClick()

        assertFalse(viewModel.uiState.value.isDraft)
        assertEquals(listOf(FakeTodoRepository.Updated("5", "New", "Old d")), repository.updated)
    }

    @Test
    fun blankDictationIsNoDictation() {
        val parser = answeringParser()

        val viewModel = TodoDetailViewModel(null, repository, dictatedText = "   ", draftParser = parser)

        assertFalse(viewModel.uiState.value.isDraft)
        assertEquals(emptyList(), parser.parsed)
    }

    @Test
    fun aDraftIsAlwaysANewTodoWithAParser() {
        assertFailsWith<IllegalArgumentException> {
            TodoDetailViewModel(todoId = "5", repository = repository, dictatedText = "x", draftParser = answeringParser())
        }
        assertFailsWith<IllegalArgumentException> {
            TodoDetailViewModel(todoId = null, repository = repository, dictatedText = "x", draftParser = null)
        }
    }
}
