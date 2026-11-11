package com.example.ai.data.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground = the process lifecycle is STARTED, i.e. at least one activity is visible.
 *
 * [ProcessLifecycleOwner] reports ON_STOP only ~700 ms after the last activity stopped, so a screen
 * rotation (one activity replaced by the next) never looks like "went to the background" and does
 * not drop and re-open the connection.
 *
 * It starts out `false` when nothing is visible yet, which is also what keeps a process that
 * WorkManager started only to run a sync from opening a stream.
 *
 * @param lifecycle a parameter so a test can drive it with a `LifecycleRegistry`.
 */
class ProcessLifecycleForeground(
    lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : AppForeground {

    private val state = MutableStateFlow(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    override val isForeground: StateFlow<Boolean> = state.asStateFlow()

    init {
        // `addObserver` has to run on the main thread. Koin starts from `Application.onCreate`, which does.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> state.value = true
                    Lifecycle.Event.ON_STOP -> state.value = false
                    else -> Unit
                }
            },
        )
    }
}
