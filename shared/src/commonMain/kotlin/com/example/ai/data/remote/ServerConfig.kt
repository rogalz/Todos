package com.example.ai.data.remote

/**
 * Where the sync server lives. A type of its own (not a bare `String` in Koin) so a base URL can
 * never be mixed up with any other string binding. Each platform module provides it, so a platform
 * can differ: on the Android emulator `localhost` is the emulator itself and only reaches the
 * server after `scripts/android-reverse.sh` (`adb reverse`).
 */
data class ServerConfig(val baseUrl: String)
