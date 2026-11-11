package com.example.ai.ui.nav

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ai.ui.detail.TodoDetailScreen
import com.example.ai.ui.list.TodoListScreen
import com.example.ai.ui.theme.CLOSE_TODO_MILLIS
import com.example.ai.ui.theme.EmphasizedAccelerateEasing
import com.example.ai.ui.theme.EmphasizedEasing
import com.example.ai.ui.theme.OPEN_TODO_MILLIS

/**
 * The two screens. Opening: the list card (or the FAB) morphs into the detail screen.
 * Closing: a short fade-through, *not* the morph played backwards.
 *
 * Why not morph back: the detail screen only closes after saving, so what it should shrink into has
 * just changed. A new task would collapse into the FAB (not into the card that was just created), which
 * smears the FAB's solid colour over the whole screen; an edited one lands on a card whose text and
 * position differ from the title field it carries. The list is also behind the detail screen while it
 * is edited, so it is not guaranteed to show the saved content in the first frames of the way back.
 * The detail screen therefore leaves the shared transition as soon as it starts closing (see
 * `TodoDetailScreen`), and the destination transitions below are all that is left.
 *
 * Nothing animates on the first composition: the start destination has no transition to run, and a
 * shared element only animates while a transition is in progress.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    SharedTransitionLayout {
        val fade = tween<Float>(OPEN_TODO_MILLIS, easing = EmphasizedEasing)
        NavHost(
            navController = navController,
            startDestination = TodoListRoute,
            enterTransition = { fadeIn(fade) },
            exitTransition = { fadeOut(fade) },
            popEnterTransition = { fadeIn(tween(CLOSE_TODO_MILLIS, easing = EmphasizedEasing)) },
            popExitTransition = {
                fadeOut(tween(CLOSE_TODO_MILLIS, easing = EmphasizedAccelerateEasing)) +
                    scaleOut(tween(CLOSE_TODO_MILLIS, easing = EmphasizedAccelerateEasing), targetScale = CLOSE_END_SCALE)
            },
        ) {
            composable<TodoListRoute> { entry ->
                val sharedElements = remember(this@SharedTransitionLayout, this@composable) {
                    NavSharedElements(this@SharedTransitionLayout, this@composable)
                }
                TodoListScreen(
                    onAddClick = {
                        entry.runIfResumed { navController.navigate(TodoDetailRoute()) }
                    },
                    onTodoClick = { id ->
                        entry.runIfResumed { navController.navigate(TodoDetailRoute(id)) }
                    },
                    sharedElements = sharedElements,
                )
            }
            composable<TodoDetailRoute> { entry ->
                val route = entry.toRoute<TodoDetailRoute>()
                val sharedElements = remember(this@SharedTransitionLayout, this@composable) {
                    NavSharedElements(this@SharedTransitionLayout, this@composable)
                }
                TodoDetailScreen(
                    todoId = route.todoId,
                    onClose = { navController.popBackStack() },
                    sharedElements = sharedElements,
                )
            }
        }
    }
}

/** The detail screen shrinks only a little while it fades out: enough to feel like it leaves, not to look like it falls away. */
private const val CLOSE_END_SCALE = 0.96f

/** Ignores repeated taps while a navigation transition is already running (no duplicate screens). */
private inline fun NavBackStackEntry.runIfResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}
