# Stage 3: client networking + sync engine

## Goal

Local changes reach the server and changes from other devices reach Room, automatically,
without the UI knowing anything about the network. After this stage two clients (e.g.
desktop + Android emulator) stay in sync. No UI changes yet (sync indicator = stage 5),
no live push (SSE = stage 4): sync runs on start, after local writes and on retry.

## Principles (from stage 1 and 2, do not break)

- Room is the single source of truth; the UI keeps observing Room only.
- Local writes stay instant (`PENDING`); networking happens in the background.
- Conflict rule everywhere: last-write-wins on `updatedAt`; on a tie the existing version stays,
  **except** when applying a push response, where the server arbitrates (see "Remote apply rules").
- Server contract = `api` module DTOs. JSON is tolerant (`ignoreUnknownKeys = true`).

## Building blocks

### Networking (`shared`)

- `shared` depends on `:api`. Ktor Client with content negotiation + kotlinx JSON.
  Engines: OkHttp (Android and JVM desktop), Darwin (iOS).
- `TodoApi` interface in `data/remote`:
  - `suspend fun push(todo: TodoDto): TodoDto` (PUT, returns the server's winning version)
  - `suspend fun pull(since: Long, serverId: String?): ChangesResponse`
- `KtorTodoApi(client: HttpClient, baseUrl: String)` implements it. The `HttpClient` is
  injected, so tests can pass the client from Ktor's `testApplication`.
- The client configuration (JSON, timeouts) lives in one function used by production code and
  tests alike, so tests exercise the real configuration.
- A non-2xx response throws an `ApiException` carrying the status and the response body, so the
  engine can tell a permanent rejection (4xx) from a transient failure (5xx, network).
- Timeouts: connect 5 s, request 15 s. Without them a hung server would hold the single-flight
  lock forever and leave the state at `Syncing`.
- Base URL from the Koin platform module: `http://localhost:8080` on Android, desktop and the iOS
  simulator. On the Android emulator `localhost` is the emulator itself, so the port is tunnelled
  to the host with `adb reverse` (`scripts/android-reverse.sh`); the usual `10.0.2.2` host alias is
  not reachable on every machine.
  Android needs `INTERNET` permission and cleartext allowed for `localhost` only
  (network security config), not globally. iOS needs `NSAllowsLocalNetworking` (ATS) in
  `Info.plist` for plain HTTP to `localhost`.

### Sync state storage (Room v3)

- New table `sync_state` (single row, fixed primary key): `serverId: String?`, `revision: Long`.
  No row = never synced (`serverId = null`, `revision = 0`); the migration seeds nothing.
- Schema v3 via Room `AutoMigration(2, 3)` (only a new table) + a migration test.
- No destructive fallback (unchanged rule).

### New DAO methods (sync only, never used by the UI)

They live in a separate `SyncDao`, so `TodoDao` keeps its guarantee that tombstones are invisible.

- `getPending(): List<TodoEntity>`: rows with `syncStatus = 'PENDING'`, tombstones included.
- `observePendingCount(): Flow<Int>`.
- `getAnyById(id)`: like `getById` but includes tombstones.
- `markSynced(id, expectedUpdatedAt)`: `UPDATE … SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :expectedUpdatedAt`.
  If the user edited the row while the push was in flight, `updatedAt` changed, nothing is
  updated and the row stays `PENDING` (it will be pushed again). **This guard is the core
  race protection of the whole sync.**
- `upsertRemote(entity)`: writes a server version as `SYNCED`, including tombstones and
  resurrections. Bypasses the "tombstones are never touched" rule of user writes on purpose.
- `markAllPending()`: for the reset case. It must **not** touch `updatedAt`: a bumped timestamp
  would let the re-pushed rows beat newer edits made on other clients.
- Read cursor / save cursor for `sync_state`.
- Applying a remote version (read local row, compare, write) and updating `sync_state` run in
  **one immediate write transaction**. The read must be inside it: otherwise a user edit between
  the read and the write would be overwritten by an older remote version.

### Remote apply rules (in one place, `SyncStore`)

**Pull items.** For a remote `TodoDto` and the local row (tombstones included):
- no local row → insert as `SYNCED`;
- `remote.updatedAt > local.updatedAt` → overwrite local with remote, `SYNCED`
  (remote is newer; if local was `PENDING`, it lost under last-write-wins anyway);
- otherwise (local newer or equal) → keep local untouched.

**Push response.** The response is the server's winning version of the row we just pushed, so
the server is the arbiter here, including on a tie. For the response and the current local row:
- `response.updatedAt >= local.updatedAt` → adopt the server's version, `SYNCED`. This covers
  "the server stored what we sent", "the server had a newer version" and a tie where the server
  kept a different version with the same `updatedAt`. Under the pull rule a tie would keep the
  local content and mark it `SYNCED` while the server (and every other client) holds different
  content, and the two sides would never converge.
- `response.updatedAt < local.updatedAt` → the user edited the row while the push was in
  flight. Keep local: it stays `PENDING` and the next sync pushes it.
- When the response is exactly what we sent, `markSynced(id, sent.updatedAt)` is used (a single
  guarded statement); the comparison above is what protects every other case.

## `SyncEngine.sync(): SyncResult`

Single flight: a `Mutex`; a call while a sync runs waits and then runs once more
(changes made during the running sync must not be missed).

1. **Push.** For each row from `getPending()`, in order:
   - `api.push(dto)`; then, in one transaction: apply the response with the push-response rule.
   - HTTP **4xx** (except 408 and 429) for a single row means the server permanently rejects it
     (e.g. validation). Log it clearly (row id, status, response body), leave the row `PENDING`,
     **skip it and continue** with the remaining rows and the pull, and return a failure at the
     end. Retrying cannot fix it, and blocking every row behind it would be worse.
     (Marking such rows as permanently rejected is a follow-up; until then they are retried on
     every sync.)
   - Network error, timeout, 5xx, 408, 429: stops the sync (see 3).
2. **Pull.** `api.pull(since = state.revision, serverId = state.serverId)`:
   - `reset = false` → apply every item with the pull rule, then save `revision` and
     `serverId`, all in one transaction.
   - `reset = true` → the server lost its data. In one transaction: `markAllPending()`
     (tombstones included) **first**, then apply items with the pull rule, then save the new
     `serverId` and `revision`. Then run **push again** so the restarted server is rebuilt from
     this client.
     The order matters: rows that come back from the server snapshot end up `SYNCED`, and only
     rows that exist locally only (or are newer) stay `PENDING`. Marking after applying would
     re-push rows we just received.
3. Errors: a network/HTTP error stops the sync. Rows already pushed stay `SYNCED`, the rest
   stay `PENDING`, the cursor is unchanged. Return a failure result, never throw to the caller.
   Cancellation (`CancellationException`) is rethrown as usual.

`SyncEngine` exposes `state: StateFlow<SyncState>` (`Idle`, `Syncing`, `Failed(at)`) for stage 5.
No UI uses it yet. `at` comes from the injected `Clock`.

## Triggers (`SyncScheduler`, common code)

App-scoped coroutine, started from Koin/app start on every platform (not when the object is
created, so building the DI graph in a test never starts a sync):
- once on start;
- when `observePendingCount()` becomes > 0, debounced ~500 ms (no coupling between the
  repository and sync: the scheduler reacts to Room);
- after a successful sync, if rows are still pending (e.g. edited during the push), run again;
- after a failure: retry with exponential backoff (1 s, 2 s, 4 s … max 30 s) while rows are
  pending or the last pull failed; the backoff resets after a success.

One worker loop runs the syncs one after another. New pending-count emissions never cancel a
sync that is already running.

**Android extra (do last, droppable if time runs out):** after a failed sync, also enqueue a
one-time WorkManager job with `NetworkType.CONNECTED` (unique name, `KEEP`) that calls
`SyncEngine.sync()`, so changes made offline are uploaded when the network returns even if the
app is in the background.
Common code only knows a small `BackgroundSyncFallback` interface (called by the scheduler after a
failed sync); Android provides the WorkManager implementation, desktop and iOS provide none.
The fallback is **not** asked, and the worker does not ask WorkManager to retry, when the only
thing left is rows the server refuses (`Failure.onlyRejectionsLeft`): another attempt would be
refused again. The in-app retry with backoff is unchanged. Not covered by automated tests (it
needs WorkManager); verified by the Android build and the manual check.

## Tests (must pass)

`SyncEngine` with an in-memory Room database and a `FakeTodoApi` (jvmTest):
- pending row pushed → `SYNCED`; tombstone pushed → `SYNCED` tombstone.
- **edit during push** (fake api suspends on a `CompletableDeferred`, the test edits the row
  meanwhile) → row stays `PENDING` with the newer content, and the next sync pushes it.
- push response with a newer server version → local overwritten, `SYNCED`.
- **push response tie**: the server holds a different version with the same `updatedAt` → local
  adopts the server's version, `SYNCED`. (The same tie in a pull keeps the local row.)
- pull: new item inserted, newer item overwrites, older item ignored, tombstone hides the
  todo from `observeAll`, newer remote edit resurrects a local tombstone.
- pull never overwrites a newer local `PENDING` row.
- reset: all local rows become `PENDING` without changing `updatedAt`, new `serverId` stored,
  rows that came back from the server snapshot are `SYNCED`, the others are re-pushed.
- network error mid-push: pushed rows `SYNCED`, others `PENDING`, cursor unchanged; next
  sync completes.
- **4xx on one row**: the other rows are pushed and the pull runs, the rejected row stays
  `PENDING`, the result is a failure; a 5xx stops the sync instead.
- cursor and items are saved atomically (failure while applying → cursor not advanced).
- two concurrent `sync()` calls → no double push of the same version, second run happens.
- request timeout on the client → the call fails instead of hanging.
- `SyncScheduler`: a local write triggers a sync after the debounce (virtual time); backoff
  sequence and reset; rerun while rows are still pending.
- Migration v2 → v3 test.

**End-to-end** (shared jvmTest, real server in-process via `testApplication`, two clients =
two in-memory databases + two `SyncEngine`s using the test client):
- A creates → A syncs → B syncs → B has it.
- A edits and B edits the same todo offline → both sync → both end with the newer edit.
- A deletes while "offline" (push fails) → later sync → B receives the tombstone.
- Server restart (new store + new `serverId`) → A syncs (reset, re-push) → new client C sees
  everything.

## Manual check

`docker compose up`, then (once, after the emulator has booted) `scripts/android-reverse.sh` so the
emulator's `localhost:8080` reaches the server. The rule can disappear silently (adb server restart,
emulator reconnect → `Failed to connect to localhost:8080`); `scripts/android-reverse.sh --watch`
keeps it in place for the whole session. Desktop app + Android emulator: create on one, appears on the other
after the other syncs (restart it or make a local change to trigger a sync; live updates come
in stage 4). Airplane mode on the emulator: edit, back online → change reaches the server.

## Out of scope

SSE / live updates, UI changes (sync indicator), auth, tombstone purge, paging of pulls,
marking permanently rejected rows (follow-up).
