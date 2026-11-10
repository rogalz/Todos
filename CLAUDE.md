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

## How to work

- Before coding a stage, read its spec and propose a short plan; wait for approval.
- Run `./gradlew :shared:jvmTest` after each meaningful change; the stage is not done while
  tests fail.
- Never weaken or delete an existing test to make it pass. If a test seems wrong, stop and say so.
- Ask before adding a new dependency; add it via `gradle/libs.versions.toml`.
- Keep changes scoped to the current stage. Suggest follow-ups instead of doing them.
- Concurrency, sync and migration code: keep it simple and explicit, document edge cases
  in comments and cover them with tests.
