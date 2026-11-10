# Plan projektu

## Cel

Rozbudować prostą aplikację to-do (Compose Multiplatform + Room) do **offline-first aplikacji
z synchronizacją przez własny backend**, z odświeżaniem na żywo i dyktowaniem notatek głosem
(mowa → LLM → ustrukturyzowana notatka). Termin: **czwartek 12.11, 11:00** (rozmowa o 13:00).

### Jak wygląda „gotowe” (scenariusz demo)

1. `docker compose up`: lokalny serwer wstaje.
2. Emulator Androida i aplikacja desktopowa obok siebie.
3. Dodaję notatkę na desktopie i po chwili pojawia się na Androidzie (SSE).
4. Android w trybie samolotowym: edytuję offline, widać „niezsynchronizowane”.
   Wyłączam tryb samolotowy i zmiana trafia na desktop.
5. Dyktuję notatkę, LLM wypełnia tytuł i opis, poprawiam i zapisuję.

## Architektura docelowa

```
 UI (Compose) ──obserwuje──► Room (jedyne źródło prawdy)
                                ▲            │
                       zapis    │            │ rekordy PENDING
                       z pull   │            ▼
                         Sync engine (shared, Ktor Client)
                                ▲            │ PUT / GET ?since
                     event SSE  │            ▼
                         Ktor Server (lokalnie, Docker), pamięć + licznik rewizji
                                             │
                                  Ollama (LLM) ◄── POST /parse
```

Zasady, które trzymają całość:
- **UI czyta tylko z Rooma.** Sieć nigdy nie rozmawia z UI bezpośrednio.
- **Zapis lokalny jest natychmiastowy** (`PENDING`), a sync dzieje się w tle.
- **Event SSE to tylko sygnał „coś się zmieniło”**, a dane zawsze przychodzą przez pull.
- **Konflikty:** last-write-wins po `updatedAt`. Usunięcie = tombstone (`deletedAt`).
- **Kontrakt klient–serwer** to wspólne DTO w module `api`.

## Etapy

Status: ✅ gotowe · 🔄 w trakcie · ⏳ czeka

| # | Etap | Status | Gotowe, gdy… |
|---|---|---|---|
| 0 | Fundament: git, `CLAUDE.md`, `AI_NOTES.md`, spec 01 | ✅ | — |
| 1 | Model danych pod sync: UUID, timestampy, soft delete, migracja v1→v2 | 🔄 kod jest, niezacommitowany | `jvmTest` zielony, ręczny test migracji na desktopie OK, commit | ✅ | 
| 1b | Zabezpieczenia: test grafu Koina + testy architektury (Konsist) | ⏳ | testy zielone, commit |
| 2 | Backend Ktor: moduły `api` + `server`, `GET /todos?since`, `PUT /todos/{id}`, Docker | ⏳ spec gotowa | testy `testApplication` zielone, `docker compose up` działa, curl działa |
| 3 | Klient + sync engine: Ktor Client, push PENDING → pull → Room; WorkManager na Androidzie, sync przy starcie na desktopie | ⏳ | test E2E: 2 klientów + serwer w teście; ręcznie desktop ↔ Android |
| 4 | Live updates: SSE na serwerze, hot Flow na kliencie → pull | ⏳ | zmiana na A pojawia się na B bez klikania |
| 5 | UI: wskaźnik syncu, animacje listy/checkboxa, **testy UI** | ⏳ | 2–3 testy UI zielone, ładnie wygląda |
| 6 | Głos → LLM: SpeechRecognizer → `POST /parse` → Ollama → szkic do edycji | ⏳ **limit 3 h** | działa end-to-end; eval na ~10 zdaniach |
| 7 | Finisz: CI (GitHub Actions), README z diagramem, lista pomysłów | ⏳ | — |

### Harmonogram

- **Wtorek:** 1 → 1b → 2 → początek 3
- **Środa:** 3 → 4 → 5 → 6 (jeśli zostanie czas)
- **Czwartek do 11:00:** tylko 7 i próba demo. **Zero nowych funkcji.**

### Zasady cięcia zakresu

- Nie zaczynam etapu, dopóki poprzedni nie jest zielony i zacommitowany.
- Jeśli brakuje czasu, tnę od końca: 6 → animacje z 5 → 4. Etapy 1–3 są nienaruszalne.
- To, czego nie zrobię, trafia do listy pomysłów z opisem, jak bym to zrobił.

## Testy: co i kiedy

Testy powstają **razem z etapem** (każda spec ma sekcję „Tests”), a nie na końcu.

| Etap | Testy |
|---|---|
| 1 | migracja, DAO na prawdziwym SQLite, repozytorium z kontrolowanym zegarem, ViewModele |
| 1b | graf Koina, granice architektury (Konsist) |
| 2 | endpointy przez `testApplication` (idempotencja, LWW, tombstone, `since`, reset, walidacja) |
| 3 | sync engine na fake API (w tym „edycja w trakcie syncu”) + **E2E: 2 klientów + serwer** |
| 4 | event SSE wyzwala pull; reconnect |
| 5 | **testy UI** (`runComposeUiTest`): dodanie, edycja, checkbox, wskaźnik syncu |
| 6 | eval LLM: ~10 zdań → oczekiwany JSON (pomiar trafności, nie zielony/czerwony) |
| 7 | CI odpala wszystko przy pushu |

**Dlaczego testy UI dopiero w etapie 5:** UI zmieni się w etapach 3–5 (wskaźnik syncu,
animacje). Testy napisane wcześniej trzeba by przepisywać. Do tego czasu logikę ekranów
pokrywają testy ViewModeli.

Świadomie pomijam: testy wydajnościowe, screenshotowe, instrumentalne na emulatorze.

## Pętla pracy (każdy etap)

1. **Spec** w `docs/specs/NN-*.md`: przygotowuję z Claude (chat), czytam i akceptuję.
2. **Plan:** Claude Code czyta spec i proponuje plan *bez kodu*. Przeglądam (w razie
   wątpliwości wklejam go do chatu).
3. **Implementacja:** Claude Code, test po teście, `jvmTest` po każdej zmianie.
4. **Weryfikacja:** testy zielone + uruchamiam aplikację + czytam linijka po linijce
   to, co krytyczne (migracje, sync, współbieżność).
5. **Wpis do `AI_NOTES.md`** (co AI zrobiło dobrze, gdzie się pomyliło, jak to wyłapałem).
6. **Commit** z opisem etapu. Aktualizuję status w tym pliku.

## Przydatne

| Co | Komenda |
|---|---|
| Testy wspólne | `./gradlew :shared:jvmTest` |
| Build Androida | `./gradlew :androidApp:assembleDebug` |
| Desktop | `./gradlew :desktopApp:run` |
| Baza desktopowa (backup przed migracją) | `~/Library/Application Support/AiTodo/todo.db` |
| Serwer (od etapu 2) | `./gradlew :server:run` lub `docker compose up` (port 8080) |
| Adres serwera z emulatora | `http://10.0.2.2:8080` (nie `localhost`) |
| Ollama (etap 6) | `ollama pull llama3.2:3b`; pobrać wcześniej w tle |
