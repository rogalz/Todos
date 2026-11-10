package com.example.ai.di

import androidx.room.RoomDatabase
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.getDatabaseBuilder
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<RoomDatabase.Builder<AppDatabase>> { getDatabaseBuilder() }
}
