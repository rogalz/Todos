package com.example.ai.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

fun getDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val dbFile = File(appDataDirectory(), DATABASE_FILE_NAME)
    return Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
}

// Not `java.io.tmpdir`: the OS is free to clean it, which would silently delete the user's tasks.
private fun appDataDirectory(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val base = when {
        "mac" in os -> File(home, "Library/Application Support")
        "win" in os -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".local/share")
    }
    return File(base, "AiTodo").also { it.mkdirs() }
}
