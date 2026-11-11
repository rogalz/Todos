package com.example.ai.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.ai.di.viewModelModule
import com.example.ai.domain.SyncStatusRepository
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.theme.AppTheme
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The app's real UI (theme, `AppNavHost`, the real ViewModels from [viewModelModule]) over fakes.
 *
 * Koin is *isolated* (not the global one) so tests cannot leak into each other. Only what sits below the
 * ViewModels is replaced: the repositories and the clock.
 */
@Composable
fun FakeAppEnvironment(
    repository: TodoRepository,
    syncStatus: SyncStatusRepository = FakeSyncStatusRepository(),
    clock: Clock = FakeClock(),
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val koin = remember {
        koinApplication {
            modules(
                viewModelModule,
                module {
                    single<TodoRepository> { repository }
                    single<SyncStatusRepository> { syncStatus }
                    single<Clock> { clock }
                },
            )
        }
    }
    KoinIsolatedContext(koin) {
        AppTheme(darkTheme = darkTheme, content = content)
    }
}
