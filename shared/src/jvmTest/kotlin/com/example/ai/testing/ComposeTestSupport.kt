package com.example.ai.testing

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * [runComposeUiTest] with `Dispatchers.Main` and the composition sharing one virtual-time scheduler.
 *
 * Why Main must be replaced: a ViewModel's `viewModelScope` runs on `Dispatchers.Main`, which does not
 * exist on a plain JVM. Why they share a scheduler: then `mainClock.advanceTimeBy` also moves a `delay`
 * inside a ViewModel, which the "Syncing… only after 300 ms" test needs.
 *
 * Why Main is *unconfined* and the composition is *standard* (queued, like production): lifecycle code
 * (every navigation back stack entry) calls `runBlocking(Dispatchers.Main.immediate)` on the UI thread
 * to find out whether it is the main thread. With a queued Main dispatcher that work waits for the test
 * thread, which is itself blocked waiting for the UI thread: a deadlock that only shows up once a NavHost
 * is composed. An unconfined dispatcher runs it inline.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
fun runAppComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
    val scheduler = TestCoroutineScheduler()
    Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    try {
        runComposeUiTest(effectContext = StandardTestDispatcher(scheduler), block = block)
    } finally {
        Dispatchers.resetMain()
    }
}
