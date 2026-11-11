package com.example.ai.data

import com.example.ai.domain.MicPermission
import com.example.ai.domain.SpeechEvent
import com.example.ai.domain.SpeechToText
import kotlinx.coroutines.flow.Flow

/** Desktop and iOS: no speech recognizer. The UI sees [isSupported] `false` and asks for a typed sentence. */
object NoSpeechToText : SpeechToText {
    override val isSupported: Boolean = false

    override fun listen(): Flow<SpeechEvent> = error("speech recognition is not supported on this platform")

    override fun stop() = Unit
}

/** Platforms with no microphone permission to ask for. */
object NoMicPermissionNeeded : MicPermission {
    override fun isGranted(): Boolean = true

    override suspend fun request(): Boolean = true
}
