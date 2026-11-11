package com.example.ai.di

import com.example.ai.data.local.AppDatabase
import com.example.ai.domain.TodoRepository
import com.example.ai.testing.MainDispatcherTest
import com.example.ai.testing.fakePlatformModule
import com.example.ai.ui.detail.TodoDetailViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.core.KoinApplication
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.test.check.checkModules
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builds the real [appModule] graph (real Room on an in-memory database, real repository, real
 * ViewModels) so wiring mistakes fail here instead of at runtime on a device.
 *
 * Only the platform module is swapped: the real one opens the user's on-disk database.
 */
class KoinGraphTest : MainDispatcherTest() {

    // Isolated (non-global) Koin so no state leaks between tests. Definitions are lazy: nothing is
    // created, and no database opened, until a test resolves it.
    private val app: KoinApplication = koinApplication { modules(fakePlatformModule(), appModule) }
    private val koin get() = app.koin

    @AfterTest
    fun tearDown() {
        // Koin does not close the database, the HTTP client or the app scope for us.
        koin.getOrNull<AppDatabase>()?.close()
        koin.getOrNull<HttpClient>()?.close()
        koin.getOrNull<CoroutineScope>(AppScope)?.cancel()
        app.close()
    }

    /**
     * Resolves every definition, so a missing binding or a broken constructor call fails the build.
     *
     * `checkModules` is deprecated in favour of `verify()`, but `verify()` only compares constructor
     * parameter types statically and never runs the definition lambdas, so it cannot see a wrong
     * `get()` or a bad parameter read. Revisit if a later Koin version removes `checkModules`.
     */
    @Suppress("DEPRECATION")
    @Test
    fun everyDefinitionInTheGraphResolves() {
        app.checkModules {
            // Required parameter for the detail ViewModel's definition lambda. It cannot prove the
            // parameter *type* matches (see the next tests), only that resolution does not throw.
            withParameter<TodoDetailViewModel> { "some-todo-id" }
        }
    }

    /**
     * `params.getOrNull<T>()` compiles for any `T` and just returns `null` when the real argument has
     * another type, silently turning "edit this todo" into "create a new one". Only behaviour
     * can show that, so this goes through Koin exactly like `koinViewModel(parameters = ...)` does.
     */
    @Test
    fun detailViewModelReceivesTheTodoIdParameterAsString() = runTest {
        val id = koin.get<TodoRepository>().createTodo(title = "Seeded title", description = "Seeded description")

        val viewModel = koin.get<TodoDetailViewModel> { parametersOf(id) }

        assertFalse(viewModel.uiState.value.isNew, "id was not recognised: the ViewModel opened in create mode")
        assertTrue(viewModel.uiState.value.canDelete)
        // Loading happens on Room's IO dispatcher, so wait for it instead of reading state eagerly.
        viewModel.uiState.first { !it.isLoading }
        assertEquals("Seeded title", viewModel.titleState.text.toString())
        assertEquals("Seeded description", viewModel.descriptionState.text.toString())
    }

    @Test
    fun detailViewModelWithNullIdOpensInCreateMode() {
        // The screen always passes `parametersOf(todoId)`, with a null id for "new todo".
        val viewModel = koin.get<TodoDetailViewModel> { parametersOf(null) }

        val state = viewModel.uiState.value
        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertFalse(state.canDelete)
    }
}
