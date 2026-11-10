package com.example.ai.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ai.ui.detail.TodoDetailScreen
import com.example.ai.ui.list.TodoListScreen

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = TodoListRoute) {
        composable<TodoListRoute> { entry ->
            TodoListScreen(
                onAddClick = {
                    entry.runIfResumed { navController.navigate(TodoDetailRoute()) }
                },
                onTodoClick = { id ->
                    entry.runIfResumed { navController.navigate(TodoDetailRoute(id)) }
                },
            )
        }
        composable<TodoDetailRoute> { entry ->
            val route = entry.toRoute<TodoDetailRoute>()
            TodoDetailScreen(
                todoId = route.todoId,
                onClose = { navController.popBackStack() },
            )
        }
    }
}

/** Ignores repeated taps while a navigation transition is already running (no duplicate screens). */
private inline fun NavBackStackEntry.runIfResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}
