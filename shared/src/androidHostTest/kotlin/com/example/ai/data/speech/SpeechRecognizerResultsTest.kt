package com.example.ai.data.speech

import android.speech.SpeechRecognizer
import com.example.ai.domain.SpeechFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechRecognizerResultsTest {

    // ---- error codes ---------------------------------------------------------------------------

    @Test
    fun saidNothingUsableIsNoSpeech() {
        assertEquals(SpeechFailure.NoSpeech, failureFor(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(SpeechFailure.NoSpeech, failureFor(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun aMissingPermissionIsItsOwnFailure() {
        assertEquals(SpeechFailure.PermissionDenied, failureFor(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun aMissingLanguageIsUnavailable() {
        assertEquals(SpeechFailure.Unavailable, failureFor(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(SpeechFailure.Unavailable, failureFor(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    }

    @Test
    fun everythingElseIsAnOrdinaryFailure() {
        val others = listOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            0, -1, 9999, // a code a future Android adds
        )
        for (code in others) assertEquals(SpeechFailure.Failed, failureFor(code), "code=$code")
    }

    // ---- the recognized text -------------------------------------------------------------------

    @Test
    fun theFirstAlternativeWinsAndIsTrimmed() {
        assertEquals("buy milk", bestResult(listOf("  buy milk ", "by milk")))
    }

    @Test
    fun blankAlternativesAreSkipped() {
        assertEquals("buy milk", bestResult(listOf("", "   ", "buy milk")))
    }

    @Test
    fun noUsableAlternativeIsEmpty() {
        assertEquals("", bestResult(null))
        assertEquals("", bestResult(emptyList()))
        assertEquals("", bestResult(listOf(" ", "\n")))
    }

    @Test
    fun polishAndEmojiAreKeptAsTheyAre() {
        assertEquals("kup mleko 🥛 zażółć", bestResult(listOf("kup mleko 🥛 zażółć")))
    }

    // ---- on-device or default recognizer -------------------------------------------------------

    @Test
    fun beforeApi31ThereIsNoOnDeviceRecognizer() {
        assertFalse(shouldTryOnDevice(sdkInt = 29, onDeviceReportedAvailable = true))
        assertFalse(shouldTryOnDevice(sdkInt = 30, onDeviceReportedAvailable = false))
    }

    @Test
    fun api31And32TryItBecauseTheyCannotBeAskedFirst() {
        assertTrue(shouldTryOnDevice(sdkInt = 31, onDeviceReportedAvailable = false))
        assertTrue(shouldTryOnDevice(sdkInt = 32, onDeviceReportedAvailable = false))
    }

    @Test
    fun fromApi33ItIsUsedOnlyWhenThePlatformSaysItIsAvailable() {
        assertTrue(shouldTryOnDevice(sdkInt = 33, onDeviceReportedAvailable = true))
        assertTrue(shouldTryOnDevice(sdkInt = 37, onDeviceReportedAvailable = true))
        assertFalse(shouldTryOnDevice(sdkInt = 33, onDeviceReportedAvailable = false))
        assertFalse(shouldTryOnDevice(sdkInt = 37, onDeviceReportedAvailable = false))
    }
}
