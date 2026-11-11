package com.example.ai.testing

import com.example.ai.domain.SyncOverview
import com.example.ai.domain.SyncStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The sync overview as a value the test sets. */
class FakeSyncStatusRepository(initial: SyncOverview = SyncOverview.Synced) : SyncStatusRepository {
    val overview = MutableStateFlow(initial)

    override fun observeOverview(): Flow<SyncOverview> = overview
}
