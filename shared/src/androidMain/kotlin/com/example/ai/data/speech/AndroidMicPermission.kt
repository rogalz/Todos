package com.example.ai.data.speech

import com.example.ai.domain.MicPermission
import kotlinx.coroutines.CompletableDeferred

/**
 * The `RECORD_AUDIO` permission on Android.
 *
 * Showing the system dialog needs an `ActivityResultLauncher`, which only an activity can register.
 * `MainActivity` does that and hands this object two things: [attach] (how to open the dialog) and, when the
 * user answers, [onResult]. That keeps `shared` free of an activity dependency.
 *
 * Everything here runs on the main thread (ViewModel scope, activity callbacks).
 *
 * @param hasPermission asks the system whether the permission is already granted; a parameter so a test
 *   does not need a `Context`.
 */
class AndroidMicPermission(private val hasPermission: () -> Boolean) : MicPermission {

    private var launchRequest: (() -> Unit)? = null

    /** The dialog that is open right now, so a second request shares its answer instead of opening another. */
    private var pending: CompletableDeferred<Boolean>? = null

    override fun isGranted(): Boolean = hasPermission()

    /** `false` also when there is no activity to show the dialog (the app is not in front): treated like a refusal. */
    override suspend fun request(): Boolean {
        if (hasPermission()) return true
        pending?.let { return it.await() }
        val launch = launchRequest ?: return false

        val answer = CompletableDeferred<Boolean>()
        pending = answer
        try {
            launch()
            return answer.await()
        } finally {
            // Also when the caller was cancelled: the system dialog may still be open, and its late
            // answer then finds nothing pending and is ignored (the next tap asks again).
            if (pending === answer) pending = null
        }
    }

    /** Called by `MainActivity` when it is created, with the call that opens the system dialog. */
    fun attach(launchRequest: () -> Unit) {
        this.launchRequest = launchRequest
    }

    /**
     * Called by `MainActivity` when it is destroyed. A dialog that is open is *not* cancelled: on a screen
     * rotation the new activity receives the answer through its own launcher, and it completes [pending].
     */
    fun detach() {
        launchRequest = null
    }

    /** The user's answer, from the activity's launcher callback. Ignored when nothing is waiting for it. */
    fun onResult(granted: Boolean) {
        pending?.complete(granted)
    }
}
