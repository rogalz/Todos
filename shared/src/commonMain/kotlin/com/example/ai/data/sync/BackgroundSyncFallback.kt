package com.example.ai.data.sync

/**
 * A way to get a sync done when the app itself can no longer do it: the app's scheduler lives and
 * dies with the process, so changes made offline would wait until the app is opened again.
 *
 * Only Android provides one (WorkManager, which survives process death and waits for a network).
 * Desktop and iOS leave it out; the scheduler then simply has no fallback.
 */
fun interface BackgroundSyncFallback {

    /**
     * Asks the platform to run one sync as soon as a network is available. Called after every failed
     * sync, so it must be cheap and idempotent: asking twice must not queue two jobs.
     */
    fun scheduleSyncWhenOnline()
}
