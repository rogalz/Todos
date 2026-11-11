package com.example.ai.ui.nav

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.ai.domain.MicPermission
import com.example.ai.domain.SpeechFailure
import com.example.ai.domain.SpeechToText
import com.example.ai.domain.TodoDraft
import com.example.ai.testing.FakeAppEnvironment
import com.example.ai.testing.FakeMicPermission
import com.example.ai.testing.FakeSpeechToText
import com.example.ai.testing.FakeTodoDraftParser
import com.example.ai.testing.FakeTodoRepository
import com.example.ai.testing.runAppComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Voice to todo, through the whole UI (the real `AppNavHost` and ViewModels, fake recognizer and fake AI):
 * a dictated task is a draft that is stored only by an explicit Save.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceFlowTest {

    private val transcript = "uh, buy milk and bread, tomorrow morning before work"
    private val aiDraft = TodoDraft("Buy milk and bread", "Tomorrow morning, before work")

    private val repository = FakeTodoRepository()
    private val speech = FakeSpeechToText()

    private fun ComposeUiTest.showApp(
        speechToText: SpeechToText = speech,
        micPermission: MicPermission = FakeMicPermission(),
        parser: FakeTodoDraftParser = FakeTodoDraftParser { aiDraft },
    ) {
        setContent {
            FakeAppEnvironment(
                repository = repository,
                speechToText = speechToText,
                micPermission = micPermission,
                draftParser = parser,
            ) { AppNavHost() }
        }
        waitForIdle()
    }

    /** Taps the mic, "says" the sentence and lands on the new-task screen. */
    private fun ComposeUiTest.dictate(sentence: String = transcript) {
        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()
        assertTrue(speech.isListening)
        speech.partial(sentence)
        speech.final(sentence)
        waitForIdle()
    }

    // ---- the happy path ------------------------------------------------------------------------

    @Test
    fun micThenTranscriptThenAPrefilledDraftThenSaveThenItIsInTheList() = runAppComposeUiTest {
        showApp()

        dictate()

        onNodeWithText("New task").assertExists()
        onNodeWithText("Buy milk and bread").assertExists() // the title field, filled by the AI
        onNodeWithText("Tomorrow morning, before work").assertExists()
        onNodeWithText("Filled by AI, check before saving").assertExists()
        assertEquals(0, repository.writeCount, "a draft is not stored before Save")

        onNodeWithText("Save").performClick()
        waitForIdle()

        onNodeWithText("No tasks yet").assertDoesNotExist()
        onNodeWithText("Buy milk and bread").assertExists() // the card in the list
        assertEquals(
            listOf(FakeTodoRepository.Created("Buy milk and bread", "Tomorrow morning, before work")),
            repository.created,
        )
    }

    @Test
    fun theListeningDialogShowsTheLiveTranscript() = runAppComposeUiTest {
        showApp()

        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()
        onNodeWithText("Listening…").assertExists()
        onNodeWithText("Say your task").assertExists()

        speech.partial("buy milk and")
        waitForIdle()
        onNodeWithText("buy milk and").assertExists()
    }

    @Test
    fun editingTheDraftAndThenSaveStoresTheEditedVersion() = runAppComposeUiTest {
        showApp()
        dictate()

        onNodeWithText("Buy milk and bread").performTextReplacement("Buy oat milk")
        onNodeWithText("Filled by AI, check before saving").assertDoesNotExist() // the user edited it
        onNodeWithText("Save").performClick()
        waitForIdle()

        onNodeWithText("Buy oat milk").assertExists()
        assertEquals(
            listOf(FakeTodoRepository.Created("Buy oat milk", "Tomorrow morning, before work")),
            repository.created,
        )
    }

    // ---- approval: back never saves a draft ----------------------------------------------------

    @Test
    fun backOnTheDraftAsksToDiscardAndDiscardStoresNothing() = runAppComposeUiTest {
        showApp()
        dictate()

        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        onNodeWithText("Discard draft?").assertExists()
        assertEquals(0, repository.writeCount)

        onNodeWithText("Discard").performClick()
        waitForIdle()

        onNodeWithText("No tasks yet").assertExists()
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun keepEditingStaysOnTheDraftAndSaveStillWorks() = runAppComposeUiTest {
        showApp()
        dictate()

        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        onNodeWithText("Keep editing").performClick()
        waitForIdle()

        onNodeWithText("Discard draft?").assertDoesNotExist()
        onNodeWithText("New task").assertExists()
        assertEquals(0, repository.writeCount)

        onNodeWithText("Save").performClick()
        waitForIdle()
        assertEquals(1, repository.created.size)
    }

    // ---- while the AI is still working / not reachable -----------------------------------------

    @Test
    fun theFieldsSayFillingUntilTheAiAnswers() = runAppComposeUiTest {
        val gate = CompletableDeferred<TodoDraft>()
        showApp(parser = FakeTodoDraftParser { gate.await() })

        dictate()
        onAllNodesWithText("Filling…").assertCountEquals(2) // title and description
        onNodeWithText("Filled by AI, check before saving").assertDoesNotExist()

        gate.complete(aiDraft)
        waitForIdle()

        onAllNodesWithText("Filling…").assertCountEquals(0)
        onNodeWithText("Buy milk and bread").assertExists()
        onNodeWithText("Filled by AI, check before saving").assertExists()
    }

    @Test
    fun whenTheAiIsUnreachableTheUsersWordsFillTheTitleAndASnackbarSaysSo() = runAppComposeUiTest {
        showApp(parser = FakeTodoDraftParser { throw IllegalStateException("offline") })

        dictate()

        onNodeWithText(transcript).assertExists() // the title field
        onNodeWithText("Couldn't reach the AI, used your words").assertExists()
        onNodeWithText("Filled by AI, check before saving").assertDoesNotExist()
        assertEquals(0, repository.writeCount)

        // The fallback is a draft like any other: it needs Save, and back asks.
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        onNodeWithText("Discard draft?").assertExists()
    }

    // ---- the mic button itself -----------------------------------------------------------------

    @Test
    fun cancelWhileListeningOpensNothing() = runAppComposeUiTest {
        showApp()
        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()

        onNodeWithText("Cancel").performClick()
        waitForIdle()

        onNodeWithText("Listening…").assertDoesNotExist()
        onNodeWithText("No tasks yet").assertExists()
        assertEquals(1, speech.cancelledCount)
    }

    @Test
    fun doneWhileListeningKeepsWhatWasSaid() = runAppComposeUiTest {
        showApp()
        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()
        speech.partial("call the dentist")

        onNodeWithText("Done").performClick()
        waitForIdle()

        onNodeWithText("New task").assertExists()
        assertEquals(1, speech.stopCount)
    }

    @Test
    fun nothingSaidShowsASnackbarAndOpensNoScreen() = runAppComposeUiTest {
        showApp()
        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()

        speech.fail(SpeechFailure.NoSpeech)
        waitForIdle()

        onNodeWithText("Didn't catch that. Please try again.").assertExists()
        onNodeWithText("No tasks yet").assertExists()
    }

    @Test
    fun aRefusedMicrophoneIsExplainedOnceAndTheButtonStaysDisabled() = runAppComposeUiTest {
        showApp(micPermission = FakeMicPermission(granted = false, userGrants = false))

        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()
        onNodeWithText("Microphone access needed").assertExists()
        onNodeWithText("OK").performClick()
        waitForIdle()

        onNodeWithText("Microphone access needed").assertDoesNotExist()
        onNodeWithContentDescription("Dictate a task (microphone access denied)").assertIsNotEnabled()
    }

    // ---- platforms without speech (desktop, iOS) -----------------------------------------------

    @Test
    fun withoutSpeechTheSameButtonAsksForATypedSentenceAndTheRestIsIdentical() = runAppComposeUiTest {
        val parser = FakeTodoDraftParser { aiDraft }
        showApp(speechToText = com.example.ai.data.NoSpeechToText, parser = parser)

        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()
        onNodeWithText("Describe your task").assertExists()
        onNodeWithText("What do you need to do?").performTextInput(transcript)
        onNodeWithText("Continue").performClick()
        waitForIdle()

        assertEquals(listOf(transcript), parser.parsed)
        onNodeWithText("Buy milk and bread").assertExists()
        onNodeWithText("Filled by AI, check before saving").assertExists()

        onNodeWithText("Save").performClick()
        waitForIdle()
        assertEquals(listOf(FakeTodoRepository.Created("Buy milk and bread", "Tomorrow morning, before work")), repository.created)
    }

    @Test
    fun theTypedDialogCannotContinueWithoutText() = runAppComposeUiTest {
        showApp(speechToText = com.example.ai.data.NoSpeechToText)

        onNodeWithContentDescription("Dictate a task").performClick()
        waitForIdle()

        onNodeWithText("Continue").assertIsNotEnabled()
    }

    // ---- ordinary create and edit are untouched ------------------------------------------------

    @Test
    fun theNewTaskButtonStillCreatesWithSaveOnBackAndHasNoSaveAction() = runAppComposeUiTest {
        showApp()

        onNodeWithContentDescription("New task").performClick()
        waitForIdle()
        onNodeWithText("Save").assertDoesNotExist()
        onNodeWithText("Title").performTextInput("Water the plants")
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Discard draft?").assertDoesNotExist()
        onNodeWithText("Water the plants").assertExists()
        assertEquals(listOf(FakeTodoRepository.Created("Water the plants", "")), repository.created)
    }
}
