package com.example.ai.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

// No destructive-migration fallback on purpose: a schema change without a Migration must fail
// loudly instead of silently wiping the user's tasks.
@Database(entities = [TodoEntity::class], version = 1, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
}

// The Room compiler generates the `actual` implementations for every target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

internal const val DATABASE_FILE_NAME = "todo.db"

// Query context is left at Room's default (Dispatchers.IO); `Dispatchers.IO` is not accessible from
// common code on Kotlin/Native.
fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .build()
