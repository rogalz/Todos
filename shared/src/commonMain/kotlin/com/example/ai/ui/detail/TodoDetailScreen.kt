package com.example.ai.ui.detail

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.delete_dialog_cancel
import ai.shared.generated.resources.delete_dialog_confirm
import ai.shared.generated.resources.delete_dialog_message
import ai.shared.generated.resources.delete_dialog_title
import ai.shared.generated.resources.detail_ai_hint
import ai.shared.generated.resources.detail_ai_unavailable
import ai.shared.generated.resources.detail_back
import ai.shared.generated.resources.detail_delete
import ai.shared.generated.resources.detail_delete_error
import ai.shared.generated.resources.detail_field_description
import ai.shared.generated.resources.detail_field_title
import ai.shared.generated.resources.detail_filling
import ai.shared.generated.resources.detail_save
import ai.shared.generated.resources.detail_save_error
import ai.shared.generated.resources.detail_save_error_discard
import ai.shared.generated.resources.detail_title_edit
import ai.shared.generated.resources.detail_title_new
import ai.shared.generated.resources.discard_draft_confirm
import ai.shared.generated.resources.discard_draft_keep
import ai.shared.generated.resources.discard_draft_message
import ai.shared.generated.resources.discard_draft_title
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.ai.ui.nav.NoSharedElements
import com.example.ai.ui.nav.SharedElements
import com.example.ai.ui.nav.SharedKeys
import com.example.ai.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock

/**
 * @param todoId id of the todo to edit, `null` to create a new one.
 * @param dictatedText a sentence the user spoke or typed, to create a new todo as an AI draft (needs a
 *   `null` [todoId]). A draft has a Save action and is never stored by going back.
 * @param onClose called exactly once, after changes have been saved, when the screen should be popped.
 * @param sharedElements the parts that travel from the list card (or the FAB) into this screen.
 * @param clock only for the "Edited ..." footer.
 */
@Composable
fun TodoDetailScreen(
    todoId: String?,
    onClose: () -> Unit,
    dictatedText: String? = null,
    viewModel: TodoDetailViewModel = koinViewModel(
        // The `Dictation` is only passed when there is one: `parametersOf(todoId)` stays what it always was.
        parameters = {
            if (dictatedText == null) parametersOf(todoId) else parametersOf(todoId, Dictation(dictatedText))
        },
    ),
    sharedElements: SharedElements = NoSharedElements,
    clock: Clock = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Every way of going back funnels into the ViewModel, which saves before asking us to close.
    //
    // Trade-off, on purpose: because this handler owns "back", NavHost's own back handling is bypassed
    // here. The closing animation therefore starts once back is *committed* (arrow, key or gesture); on
    // Android the predictive-back gesture does not scrub it under the finger. Letting NavHost seek the
    // transition would mean popping first and saving afterwards, which loses "stay on the screen and
    // offer Discard when the save fails" and ties the save of a new todo to the screen's lifetime.
    // Save-then-pop is the behaviour this screen guarantees, so it wins.
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = viewModel::onBackClick,
    )
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }

    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(state.closeRequested) {
        if (state.closeRequested) currentOnClose()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val saveErrorMessage = stringResource(Res.string.detail_save_error)
    val discardLabel = stringResource(Res.string.detail_save_error_discard)
    val deleteErrorMessage = stringResource(Res.string.detail_delete_error)
    val aiUnavailableMessage = stringResource(Res.string.detail_ai_unavailable)
    LaunchedEffect(state.error) {
        when (state.error) {
            DetailError.SaveFailed -> {
                val result = snackbarHostState.showSnackbar(
                    message = saveErrorMessage,
                    actionLabel = discardLabel,
                    duration = SnackbarDuration.Long,
                )
                viewModel.onErrorShown()
                if (result == SnackbarResult.ActionPerformed) viewModel.onDiscardChanges()
            }

            DetailError.DeleteFailed -> {
                snackbarHostState.showSnackbar(deleteErrorMessage)
                viewModel.onErrorShown()
            }

            DetailError.AiUnavailable -> {
                snackbarHostState.showSnackbar(aiUnavailableMessage)
                viewModel.onErrorShown()
            }

            null -> Unit
        }
    }

    TodoDetailContent(
        state = state,
        todoId = todoId,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        sharedElements = sharedElements,
        clock = clock,
    )

    if (state.showDiscardDraftConfirmation) {
        // Back on a draft never saves: it asks. Saving is the Save action in the top bar.
        AlertDialog(
            onDismissRequest = viewModel::onDiscardDraftDismissed,
            title = { Text(stringResource(Res.string.discard_draft_title)) },
            text = { Text(stringResource(Res.string.discard_draft_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onDiscardDraftConfirmed) {
                    Text(stringResource(Res.string.discard_draft_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDiscardDraftDismissed) {
                    Text(stringResource(Res.string.discard_draft_keep))
                }
            },
        )
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismissed,
            title = { Text(stringResource(Res.string.delete_dialog_title)) },
            text = { Text(stringResource(Res.string.delete_dialog_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteConfirmed) {
                    Text(stringResource(Res.string.delete_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDeleteDismissed) {
                    Text(stringResource(Res.string.delete_dialog_cancel))
                }
            },
        )
    }
}

/**
 * The fields are "borderless": the container and the indicator line are transparent, so the text sits
 * directly on the page like a note. `Color.Transparent` is the absence of a colour, not a palette
 * colour, so it is the one colour value allowed outside the theme.
 */
@Composable
private fun borderlessFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

@Composable
private fun TodoDetailContent(
    state: TodoDetailUiState,
    todoId: String?,
    viewModel: TodoDetailViewModel,
    snackbarHostState: SnackbarHostState,
    sharedElements: SharedElements,
    clock: Clock,
) {
    // The morph is for opening only. Once the screen is closing (the save has finished) it stops being a
    // shared element, so there is nothing to match on the list and the way out is the plain fade-through
    // from `AppNavHost`. The modifier is gone before the pop starts: `closeRequested` is what triggers it.
    val closing = state.closeRequested
    Scaffold(
        // The whole screen is what the list card (or the FAB) morphs into.
        modifier = if (closing) Modifier else sharedElements.container(SharedKeys.container(todoId)),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) Res.string.detail_title_new else Res.string.detail_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.detail_back),
                        )
                    }
                },
                actions = {
                    if (state.isDraft) {
                        // The explicit approval. Needs a title, like every todo.
                        TextButton(
                            onClick = viewModel::onSaveClick,
                            enabled = viewModel.titleState.text.isNotBlank(),
                        ) {
                            Text(stringResource(Res.string.detail_save))
                        }
                    }
                    if (state.canDelete) {
                        IconButton(onClick = viewModel::onDeleteClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(Res.string.detail_delete),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val titleFocus = remember { FocusRequester() }
            // Not for a draft: the keyboard would cover the draft the user came here to read.
            if (state.isNew && !state.isDraft) {
                LaunchedEffect(Unit) { titleFocus.requestFocus() }
            }
            val fillingLabel = stringResource(Res.string.detail_filling)
            val fieldColors = borderlessFieldColors()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(Spacing.S),
                verticalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                TextField(
                    state = viewModel.titleState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(titleFocus)
                        // The title morphs along with the card it came from (opening only, see above).
                        .then(if (closing) Modifier else sharedElements.title(SharedKeys.title(todoId))),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    label = {
                        val waiting = state.isFilling && viewModel.titleState.text.isEmpty()
                        Text(if (waiting) fillingLabel else stringResource(Res.string.detail_field_title))
                    },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    colors = fieldColors,
                )
                if (viewModel.showAiHint) {
                    AiHint(modifier = Modifier.padding(horizontal = Spacing.M))
                }
                TextField(
                    state = viewModel.descriptionState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = {
                        val waiting = state.isFilling && viewModel.descriptionState.text.isEmpty()
                        Text(if (waiting) fillingLabel else stringResource(Res.string.detail_field_description))
                    },
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    colors = fieldColors,
                )
                // A new todo has no history yet.
                state.updatedAt?.let { updatedAt ->
                    // One reading of "now" per opened todo: the text does not tick while the screen is open.
                    val now = remember(updatedAt) { clock.now() }
                    EditedFooter(
                        updatedAt = updatedAt,
                        now = now,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.M, vertical = Spacing.S),
                    )
                }
            }
        }
    }
}

/** Says the text came from the AI and should be checked; the icon plus text, so it is not colour alone. */
@Composable
private fun AiHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.detail_ai_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
