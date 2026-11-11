package com.example.ai.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Queues a [SyncWorker] that WorkManager runs as soon as a network is connected, even if the app
 * is in the background or its process has been killed meanwhile.
 */
internal class WorkManagerSyncFallback(context: Context) : BackgroundSyncFallback {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun scheduleSyncWhenOnline() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Unique + KEEP: the scheduler asks after every failed attempt, and a job that is already
        // waiting for the network must not be replaced or duplicated.
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "todo-sync"
    }
}
