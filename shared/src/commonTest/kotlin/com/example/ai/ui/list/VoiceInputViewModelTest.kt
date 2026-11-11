package com.example.ai.ui.list

import com.example.ai.data.NoMicPermissionNeeded
import com.example.ai.data.NoSpeechToText
import com.example.ai.domain.SpeechFailure
import com.example.ai.testing.FakeMicPermission
import com.example.ai.testing.FakeSpeechToText
import com.example.ai.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceInputViewModelTest : MainDispatcherTest() {

    private val speech = FakeSpeechToText()
    private val permission = FakeMicPermission()

    private fun viewModel() = VoiceInputViewModel(speech, permission)

    private val VoiceInputViewModel.state get() = uiState.value

    // ---- listening -----------------------------------------------------------------------------

    @Test
    fun startsIdleAndEnabled() {
        val state = viewModel().state

        assertEquals(VoicePhase.Idle, state.phase)
        assertTrue(state.micEnabled)
        assertNull(state.dictatedText)
    }

    @Test
    fun tappingTheMicStartsListeningAndShowsThePartialTranscriptLive() {
        val viewModel = viewModel()

        viewModel.onMicClick()
        assertEquals(VoicePhase.Listening, viewModel.state.phase)
        assertEquals("", viewModel.state.partialTranscript)

        speech.partial("buy")
        assertEquals("buy", viewModel.state.partialTranscript)
        speech.partial("buy milk and bread")
        assertEquals("buy milk and bread", viewModel.state.partialTranscript)
    }

    @Test
    fun theFinalResultHandsTheSentenceOnAndGoesIdle() {
        val viewModel = viewModel()
        viewModel.onMicClick()
        speech.partial("buy milk")

        speech.final("buy milk and bread")

        assertEquals("buy milk and bread", viewModel.state.dictatedText)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
        assertEquals("", viewModel.state.partialTranscript)
    }

    @Test
    fun theHandedOnSentenceIsTrimmedAndClearedOnceHandled() {
        val viewModel = viewModel()
        viewModel.onMicClick()
        speech.final("  kup mleko \n")

        assertEquals("kup mleko", viewModel.state.dictatedText)
        viewModel.onDictationHandled()
        assertNull(viewModel.state.dictatedText)
    }

    @Test
    fun tappingStopKeepsWhatWasSaid() {
        val viewModel = viewModel()
        viewModel.onMicClick()
        speech.partial("call the dentist")

        viewModel.onStopClick()

        assertEquals(1, speech.stopCount)
        assertEquals("call the dentist", viewModel.state.dictatedText)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
    }

    @Test
    fun cancelStopsListeningAndHandsNothingOn() {
        val viewModel = viewModel()
        viewModel.onMicClick()
        speech.partial("call the dentist")

        viewModel.onCancelClick()

        assertEquals(VoicePhase.Idle, viewModel.state.phase)
        assertEquals("", viewModel.state.partialTranscript)
        assertNull(viewModel.state.dictatedText)
        assertEquals(1, speech.cancelledCount)
        assertFalse(speech.isListening)
    }

    @Test
    fun aSecondTapWhileListeningDoesNotStartAnotherSession() {
        val viewModel = viewModel()

        viewModel.onMicClick()
        viewModel.onMicClick()

        assertEquals(1, speech.listenCount)
    }

    @Test
    fun canListenAgainAfterAFinishedSession() {
        val viewModel = viewModel()
        viewModel.onMicClick()
        speech.final("first")
        viewModel.onDictationHandled()

        viewModel.onMicClick()
        speech.final("second")

        assertEquals(2, speech.listenCount)
        assertEquals("second", viewModel.state.dictatedText)
    }

    @Test
    fun silenceOpensNoScreenAndSaysSo() {
        val viewModel = viewModel()
        viewModel.onMicClick()

        speech.final("   ")

        assertNull(viewModel.state.dictatedText)
        assertEquals(VoiceMessage.NothingHeard, viewModel.state.message)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
    }

    @Test
    fun stoppingWithNothingSaidIsSilenceToo() {
        val viewModel = viewModel()
        viewModel.onMicClick()

        viewModel.onStopClick()

        assertNull(viewModel.state.dictatedText)
        assertEquals(VoiceMessage.NothingHeard, viewModel.state.message)
    }

    // ---- recognizer failures -------------------------------------------------------------------

    @Test
    fun theRecognizerHearingNothingIsNothingHeard() {
        val viewModel = viewModel()
        viewModel.onMicClick()

        speech.fail(SpeechFailure.NoSpeech)

        assertEquals(VoiceMessage.NothingHeard, viewModel.state.message)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
        assertTrue(viewModel.state.micEnabled)
    }

    @Test
    fun otherRecognizerFailuresSaySpeechIsUnavailableAndKeepTheMicUsable() {
        for (reason in listOf(SpeechFailure.Unavailable, SpeechFailure.Failed)) {
            val viewModel = viewModel()
            viewModel.onMicClick()

            speech.fail(reason)

            assertEquals(VoiceMessage.SpeechUnavailable, viewModel.state.message, "$reason")
            assertEquals(VoicePhase.Idle, viewModel.state.phase)
            assertTrue(viewModel.state.micEnabled)
        }
    }

    @Test
    fun anUnexpectedExceptionIsHandledLikeAFailedRecognizer() {
        val viewModel = viewModel()
        viewModel.onMicClick()

        speech.failWith(IllegalStateException("recognizer crashed"))

        assertEquals(VoiceMessage.SpeechUnavailable, viewModel.state.message)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
    }

    @Test
    fun theMessageIsClearedOnceShown() {
        val viewModel = viewModel()
        viewModel.onMicClick()
        speech.fail(SpeechFailure.NoSpeech)

        viewModel.onMessageShown()

        assertNull(viewModel.state.message)
    }

    // ---- the microphone permission -------------------------------------------------------------

    @Test
    fun aGrantedPermissionIsNotAskedAgain() {
        val viewModel = viewModel()

        viewModel.onMicClick()

        assertEquals(0, permission.requests)
        assertEquals(VoicePhase.Listening, viewModel.state.phase)
    }

    @Test
    fun aMissingPermissionIsRequestedAndListeningStartsOnceGranted() {
        val permission = FakeMicPermission(granted = false, userGrants = true)
        val viewModel = VoiceInputViewModel(speech, permission)

        viewModel.onMicClick()

        assertEquals(1, permission.requests)
        assertEquals(VoicePhase.Listening, viewModel.state.phase)
    }

    @Test
    fun aRefusedPermissionIsExplainedOnceAndTheButtonStaysDisabled() {
        val permission = FakeMicPermission(granted = false, userGrants = false)
        val viewModel = VoiceInputViewModel(speech, permission)

        viewModel.onMicClick()

        assertFalse(viewModel.state.micEnabled)
        assertTrue(viewModel.state.showPermissionExplanation)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
        assertEquals(0, speech.listenCount)

        viewModel.onPermissionExplanationDismissed()
        assertFalse(viewModel.state.showPermissionExplanation)
        assertFalse(viewModel.state.micEnabled)

        // The disabled button ignores taps: no second request, no second explanation.
        viewModel.onMicClick()
        assertEquals(1, permission.requests)
        assertFalse(viewModel.state.showPermissionExplanation)
    }

    @Test
    fun theRecognizerReportingMissingPermissionDisablesTheButtonToo() {
        val viewModel = viewModel()
        viewModel.onMicClick()

        speech.fail(SpeechFailure.PermissionDenied)

        assertFalse(viewModel.state.micEnabled)
        assertTrue(viewModel.state.showPermissionExplanation)
        assertNull(viewModel.state.message)
    }

    @Test
    fun aSecondTapWhileThePermissionDialogIsOpenIsIgnored() {
        val answer = CompletableDeferred<Boolean>()
        val permission = object : com.example.ai.domain.MicPermission {
            var requests = 0
            override fun isGranted() = false
            override suspend fun request(): Boolean {
                requests++
                return answer.await()
            }
        }
        val viewModel = VoiceInputViewModel(speech, permission)

        viewModel.onMicClick()
        viewModel.onMicClick()
        answer.complete(true)

        assertEquals(1, permission.requests)
        assertEquals(1, speech.listenCount)
    }

    // ---- platforms without speech (desktop, iOS) -----------------------------------------------

    private fun typingViewModel() = VoiceInputViewModel(NoSpeechToText, NoMicPermissionNeeded)

    @Test
    fun withoutSpeechTheMicOpensTheTypedDialog() {
        val viewModel = typingViewModel()

        viewModel.onMicClick()

        assertEquals(VoicePhase.Typing, viewModel.state.phase)
    }

    @Test
    fun aTypedSentenceIsHandedOnTrimmed() {
        val viewModel = typingViewModel()
        viewModel.onMicClick()

        viewModel.onTypedTextSubmitted("  buy milk and bread tomorrow  ")

        assertEquals("buy milk and bread tomorrow", viewModel.state.dictatedText)
        assertEquals(VoicePhase.Idle, viewModel.state.phase)
    }

    @Test
    fun aBlankTypedSentenceIsIgnored() {
        val viewModel = typingViewModel()
        viewModel.onMicClick()

        viewModel.onTypedTextSubmitted("   ")

        assertNull(viewModel.state.dictatedText)
        assertEquals(VoicePhase.Typing, viewModel.state.phase)
    }

    @Test
    fun dismissingTheTypedDialogHandsNothingOn() {
        val viewModel = typingViewModel()
        viewModel.onMicClick()

        viewModel.onTypedDialogDismissed()

        assertEquals(VoicePhase.Idle, viewModel.state.phase)
        assertNull(viewModel.state.dictatedText)
    }
}
