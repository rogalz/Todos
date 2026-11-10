package com.example.ai.ui.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but never swallows [CancellationException], so structured concurrency keeps
 * working when a scope is cancelled while the block is suspended.
 */
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
