package com.example.ai.di

import com.example.ai.data.RoomTodoRepository
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.TodoDao
import com.example.ai.data.local.getRoomDatabase
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.detail.TodoDetailViewModel
import com.example.ai.ui.list.TodoListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

/** Provides the platform specific `RoomDatabase.Builder<AppDatabase>`. */
expect val platformModule: Module

val appModule = module {
    // The only place that names Clock.System; everything else gets the clock injected.
    single<Clock> { Clock.System }
    single<AppDatabase> { getRoomDatabase(builder = get(), clock = get()) }
    single<TodoDao> { get<AppDatabase>().todoDao() }
    single<TodoRepository> { RoomTodoRepository(dao = get(), clock = get()) }

    viewModel { TodoListViewModel(get()) }
    // Parameter is the todo id; `null` (nothing matching String) means "create a new todo". The type
    // argument must stay in sync with the id type: a mismatch compiles but silently reads as `null`.
    viewModel { params -> TodoDetailViewModel(todoId = params.getOrNull<String>(), repository = get()) }
}
