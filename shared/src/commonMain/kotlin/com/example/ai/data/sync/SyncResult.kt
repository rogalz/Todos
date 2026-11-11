package com.example.ai.data.sync

import com.example.ai.data.remote.ApiException

/** Outcome of one [SyncEngine.sync]. The engine never throws, it reports. */
sealed interface SyncResult {

    /** Everything local was uploaded and everything new from the server was applied. */
    data object Success : SyncResult

    /**
     * The sync did not complete.
     *
     * @property cause the error that stopped it, or, when the sync ran to the end but the server
     *   refused some rows, the first of those refusals.
     * @property rejectedIds rows the server permanently refused (a 4xx). They are still `PENDING`
     *   and were skipped so they could not block the rest; empty when the sync was stopped by a
     *   network or server error instead.
     */
    data class Failure(val cause: Throwable, val rejectedIds: List<String> = emptyList()) : SyncResult {

        /**
         * The sync ran to the end and the only problem left is rows the server refuses (it stopped for
         * no other reason). Trying again cannot change that, only a fixed row can, so callers that
         * would retry elsewhere (the background fallback) should not.
         */
        val onlyRejectionsLeft: Boolean
            get() = rejectedIds.isNotEmpty() && (cause as? ApiException)?.isRejection == true
    }
}
