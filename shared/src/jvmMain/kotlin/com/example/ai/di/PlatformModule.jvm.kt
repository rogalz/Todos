package com.example.ai.di

import androidx.room.RoomDatabase
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.getDatabaseBuilder
import com.example.ai.data.remote.ServerConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<RoomDatabase.Builder<AppDatabase>> { getDatabaseBuilder() }
    single<HttpClientEngine> { OkHttp.create() }
    single { ServerConfig(baseUrl = "http://localhost:8080") }
}
