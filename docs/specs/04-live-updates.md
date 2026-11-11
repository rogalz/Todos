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
  are not dropped. The period is a parameter of `module(...)` so tests do not wait 15 s. Ktor's
  `heartbeat {}` sends its first comment immediately, so on the wire a comment can come *before*
  `hello`; clients ignore comments, so `hello` is still the first *event*.
- Event names and payload DTO (`ServerEvent`) live in `api`.
- `hello` is sent from `onSubscription` of the store's flow: the connection is subscribed
  **before** the revision for `hello` is read, so a write in between is buffered, not lost.
- The store's flow has no replay and a small buffer that drops the oldest event: a slow or
  stalled SSE client can never block a write, and losing an old event is harmless because only
  the newest revision matters (any event makes the client pull).

## Client

- `TodoEvents` in `data/remote`: `fun events(): Flow<ServerEvent>` using the Ktor client SSE
  plugin. Cold: connects when collected, disconnects when the collector is cancelled.
- **The 15 s request timeout from stage 3 must not apply to the SSE request** (the stream is
  long-lived by design); keep the connect timeout. Heartbeats keep it alive.
- **The SSE request sets an explicit socket (idle) timeout of 45 s = three missed heartbeats.**
  The request timeout is not the only thing that can cut an idle stream (engines have their own
  read/idle timeouts, which `testApplication` cannot show), and the client has no other way to
  notice a dead (half-open) connection: the server only detects dead peers when a write fails.
  Verified, not assumed: Ktor's OkHttp engine starts from a plain `OkHttpClient`, whose default
  read timeout is 10 s, shorter than the 15 s heartbeat; without the explicit timeout a test
  against the real OkHttp engine fails. (Ktor already exempts SSE from the *request* timeout; the
  explicit infinite request timeout is stated anyway so nothing depends on that detail.)
  Darwin applies the same value as the request's idle `timeoutInterval`.
- `TodoEvents` is an interface (like `TodoApi`) with a Ktor implementation, so tests use fakes.
  Unknown event names, malformed data and heartbeat comments are ignored (tolerant reader).
  A normal close of the stream ends the flow.
- `LiveUpdates` (common): collects events only while the app is in the foreground:
  - on `hello` (we were disconnected, maybe missed changes) it calls
    `SyncScheduler.serverIsReachable()`: the server just proved it is reachable, so the
    scheduler forgets its failure backoff and syncs **now**;
  - on `changed` it calls `SyncScheduler.requestSync()`, but only when the revision is greater
    than the stored cursor or the `serverId` differs. Skipping known revisions avoids a needless
    pull after our own push.
  - `changed` carries only the revision, so "the `serverId`" is the one from the `hello` of the
    **current connection**. A `changed` before any `hello` cannot be compared and always syncs.
    This matters after a server restart: the revision counter starts again at 1, so a
    `changed 3` that arrives before the `hello`-triggered sync has stored the new cursor would
    otherwise be compared with the old server's cursor (say 50) and wrongly skipped.
  - On error, disconnect **or a normal close of the stream**: reconnect with backoff
    (1 s, 2 s, 4 s, 8 s, then 10 s max). The cap is lower than the scheduler's 30 s on purpose:
    an attempt costs almost nothing, and recovery after going offline should be quick.
  - "Successful connection" = a `hello` was received; that resets the backoff. There is no
    minimum uptime, so a server that accepts and then drops every connection is retried every 1 s.
  - The backoff is one function (`SyncScheduler.backoffMillis(failures, maxMillis)`), used by both.
- Foreground signal: `AppForeground`, a small interface exposing `StateFlow<Boolean>` (a bare
  `StateFlow<Boolean>` would be a poor Koin binding: Koin keys on the raw class). Android:
  `ProcessLifecycleOwner` (STARTED = foreground). Desktop and iOS: always `true` for now.
  Gating on foreground also keeps a process that WorkManager started in the background from
  opening a stream. Implementation: `foreground.collectLatest { if (it) stayConnected() }`
  (equivalent to `flatMapLatest { ... emptyFlow() }`).
- `SyncScheduler` gets two entry points:
  - `requestSync()`: conflated, a burst of events never becomes a queue of syncs. That means
    **at most one running and one queued**: requests that arrive while a sync runs collapse into
    exactly one follow-up; a burst that hits an idle worker starts one sync at once (the first
    request is handed to the worker) and queues at most one more for the rest. It respects the
    failure backoff, like a local write does.
  - `serverIsReachable()`: resets the failure backoff and syncs immediately. A signal that
    arrives *during* a sync that then fails also cuts the backoff short (the server was
    reachable after that sync started); one that arrived *before* the sync started does not
    (that sync failed after it, so the failure is real).
- Started together with the scheduler from `startKoinApp`, not on graph construction.

### Accepted limits

- **Echo of our own push:** the cursor only moves on pull. If the echo of our own change beats
  the pull of the same sync, one extra (cheap) sync runs. Conflation bounds it; no attempt to
  eliminate it.
- **Duplicate start-up sync:** at launch the scheduler's own sync and the `hello` sync can run
  back to back; the second finds nothing to do.
- **Latency:** the end-to-end time is dominated by the sender's 500 ms debounce
  (`SyncScheduler`), so the ~1 s target holds with little slack. The debounce is not changed here.

## Tests (must pass)

Server (`testApplication`):
- connect → `hello` with current `serverId` and revision.
- applied `PUT` → `changed` with the new revision; no-op `PUT` (same or older) → no event.
- two connected clients both receive the event.

Client (virtual time, fakes):
- `hello` → sync requested; `changed` above the cursor → sync requested; `changed` at or
  below the cursor → no sync.
- `changed` before any `hello` → sync; `changed` with a different `serverId` than the cursor's
  → sync even when its revision is lower.
- burst of 10 events while a sync runs → exactly one follow-up; a burst of 10 in the same
  instant → at most one running and one queued, never ten (conflation, tested on
  `SyncScheduler.requestSync()`).
- stream error or normal close → reconnect with backoff (1, 2, 4, 8, 10, 10 s); backoff resets
  after `hello`.
- foreground → background → the stream is cancelled; back to foreground → reconnects.
- `SyncScheduler`: failing syncs → backoff at its cap → `serverIsReachable()` → the sync runs
  now and the next failure starts again at 1 s; a signal during a failing sync cuts the backoff;
  a signal while idle does not shorten a later backoff.
- SSE request is not cut by the request timeout (a test with a stream idle longer than the
  request timeout, using heartbeats). A stream that goes silent for longer than the socket
  timeout ends (as an error or as a normal-looking close, see the client transport note below;
  `LiveUpdates` reconnects after either).

Client transport (real OkHttp engine against a scripted raw-socket server, because
`testApplication` has no sockets):
- the socket timeout is enforced (a silent stream ends after it; whether that shows as an error or
  as a normal end is a race inside Ktor's SSE session, which swallows a `CancellationException`, so
  the test does not assert it); heartbeats keep a stream open
  beyond both the socket and the request timeout; cancelling the collector closes the connection;
  unknown events and unreadable data are skipped; a non-2xx answer and a refused connection fail
  the flow; a normal close completes it.

End-to-end (real server in-process, two clients):
- A collects live updates; B creates a todo and syncs → A's database contains it without
  calling `sync()` on A directly.
- Create / edit / tick / delete in both directions between two live devices, after which nothing
  keeps syncing (no ping-pong from the echoes).
- A device in the background misses the live update and catches up from the `hello` as soon as it
  is back in the foreground.

## Manual check

`docker compose up`, `scripts/android-reverse.sh --watch` (left running: the reverse rule can vanish
silently, e.g. after an adb server restart, and then the stream and every sync fail with
`Failed to connect to localhost:8080`; the watcher prints a line whenever it had to restore the rule),
desktop app + emulator side by side:
create / edit / tick / delete on one, it appears on the other within ~1 s, both directions.
Put the Android app in the background, change something on desktop, bring Android back:
the change appears right after reconnecting.

## Out of scope

Sending todo data in events, per-user channels, auth, WebSockets, Android background push.
