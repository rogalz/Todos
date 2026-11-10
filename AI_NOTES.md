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
