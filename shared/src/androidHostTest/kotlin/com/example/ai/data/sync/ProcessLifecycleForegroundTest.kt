package com.example.ai.data.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android foreground signal, driven by a plain [LifecycleRegistry] standing in for
 * `ProcessLifecycleOwner` (whose own delay for ON_STOP is the platform's business, not ours).
 */
class ProcessLifecycleForegroundTest {

    private class FakeProcess : LifecycleOwner {
        // "Unsafe": the real registry insists on the main thread, which a host test does not have.
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun startsAsBackgroundWhenNothingIsVisibleYet() {
        // This is also what keeps a process that WorkManager started in the background from opening a stream.
        val process = FakeProcess()

        assertFalse(ProcessLifecycleForeground(process.lifecycle).isForeground.value)
    }

    @Test
    fun isForegroundFromTheFirstStartUntilTheLastStop() {
        val process = FakeProcess()
        val foreground = ProcessLifecycleForeground(process.lifecycle)

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        assertFalse(foreground.isForeground.value, "created is not visible yet")

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(foreground.isForeground.value)

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertTrue(foreground.isForeground.value, "resume and pause are still visible")

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(foreground.isForeground.value)

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(foreground.isForeground.value, "and it can come back")
    }

    @Test
    fun anAlreadyVisibleProcessIsForegroundFromTheStart() {
        val process = FakeProcess()
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        val foreground = ProcessLifecycleForeground(process.lifecycle)

        assertTrue(foreground.isForeground.value, "created late, e.g. by lazy injection after the first activity")
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(false, foreground.isForeground.value)
    }
}
