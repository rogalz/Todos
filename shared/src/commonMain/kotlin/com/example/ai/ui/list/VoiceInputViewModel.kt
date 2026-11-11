package com.example.ai.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.domain.MicPermission
import com.example.ai.domain.SpeechEvent
import com.example.ai.domain.SpeechFailure
import com.example.ai.domain.SpeechRecognitionException
import com.example.ai.domain.SpeechToText
import com.example.ai.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the voice-input UI on the list screen is showing. */
enum class VoicePhase {
    Idle,

    /** The microphone is open: a listening indicator with the live transcript. */
    Listening,

    /** This platform cannot recognize speech: a text dialog asks for a typed sentence instead. */
    Typing,
}

enum class VoiceMessage { NothingHeard, SpeechUnavailable }

data class VoiceInputUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    /** What has been recognized so far while [VoicePhase.Listening]. */
    val partialTranscript: String = "",
    /** `false` once the user has refused the microphone: the button stays disabled for this session. */
    val micEnabled: Boolean = true,
    /** The one-time explanation after a refusal. */
    val showPermissionExplanation: Boolean = false,
    /** Shown once as a snackbar, then cleared by [VoiceInputViewModel.onMessageShown]. */
    val message: VoiceMessage? = null,
    /**
     * One-shot-by-state: a sentence is ready (spoken or typed) and the UI opens the new-task screen with
     * it, then calls [VoiceInputViewModel.onDictationHandled].
     */
    val dictatedText: String? = null,
)

/**
 * The mic button of the list screen: gets the permission, listens (or, where there is no speech
 * recognizer, asks for a typed sentence) and hands the sentence on. It stores and parses nothing:
 * the new-task screen does that, and the result is only ever a draft there.
 */
class VoiceInputViewModel(
    private val speech: SpeechToText,
    private val micPermission: MicPermission,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceInputUiState())
    val uiState: StateFlow<VoiceInputUiState> = _uiState.asStateFlow()

    /** Covers the permission request and the listening, so a second tap in between is ignored. */
    private var listenJob: Job? = null

    fun onMicClick() {
        val state = _uiState.value
        if (!state.micEnabled || state.phase != VoicePhase.Idle || listenJob?.isActive == true) return
        if (!speech.isSupported) {
            _uiState.update { it.copy(phase = VoicePhase.Typing) }
            return
        }
        listenJob = viewModelScope.launch { listen() }
    }

    private suspend fun listen() {
        if (!micPermission.isGranted() && !micPermission.request()) {
            denyMicrophone()
            return
        }
        _uiState.update { it.copy(phase = VoicePhase.Listening, partialTranscript = "") }

        var finalText: String? = null
        val result = runSuspendCatching {
            speech.listen().collect { event ->
                when (event) {
                    is SpeechEvent.Partial -> _uiState.update { it.copy(partialTranscript = event.text) }
                    is SpeechEvent.Final -> finalText = event.text
                }
            }
        }
        result.onFailure { failure ->
            if ((failure as? SpeechRecognitionException)?.reason == SpeechFailure.PermissionDenied) {
                denyMicrophone()
            } else {
                val message = if ((failure as? SpeechRecognitionException)?.reason == SpeechFailure.NoSpeech) {
                    VoiceMessage.NothingHeard
                } else {
                    VoiceMessage.SpeechUnavailable
                }
                _uiState.update { it.copy(phase = VoicePhase.Idle, partialTranscript = "", message = message) }
            }
            return
        }
        deliver(finalText)
    }

    private fun denyMicrophone() {
        _uiState.update {
            it.copy(
                phase = VoicePhase.Idle,
                partialTranscript = "",
                micEnabled = false,
                showPermissionExplanation = true,
            )
        }
    }

    /** A blank result (the user said nothing) opens no screen: there is nothing to turn into a task. */
    private fun deliver(text: String?) {
        val spoken = text?.trim().orEmpty()
        _uiState.update {
            if (spoken.isEmpty()) {
                it.copy(phase = VoicePhase.Idle, partialTranscript = "", message = VoiceMessage.NothingHeard)
            } else {
                it.copy(phase = VoicePhase.Idle, partialTranscript = "", dictatedText = spoken)
            }
        }
    }

    /** "Done": stop listening but keep what was said. The running [listen] then receives the final result. */
    fun onStopClick() {
        speech.stop()
    }

    /** "Cancel": stop listening and throw away what was heard. */
    fun onCancelClick() {
        listenJob?.cancel()
        listenJob = null
        _uiState.update { it.copy(phase = VoicePhase.Idle, partialTranscript = "") }
    }

    /** The typed sentence of platforms without speech recognition. */
    fun onTypedTextSubmitted(text: String) {
        val typed = text.trim()
        if (typed.isEmpty()) return
        _uiState.update { it.copy(phase = VoicePhase.Idle, dictatedText = typed) }
    }

    fun onTypedDialogDismissed() {
        _uiState.update { it.copy(phase = VoicePhase.Idle) }
    }

    fun onPermissionExplanationDismissed() {
        _uiState.update { it.copy(showPermissionExplanation = false) }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    fun onDictationHandled() {
        _uiState.update { it.copy(dictatedText = null) }
    }
}
