package com.example.ai.di

import com.example.ai.data.ApiTodoDraftParser
import com.example.ai.data.RoomTodoRepository
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.SyncDao
import com.example.ai.data.local.TodoDao
import com.example.ai.data.local.getRoomDatabase
import com.example.ai.data.remote.KtorTodoApi
import com.example.ai.data.remote.KtorTodoEvents
import com.example.ai.data.remote.ServerConfig
import com.example.ai.data.remote.TodoApi
import com.example.ai.data.remote.TodoEvents
import com.example.ai.data.remote.configureTodoClient
import com.example.ai.data.sync.AppForeground
import com.example.ai.data.sync.BackgroundSyncFallback
import com.example.ai.data.sync.LiveUpdates
import com.example.ai.data.sync.SyncEngine
import com.example.ai.data.sync.SyncEngineStatusRepository
import com.example.ai.data.sync.SyncScheduler
import com.example.ai.data.sync.SyncStore
import com.example.ai.domain.SyncStatusRepository
import com.example.ai.domain.TodoDraftParser
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.detail.Dictation
import com.example.ai.ui.detail.TodoDetailViewModel
import com.example.ai.ui.list.TodoListViewModel
import com.example.ai.ui.list.VoiceInputViewModel
import com.example.ai.ui.sync.SyncStatusViewModel
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
 * `HttpClientEngine`, the [ServerConfig] (the server's address), the [AppForeground] signal and,
 * for voice input, the `SpeechToText` and `MicPermission` (no-ops everywhere but Android).
 */
expect val platformModule: Module

/** Qualifier of the app-lifetime scope that the sync scheduler runs in. */
internal val AppScope = named("appScope")

/**
 * The ViewModels, in a module of their own so that UI tests run exactly this wiring over fake
 * repositories (the detail screen's `todoId` parameter included) instead of a copy that could drift.
 */
val viewModelModule = module {
    viewModel { TodoListViewModel(get()) }
    viewModel { SyncStatusViewModel(get()) }
    viewModel { VoiceInputViewModel(speech = get(), micPermission = get()) }
    // Parameters: the todo id, `null` (nothing matching String) meaning "create a new todo", and,
    // only for a task that comes from dictation, a `Dictation`. Koin matches parameters by TYPE, so
    // the dictated text is wrapped in its own type: two `String?` would be mixed up (`parametersOf(null,
    // "milk")` would read "milk" as an id). The type arguments must stay in sync with what the screen
    // passes: a mismatch compiles but silently reads as `null`.
    viewModel { params ->
        TodoDetailViewModel(
            todoId = params.getOrNull<String>(),
            repository = get(),
            dictatedText = params.getOrNull<Dictation>()?.text,
            draftParser = get(),
        )
    }
}

val appModule = module {
    includes(viewModelModule)

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

    // ---- live updates (stage 4) ----
    single<TodoEvents> { KtorTodoEvents(client = get(), baseUrl = get<ServerConfig>().baseUrl) }
    // Like the scheduler: created here, started by `startKoinApp`. Resolving it must never open a connection.
    single {
        val scheduler = get<SyncScheduler>()
        LiveUpdates(
            events = get(),
            foreground = get(),
            readCursor = get<SyncStore>()::readCursor,
            requestSync = scheduler::requestSync,
            serverIsReachable = scheduler::serverIsReachable,
            scope = get(AppScope),
        )
    }

    // ---- voice to todo (stage 6) ----
    single<TodoDraftParser> { ApiTodoDraftParser(api = get()) }

    // ---- sync visibility (stage 5) ----
    // Read-only: the UI sees only the domain interface, never the engine. Resolving it starts nothing.
    single<SyncStatusRepository> {
        SyncEngineStatusRepository(
            engineState = get<SyncEngine>().state,
            pendingCount = get<SyncStore>().observePendingCount(),
            connection = get<LiveUpdates>().connection,
        )
    }
}
