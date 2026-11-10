package com.example.ai.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatformTools

/** Starts Koin for platforms that need no extra setup (Desktop, iOS). Safe to call repeatedly. */
fun initKoin() = startKoinApp(config = null)

internal fun startKoinApp(config: KoinAppDeclaration?) {
    // iOS may create the root view controller more than once, so starting must be idempotent.
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin {
        config?.invoke(this)
        modules(platformModule, appModule)
    }
}
