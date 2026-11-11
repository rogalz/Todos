package com.example.ai.data.local

/**
 * Persisted by name (TEXT) and also written as a literal in [TodoDao] queries and in the 1→2
 * migration, so renaming a value is a schema change: it needs a migration, not just a refactor.
 */
enum class SyncStatus {
    /** The server has this exact version of the row. */
    SYNCED,

    /** Changed locally (created, edited, toggled or deleted) and not uploaded yet. */
    PENDING,
}
