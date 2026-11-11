package com.example.ai.ui.sync

import com.example.ai.domain.SyncOverview
import com.example.ai.testing.FakeSyncStatusRepository
import com.example.ai.testing.MainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Virtual time (`runTest` shares the scheduler of the test Main dispatcher, which `viewModelScope` uses). */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStatusViewModelTest : MainDispatcherTest() {

    private class Fixture(scope: TestScope, initial: SyncOverview = SyncOverview.Synced) {
        val repository = FakeSyncStatusRepository(initial)
        val viewModel = SyncStatusViewModel(repository)

        /** Everything the chip would have shown, in order. */
        val shown = mutableListOf<SyncOverview>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                viewModel.overview.collect { shown += it }
            }
        }
    }

    private fun TestScope.passTime(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun startsCalm() = runTest {
        val f = Fixture(this)

        assertEquals(listOf<SyncOverview>(SyncOverview.Synced), f.shown)
    }

    @Test
    fun aSyncOf100MillisecondsIsNeverShown() = runTest {
        val f = Fixture(this)

        f.repository.overview.value = SyncOverview.Syncing
        passTime(100)
        f.repository.overview.value = SyncOverview.Synced
        passTime(1_000)

        assertFalse(SyncOverview.Syncing in f.shown, "shown: ${f.shown}")
    }

    @Test
    fun aSyncOfOneSecondIsShownAfter300MillisecondsAndRemovedWhenItEnds() = runTest {
        val f = Fixture(this)

        f.repository.overview.value = SyncOverview.Syncing
        passTime(299)
        assertEquals(SyncOverview.Synced, f.viewModel.overview.value, "still inside the grace period")

        passTime(2)
        assertEquals(SyncOverview.Syncing, f.viewModel.overview.value)

        passTime(700)
        f.repository.overview.value = SyncOverview.Synced
        passTime(0)
        assertEquals(listOf(SyncOverview.Synced, SyncOverview.Syncing, SyncOverview.Synced), f.shown)
    }

    @Test
    fun offlineIsShownAtOnceWithoutAnyDelay() = runTest {
        val f = Fixture(this)

        f.repository.overview.value = SyncOverview.Offline(2)
        passTime(0)

        assertEquals(SyncOverview.Offline(2), f.viewModel.overview.value)
    }

    @Test
    fun syncingThatEndsOfflineNeverShowsSyncingIfItWasShort() = runTest {
        val f = Fixture(this)

        f.repository.overview.value = SyncOverview.Syncing
        passTime(50)
        f.repository.overview.value = SyncOverview.Offline(1)
        passTime(1_000)

        assertEquals(listOf(SyncOverview.Synced, SyncOverview.Offline(1)), f.shown)
    }

    @Test
    fun twoShortSyncsInARowDoNotAddUpToAVisibleOne() = runTest {
        val f = Fixture(this)

        repeat(3) {
            f.repository.overview.value = SyncOverview.Syncing
            passTime(200)
            f.repository.overview.value = SyncOverview.Synced
            passTime(200)
        }

        assertFalse(SyncOverview.Syncing in f.shown, "each wait starts over: ${f.shown}")
    }

    @Test
    fun theGraceIsExactlyThe300MillisecondsOfTheSpec() {
        assertEquals(300L, SyncStatusViewModel.SYNCING_GRACE.inWholeMilliseconds)
    }
}
