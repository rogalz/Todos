package com.example.ai.ui.sync

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.sync_status_offline
import ai.shared.generated.resources.sync_status_offline_description
import ai.shared.generated.resources.sync_status_offline_waiting
import ai.shared.generated.resources.sync_status_offline_waiting_description
import ai.shared.generated.resources.sync_status_synced
import ai.shared.generated.resources.sync_status_synced_description
import ai.shared.generated.resources.sync_status_syncing
import ai.shared.generated.resources.sync_status_syncing_description
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ai.domain.SyncOverview
import com.example.ai.ui.icons.AppIcons
import com.example.ai.ui.theme.AppTheme
import com.example.ai.ui.theme.Spacing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Small pill in the list's top bar that says whether the changes reached the server. State is never
 * carried by colour alone: every variant has its own icon *and* its own text.
 *
 * Moving between states crossfades. Going from `Offline · 2 waiting` to `Offline · 3 waiting` is the
 * same state with a new number, so it updates in place instead of fading (`contentKey` is the kind).
 */
@Composable
fun SyncStatusChip(
    overview: SyncOverview,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = overview,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(CROSSFADE_MILLIS)) togetherWith fadeOut(tween(CROSSFADE_MILLIS)) },
        label = "syncStatusChip",
        contentKey = { it::class },
    ) { shown ->
        ChipContent(shown)
    }
}

@Composable
private fun ChipContent(overview: SyncOverview) {
    val colors = MaterialTheme.colorScheme
    val containerColor: Color
    val contentColor: Color
    val icon: Painter
    val label: String
    val description: String
    when (overview) {
        SyncOverview.Synced -> {
            containerColor = colors.tertiaryContainer
            contentColor = colors.onTertiaryContainer
            icon = AppIcons.CloudDone
            label = stringResource(Res.string.sync_status_synced)
            description = stringResource(Res.string.sync_status_synced_description)
        }

        SyncOverview.Syncing -> {
            containerColor = colors.surfaceContainerHigh
            contentColor = colors.onSurfaceVariant
            icon = rememberVectorPainter(Icons.Default.Refresh)
            label = stringResource(Res.string.sync_status_syncing)
            description = stringResource(Res.string.sync_status_syncing_description)
        }

        is SyncOverview.Offline -> {
            containerColor = colors.secondaryContainer
            contentColor = colors.onSecondaryContainer
            icon = AppIcons.CloudOff
            // Nothing waiting: "Offline · 0 waiting" would read oddly, so the number is left out.
            label = if (overview.pendingCount > 0) {
                stringResource(Res.string.sync_status_offline_waiting, overview.pendingCount)
            } else {
                stringResource(Res.string.sync_status_offline)
            }
            description = if (overview.pendingCount > 0) {
                pluralStringResource(
                    Res.plurals.sync_status_offline_waiting_description,
                    overview.pendingCount,
                    overview.pendingCount,
                )
            } else {
                stringResource(Res.string.sync_status_offline_description)
            }
        }
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        // One node for a screen reader, announced politely when it changes; the sentence is fuller than the label.
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.S + Spacing.Xs, vertical = Spacing.S - Spacing.Xs / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = Spacing.S),
            )
        }
    }
}

private const val CROSSFADE_MILLIS = 250
private val ICON_SIZE = 18.dp

@Preview(name = "Synced")
@Composable
private fun SyncedPreview() = AppTheme(darkTheme = false) { SyncStatusChip(SyncOverview.Synced) }

@Preview(name = "Syncing")
@Composable
private fun SyncingPreview() = AppTheme(darkTheme = false) { SyncStatusChip(SyncOverview.Syncing) }

@Preview(name = "Offline, 2 waiting")
@Composable
private fun OfflineWaitingPreview() = AppTheme(darkTheme = false) { SyncStatusChip(SyncOverview.Offline(2)) }

@Preview(name = "Offline, nothing waiting")
@Composable
private fun OfflineIdlePreview() = AppTheme(darkTheme = false) { SyncStatusChip(SyncOverview.Offline(0)) }

@Preview(name = "Synced, dark")
@Composable
private fun SyncedDarkPreview() = AppTheme(darkTheme = true) { SyncStatusChip(SyncOverview.Synced) }

@Preview(name = "Offline, dark")
@Composable
private fun OfflineDarkPreview() = AppTheme(darkTheme = true) { SyncStatusChip(SyncOverview.Offline(2)) }
