# Stage 3: client networking + sync engine

## Goal

Local changes reach the server and changes from other devices reach Room, automatically,
without the UI knowing anything about the network. After this stage two clients (e.g.
desktop + Android emulator) stay in sync. No UI changes yet (sync indicator = stage 5),
no live push (SSE = stage 4): sync runs on start, after local writes and on retry.

## Principles (from stage 1 and 2, do not break)

- Room is the single source of truth; the UI keeps observing Room only.
- Local writes stay instant (`PENDING`); networking happens in the background.
- Conflict rule everywhere: last-write-wins on `updatedAt`; on a tie the existing version stays.
- Server contract = `api` module DTOs. JSON is tolerant (`ignoreUnknownKeys = true`).

## Building blocks

### Networking (`shared`)

- `shared` depends on `:api`. Ktor Client with content negotiation + kotlinx JSON.
  Engines: OkHttp (Android), OkHttp or CIO (JVM desktop), Darwin (iOS).
- `TodoApi` interface in `data/remote`:
  - `suspend fun push(todo: TodoDto): TodoDto` (PUT, returns the server's winning version)
  - `suspend fun pull(since: Long, serverId: String?): ChangesResponse`
- `KtorTodoApi(client: HttpClient, baseUrl: String)` implements it. The `HttpClient` is
  injected, so tests can pass the client from Ktor's `testApplication`.
- Base URL from the Koin platform module: Android `http://10.0.2.2:8080` (emulator → host),
  desktop `http://localhost:8080`, iOS simulator `http://localhost:8080`.
  Android needs `INTERNET` permission and cleartext allowed for these hosts only
  (network security config), not globally.

### Sync state storage (Room v3)

- New table `sync_state` (single row): `serverId: String?`, `revision: Long`.
- Schema v3 via Room `AutoMigration(2, 3)` (only a new table) + a migration test.
- No destructive fallback (unchanged rule).

### New DAO methods (sync only, never used by the UI)

- `getPending(): List<TodoEntity>`: rows with `syncStatus = 'PENDING'`, tombstones included.
- `observePendingCount(): Flow<Int>`.
- `getAnyById(id)`: like `getById` but includes tombstones.
- `markSynced(id, expectedUpdatedAt)`: `UPDATE … SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :expectedUpdatedAt`.
  If the user edited the row while the push was in flight, `updatedAt` changed, nothing is
  updated and the row stays `PENDING` (it will be pushed again). **This guard is the core
  race protection of the whole sync.**
- `upsertRemote(entity)`: writes a server version as `SYNCED`, including tombstones and
  resurrections. Bypasses the "tombstones are never touched" rule of user writes on purpose.
- `markAllPending()`: for the reset case.
- Applying a remote version and updating `sync_state` run in one transaction.

### Remote apply rule (one function, used by push responses and pull)

For a remote `TodoDto` and the local row (tombstones included):
- no local row → insert as `SYNCED`;
- `remote.updatedAt > local.updatedAt` → overwrite local with remote, `SYNCED`
  (remote is newer; if local was `PENDING`, it lost under last-write-wins anyway);
- otherwise (local newer or equal) → keep local untouched.

## `SyncEngine.sync(): SyncResult`

Single flight: a `Mutex`; a call while a sync runs waits and then runs once more
(changes made during the running sync must not be missed).

1. **Push.** For each row from `getPending()`:
   - `api.push(dto)`; then, in one transaction: apply the response with the remote apply
     rule, and `markSynced(id, expectedUpdatedAt = the updatedAt that was sent)`.
   - Order of the two writes matters: if the server returned a newer version, the apply rule
     already set the row `SYNCED`; `markSynced` then simply matches nothing or the same value.
2. **Pull.** `api.pull(since = state.revision, serverId = state.serverId)`:
   - `reset = false` → apply every item with the remote apply rule, then save `revision` and
     `serverId`, all in one transaction.
   - `reset = true` → the server lost its data. In one transaction: apply items with the rule,
     `markAllPending()` (tombstones included), save the new `serverId` and `revision`.
     Then run **push again** so the restarted server is rebuilt from this client.
3. Errors: a network/HTTP error stops the sync. Rows already pushed stay `SYNCED`, the rest
   stay `PENDING`, the cursor is unchanged. Return a failure result, never throw to the caller.
   Cancellation (`CancellationException`) is rethrown as usual.

`SyncEngine` exposes `state: StateFlow<SyncState>` (`Idle`, `Syncing`, `Failed(at)`) for stage 5.
No UI uses it yet.

## Triggers (`SyncScheduler`, common code)

App-scoped coroutine, started from Koin/app start on every platform:
- once on start;
- when `observePendingCount()` becomes > 0, debounced ~500 ms (no coupling between the
  repository and sync: the scheduler reacts to Room);
- after a failure: retry with exponential backoff (1 s, 2 s, 4 s … max 30 s) while rows are
  pending or the last pull failed.

**Android extra (do last, droppable if time runs out):** after a failed sync, also enqueue a
one-time WorkManager job with `NetworkType.CONNECTED` (unique name, `KEEP`) that calls
`SyncEngine.sync()`, so changes made offline are uploaded when the network returns even if the
app is in the background.

## Tests (must pass)

`SyncEngine` with an in-memory Room database and a `FakeTodoApi` (jvmTest):
- pending row pushed → `SYNCED`; tombstone pushed → `SYNCED` tombstone.
- **edit during push** (fake api suspends on a `CompletableDeferred`, the test edits the row
  meanwhile) → row stays `PENDING` with the newer content, and the next sync pushes it.
- push response with a newer server version → local overwritten, `SYNCED`.
- pull: new item inserted, newer item overwrites, older item ignored, tombstone hides the
  todo from `observeAll`, newer remote edit resurrects a local tombstone.
- pull never overwrites a newer local `PENDING` row.
- reset: all local rows become `PENDING`, new `serverId` stored, rows re-pushed.
- network error mid-push: pushed rows `SYNCED`, others `PENDING`, cursor unchanged; next
  sync completes.
- cursor and items are saved atomically (failure while applying → cursor not advanced).
- two concurrent `sync()` calls → no double push of the same version, second run happens.
- `SyncScheduler`: a local write triggers a sync after the debounce (virtual time).
- Migration v2 → v3 test.

**End-to-end** (shared jvmTest, real server in-process via `testApplication`, two clients =
two in-memory databases + two `SyncEngine`s using the test client):
- A creates → A syncs → B syncs → B has it.
- A edits and B edits the same todo offline → both sync → both end with the newer edit.
- A deletes while "offline" (push fails) → later sync → B receives the tombstone.
- Server restart (new store + new `serverId`) → A syncs (reset, re-push) → new client C sees
  everything.

## Manual check

`docker compose up`, desktop app + Android emulator: create on one, appears on the other
after the other syncs (restart it or make a local change to trigger a sync; live updates come
in stage 4). Airplane mode on the emulator: edit, back online → change reaches the server.

## Out of scope

SSE / live updates, UI changes (sync indicator), auth, tombstone purge, paging of pulls.
