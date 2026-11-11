package com.example.ai.ui.list

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.voice_listening_cancel
import ai.shared.generated.resources.voice_listening_done
import ai.shared.generated.resources.voice_listening_prompt
import ai.shared.generated.resources.voice_listening_title
import ai.shared.generated.resources.voice_mic_button
import ai.shared.generated.resources.voice_mic_button_disabled
import ai.shared.generated.resources.voice_permission_message
import ai.shared.generated.resources.voice_permission_ok
import ai.shared.generated.resources.voice_permission_title
import ai.shared.generated.resources.voice_type_cancel
import ai.shared.generated.resources.voice_type_confirm
import ai.shared.generated.resources.voice_type_label
import ai.shared.generated.resources.voice_type_title
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ai.ui.icons.AppIcons
import com.example.ai.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The small FAB above "New task". While the microphone is refused it stays visible but disabled
 * (dimmed, announced as disabled, taps ignored), as the spec asks.
 */
@Composable
internal fun MicFab(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(if (enabled) Res.string.voice_mic_button else Res.string.voice_mic_button_disabled)
    SmallFloatingActionButton(
        onClick = if (enabled) onClick else ({}),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
    ) {
        Icon(painter = AppIcons.Mic, contentDescription = null)
    }
}

/** Everything the voice input can put on top of the list: the listening dialog, the typing dialog, the permission note. */
@Composable
internal fun VoiceInputDialogs(
    state: VoiceInputUiState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onTypedSubmit: (String) -> Unit,
    onTypedDismiss: () -> Unit,
    onPermissionDismiss: () -> Unit,
) {
    when (state.phase) {
        VoicePhase.Idle -> Unit
        VoicePhase.Listening -> ListeningDialog(state.partialTranscript, onStop = onStop, onCancel = onCancel)
        VoicePhase.Typing -> TypingDialog(onSubmit = onTypedSubmit, onDismiss = onTypedDismiss)
    }
    if (state.showPermissionExplanation) {
        AlertDialog(
            onDismissRequest = onPermissionDismiss,
            title = { Text(stringResource(Res.string.voice_permission_title)) },
            text = { Text(stringResource(Res.string.voice_permission_message)) },
            confirmButton = {
                TextButton(onClick = onPermissionDismiss) { Text(stringResource(Res.string.voice_permission_ok)) }
            },
        )
    }
}

/** A pulsing microphone and the live transcript. Done keeps what was said, Cancel throws it away. */
@Composable
private fun ListeningDialog(partial: String, onStop: () -> Unit, onCancel: () -> Unit) {
    // A calm pulse (scale only, no flashing); it is decoration, so the icon has no description of its own.
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = PULSE_SCALE,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
    )
    AlertDialog(
        // Tapping outside cancels, like the Cancel button: nothing is stored either way.
        onDismissRequest = onCancel,
        icon = {
            Icon(
                painter = AppIcons.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).graphicsLayer { scaleX = pulse; scaleY = pulse },
            )
        },
        title = { Text(stringResource(Res.string.voice_listening_title)) },
        text = {
            Text(
                text = partial.ifBlank { stringResource(Res.string.voice_listening_prompt) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = { TextButton(onClick = onStop) { Text(stringResource(Res.string.voice_listening_done)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.voice_listening_cancel)) } },
    )
}

/** Desktop and iOS: no speech, so the same button asks for one typed sentence. */
@Composable
private fun TypingDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val text = rememberTextFieldState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.voice_type_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S), horizontalAlignment = Alignment.Start) {
                OutlinedTextField(
                    state = text,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.voice_type_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text.text.toString()) }, enabled = text.text.isNotBlank()) {
                Text(stringResource(Res.string.voice_type_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.voice_type_cancel)) } },
    )
}

private const val DISABLED_ALPHA = 0.38f
private const val PULSE_SCALE = 1.2f
private const val PULSE_MILLIS = 700
