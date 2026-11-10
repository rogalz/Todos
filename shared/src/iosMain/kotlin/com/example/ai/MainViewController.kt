package com.example.ai

import androidx.compose.ui.window.ComposeUIViewController
import com.example.ai.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}
