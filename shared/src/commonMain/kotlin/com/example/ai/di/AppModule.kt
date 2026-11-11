package com.example.ai.di

import com.example.ai.data.RoomTodoRepository
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.SyncDao
import com.example.ai.data.local.TodoDao
import com.example.ai.data.local.getRoomDatabase
import com.example.ai.data.remote.KtorTodoApi
import com.example.ai.data.remote.ServerConfig
import com.example.ai.data.remote.TodoApi
import com.example.ai.data.remote.configureTodoClient
import com.example.ai.data.sync.BackgroundSyncFallback
import com.example.ai.data.sync.SyncEngine
import com.example.ai.data.sync.SyncScheduler
import com.example.ai.data.sync.SyncStore
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.detail.TodoDetailViewModel
import com.example.ai.ui.list.TodoListViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Provides what differs per platform: the `RoomDatabase.Builder<AppDatabase>`, the Ktor
 * `HttpClientEngine` and the [ServerConfig] (the server's address).
 */
expect val platformModule: Module

/** Qualifier of the app-lifetime scope that the sync scheduler runs in. */
internal val AppScope = named("appScope")

val appModule = module {
    // The only place that names Clock.System; everything else gets the clock injected.
    single<Clock> { Clock.System }
    single<AppDatabase> { getRoomDatabase(builder = get(), clock = get()) }
    single<TodoDao> { get<AppDatabase>().todoDao() }
    single<TodoRepository> { RoomTodoRepository(dao = get(), clock = get()) }

    // ---- sync (stage 3) ----
    // Lives as long as the process. SupervisorJob: one failing child must not take the scheduler down.
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<SyncDao> { get<AppDatabase>().syncDao() }
    single<HttpClient> { HttpClient(engine = get<HttpClientEngine>()) { configureTodoClient() } }
    single<TodoApi> { KtorTodoApi(client = get(), baseUrl = get<ServerConfig>().baseUrl) }
    single { SyncStore(database = get<AppDatabase>(), dao = get()) }
    single { SyncEngine(api = get(), store = get(), clock = get()) }
    // Created here, started by `startKoinApp`: constructing it must never begin syncing, or merely
    // resolving the graph (KoinGraphTest) would start talking to a server.
    single {
        SyncScheduler(
            pendingCount = get<SyncStore>().observePendingCount(),
            sync = get<SyncEngine>()::sync,
            scope = get(AppScope),
            // Provided by Android only (WorkManager); desktop and iOS have none.
            fallback = getOrNull<BackgroundSyncFallback>(),
        )
    }

    viewModel { TodoListViewModel(get()) }
    // Parameter is the todo id; `null` (nothing matching String) means "create a new todo". The type
    // argument must stay in sync with the id type: a mismatch compiles but silently reads as `null`.
    viewModel { params -> TodoDetailViewModel(todoId = params.getOrNull<String>(), repository = get()) }
}
