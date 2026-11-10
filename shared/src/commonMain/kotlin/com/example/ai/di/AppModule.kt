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

/** Provides the platform specific `RoomDatabase.Builder<AppDatabase>`. */
expect val platformModule: Module

val appModule = module {
    single<AppDatabase> { getRoomDatabase(get()) }
    single<TodoDao> { get<AppDatabase>().todoDao() }
    single<TodoRepository> { RoomTodoRepository(get()) }

    viewModel { TodoListViewModel(get()) }
    // Parameter is the todo id; `null` (nothing matching Long) means "create a new todo".
    viewModel { params -> TodoDetailViewModel(todoId = params.getOrNull<Long>(), repository = get()) }
}
