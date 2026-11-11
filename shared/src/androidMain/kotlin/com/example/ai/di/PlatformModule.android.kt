package com.example.ai.di

import android.content.Context
import androidx.room.RoomDatabase
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.getDatabaseBuilder
import com.example.ai.data.remote.ServerConfig
import com.example.ai.data.sync.AppForeground
import com.example.ai.data.sync.BackgroundSyncFallback
import com.example.ai.data.sync.ProcessLifecycleForeground
import com.example.ai.data.sync.WorkManagerSyncFallback
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<RoomDatabase.Builder<AppDatabase>> { getDatabaseBuilder(androidContext()) }
    single<HttpClientEngine> { OkHttp.create() }
    // Inside the emulator `localhost` is the emulator itself, so this only reaches the dev server
    // after `scripts/android-reverse.sh` (adb reverse) has tunnelled the port to the host. Not the
    // usual 10.0.2.2 host alias: it is not reachable on every machine. Cleartext for localhost is
    // allowed in res/xml/network_security_config.xml.
    single { ServerConfig(baseUrl = "http://localhost:8080") }
    // Android only: uploads offline changes once the network is back, even if the app is not running.
    single<BackgroundSyncFallback> { WorkManagerSyncFallback(androidContext()) }
    // The live-updates stream is open only while an activity is visible.
    single<AppForeground> { ProcessLifecycleForeground() }
}

/** Android entry point, call from `Application.onCreate`. Safe to call repeatedly. */
fun initKoin(context: Context) = startKoinApp { androidContext(context.applicationContext) }
