package com.example.ai.data.speech

import android.os.Build
import android.speech.SpeechRecognizer
import com.example.ai.domain.SpeechFailure

/*
 * The parts of the Android recognizer glue that need no Android runtime: they are plain functions of
 * ints and strings, so they are unit-tested on the host (the recognizer itself needs a device).
 */

/**
 * `SpeechRecognizer.ERROR_*` → what the app cares about. Several codes mean "the user said nothing
 * usable", which is a retry and not a fault; only a missing permission and a missing recognizer or
 * language get their own treatment.
 */
internal fun failureFor(errorCode: Int): SpeechFailure = when (errorCode) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> SpeechFailure.NoSpeech

    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.PermissionDenied

    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    -> SpeechFailure.Unavailable

    // Audio, network, server, client, busy, too many requests, ...: not the user's doing and not fixable by them.
    else -> SpeechFailure.Failed
}

/**
 * The recognizer offers alternatives, best first. The first one with text in it is used; blank
 * alternatives are skipped, and no usable alternative at all is an empty string (the caller treats
 * that as "nothing heard").
 */
internal fun bestResult(candidates: List<String>?): String =
    candidates.orEmpty().firstNotNullOfOrNull { it.trim().ifEmpty { null } }.orEmpty()

/**
 * Whether to try `createOnDeviceSpeechRecognizer` (API 31) before the default recognizer. Whether the
 * on-device recognizer exists can only be asked from API 33 (`isOnDeviceRecognitionAvailable`); on 31 and 32
 * it is tried and the caller falls back if the platform says it is not supported.
 */
internal fun shouldTryOnDevice(sdkInt: Int, onDeviceReportedAvailable: Boolean): Boolean = when {
    sdkInt < Build.VERSION_CODES.S -> false
    sdkInt < Build.VERSION_CODES.TIRAMISU -> true
    else -> onDeviceReportedAvailable
}
