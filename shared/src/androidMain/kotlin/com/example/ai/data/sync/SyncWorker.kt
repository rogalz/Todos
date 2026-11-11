package com.example.ai.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs one sync from WorkManager, when the app's own scheduler is gone (process killed) or could not
 * reach the server and the network has come back since.
 *
 * WorkManager may start the process just for this, in which case `Application.onCreate` has already
 * started Koin (and with it the in-app scheduler, whose start-up sync is serialised with this one by
 * the engine's lock), so the engine is available through Koin.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {

    private val engine: SyncEngine by inject()

    override suspend fun doWork(): Result =
        when (val result = engine.sync()) {
            SyncResult.Success -> Result.success()
            // Only refused rows are left: another attempt would be refused again, so do not retry forever.
            is SyncResult.Failure -> if (result.onlyRejectionsLeft) Result.success() else Result.retry()
        }
}
