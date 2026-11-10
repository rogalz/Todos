package com.example.ai

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.ai.di.initKoin

fun main() {
    initKoin()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "To-do",
        ) {
            App()
        }
    }
}
