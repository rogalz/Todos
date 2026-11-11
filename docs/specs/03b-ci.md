# Stage 3b: CI on GitHub Actions + code coverage

## Goal

Every push to any branch runs the tests on GitHub Actions and shows
**how much of the code the tests actually execute**, not only how many tests there are.
No production code changes.

## Coverage: Kover

- JetBrains Kover Gradle plugin (latest stable compatible with Kotlin 2.4.21 / Gradle 9.6 /
  AGP 9.4), via the version catalog. Applied to `shared`, `api` and `server`.
- The root project aggregates the three modules into one report (`dependencies { kover(project(...)) }`),
  so there is one total number plus per-module / per-package detail.
- Measured: tests that run on the JVM (`:shared:jvmTest`, `:api:jvmTest`, `:server:test`).
  If Kover can also include `:shared:testAndroidHostTest` without fighting the setup, include
  it; otherwise leave it out and say so. iOS (Kotlin/Native) cannot be measured; that is fine.
- **Exclude generated code** so it does not distort the number: Room generated classes
  (`*_Impl`, `*_Impl$*`, `*AppDatabaseConstructor*`), Compose compiler output
  (`ComposableSingletons*`), Compose Resources accessors (`Res`, `*.generated.resources.*`),
  kotlinx.serialization serializers (`*$$serializer`), and `@Preview` functions if any. Do not
  exclude hand-written code just to raise the number.
- Report: line and branch coverage, total and per package. HTML + XML.
- No minimum threshold yet (it must not fail the build); we only want to see the numbers.

## Workflow `.github/workflows/ci.yml`

- Triggers: `push` on all branches. No `pull_request` trigger: the push already runs the tests
  for every PR branch, and with both a same-repo PR would run twice.
- `concurrency`: one run per ref, cancel the older in-progress run.
- `ubuntu-latest`, `actions/checkout`, `actions/setup-java` (Temurin 21, the server needs 21),
  `gradle/actions/setup-gradle` (Gradle cache; `cache-read-only: false` so feature branches also
  write the cache, by default only the default branch does).
- Job **test**: `./gradlew :api:jvmTest :server:test :shared:jvmTest :shared:testAndroidHostTest koverXmlReport koverHtmlReport --continue`
  (`--continue` so one failing test task does not hide failures in the others)
  - Write a short Markdown table to `$GITHUB_STEP_SUMMARY`: test count (passed / failed /
    skipped) and coverage (line % and branch %, total + per module). Parse the Kover XML and
    JUnit XML with a small script in `scripts/` (bash + standard tools available on the runner,
    or a tiny Kotlin/Python script, your choice), so the numbers are on the run page without
    downloading anything. Add one line noting that server coverage includes execution from
    shared's end-to-end tests.
  - Upload the Kover HTML report as an artifact (always) and the test reports (on failure).
- Job **android-build** (parallel): `./gradlew :androidApp:assembleDebug`.
- No secrets, no deploy, no iOS job (macOS runners are slow; out of scope).
- Use only official `actions/*` and `gradle/actions/*`; no third-party actions without asking.

## Local

- `./gradlew koverHtmlReport` → `build/reports/kover/html/index.html`.
- Add the commands and the CI description to `CLAUDE.md`.
- Add a CI status badge for `develop` to `README.md`.

## Done when

- A pushed branch shows a green run with the summary table (tests + coverage).
- The HTML coverage report can be downloaded from the run.
- A deliberately failing test (try it on a throwaway branch, then delete the branch) makes
  the run red and uploads the test reports.
- Local `./gradlew koverHtmlReport` works.

## Out of scope

Coverage thresholds, PR comments, badges with the coverage percentage, iOS CI, release builds.
