package com.example.ai.di

import com.example.ai.data.sync.SyncScheduler
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatformTools

/** Starts Koin for platforms that need no extra setup (Desktop, iOS). Safe to call repeatedly. */
fun initKoin() = startKoinApp(config = null)

internal fun startKoinApp(config: KoinAppDeclaration?) {
    // iOS may create the root view controller more than once, so starting must be idempotent.
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    val koin = startKoin {
        config?.invoke(this)
        modules(platformModule, appModule)
    }.koin
    // Syncing begins here, once per process (the guard above makes this idempotent), and on every
    // platform alike. It is not part of the graph itself so that tests can build the graph safely.
    koin.get<SyncScheduler>().start()
}
