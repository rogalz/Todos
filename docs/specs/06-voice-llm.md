# Stage 6: voice → LLM → structured task (human in the loop)

## Goal

The user speaks a task ("uh, buy milk and bread, tomorrow morning before work"), the app turns
it into a clean draft (title + description) using a local LLM, the user reviews and edits it,
and only then saves it. Same idea as voize: speech becomes structured documentation, the human
approves. **Timebox: 3 h.** If it doesn't work end-to-end by then, stop and describe the rest
as next steps.

## Flow

```
mic button ─► speech-to-text (on the device) ─► transcript
          ─► new-task screen opens in "Filling…" state
          ─► POST /parse {text} ─► server ─► Ollama (llama3.2:3b, JSON schema output)
          ─► draft {title, description} pre-fills the fields
          ─► user edits ─► explicit Save ─► normal path: Room (PENDING) ─► sync
```

The draft is **not stored** until the user saves. Nothing about sync changes.

### Human approval (the core of the feature)

A task that comes from dictation is a **draft** and needs an explicit approval; the app's
usual "back saves" does **not** apply to it:

- The detail screen of a draft shows a **Save** action in the top bar. It is enabled while the
  title is not blank. Save stores the fields as they are at that moment (so an edited draft is
  stored in its edited form), then the screen closes.
- Leaving a draft any other way (back arrow, system back, back gesture) does not save. It asks
  **"Discard draft?"** (Discard / Keep editing); Discard closes the screen and stores nothing.
- Normal create and edit (FAB, tapping a card) are unchanged: save-on-back.
- The fallback (the AI was unreachable and the title is the raw transcript) is also a draft: it
  needs Save too. It just doesn't show the "Filled by AI" hint, because the AI did not fill it.

## Server

- `POST /parse`, body `ParseRequest(text: String)` → `ParsedTodoDto(title: String, description: String)`
  (DTOs in `api`, golden-JSON tests like the others).
- `LlmClient` interface; `OllamaLlmClient` implementation using a Ktor client (new server
  dependencies, ask first): `POST {OLLAMA_URL}/api/chat` with `stream = false`,
  `options.temperature = 0`, `format` = JSON schema `{title: string, description: string}`
  (Ollama structured outputs), model from env `OLLAMA_MODEL` (default `llama3.2:3b`).
- `OLLAMA_URL` env: default `http://localhost:11434` for `:server:run`; `docker-compose.yml`
  sets `http://host.docker.internal:11434` (Ollama runs on the Mac, not in the container).
- System prompt (keep it in one place, versioned in the repo):
  - title: short (≤ 60 chars), imperative, the core action ("Buy milk and bread");
  - description: the useful details (time, quantities, people, places); empty if none;
  - drop filler words and self-corrections ("uh", "no wait, two litres");
  - **never invent information** that isn't in the text;
  - answer in the language of the input (English or Polish).
- Post-processing: trim, cap title at 80 chars, if the title comes back blank use the first
  words of the input.
- Errors: blank text or > 1000 chars → 400; Ollama unreachable / timeout (30 s) → 503;
  output that isn't valid JSON for the schema → 502. Log model name + latency per call.
  Decided while planning: the 1000 limit is measured on the trimmed text (what the model gets);
  a non-2xx answer from Ollama itself (e.g. the model is not pulled) is also 503 (the LLM is not
  available), with Ollama's body in the log.
- Optional, cheap: on server start, send one tiny request to warm the model up (first call
  otherwise takes seconds); failure is only logged.

## Client

- `TodoApi.parse(text)`: request timeout 30 s for this call only.
- Domain: `TodoDraftParser` interface (`suspend fun parse(text): TodoDraft`), implemented in
  `data`; UI depends only on the domain (Konsist rule stays).
- Speech-to-text, `SpeechToText` interface (common), platform implementations via Koin:
  - **Android:** `SpeechRecognizer`, prefer `createOnDeviceSpeechRecognizer` (API 31+) when
    available, else the default recognizer. Language = device locale. `RECORD_AUDIO` runtime
    permission; if denied, explain once and keep the button disabled. Exposes partial
    transcript and final result as a Flow; cancellable. The permission goes through a small
    `MicPermission` interface (domain); on Android `MainActivity` owns the
    `ActivityResultLauncher` and hands it to the implementation, so `shared` gains no dependency.
  - **Desktop / iOS:** no speech. The same button opens a small "Describe your task" text
    dialog (typed sentence), and the rest of the flow is identical. This also makes the LLM
    demo-able on desktop.
- UI:
  - List screen: a mic button next to the "New task" FAB (small FAB). While listening: a
    listening indicator + live partial transcript, tap to stop, cancel button.
  - Detail screen opened with the transcript (route gets `dictatedText: String?`): fields
    show a "Filling…" placeholder / shimmer, then the draft. If the user starts typing in a
    field before the draft arrives, that field is **not** overwritten.
  - A small hint "Filled by AI, check before saving" until the user edits or saves.
  - Save / "Discard draft?" as described under "Human approval" above. While the draft is still
    being filled, Save is enabled as soon as the user has typed a title; saving then ends the
    wait and the late answer is dropped.
  - Fallback (offline, 4xx/5xx, timeout): title = the raw transcript (trimmed, capped),
    description empty, snackbar "Couldn't reach the AI, used your words".
- Strings in `strings.xml`, content descriptions for the mic button and states.

## Tests (must pass)

Server (`testApplication`, fake `LlmClient`): happy path; 400 cases; LLM down → 503; garbage
output → 502; title capping and blank-title fallback. `OllamaLlmClient` with Ktor `MockEngine`:
request shape (model, `stream=false`, temperature 0, `format` schema, system prompt present) and
parsing of a real-looking Ollama response.

Client: ViewModel with fake parser: loading → draft; fallback on error; a field the user
already typed in is not overwritten; nothing is saved until Save. Approval: **back on an AI draft
saves nothing** (it asks to discard, and Discard stores nothing); **Save stores the draft**;
**editing and then Save stores the edited version**; normal create/edit still saves on back. A UI
flow test with a fake `SpeechToText` and fake parser: mic → transcript → detail pre-filled →
Save → in the list; and mic → transcript → back → "Discard draft?" → Discard → list unchanged.

Also covered: `SpeechToText`/`MicPermission` wiring in the Koin graph test; the pure parts of the
Android recognizer glue (error-code mapping, picking the best result) in `testAndroidHostTest`.

## Eval (not in CI, needs Ollama)

- `scripts/eval-parse.py` (Python 3, standard library only, like `scripts/ci-summary.py`) runs
  ~12 sentences against a running server:
  English and Polish, filler words, a self-correction, numbers, a time, a very short one
  ("milk"), a long rambling one.
- Each case has expected keywords for the title, keywords that must appear in the description,
  and words that must **not** appear (to catch invented details).
- Prints a table: case, title, description, pass/fail per check, latency; plus totals
  (accuracy %, average and max latency). Save the output to `docs/eval/<date>.md`.
- It measures quality; it is not a pass/fail gate.

## Manual check

`ollama serve` (or `brew services start ollama`), `docker compose up --build`, emulator with
**host audio input enabled** (Extended controls → Microphone → "Virtual microphone uses host
audio input"), dictate a task, edit, save, see it sync to desktop. Then stop Ollama and check
the fallback. Desktop: typed sentence → filled draft.

## Out of scope

On-device LLM, streaming tokens into the fields, structured due dates / reminders, several
tasks from one sentence, editing an existing task by voice.
