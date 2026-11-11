package com.example.ai.data.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.ai.domain.SpeechEvent
import com.example.ai.domain.SpeechFailure
import com.example.ai.domain.SpeechRecognitionException
import com.example.ai.domain.SpeechToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * Speech recognition with Android's [SpeechRecognizer], preferring the on-device recognizer (API 31+) and
 * falling back to the system's default one. The language is the device's.
 *
 * Deliberately thin: everything that can be decided without a device lives in `SpeechRecognizerResults.kt`
 * and is unit-tested; this class only wires the callbacks to a [Flow], so it is checked by hand (the manual
 * check in `docs/specs/06-voice-llm.md`).
 *
 * `SpeechRecognizer` must be created and used on the main thread, hence `flowOn(Dispatchers.Main.immediate)`.
 */
class AndroidSpeechToText(private val context: Context) : SpeechToText {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The running session, for [stop]. Only touched on the main thread. */
    private var active: SpeechRecognizer? = null

    /**
     * `false` when the device has no recognizer at all (an emulator image without the Google app, for
     * example): the UI then asks for typed text instead of failing after the tap.
     */
    override val isSupported: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        val recognizer = createRecognizer()
        active = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                val text = bestResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                if (text.isNotEmpty()) trySend(SpeechEvent.Partial(text))
            }

            override fun onResults(results: Bundle?) {
                trySend(SpeechEvent.Final(bestResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))))
                close()
            }

            override fun onError(error: Int) {
                close(SpeechRecognitionException(failureFor(error), "SpeechRecognizer error $error"))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            recognizer.startListening(recognizerIntent())
        } catch (e: RuntimeException) {
            close(SpeechRecognitionException(SpeechFailure.Failed, "could not start listening", e))
        }
        // Runs on cancellation (the user pressed Cancel, the screen left) and after a result or an error alike.
        awaitClose {
            active = null
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)

    override fun stop() {
        // `stopListening` makes the recognizer deliver what it heard so far through `onResults`.
        mainHandler.post { active?.stopListening() }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (shouldTryOnDevice(Build.VERSION.SDK_INT, onDeviceAvailable)) {
            try {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (_: UnsupportedOperationException) {
                // API 31 and 32 cannot be asked beforehand; no on-device recognizer means the default one.
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private companion object {
        /** A few alternatives, so a blank best one can be skipped (see `bestResult`). */
        const val MAX_ALTERNATIVES = 3
    }
}
