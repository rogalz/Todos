package com.example.ai.ui.format

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * How long ago something happened, as a bucket. The words ("5 min ago") belong to the UI, which owns the
 * string resources; this type only decides *which* bucket, so the rules are testable without Compose.
 */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class MinutesAgo(val minutes: Int) : RelativeTime
    data class HoursAgo(val hours: Int) : RelativeTime
    data object Yesterday : RelativeTime
    data class OnDate(val date: LocalDate) : RelativeTime
}

/**
 * Buckets [then] relative to [now]:
 *
 *  1. under a minute: [RelativeTime.JustNow]. This includes a [then] in the future, which happens when a
 *     timestamp arrives from a server whose clock runs slightly ahead; "in 2 minutes" would be absurd here.
 *  2. under an hour: minutes. Decided by elapsed time only, so 23:50 → 00:10 is "20 min ago", not "yesterday".
 *  3. otherwise by calendar day in [zone]: the same day is hours, the day before is [RelativeTime.Yesterday],
 *     anything older is the date.
 *
 * The calendar day matters for 3 only: "yesterday" means what the user's wall clock says, not "24 hours ago",
 * and that depends on the time zone, which is why [zone] is a parameter (tests fix it) and not read here.
 */
fun relativeTime(then: Instant, now: Instant, zone: TimeZone): RelativeTime {
    val elapsed = now - then
    if (elapsed < 1.minutes) return RelativeTime.JustNow
    if (elapsed < 1.hours) return RelativeTime.MinutesAgo(elapsed.inWholeMinutes.toInt())

    val today = now.toLocalDateTime(zone).date
    val day = then.toLocalDateTime(zone).date
    return when (day) {
        today -> RelativeTime.HoursAgo(elapsed.inWholeHours.toInt())
        today.minus(1, DateTimeUnit.DAY) -> RelativeTime.Yesterday
        else -> RelativeTime.OnDate(day)
    }
}

private val DateFormat = LocalDate.Format {
    day(padding = Padding.NONE)
    char(' ')
    // English only, like every string in the app today; localised month names are a separate piece of work.
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    year()
}

/** "4 Nov 2026". */
fun formatDate(date: LocalDate): String = DateFormat.format(date)
