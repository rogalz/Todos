# Stage 4: live updates (SSE)

## Goal

A change made on one device shows up on the other devices within ~1 s, without a restart or
a local edit. Sync logic does not change: the server only **signals** "something changed" and
the client reacts by running the existing `SyncEngine.sync()` (push + pull).

## Design

```
Device B: PUT /todos/{id} ─► server applies it, revision++ ─► emits on SharedFlow
                                                              │ SSE  GET /events
Device A: events Flow (hot on the server, cold on the client) ◄┘
            └─► SyncScheduler.requestSync() ─► pull ─► Room ─► UI
```

- **Events carry no todo data.** Data only comes through pull, so there is one write path
  into Room, a missed event loses nothing (the next pull catches up) and an echo of a device's
  own change is harmless.
- SSE is an optimisation for speed, not a delivery guarantee. Correctness still comes from
  pull with the revision cursor.

## Server

- Ktor SSE plugin. Endpoint `GET /events` (`text/event-stream`).
- `TodoStore` exposes a hot `SharedFlow<Long>` of revisions, emitted after every **applied**
  write (a no-op `PUT` emits nothing).
- On connect the server sends `event: hello` with `data` = `{"serverId": …, "revision": …}`,
  then `event: changed` with `data` = the new revision for every applied write.
- Heartbeat: an SSE comment every 15 s so dead connections are detected and idle connections
  are not dropped.
- Event names and payload DTO (`ServerEvent`) live in `api`.

## Client

- `TodoEvents` in `data/remote`: `fun events(): Flow<ServerEvent>` using the Ktor client SSE
  plugin. Cold: connects when collected, disconnects when the collector is cancelled.
- **The 15 s request timeout from stage 3 must not apply to the SSE request** (the stream is
  long-lived by design); keep the connect timeout. Heartbeats keep it alive.
- `LiveUpdates` (common): collects events only while the app is in the foreground and
  calls `SyncScheduler.requestSync()`:
  - on `hello` (we were disconnected, maybe missed changes);
  - on `changed` with a revision greater than the stored cursor (or a different `serverId`).
    Skipping known revisions avoids a needless pull after our own push.
  - On error or disconnect: reconnect with backoff (1 s, 2 s, 4 s … max 30 s), reset after a
    successful connection.
- Foreground signal: `AppForeground: StateFlow<Boolean>`. Android: `ProcessLifecycleOwner`
  (STARTED = foreground). Desktop and iOS: always `true` for now.
  Implementation sketch: `foreground.flatMapLatest { if (it) events().retryWithBackoff() else emptyFlow() }`.
- `SyncScheduler.requestSync()` must be conflated: a burst of events triggers one sync, never
  a queue of syncs.
- Started together with the scheduler from `startKoinApp`, not on graph construction.

## Tests (must pass)

Server (`testApplication`):
- connect → `hello` with current `serverId` and revision.
- applied `PUT` → `changed` with the new revision; no-op `PUT` (same or older) → no event.
- two connected clients both receive the event.

Client (virtual time, fakes):
- `hello` → sync requested; `changed` above the cursor → sync requested; `changed` at or
  below the cursor → no sync.
- burst of 10 events → one sync (conflation).
- stream error → reconnect with backoff; backoff resets after a successful connection.
- foreground → background → the stream is cancelled; back to foreground → reconnects.
- SSE request is not cut by the request timeout (a test with a stream idle longer than the
  request timeout, using heartbeats).

End-to-end (real server in-process, two clients):
- A collects live updates; B creates a todo and syncs → A's database contains it without
  calling `sync()` on A directly.

## Manual check

`docker compose up`, `scripts/android-reverse.sh`, desktop app + emulator side by side:
create / edit / tick / delete on one, it appears on the other within ~1 s, both directions.
Put the Android app in the background, change something on desktop, bring Android back:
the change appears right after reconnecting.

## Out of scope

Sending todo data in events, per-user channels, auth, WebSockets, Android background push.
