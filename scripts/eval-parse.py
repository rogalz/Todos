#!/usr/bin/env python3
"""Measures how well `POST /parse` turns sentences into todo drafts (stage 6 eval).

Needs a running server that can reach Ollama (not part of CI):

    ollama serve                              # or: brew services start ollama
    ./gradlew :server:run                     # or: docker compose up --build
    python3 scripts/eval-parse.py [BASE_URL] [--note TEXT] [--no-save]

Every case is a sentence plus three checks on the draft the server returns:
  title        every keyword must appear in the title
  description  every keyword must appear in the description; plus the "either" keywords, which are
               details that may reasonably sit in the title OR the description (a place or a name)
  invention    none of the "forbidden" words may appear in title or description
               (they are details the speaker never said, so seeing one means the model made it up)

A keyword may offer alternatives with `|` ("litre|liter"). Keywords are matched case-insensitively
as substrings (so a Polish stem like "mlek" covers "mleko" and "mleka"); forbidden words must start
a word, so "mam" catches "mamy" but not "tomorrow".

This measures quality, it is not a gate: the exit status is 0 whatever the accuracy. It is 1 only
when the server could not be reached at all. The result is also written to docs/eval/<date>.md
(-2, -3, ... for further runs of the same day; use --no-save to skip that), which is how runs are compared over time (model or prompt changes).
"""
import argparse
import json
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

REQUEST_TIMEOUT_SECONDS = 45


@dataclass
class Case:
    name: str
    text: str
    title_has: list = field(default_factory=list)
    description_has: list = field(default_factory=list)
    either_has: list = field(default_factory=list)
    forbidden: list = field(default_factory=list)


CASES = [
    Case(
        "en filler words",
        "uh, buy milk and bread, tomorrow morning before work",
        title_has=["milk", "bread"],
        description_has=["tomorrow", "morning"],
        forbidden=["uh", "pm", "evening", "weekend", "friday"],
    ),
    Case(
        "en self-correction",
        "buy milk, no wait, two litres of oat milk",
        title_has=["milk"],
        description_has=["2|two", "litre|liter"],
        forbidden=["wait", "soy", "almond"],
    ),
    Case(
        "en numbers + time",
        "book a table for four at seven thirty at Luigi's",
        title_has=["table"],
        description_has=["4|four", "7:30|7.30|seven thirty|19:30"],
        either_has=["luigi"],
        forbidden=["pm", "evening", "birthday", "friday"],
    ),
    Case(
        "en weekday + time",
        "call the dentist on Monday at nine to move my appointment",
        title_has=["dentist"],
        description_has=["monday", "9|nine"],
        either_has=["appointment"],
        forbidden=["tuesday", "afternoon", "pm"],
    ),
    Case(
        "en names + place",
        "email Anna the budget report before the Thursday meeting in room 4B",
        title_has=["report|budget"],
        description_has=["thursday", "4b"],
        either_has=["anna"],
        forbidden=["friday", "john", "boss"],
    ),
    Case(
        "en very short",
        "milk",
        title_has=["milk"],
        forbidden=["litre", "liter", "gallon", "tomorrow", "store", "oat", "soy"],
    ),
    Case(
        "en recurring",
        "remind me to water the plants every Sunday evening",
        title_has=["water", "plant"],
        description_has=["sunday", "evening"],
        forbidden=["monday", "morning", "daily"],
    ),
    Case(
        "en gift",
        "buy a birthday present for my sister, she turns thirty next week",
        title_has=["present|gift"],
        description_has=["sister", "30|thirty", "next week"],
        forbidden=["brother", "mother", "friend", "cake", "book"],
    ),
    Case(
        "en long rambling",
        "So I was thinking about the weekend and, uh, you know, we really need to sort out the garden, "
        "the grass is getting super long, so I need to mow the lawn on Saturday morning, and also, um, "
        "ask my neighbour Tom if I can borrow his lawnmower because mine is broken, oh and I should buy "
        "petrol for it, like five litres, and maybe pick up some garden bags from the hardware store on "
        "the way, I think that's everything, yeah.",
        title_has=["mow|lawn|garden"],
        description_has=["saturday", "tom", "petrol|gas", "5|five"],
        forbidden=["sunday", "friday", "john", "diesel"],
    ),
    Case(
        "pl basic",
        "kup mleko i chleb jutro rano przed pracą",
        title_has=["mlek", "chleb"],
        description_has=["jutro", "prac"],
        # An answer in English would mean the language rule failed.
        forbidden=["buy", "milk", "bread", "tomorrow", "morning"],
    ),
    Case(
        "pl self-correction",
        "yyy, zadzwoń do mamy, no nie, do taty, w sobotę po południu",
        title_has=["zadzwo", "tat"],
        description_has=["sobot", "południ"],
        forbidden=["yyy", "mam", "call"],
    ),
    Case(
        "pl numbers + time",
        "umów wizytę u dentysty na wtorek o dziesiątej, to ma być kontrola",
        title_has=["wizyt|dentyst"],
        description_has=["wtor", "dziesi|10", "kontrol"],
        forbidden=["środ", "czwart", "piąt", "appointment"],
    ),
    Case(
        "pl very short",
        "mleko",
        title_has=["mlek"],
        forbidden=["litr", "jutro", "sklep", "dzisiaj", "bio"],
    ),
]


@dataclass
class Result:
    case: Case
    title: str = ""
    description: str = ""
    latency_ms: int = 0
    error: str = ""
    title_ok: bool = False
    description_ok: bool = False
    invention_ok: bool = False
    problems: list = field(default_factory=list)

    @property
    def checks(self):
        return [self.title_ok, self.description_ok, self.invention_ok]

    @property
    def passed(self):
        return all(self.checks)


def missing(text, keywords):
    """The keywords (alternatives joined with `|`) none of whose alternatives appears in `text`."""
    lowered = text.lower()
    return [kw for kw in keywords if not any(alt.lower() in lowered for alt in kw.split("|"))]


def present_forbidden(text, forbidden):
    """The forbidden words that start a word somewhere in `text`."""
    return [w for w in forbidden if re.search(r"(?<!\w)" + re.escape(w.lower()), text.lower())]


def run_case(base_url, case):
    result = Result(case)
    body = json.dumps({"text": case.text}).encode("utf-8")
    request = urllib.request.Request(
        base_url + "/parse", data=body, headers={"Content-Type": "application/json"}, method="POST"
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            draft = json.loads(response.read().decode("utf-8"))
        result.latency_ms = round((time.monotonic() - started) * 1000)
    except urllib.error.HTTPError as e:
        result.latency_ms = round((time.monotonic() - started) * 1000)
        result.error = f"HTTP {e.code}"
        return result
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        result.latency_ms = round((time.monotonic() - started) * 1000)
        result.error = f"unreachable: {getattr(e, 'reason', e)}"
        return result

    result.title = draft.get("title", "")
    result.description = draft.get("description", "")

    absent = missing(result.title, case.title_has)
    result.title_ok = not absent
    if absent:
        result.problems.append(f"title lacks {absent}")

    absent = missing(result.description, case.description_has)
    absent += missing(result.title + " " + result.description, case.either_has)
    result.description_ok = not absent
    if absent:
        result.problems.append(f"description lacks {absent}")

    invented = present_forbidden(result.title + " " + result.description, case.forbidden)
    result.invention_ok = not invented
    if invented:
        result.problems.append(f"contains forbidden {invented}")
    return result


def cell(text, width=None):
    text = " ".join(str(text).split())
    if width and len(text) > width:
        text = text[: width - 1] + "…"
    return text


def mark(ok):
    return "pass" if ok else "FAIL"


def summarize(results):
    done = [r for r in results if not r.error]
    all_checks = [c for r in results for c in r.checks]
    latencies = [r.latency_ms for r in done]
    return {
        "cases": len(results),
        "cases_passed": sum(1 for r in results if r.passed),
        "checks": len(all_checks),
        "checks_passed": sum(all_checks),
        "errors": sum(1 for r in results if r.error),
        "avg_ms": round(statistics.mean(latencies)) if latencies else 0,
        "max_ms": max(latencies) if latencies else 0,
    }


def pct(part, whole):
    return f"{100 * part / whole:.0f} %" if whole else "n/a"


def render_terminal(results, totals):
    lines = []
    header = f"{'case':<20} {'title':<34} {'description':<44} {'T':<4} {'D':<4} {'N':<4} {'ms':>6}"
    lines += [header, "-" * len(header)]
    for r in results:
        if r.error:
            lines.append(f"{r.case.name:<20} {r.error:<34} {'':<44} {'FAIL':<4} {'FAIL':<4} {'FAIL':<4} {r.latency_ms:>6}")
            continue
        lines.append(
            f"{r.case.name:<20} {cell(r.title, 34):<34} {cell(r.description, 44):<44} "
            f"{mark(r.title_ok):<4} {mark(r.description_ok):<4} {mark(r.invention_ok):<4} {r.latency_ms:>6}"
        )
    lines += render_totals(totals)
    lines.append("T = title keywords, D = description keywords, N = nothing invented")
    return "\n".join(lines)


def render_totals(t):
    return [
        "",
        f"cases fully correct: {t['cases_passed']}/{t['cases']} ({pct(t['cases_passed'], t['cases'])})",
        f"checks passed:       {t['checks_passed']}/{t['checks']} ({pct(t['checks_passed'], t['checks'])})",
        f"latency:             avg {t['avg_ms']} ms, max {t['max_ms']} ms"
        + (f"  ({t['errors']} request(s) failed)" if t["errors"] else ""),
    ]


def render_markdown(results, totals, base_url, note):
    md = [f"# /parse eval, {date.today().isoformat()}", ""]
    md.append(f"- server: `{base_url}`")
    if note:
        md.append(f"- note: {note}")
    md += [
        f"- cases: {totals['cases']}, checks per case: title keywords (T), description keywords (D), nothing invented (N)",
        "- This measures quality; it is not a pass/fail gate. See `scripts/eval-parse.py` for how a case is judged.",
        "",
        "| case | input | title | description | T | D | N | ms |",
        "|---|---|---|---|---|---|---|---|",
    ]

    def esc(s):
        return cell(s).replace("|", "\\|")

    for r in results:
        if r.error:
            md.append(f"| {esc(r.case.name)} | {esc(cell(r.case.text, 60))} | {esc(r.error)} | | FAIL | FAIL | FAIL | {r.latency_ms} |")
        else:
            md.append(
                f"| {esc(r.case.name)} | {esc(cell(r.case.text, 60))} | {esc(r.title)} | {esc(r.description)} "
                f"| {mark(r.title_ok)} | {mark(r.description_ok)} | {mark(r.invention_ok)} | {r.latency_ms} |"
            )
    md += ["", "## Totals", ""]
    md += [f"- {line}" for line in render_totals(totals) if line.strip()]
    problems = [r for r in results if r.problems or r.error]
    if problems:
        md += ["", "## What failed", ""]
        for r in problems:
            md.append(f"- **{r.case.name}**: {r.error or '; '.join(r.problems)}")
    return "\n".join(md) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Evaluate POST /parse on a fixed set of sentences.")
    parser.add_argument("base_url", nargs="?", default="http://localhost:8080")
    parser.add_argument("--note", default="", help="free text for the saved report, e.g. the model and prompt version")
    parser.add_argument("--no-save", action="store_true", help="do not write docs/eval/<date>.md")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    results = []
    for case in CASES:
        print(f"... {case.name}", file=sys.stderr, flush=True)
        results.append(run_case(base_url, case))

    totals = summarize(results)
    print(render_terminal(results, totals))

    if not args.no_save:
        out_dir = Path(__file__).resolve().parent.parent / "docs" / "eval"
        out_dir.mkdir(parents=True, exist_ok=True)
        # Never overwrite an earlier run of the same day: comparing runs is the point of keeping them.
        out = out_dir / f"{date.today().isoformat()}.md"
        run = 1
        while out.exists():
            run += 1
            out = out_dir / f"{date.today().isoformat()}-{run}.md"
        out.write_text(render_markdown(results, totals, base_url, args.note), encoding="utf-8")
        print(f"\nsaved to {out.relative_to(out_dir.parent.parent)}")

    # Not a gate: only "could not talk to the server at all" is an error.
    sys.exit(1 if totals["errors"] == totals["cases"] else 0)


if __name__ == "__main__":
    main()
