package com.example.ai

import android.app.Application
import com.example.ai.di.initKoin

class TodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this)
    }
}
