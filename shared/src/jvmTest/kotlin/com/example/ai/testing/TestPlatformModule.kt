package com.example.ai.testing

import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.remote.ServerConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module

/**
 * Stands in for the real `platformModule` in graph tests: the real one opens the user's on-disk
 * database. The engine is a real OkHttp engine, which opens no connection until a request is made,
 * and tests that must not reach a server replace `TodoApi` instead.
 */
fun fakePlatformModule() = module {
    single<RoomDatabase.Builder<AppDatabase>> { Room.inMemoryDatabaseBuilder<AppDatabase>() }
    single<HttpClientEngine> { OkHttp.create() }
    single { ServerConfig(baseUrl = "http://localhost:8080") }
}
