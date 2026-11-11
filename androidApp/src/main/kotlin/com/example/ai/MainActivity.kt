package com.example.ai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ai.di.androidMicPermission

class MainActivity : ComponentActivity() {

    private val micPermission by lazy { androidMicPermission() }

    // Registered while the activity is constructed, as the Activity Result API requires. Only an activity can
    // do this, which is why the microphone permission is wired through here and not inside `shared`.
    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micPermission.onResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        micPermission.attach { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        micPermission.detach()
        super.onDestroy()
    }
}
