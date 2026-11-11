package com.example.ai.data.local

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.time.Clock

// No destructive-migration fallback on purpose: a schema change without a Migration must fail
// loudly instead of silently wiping the user's tasks.
@Database(
    entities = [TodoEntity::class, SyncCursorEntity::class],
    version = 3,
    exportSchema = true,
    // v3 only adds the `sync_state` table, which Room can generate on its own. v1 -> v2 rebuilds a
    // table and stays a hand-written Migration1To2.
    autoMigrations = [AutoMigration(from = 2, to = 3)],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun syncDao(): SyncDao
}

// The Room compiler generates the `actual` implementations for every target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

internal const val DATABASE_FILE_NAME = "todo.db"

// Query context is left at Room's default (Dispatchers.IO); `Dispatchers.IO` is not accessible from
// common code on Kotlin/Native.
fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>, clock: Clock): AppDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .addMigrations(Migration1To2(clock))
        .build()
