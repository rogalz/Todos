package com.example.ai.domain

/** The runtime permission to record audio. Only Android has one to ask for. */
interface MicPermission {
    fun isGranted(): Boolean

    /** Asks the user if needed and returns whether the permission is now granted. */
    suspend fun request(): Boolean
}
