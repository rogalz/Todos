package com.example.ai.ui.detail

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.delete_dialog_cancel
import ai.shared.generated.resources.delete_dialog_confirm
import ai.shared.generated.resources.delete_dialog_message
import ai.shared.generated.resources.delete_dialog_title
import ai.shared.generated.resources.detail_back
import ai.shared.generated.resources.detail_delete
import ai.shared.generated.resources.detail_delete_error
import ai.shared.generated.resources.detail_field_description
import ai.shared.generated.resources.detail_field_title
import ai.shared.generated.resources.detail_save_error
import ai.shared.generated.resources.detail_save_error_discard
import ai.shared.generated.resources.detail_title_edit
import ai.shared.generated.resources.detail_title_new
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * @param todoId id of the todo to edit, `null` to create a new one.
 * @param onClose called exactly once, after changes have been saved, when the screen should be popped.
 */
@Composable
fun TodoDetailScreen(
    todoId: String?,
    onClose: () -> Unit,
    viewModel: TodoDetailViewModel = koinViewModel(parameters = { parametersOf(todoId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Every way of going back funnels into the ViewModel, which saves before asking us to close.
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

            null -> Unit
        }
    }

    TodoDetailContent(
        state = state,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
    )

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

@Composable
private fun TodoDetailContent(
    state: TodoDetailUiState,
    viewModel: TodoDetailViewModel,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
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
            if (state.isNew) {
                LaunchedEffect(Unit) { titleFocus.requestFocus() }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    state = viewModel.titleState,
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                    label = { Text(stringResource(Res.string.detail_field_title)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
                OutlinedTextField(
                    state = viewModel.descriptionState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text(stringResource(Res.string.detail_field_description)) },
                    lineLimits = TextFieldLineLimits.MultiLine(),
                )
            }
        }
    }
}
