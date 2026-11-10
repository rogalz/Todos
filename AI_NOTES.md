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
- 
## 2026-11-10 — Stage 1: sync-ready data model + migration
- **Task:** UUID ids, timestamps, soft delete, migration v1→v2 (spec 01).
- **What the AI did:** implemented it with 69 green tests (45 before); flagged in its plan a
  destructive-migration fallback that contradicted CLAUDE.md, and a Koin param `getOrNull<Long>()`
  that would still compile but turn every edit into a duplicate.
- **Surprise:** it mutation-tested its own migration (4 mutations + re-adding the fallback)
  to prove the tests catch them. It also kept `toEntity()` against its own plan because my
  "keep the same assertions" instruction conflicted with removing it, and reported it as a deviation.
- **How I verified:** plan review before coding, test report, manual migration on my real v1 database.
- **Takeaway:** a written spec + CLAUDE.md rules made the agent surface conflicts instead of
  guessing. Runtime-only wiring (DI params) is a blind spot for the compiler, so guard it with a test.