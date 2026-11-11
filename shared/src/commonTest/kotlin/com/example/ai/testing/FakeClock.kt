package com.example.ai.testing

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A clock that only moves when the test says so. Tests advance it between writes, so a timestamp
 * that was not refreshed by a write shows up as a stale value instead of passing by accident.
 */
class FakeClock(startMillis: Long = 1_000_000L) : Clock {
    var currentMillis: Long = startMillis
        private set

    override fun now(): Instant = Instant.fromEpochMilliseconds(currentMillis)

    fun advanceBy(millis: Long) {
        currentMillis += millis
    }
}
