package com.example.ai.ui.list

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.list_add_task
import ai.shared.generated.resources.list_empty_hint
import ai.shared.generated.resources.list_empty_title
import ai.shared.generated.resources.list_error_message
import ai.shared.generated.resources.list_error_retry
import ai.shared.generated.resources.list_title
import ai.shared.generated.resources.list_write_error
import ai.shared.generated.resources.voice_nothing_heard
import ai.shared.generated.resources.voice_unavailable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.domain.SyncOverview
import com.example.ai.ui.nav.NoSharedElements
import com.example.ai.ui.nav.SharedElements
import com.example.ai.ui.nav.SharedKeys
import com.example.ai.ui.sync.SyncStatusChip
import com.example.ai.ui.sync.SyncStatusViewModel
import com.example.ai.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param onDictated called once with a sentence the user spoke or typed through the mic button; the
 *   caller opens the new-task screen with it as a draft.
 */
@Composable
fun TodoListScreen(
    onAddClick: () -> Unit,
    onTodoClick: (String) -> Unit,
    onDictated: (String) -> Unit = {},
    viewModel: TodoListViewModel = koinViewModel(),
    syncStatusViewModel: SyncStatusViewModel = koinViewModel(),
    voiceInputViewModel: VoiceInputViewModel = koinViewModel(),
    sharedElements: SharedElements = NoSharedElements,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
    val syncOverview by syncStatusViewModel.overview.collectAsStateWithLifecycle()
    val voice by voiceInputViewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val writeErrorMessage = stringResource(Res.string.list_write_error)
    LaunchedEffect(writeFailed) {
        if (writeFailed) {
            snackbarHostState.showSnackbar(writeErrorMessage)
            viewModel.onWriteErrorShown()
        }
    }

    val nothingHeard = stringResource(Res.string.voice_nothing_heard)
    val speechUnavailable = stringResource(Res.string.voice_unavailable)
    LaunchedEffect(voice.message) {
        val message = when (voice.message) {
            VoiceMessage.NothingHeard -> nothingHeard
            VoiceMessage.SpeechUnavailable -> speechUnavailable
            null -> return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(message)
        voiceInputViewModel.onMessageShown()
    }

    val currentOnDictated by rememberUpdatedState(onDictated)
    LaunchedEffect(voice.dictatedText) {
        voice.dictatedText?.let {
            currentOnDictated(it)
            voiceInputViewModel.onDictationHandled()
        }
    }

    TodoListContent(
        state = state,
        syncOverview = syncOverview,
        micEnabled = voice.micEnabled,
        snackbarHostState = snackbarHostState,
        sharedElements = sharedElements,
        onAddClick = onAddClick,
        onMicClick = voiceInputViewModel::onMicClick,
        onTodoClick = onTodoClick,
        onToggleDone = viewModel::onToggleDone,
        onRetry = viewModel::onRetry,
    )
    VoiceInputDialogs(
        state = voice,
        onStop = voiceInputViewModel::onStopClick,
        onCancel = voiceInputViewModel::onCancelClick,
        onTypedSubmit = voiceInputViewModel::onTypedTextSubmitted,
        onTypedDismiss = voiceInputViewModel::onTypedDialogDismissed,
        onPermissionDismiss = voiceInputViewModel::onPermissionExplanationDismissed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoListContent(
    state: TodoListUiState,
    syncOverview: SyncOverview,
    micEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    sharedElements: SharedElements,
    onAddClick: () -> Unit,
    onMicClick: () -> Unit,
    onTodoClick: (String) -> Unit,
    onToggleDone: (id: String, isDone: Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Wide while the list is at the top or the user is scrolling back up, an icon while reading down.
    val fabExpanded by remember {
        derivedStateOf { !listState.canScrollBackward || listState.lastScrolledBackward }
    }
    val addLabel = stringResource(Res.string.list_add_task)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.list_title)) },
                actions = { SyncStatusChip(overview = syncOverview, modifier = Modifier.padding(end = Spacing.M)) },
            )
        },
        // Always visible, in every state, so there is always a way to create a task.
        floatingActionButton = {
            // The small mic sits above the main button, centred on its icon (the extended one is wider).
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                MicFab(enabled = micEnabled, onClick = onMicClick)
                ExtendedFloatingActionButton(
                    onClick = onAddClick,
                    expanded = fabExpanded,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    // The text is gone while collapsed, so the label lives here: one announcement either way.
                    modifier = Modifier
                        .then(sharedElements.container(SharedKeys.container(null)))
                        .semantics { contentDescription = addLabel },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(addLabel) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when (state) {
            TodoListUiState.Loading -> CenteredColumn(contentModifier) {
                CircularProgressIndicator()
            }

            TodoListUiState.Empty -> CenteredColumn(contentModifier) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.list_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                // Points at the FAB, which is always on screen; a second button here would only duplicate it.
                Text(
                    text = stringResource(Res.string.list_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            TodoListUiState.Error -> CenteredColumn(contentModifier) {
                Text(
                    text = stringResource(Res.string.list_error_message),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text(stringResource(Res.string.list_error_retry))
                }
            }

            is TodoListUiState.Content -> LazyColumn(
                modifier = contentModifier,
                state = listState,
                // Bottom room so the FAB never covers the last card.
                contentPadding = PaddingValues(start = Spacing.M, top = Spacing.S, end = Spacing.M, bottom = FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                items(items = state.items, key = { it.id }) { todo ->
                    TodoCard(
                        title = todo.title,
                        description = todo.description,
                        isDone = todo.isDone,
                        isSynced = todo.isSynced,
                        onClick = { onTodoClick(todo.id) },
                        onCheckedChange = { onToggleDone(todo.id, it) },
                        // Insert, delete and reorder animate; ticking never reorders (see TodoDao).
                        modifier = Modifier
                            .animateItem()
                            .then(sharedElements.container(SharedKeys.container(todo.id))),
                        titleModifier = sharedElements.title(SharedKeys.title(todo.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(Spacing.Xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.M, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

private val FAB_CLEARANCE = 96.dp
