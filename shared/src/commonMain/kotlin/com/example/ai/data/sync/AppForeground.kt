package com.example.ai.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user can see the app. The live-updates stream is held open only while this is `true`:
 * in the background an open connection costs battery and data and the OS may kill it anyway, and the
 * `hello` on the next connect catches up on everything missed.
 *
 * An interface around the [StateFlow] (not a bare `StateFlow<Boolean>` binding) because Koin keys a
 * binding on the raw class, which would make this one indistinguishable from any other `StateFlow`.
 */
interface AppForeground {
    val isForeground: StateFlow<Boolean>
}

/**
 * Desktop and iOS: no foreground signal yet, so the stream stays open for as long as the process
 * lives. (Window focus on desktop and the scene phase on iOS are the natural follow-ups.)
 */
object AlwaysForeground : AppForeground {
    override val isForeground: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
