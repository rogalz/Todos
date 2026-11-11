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
