package com.example.ai.ui.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RelativeTimeTest {

    private val utc = TimeZone.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, zone: TimeZone = utc): Instant =
        LocalDateTime(year, month, day, hour, minute, second).toInstant(zone)

    /** Noon on 11 Nov 2026 unless a test says otherwise: far from midnight, so only the bucket matters. */
    private val now = at(2026, 11, 11, 12)

    private fun bucket(ago: kotlin.time.Duration) = relativeTime(now - ago, now, utc)

    // ---- elapsed-time buckets --------------------------------------------------------------

    @Test
    fun nothingElapsedIsJustNow() = assertEquals(RelativeTime.JustNow, bucket(0.seconds))

    @Test
    fun underAMinuteIsJustNow() = assertEquals(RelativeTime.JustNow, bucket(59.seconds))

    @Test
    fun exactlyAMinuteIsOneMinute() = assertEquals(RelativeTime.MinutesAgo(1), bucket(60.seconds))

    @Test
    fun fiveMinutes() = assertEquals(RelativeTime.MinutesAgo(5), bucket(5.minutes))

    @Test
    fun minutesAreRoundedDownNotUp() = assertEquals(RelativeTime.MinutesAgo(5), bucket(5.minutes + 59.seconds))

    @Test
    fun theLastSecondBeforeAnHourIsStillMinutes() = assertEquals(RelativeTime.MinutesAgo(59), bucket(59.minutes + 59.seconds))

    @Test
    fun exactlyAnHourIsOneHour() = assertEquals(RelativeTime.HoursAgo(1), bucket(60.minutes))

    @Test
    fun hoursAreRoundedDownNotUp() = assertEquals(RelativeTime.HoursAgo(2), bucket(2.hours + 59.minutes))

    @Test
    fun elevenHoursEarlierTheSameDayIsHours() = assertEquals(RelativeTime.HoursAgo(12), bucket(12.hours)) // 00:00 today

    // ---- calendar days -----------------------------------------------------------------------

    @Test
    fun theDayBeforeIsYesterdayWhateverTheHour() {
        assertEquals(RelativeTime.Yesterday, relativeTime(at(2026, 11, 10, 0, 0), now, utc), "start of yesterday")
        assertEquals(RelativeTime.Yesterday, relativeTime(at(2026, 11, 10, 23, 59), now, utc), "end of yesterday")
    }

    @Test
    fun yesterdayEveningSeenFromEarlyMorningIsYesterdayNotHours() {
        // 23:00 -> 01:00 is two hours elapsed but it is the calendar day before: the wall clock says "yesterday".
        assertEquals(RelativeTime.Yesterday, relativeTime(at(2026, 11, 10, 23), at(2026, 11, 11, 1), utc))
    }

    @Test
    fun underAnHourAcrossMidnightIsMinutesNotYesterday() {
        assertEquals(RelativeTime.MinutesAgo(20), relativeTime(at(2026, 11, 10, 23, 50), at(2026, 11, 11, 0, 10), utc))
    }

    @Test
    fun twoDaysAgoIsTheDate() {
        assertEquals(RelativeTime.OnDate(LocalDate(2026, 11, 9)), relativeTime(at(2026, 11, 9, 23, 59), now, utc))
    }

    @Test
    fun aMonthAndAYearAgoAreDatesToo() {
        assertEquals(RelativeTime.OnDate(LocalDate(2026, 10, 4)), relativeTime(at(2026, 10, 4, 9), now, utc))
        assertEquals(RelativeTime.OnDate(LocalDate(2025, 11, 11)), relativeTime(at(2025, 11, 11, 12), now, utc))
    }

    @Test
    fun yesterdayAcrossAMonthBoundary() {
        assertEquals(RelativeTime.Yesterday, relativeTime(at(2026, 10, 31, 15), at(2026, 11, 1, 9), utc))
    }

    @Test
    fun yesterdayAcrossALeapDay() {
        assertEquals(RelativeTime.Yesterday, relativeTime(at(2028, 2, 29, 15), at(2028, 3, 1, 9), utc))
    }

    // ---- unusual input ---------------------------------------------------------------------

    @Test
    fun aTimestampInTheFutureIsJustNow() {
        // A server clock that runs ahead must not produce "in 3 hours" or a negative number.
        assertEquals(RelativeTime.JustNow, relativeTime(now + 3.hours, now, utc))
        assertEquals(RelativeTime.JustNow, relativeTime(now + 30.seconds, now, utc))
    }

    // ---- time zones ------------------------------------------------------------------------

    @Test
    fun theCalendarDayIsTheUsersNotUtc() {
        val then = at(2026, 11, 10, 23, 30) // UTC
        val laterNow = at(2026, 11, 11, 10, 0) // UTC

        // In UTC the change was yesterday evening ...
        assertEquals(RelativeTime.Yesterday, relativeTime(then, laterNow, utc))
        // ... but in Auckland (UTC+13 in November) both instants fall on the same local day (11 Nov).
        val auckland = TimeZone.of("Pacific/Auckland")
        assertEquals(RelativeTime.HoursAgo(10), relativeTime(then, laterNow, auckland))
    }

    // ---- date text -------------------------------------------------------------------------

    @Test
    fun datesAreFormattedWithAShortMonthAndNoPaddingOnTheDay() {
        assertEquals("4 Nov 2026", formatDate(LocalDate(2026, 11, 4)))
        assertEquals("31 Dec 2025", formatDate(LocalDate(2025, 12, 31)))
        assertEquals("1 Jan 2027", formatDate(LocalDate(2027, 1, 1)))
    }
}
