# Stage 1: data model ready for offline-first sync

## Goal

Change the local data model so todos can later be synced with a backend, without any
networking yet. After this stage the app behaves exactly as before for the user.

## Why

- Offline, the client must create ids itself: replace the auto-increment `Long` id with a
  client-generated UUID (`String`).
- The sync needs to know what changed and when: add timestamps and a sync status.
- Deletions must reach other devices: deleting becomes a soft delete (tombstone).

## Changes

### Entity `TodoEntity` (table `todos`, schema version 2)

| column       | type            | notes                                           |
|--------------|-----------------|-------------------------------------------------|
| `id`         | String, PK      | UUID generated on the client (`kotlin.uuid.Uuid`) |
| `title`      | String          |                                                 |
| `description`| String          |                                                 |
| `isDone`     | Boolean         |                                                 |
| `createdAt`  | Long (epoch ms) | used for list ordering                          |
| `updatedAt`  | Long (epoch ms) | set on every local change                       |
| `deletedAt`  | Long? (epoch ms)| non-null = soft deleted (tombstone)             |
| `syncStatus` | String enum     | `SYNCED` or `PENDING`                           |

### Behaviour

- Every local write (create, update content, toggle done, delete) sets `updatedAt = now`
  and `syncStatus = PENDING`.
- Delete = set `deletedAt = now` (+ the above). The row stays in the table.
- Queries used by the UI ignore rows with `deletedAt != null`.
- List order: `createdAt DESC` (same visual order as before: newest first, toggling done does
  not move items).
- `now` comes from an injected `kotlin.time.Clock` (Koin provides `Clock.System`).

### Domain / UI

- `Todo.id` becomes `String`. `TodoRepository.createTodo` returns the new `String` id.
- `TodoDetailRoute.todoId` becomes `String?`.
- Domain model does not expose `syncStatus`/timestamps yet (not needed by the UI in this stage).

### Migration 1 → 2

- SQLite cannot change a primary key type: create a new table, copy rows, drop the old one,
  rename.
- Each existing row gets a new UUID.
- Preserve the existing order: old order was `id DESC`. Set
  `createdAt = migrationTime - (maxOldId - oldId)` (ms) so relative order is kept.
- `updatedAt = createdAt`, `deletedAt = null`, `syncStatus = PENDING` (existing todos will be
  uploaded on the first sync).

## Tests (must pass)

- Migration test 1 → 2 (jvmTest, `MigrationTestHelper`): data preserved, order preserved,
  ids are unique valid UUIDs, all rows `PENDING`.
- DAO tests updated + new: soft-deleted rows are hidden from `observeAll`/`getById`;
  each write sets `updatedAt` and `PENDING` (fixed test clock).
- Existing ViewModel tests updated for `String` ids, still green.
- `./gradlew :shared:jvmTest` and `./gradlew :androidApp:assembleDebug` succeed.

## Out of scope

Networking, sync engine, backend, UI changes.
