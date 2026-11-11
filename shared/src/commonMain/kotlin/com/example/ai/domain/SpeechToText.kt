package com.example.ai.domain

import kotlinx.coroutines.flow.Flow

/**
 * Speech recognition. Only Android has a real one; elsewhere [isSupported] is `false` and the UI
 * asks for a typed sentence instead, so the rest of the flow is the same on every platform.
 */
interface SpeechToText {

    /** `false` when this platform cannot recognize speech (desktop, iOS). [listen] must not be called then. */
    val isSupported: Boolean

    /**
     * Starts listening and emits what was heard: any number of [SpeechEvent.Partial] while the user
     * speaks, then one [SpeechEvent.Final], after which the flow completes. A failure is thrown as
     * [SpeechRecognitionException]. Cancelling the collector cancels the listening, no result.
     */
    fun listen(): Flow<SpeechEvent>

    /** Stop listening but keep what was said: the running [listen] still delivers its [SpeechEvent.Final]. */
    fun stop()
}

sealed interface SpeechEvent {
    /** What has been recognized so far; replaced by the next one, not appended to. */
    data class Partial(val text: String) : SpeechEvent

    /** The recognized sentence. May be blank. */
    data class Final(val text: String) : SpeechEvent
}

enum class SpeechFailure {
    /** The user said nothing, or the recognizer could not make sense of it. Worth a retry. */
    NoSpeech,

    /** The microphone permission is missing. */
    PermissionDenied,

    /** No recognizer on this device, or none for the language. */
    Unavailable,

    /** Anything else (audio, network, the recognizer is busy). */
    Failed,
}

class SpeechRecognitionException(val reason: SpeechFailure, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: reason.name, cause)
