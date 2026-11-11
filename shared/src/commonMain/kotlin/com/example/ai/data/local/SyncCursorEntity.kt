package com.example.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where this client stands with the server: the one row of the `sync_state` table.
 *
 * The primary key is a constant, so there can never be more than one row. *No* row means "never
 * synced" and is read as `serverId = null, revision = 0`; the migration that created the table
 * therefore does not have to seed anything.
 *
 * @property serverId the server instance the [revision] belongs to. It is sent back with every
 *   pull; a server that answers with `reset` tells us its counter no longer exists.
 * @property revision cursor for the next pull (a server counter, not a time).
 */
@Entity(tableName = "sync_state")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val serverId: String?,
    val revision: Long,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
