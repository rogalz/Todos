package com.example.ai.di

import android.content.Context
import androidx.room.RoomDatabase
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.getDatabaseBuilder
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<RoomDatabase.Builder<AppDatabase>> { getDatabaseBuilder(androidContext()) }
}

/** Android entry point, call from `Application.onCreate`. Safe to call repeatedly. */
fun initKoin(context: Context) = startKoinApp { androidContext(context.applicationContext) }
