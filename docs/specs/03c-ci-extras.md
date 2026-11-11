# Stage 3c: CI extras

## Goal

Make CI check more than unit tests: the Docker image we demo really works, the app can be
downloaded from any branch, coverage cannot silently drop, and dependencies stay current.
No production code changes. Timebox: about 1 hour.

## 1. Docker smoke test (new job `server-docker`)

- Runs in parallel with `test` and `android-build`, same JDK 21 + Gradle setup.
- Steps: `./gradlew :server:buildFatJar` → `docker build` (the existing `Dockerfile`, tag e.g.
  `todo-server:ci`) → run the container detached on port 8080 → wait until `/health` answers
  (poll with a timeout, e.g. 30 s; fail with the container logs if it never does) →
  `sh scripts/smoke.sh http://localhost:8080` → always print `docker logs` and stop the container.
- `scripts/smoke.sh` must exit non-zero when a check fails (verify; change it if it only
  prints). The job is red when any smoke check fails.
- Docker is preinstalled on `ubuntu-latest`; no third-party actions, plain `docker` commands.

## 2. APK artifact

- In `android-build`, upload `androidApp/build/outputs/apk/debug/*.apk` as an artifact named
  `app-debug-apk`, retention 7 days.
- Note in `README.md` that the debug APK of any branch can be downloaded from its CI run.
  (The APK points to `http://localhost:8080`, so it still needs `adb reverse`; say so.)

## 3. Coverage floor (`koverVerify`)

- Add a Kover verify rule on the aggregated report: minimum **line** coverage = the current
  total rounded **down** to the nearest 5 % (e.g. 78.4 % → 75 %). Read the current number from
  the last green run / local report and state it in the report.
- Run `koverVerify` in the `test` job after the reports. A drop below the floor makes the run
  red; the summary step still runs (`if: always()`).
- Mention the floor and how to raise it in `CLAUDE.md`. Raising it is a manual decision, never
  lowered to make a build pass.

## 4. Dependabot

- `.github/dependabot.yml`: ecosystems `gradle` (root, the version catalog) and
  `github-actions`, weekly, max 5 open PRs each, grouped so related updates (e.g. all Ktor
  artifacts, all AndroidX) come as one PR.

## Done when

- A pushed branch shows three green jobs (`test`, `android-build`, `server-docker`; the summary
  is a step inside `test`) and an `app-debug-apk` artifact.
- Breaking the server on a throwaway branch (e.g. `/health` returning 500) makes
  `server-docker` red with the container logs visible. Delete the branch afterwards.
- `koverVerify` passes locally and in CI; lowering coverage below the floor fails it (check
  locally by temporarily setting the floor above the current value, then revert).
- `dependabot.yml` is valid (GitHub shows it under Insights → Dependency graph → Dependabot).

## Out of scope

iOS CI, release signing, deploying the image anywhere, CodeQL, third-party actions, detekt
(decided out of scope for this stage; it would need a new dependency and an `upload-sarif` action).
