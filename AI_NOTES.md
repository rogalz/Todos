# AI_NOTES

A running log of building this project with an AI coding agent (Claude Code):
where it was strong, where it failed, how I verified the output, and what I learned
about delegating work to AI.

## Method

- I write specs (`docs/specs/`) and the important tests; the agent writes most of the code.
- I verify through tests, running the app and reviewing the critical parts line by line
  (migrations, sync, concurrency). UI and boilerplate get a lighter review.

## Log

<!-- Template:
## YYYY-MM-DD — <topic>
- **Task:**
- **What the AI did:**
- **Problem / surprise:** (or "worked first time")
- **How I caught it:**
- **Takeaway:**
-->

## 2026-11-08 — Stage 0: initial setup
- **Task:** Create very simple todo app with two screens. First one in a list of todos, second is todo details. 
- **What the AI did:** implemented everything according to my guidelines
- **Surprise:** with only one high quality prompt AI has implemented KMP app which worked as expected at the very first run. During code review I changed a few minor things 
(e.q retrying for TodoListViewModel was based on int - from my perspective not necessarily)
- **How I verified:** unit tests generated with another agent, manual testing of the app. 
- **Takeaway:** quality of prompt is crucial. 

## 2026-11-10 — Stage 1: sync-ready data model + migration
- **Task:** add UUID ids, timestamps, soft delete, migration v1→v2 (spec 01).
- **What the AI did:** implemented it with 69 green tests (45 before); flagged in its plan a
  destructive-migration fallback that contradicted CLAUDE.md, and a Koin param `getOrNull<Long>()`
  that would still compile but turn every edit into a duplicate.
- **Surprise:** it mutation-tested its own migration (4 mutations + re-adding the fallback)
  to prove the tests catch them. It also kept `toEntity()` against its own plan because my
  "keep the same assertions" instruction conflicted with removing it, and reported it as a deviation.
- **How I verified:** plan review before coding, test report, manual migration on my real v1 database.
- **Takeaway:** a written spec + CLAUDE.md rules made the agent surface conflicts instead of
  guessing. Runtime-only wiring (DI params) is a blind spot for the compiler, so guard it with a test.

## 2026-11-10 — Stage 1b: Koin & Konsist tests
- **Task:** add tests for Koin (way of building components), Konsist (verifies correct division between layers)
- **What the AI did:** ArchitectureTest, KoinGraphTest
- **Surprise:** I didn't know there is a possibility to test these cases
- **How I verified:** Read tests 
- **Takeaway:** knowledge about Konsist 

## 2026-11-10 — Stage 2: local sync backend (Ktor + Docker)
- **Task:** spec 02: an `api` module with DTOs shared by client and server, a Ktor server with
  `GET /todos?since` (changes after a revision cursor) and an idempotent `PUT /todos/{id}`
  with last-write-wins, in-memory storage, Docker.
- **What the AI did:** wrote the tests before the code (33 server tests, 5 contract tests incl.
  golden JSON), then mutation-checked them: 10 deliberate bugs, 10 caught. Verified the server
  for real with curl, `java -jar` and Docker, including a container restart.
- **Surprise:**
  - It found a hole in **my** spec while planning: "reset when `since` > revision" stops working
    as soon as the restarted server's counter passes the old cursor. Todos already `SYNCED` would
    then live only on clients and never reach a new device. It proposed a boot-time counter;
    I chose a server instance id (UUID per start, sent back by the client): it changes the
    contract, but answers the real question explicitly: "is this still the same server?".
  - It also corrected my client rule for a reset: not "replace the local view" (data loss) but
    "mark local rows PENDING and re-push"; last-write-wins makes that safe.
  - Its mutation run exposed one of its own tests as vacuous (an all-digit UUID can't test
    case-insensitive parsing).
- **Where I overruled it:** it made JSON parsing strict (unknown fields → 400). Locally safe,
  globally wrong: an older server would reject a newer client. Switched to a tolerant reader
  (`ignoreUnknownKeys`). In the plan I also caught two versions of kotlinx-serialization (1.8 vs 1.12).
- **How I verified:** two rounds of plan review, `scripts/smoke.sh` against the Docker container,
  read `TodoStore` (locking, revision counter, last-write-wins) line by line.
- **Takeaway:** "plan before code" turns the agent into a reviewer of my spec, and a spec bug
  costs minutes in a plan instead of hours in code. Edge cases and tests are its strength;
  decisions with long-term consequences (API evolution, contract shape) stay mine.

## 2026-11-10 — Stage 3: client sync engine
- **Task:** spec 03: Ktor client, sync state in Room (v3), `SyncEngine` (push pending → pull
  changes → re-push after a server restart), `SyncScheduler` triggered by Room's pending count,
  WorkManager fallback on Android, end-to-end tests with two clients and a real in-process server.
- **What the AI did:** in ~35 minutes: 195 green tests in `shared` (74 before), each critical
  mechanism mutation-checked (transaction, lock, 4xx skip, debounce, re-run while pending,
  `markAllPending` on reset, tie rule). It also ran a one-off test over real sockets (OkHttp →
  Netty), then removed it.
- **Surprise:** the plan found eight gaps in my spec, three of them real data bugs:
  - a tie on `updatedAt` would leave client and server different forever;
  - reading, comparing and writing outside one transaction lets an older server version
    overwrite a user edit;
  - a single row rejected with 400 would block every row after it, and the pull.

  It also suggested timeouts, and noticed that an "edit during push" doesn't change the pending
  count, so nothing would trigger the next sync. My fix for the tie (the server arbitrates on
  the push response) it implemented slightly better than I wrote it: it compares against the
  current local row, not the sent one, so an edit made during the push still survives.
- **Where it was blind:** all automated tests were green, yet on the emulator every sync
  timed out: on my machine the emulator can't reach the host via `10.0.2.2`. In-process tests
  can't see the real network. Fixed with `adb reverse` + `localhost` (`scripts/android-reverse.sh`).
  Side effect nobody (me included) predicted: `adb reverse` tunnels through adb, not the
  emulator's network, so airplane mode no longer makes the app offline. Offline is now
  simulated by removing the tunnel (`adb reverse --remove tcp:8080`). A workaround for one
  problem silently changed how another thing has to be tested.
  And removing the tunnel wasn't enough: an edit still reached the server, because OkHttp
  reused a keep-alive connection opened before the tunnel was removed. Removing a route stops
  new connections, not open ones; a reliable "offline" also needs the app process killed.
- **My own mistake:** `docker compose restart` "didn't restart" the server; a `:server:run` from
  an earlier debugging step was still holding port 8080. The constant `serverId` gave it away:
  every server start generates a new one.
- **How I verified:** plan review, test report, manual checks: desktop and Android sync,
  offline edits, and a server restart where the empty server was rebuilt from the clients
  (same active / deleted counts, new `serverId`). Read `SyncStore` and `SyncEngine` line by line.
  Offline conflict on 11.11: deleted a todo on desktop, then edited it on the offline Android;
  after reconnecting the edit won everywhere (resurrection), as last-write-wins defines.
- **Takeaway:** the agent is excellent at concurrency edge cases on paper and at proving its
  tests bite. It can't know my environment: the manual check in the spec found the one problem
  no test could. "Green tests" ≠ "works on the device".
