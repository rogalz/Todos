package com.example.ai.ui.list

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.todo_not_synced
import ai.shared.generated.resources.todo_state_done
import ai.shared.generated.resources.todo_state_not_done
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ai.ui.icons.AppIcons
import com.example.ai.ui.theme.AppTheme
import com.example.ai.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * One todo in the list. [modifier] goes on the card (item animations, the shared container) and
 * [titleModifier] on the title text (the shared title).
 *
 * A done todo shows the same state three ways, never by colour alone: a ticked checkbox (with a
 * "Done" state description for screen readers), a struck-through title and a muted colour.
 */
@Composable
internal fun TodoCard(
    title: String,
    description: String,
    isDone: Boolean,
    isSynced: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // Subtle: a hint of tonal lift off the cream background, not a drop shadow you notice.
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.S, vertical = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TodoCheckbox(checked = isDone, title = title, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.S)) {
                StrikeThroughText(text = title, struck = isDone, modifier = titleModifier)
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Appears when a local change is waiting and fades away once the server has it.
            AnimatedVisibility(
                visible = !isSynced,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Icon(
                    painter = AppIcons.CloudUpload,
                    contentDescription = stringResource(Res.string.todo_not_synced),
                    modifier = Modifier.padding(end = Spacing.S).size(SYNC_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Material's checkbox (which already animates colour and tick and keeps the 48 dp touch target) plus a
 * short scale bounce when the value *changes*. Not on first composition: a list that scrolls items in
 * must not bounce them.
 */
@Composable
private fun TodoCheckbox(
    checked: Boolean,
    title: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Compose-internal animation state only; nothing here means anything outside this composable.
    val scale = remember { Animatable(1f) }
    var lastChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked != lastChecked) {
            lastChecked = checked
            scale.snapTo(BOUNCE_START_SCALE)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    val stateLabel = stringResource(if (checked) Res.string.todo_state_done else Res.string.todo_state_not_done)
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
            // Read in the draw phase: the bounce does not recompose the card on every frame.
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            // Without this a screen reader reads an unlabeled checkbox.
            .semantics {
                contentDescription = title
                stateDescription = stateLabel
            },
    )
}

/**
 * Text whose strikethrough is *drawn* progressively (left to right, line after line) instead of
 * toggled, so ticking a todo animates rather than jumps. The colour fades at the same time.
 *
 * Both animated values are read in the draw phase (`BasicText`'s colour lambda, `drawWithContent`),
 * so the animation costs no recomposition. Starting at the target value means no animation on the
 * first composition.
 */
@Composable
private fun StrikeThroughText(
    text: String,
    struck: Boolean,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(STRIKE_MILLIS),
        label = "titleColor",
    )
    val progress by animateFloatAsState(
        targetValue = if (struck) 1f else 0f,
        animationSpec = tween(STRIKE_MILLIS),
        label = "strikeProgress",
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    BasicText(
        text = text,
        modifier = modifier.drawWithContent {
            drawContent()
            val laidOut = layout
            if (laidOut != null && progress > 0f) drawStrike(laidOut, progress, color)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = { color },
        onTextLayout = { layout = it },
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
    )
}

/** Draws the line through the text, [progress] (0..1) of its total length, filling line after line. */
private fun DrawScope.drawStrike(layout: TextLayoutResult, progress: Float, color: Color) {
    val widths = List(layout.lineCount) { layout.getLineRight(it) - layout.getLineLeft(it) }
    var remaining = widths.sum() * progress
    for (line in widths.indices) {
        val length = minOf(widths[line], remaining)
        if (length <= 0f) break
        // A little below the middle of the line box: where a strikethrough sits for lower-case text.
        val y = layout.getLineTop(line) + (layout.getLineBottom(line) - layout.getLineTop(line)) * STRIKE_HEIGHT_FRACTION
        val startX = layout.getLineLeft(line)
        drawLine(color, Offset(startX, y), Offset(startX + length, y), strokeWidth = STRIKE_THICKNESS.toPx())
        remaining -= length
    }
}

private const val BOUNCE_START_SCALE = 0.7f
private const val STRIKE_MILLIS = 300
private const val STRIKE_HEIGHT_FRACTION = 0.55f
private val STRIKE_THICKNESS = 1.5.dp
private val SYNC_ICON_SIZE = 20.dp

@Preview(name = "Open")
@Composable
private fun OpenPreview() = AppTheme(darkTheme = false) {
    TodoCard("Buy milk", "Two litres, the green cap", isDone = false, isSynced = true, onClick = {}, onCheckedChange = {})
}

@Preview(name = "Done")
@Composable
private fun DonePreview() = AppTheme(darkTheme = false) {
    TodoCard("Buy milk", "Two litres, the green cap", isDone = true, isSynced = true, onClick = {}, onCheckedChange = {})
}

@Preview(name = "Not synced yet")
@Composable
private fun PendingPreview() = AppTheme(darkTheme = false) {
    TodoCard("Buy milk", "", isDone = false, isSynced = false, onClick = {}, onCheckedChange = {})
}

@Preview(name = "Long title, done, not synced")
@Composable
private fun LongPreview() = AppTheme(darkTheme = false) {
    TodoCard(
        title = "Write the quarterly report for the whole product and design organisation before Friday noon",
        description = "Include the numbers from the last three sprints and the open risks we discussed on Monday",
        isDone = true,
        isSynced = false,
        onClick = {},
        onCheckedChange = {},
    )
}

@Preview(name = "Open, dark")
@Composable
private fun OpenDarkPreview() = AppTheme(darkTheme = true) {
    TodoCard("Buy milk", "Two litres, the green cap", isDone = false, isSynced = false, onClick = {}, onCheckedChange = {})
}

@Preview(name = "Done, dark")
@Composable
private fun DoneDarkPreview() = AppTheme(darkTheme = true) {
    TodoCard("Buy milk", "Two litres, the green cap", isDone = true, isSynced = true, onClick = {}, onCheckedChange = {})
}
