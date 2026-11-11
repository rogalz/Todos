# To-do (Compose Multiplatform + Room KMP)

[![CI](https://github.com/rogalz/Todos/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/rogalz/Todos/actions/workflows/ci.yml?query=branch%3Adevelop)

A deliberately small to-do app: a list screen and a details screen, backed by a local Room database.
Targets: Android, Desktop (JVM), iOS.

## Behaviour

- **List**: an empty state invites you to create the first task; a "+" button is always visible.
  Each item has a checkbox, ticking it updates the database immediately.
- **Details**: edit title and description. **Back saves automatically** (arrow, system back, gesture).
  - A new task with an empty title is discarded.
  - An existing task can never end up with an empty title (the old one is kept).
  - Edits of an existing task are also saved when the app goes to the background.
  - A new task is created only by leaving the screen with back, not when the app is backgrounded.
  - Trash icon deletes a task (with confirmation).

## Voice to todo

The small mic button above "New task" turns a sentence into a **draft**: on Android you speak (the
device's speech recognizer), on desktop and iOS you type one sentence. The server asks a local LLM
(Ollama) to turn it into a title and description, which pre-fill a new task marked "Filled by AI,
check before saving". **A draft is only stored by its Save button**; going back asks "Discard draft?".
If the AI cannot be reached the title is your own words and a snackbar says so.

Needs `ollama serve` with the model pulled (`ollama pull llama3.2:3b`). The server reads `OLLAMA_URL`
(default `http://localhost:11434`, `docker-compose.yml` uses `http://host.docker.internal:11434`) and
`OLLAMA_MODEL`. Quality check, not part of CI: `python3 scripts/eval-parse.py` (results in `docs/eval/`).

## Stack

| | |
|---|---|
| UI / navigation | Compose Multiplatform, Navigation Compose (type-safe routes) |
| Database | Room KMP 2.8.5 + `BundledSQLiteDriver`, KSP |
| DI | Koin |
| Architecture | `ui` (screens + ViewModels) → `domain` (`TodoRepository`) → `data` (Room) |

Schema is exported to `shared/schemas/` and must be committed. There is intentionally **no**
destructive-migration fallback: changing the schema needs a real `Migration`.

## Layout

- `shared/src/commonMain` all app code: `data/`, `domain/`, `ui/`, `di/`
- `shared/src/{androidMain,iosMain,jvmMain}` only the platform database builder and Koin module
- `androidApp`, `desktopApp`, `iosApp` thin entry points (start Koin, show `App()`)

## Running

- Android: `./gradlew :androidApp:assembleDebug`
- Desktop: `./gradlew :desktopApp:run` (database in the OS application-data directory, `AiTodo/todo.db`)
- iOS: open `iosApp` in Xcode and run

## Tests

- `./gradlew :shared:jvmTest` ViewModels, mappers and the DAO against a real in-memory SQLite
- `./gradlew :shared:testAndroidHostTest` ViewModels and mappers on the Android host target
- `./gradlew :shared:iosSimulatorArm64Test` ViewModels and mappers on iOS (needs Xcode; not run during development)

DAO tests live in `jvmTest` because Room's in-memory builder is platform specific.

## CI

Every push runs three GitHub Actions jobs: `test` (all JVM and Android host tests, coverage report,
coverage floor), `android-build` (debug APK) and `server-docker` (builds the server image, starts it
and runs `scripts/smoke.sh` against it).

**Debug APK of any branch:** open that branch's run under the Actions tab and download the
`app-debug-apk` artifact (kept for 7 days). The app talks to `http://localhost:8080`, so on an
emulator or a USB-connected phone it still needs `scripts/android-reverse.sh` (`adb reverse`) and a
server running on your machine (`./gradlew :server:run`) before it can sync.
