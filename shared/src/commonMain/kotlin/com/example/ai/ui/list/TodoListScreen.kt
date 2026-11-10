package com.example.ai.ui.list

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.list_add_task
import ai.shared.generated.resources.list_empty_action
import ai.shared.generated.resources.list_empty_message
import ai.shared.generated.resources.list_empty_title
import ai.shared.generated.resources.list_error_message
import ai.shared.generated.resources.list_error_retry
import ai.shared.generated.resources.list_title
import ai.shared.generated.resources.list_write_error
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.domain.Todo
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TodoListScreen(
    onAddClick: () -> Unit,
    onTodoClick: (Long) -> Unit,
    viewModel: TodoListViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val writeErrorMessage = stringResource(Res.string.list_write_error)
    LaunchedEffect(writeFailed) {
        if (writeFailed) {
            snackbarHostState.showSnackbar(writeErrorMessage)
            viewModel.onWriteErrorShown()
        }
    }

    TodoListContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onAddClick = onAddClick,
        onTodoClick = onTodoClick,
        onToggleDone = viewModel::onToggleDone,
        onRetry = viewModel::onRetry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoListContent(
    state: TodoListUiState,
    snackbarHostState: SnackbarHostState,
    onAddClick: () -> Unit,
    onTodoClick: (Long) -> Unit,
    onToggleDone: (id: Long, isDone: Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.list_title)) }) },
        // Always visible, in every state, so there is always a way to create a task.
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.list_add_task))
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
                Text(
                    text = stringResource(Res.string.list_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onAddClick) {
                    Text(stringResource(Res.string.list_empty_action))
                }
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
                // Leaves room so the FAB never covers the last item.
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(items = state.items, key = { it.id }) { todo ->
                    TodoRow(
                        todo = todo,
                        onClick = { onTodoClick(todo.id) },
                        onCheckedChange = { onToggleDone(todo.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun TodoRow(
    todo: Todo,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = todo.isDone,
            onCheckedChange = onCheckedChange,
            // Gives screen readers the task name, otherwise it is an unlabeled checkbox.
            modifier = Modifier.semantics { contentDescription = todo.title },
        )
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = todo.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (todo.isDone) TextDecoration.LineThrough else null,
                color = if (todo.isDone) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (todo.description.isNotBlank()) {
                Text(
                    text = todo.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
