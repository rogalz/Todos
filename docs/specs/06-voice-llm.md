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
          ─► user edits ─► Save ─► normal path: Room (PENDING) ─► sync
```

The draft is **not stored** until the user saves. Nothing about sync changes.

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
    transcript and final result as a Flow; cancellable.
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
  - Fallback (offline, 4xx/5xx, timeout): title = the raw transcript (trimmed, capped),
    description empty, snackbar "Couldn't reach the AI, used your words".
- Strings in `strings.xml`, content descriptions for the mic button and states.

## Tests (must pass)

Server (`testApplication`, fake `LlmClient`): happy path; 400 cases; LLM down → 503; garbage
output → 502; title capping and blank-title fallback. `OllamaLlmClient` with Ktor `MockEngine`:
request shape (model, `stream=false`, temperature 0, `format` schema, system prompt present) and
parsing of a real-looking Ollama response.

Client: ViewModel with fake parser: loading → draft; fallback on error; a field the user
already typed in is not overwritten; nothing is saved until Save. A UI flow test with a fake
`SpeechToText` and fake parser: mic → transcript → detail pre-filled → save → in the list.

## Eval (not in CI, needs Ollama)

- `scripts/eval-parse.sh` (or a JVM main) runs ~12 sentences against a running server:
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
