package com.example.ai.ui.detail

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.detail_edited_hours_ago
import ai.shared.generated.resources.detail_edited_just_now
import ai.shared.generated.resources.detail_edited_minutes_ago
import ai.shared.generated.resources.detail_edited_on
import ai.shared.generated.resources.detail_edited_yesterday
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.ai.ui.format.RelativeTime
import com.example.ai.ui.format.formatDate
import com.example.ai.ui.format.relativeTime
import com.example.ai.ui.theme.AppTheme
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * "Edited 5 min ago". [now] is a parameter (the screen takes it from the injected clock) so the text
 * is deterministic in tests; the bucket rules live in [relativeTime].
 */
@Composable
internal fun EditedFooter(
    updatedAt: Instant,
    now: Instant,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val text = when (val bucket = relativeTime(then = updatedAt, now = now, zone = zone)) {
        RelativeTime.JustNow -> stringResource(Res.string.detail_edited_just_now)
        is RelativeTime.MinutesAgo -> stringResource(Res.string.detail_edited_minutes_ago, bucket.minutes)
        is RelativeTime.HoursAgo -> stringResource(Res.string.detail_edited_hours_ago, bucket.hours)
        RelativeTime.Yesterday -> stringResource(Res.string.detail_edited_yesterday)
        is RelativeTime.OnDate -> stringResource(Res.string.detail_edited_on, formatDate(bucket.date))
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val PreviewNow = Instant.parse("2026-11-11T12:00:00Z")

@Preview(name = "Just now")
@Composable
private fun JustNowPreview() = AppTheme(darkTheme = false) { EditedFooter(PreviewNow, PreviewNow, zone = TimeZone.UTC) }

@Preview(name = "Minutes")
@Composable
private fun MinutesPreview() = AppTheme(darkTheme = false) { EditedFooter(PreviewNow - 5.minutes, PreviewNow, zone = TimeZone.UTC) }

@Preview(name = "Hours")
@Composable
private fun HoursPreview() = AppTheme(darkTheme = false) { EditedFooter(PreviewNow - 3.hours, PreviewNow, zone = TimeZone.UTC) }

@Preview(name = "Yesterday")
@Composable
private fun YesterdayPreview() = AppTheme(darkTheme = false) { EditedFooter(PreviewNow - 1.days, PreviewNow, zone = TimeZone.UTC) }

@Preview(name = "Date, dark")
@Composable
private fun DateDarkPreview() = AppTheme(darkTheme = true) {
    EditedFooter(PreviewNow - 20.days, PreviewNow, zone = TimeZone.UTC)
}
