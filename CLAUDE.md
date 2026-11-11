# CLAUDE.md

Instructions for Claude Code working in this repository.

## Project

Small offline-first to-do app, Compose Multiplatform (Android, Desktop/JVM, iOS) + Room KMP.
It is being extended with: a local Ktor backend, background sync, live updates via SSE,
and voice-to-todo parsing with a local LLM (Ollama). Work is done in stages; each stage has a
spec in `docs/specs/`. Implement only what the current spec asks for.

## Architecture (keep it)

- `shared/src/commonMain`: all app code, layered `ui` → `domain` → `data`.
  - `domain`: plain models + `TodoRepository` interface. No Room/Ktor types here.
  - `data`: Room entities, DAO, repository implementation, mappers.
  - `ui`: screens + ViewModels exposing `StateFlow` UI state.
- Platform source sets contain only platform glue (database builder, Koin platform module).
- **Room is the single source of truth.** The UI only observes Room. Network code never talks
  to the UI directly; it writes to Room and the UI reacts via Flow.

## Conventions already used in the code (follow them)

- Repository writes run in `withContext(NonCancellable)` so leaving a screen never aborts a write.
- Use `runSuspendCatching` (never plain `runCatching`) in suspend code: it rethrows
  `CancellationException`.
- No destructive migration fallback. Every schema change needs a real `Migration` and a
  migration test. Exported schemas in `shared/schemas/` are committed.
- ViewModels expose `StateFlow`; screen-level flows use `stateIn(WhileSubscribed(5_000))`.
- Comments explain *why*, not *what*.
- Time comes from an injected `kotlin.time.Clock`, never `Clock.System` directly in logic,
  so tests can control it.

## Commands

- Tests (main loop): `./gradlew :shared:jvmTest`
- Android host tests: `./gradlew :shared:testAndroidHostTest`
- Android build: `./gradlew :androidApp:assembleDebug`
- Desktop app: `./gradlew :desktopApp:run`
- API contract tests (`api` module): `./gradlew :api:jvmTest`
- Server tests: `./gradlew :server:test`
- Server (local): `./gradlew :server:run` → `http://localhost:8080` (`PORT` env to change it)
- Server in Docker: `./gradlew :server:buildFatJar && docker compose up --build` (the image only
  copies the host-built jar)
- Server smoke test against a running server: `scripts/smoke.sh [base-url]` (checks status codes and
  key body fragments, exits 1 on any failure; the delta checks assume a freshly started server)
- Android emulator → local server: `scripts/android-reverse.sh` (`adb reverse tcp:8080 tcp:8080`).
  The Android app uses `http://localhost:8080` (not `10.0.2.2`), so run it once after the emulator
  has booted; it needs `adb` on PATH. The rule can vanish silently later (adb server restart,
  emulator reconnect: the app then fails with `Failed to connect to localhost:8080`); leave
  `scripts/android-reverse.sh --watch` running in a terminal to have it restored every 5 s (a line is
  printed each time). Without the flag the script applies the rule once and exits.
- Coverage report: `./gradlew koverHtmlReport` → `build/reports/kover/html/index.html` (XML:
  `koverXmlReport` → `build/reports/kover/report.xml`). Kover aggregates `api`, `server` and `shared`
  at the root and runs the tests it needs (JVM and Android host; iOS cannot be measured).
- Test + coverage summary table (what CI writes to the run page), after a test run with the XML
  report: `python3 scripts/ci-summary.py`

## CI

- `.github/workflows/ci.yml` runs on every push to any branch (no `pull_request` trigger: the push
  already covers PR branches). One run per ref; a newer push cancels the older run.
- Job `test`: `./gradlew :api:jvmTest :server:test :shared:jvmTest :shared:testAndroidHostTest
  koverXmlReport koverHtmlReport --continue`, then `./gradlew koverVerify` (coverage floor, skipped
  when the tests failed), then a tests + coverage table on the run page (`scripts/ci-summary.py`,
  runs even when a gate fails). Artifacts: `coverage-report` (Kover HTML, always) and `test-reports`
  (only when the run fails).
- Job `android-build` (parallel): `:androidApp:assembleDebug`, uploads the debug APK as artifact
  `app-debug-apk` (7 days).
- Job `server-docker` (parallel): `:server:buildFatJar`, `docker build`, runs the container, waits
  for `/health` (30 s), runs `scripts/smoke.sh` against it, always prints the container logs. To run
  the same check locally: build the jar, `docker build -t todo-server:ci .`,
  `docker run -d --name todo-server -p 8080:8080 todo-server:ci`, `scripts/smoke.sh`.
- Dependabot (`.github/dependabot.yml`): weekly, `gradle` (the version catalog) and `github-actions`,
  related artifacts grouped in one PR (Kotlin + KSP, Ktor, AndroidX, Compose, ...). Its PRs go to the
  default branch (`develop`), where CI runs on them like on any push.
- **Coverage floor:** `koverVerify` enforces a minimum *line* coverage on the aggregated report. The
  value is `minBound(...)` in the root `build.gradle.kts` (currently 65: the total was 67.4 %, rounded
  down to a multiple of 5). **Raising it is a manual decision**: when the total has grown, set it to
  the new total rounded down to a multiple of 5. **Never lower it to make a build pass**; if coverage
  dropped, add tests (or, if the drop is real and intended, stop and ask). Check it locally with
  `./gradlew koverVerify`.
- Generated code (Room, Compose compiler and resources, serialization, `@Preview`) is excluded in the
  root `build.gradle.kts`. Do not exclude hand-written code to raise the number.
- Only official `actions/*` and `gradle/actions/*`; ask before adding any other action.

## How to work

- Before coding a stage, read its spec and propose a short plan; wait for approval.
- Run `./gradlew :shared:jvmTest` after each meaningful change; the stage is not done while
  tests fail.
- Never weaken or delete an existing test to make it pass. If a test seems wrong, stop and say so.
- Ask before adding a new dependency; add it via `gradle/libs.versions.toml`.
- Keep changes scoped to the current stage. Suggest follow-ups instead of doing them.
- Concurrency, sync and migration code: keep it simple and explicit, document edge cases
  in comments and cover them with tests.
