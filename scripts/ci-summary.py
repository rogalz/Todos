#!/usr/bin/env python3
"""Prints a Markdown summary (test counts + Kover coverage) of the last Gradle test run.

CI appends the output to $GITHUB_STEP_SUMMARY; locally just run `python3 scripts/ci-summary.py`
after `./gradlew <tests> koverXmlReport`. Standard library only (preinstalled on the runner).

It never fails: when the tests are red Kover produces no report, and the job must still show the
test table (the Gradle step is what turns the run red, not this script).
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOVER_XML = ROOT / "build" / "reports" / "kover" / "report.xml"

# Module -> package prefix. Modules are told apart by package because the aggregated report has no
# module attribute; anything that matches no prefix is counted as `shared` (the app code).
MODULE_PACKAGES = {
    "api": "com/example/ai/api",
    "server": "com/example/ai/server",
}
MODULES = ["api", "server", "shared"]

# Only a visual hint in the per-package table; it never fails anything (no coverage threshold yet).
WARN_BELOW_LINE_PERCENT = 50.0


def percent(covered: int, missed: int) -> str:
    total = covered + missed
    if total == 0:
        return "n/a"
    return f"{covered / total * 100:.1f}% ({covered}/{total})"


def test_table() -> list[str]:
    # One row per Gradle test task (build/test-results/<task>), so a test that runs on two targets
    # (shared's commonTest runs on jvm and on the Android host) is visibly counted twice.
    rows: dict[str, list[int]] = {}
    for path in sorted(ROOT.glob("*/build/test-results/*/TEST-*.xml")):
        module, task = path.parts[-5], path.parts[-2]
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as e:
            print(f"warning: cannot parse {path}: {e}", file=sys.stderr)
            continue
        total = int(suite.get("tests", 0))
        failed = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        skipped = int(suite.get("skipped", 0))
        row = rows.setdefault(f"{module} {task}", [0, 0, 0])
        row[0] += total - failed - skipped
        row[1] += failed
        row[2] += skipped

    if not rows:
        return ["### Tests", "", "No JUnit results found."]

    lines = ["### Tests", "", "| Task | Passed | Failed | Skipped |", "|---|--:|--:|--:|"]
    for name, (passed, failed, skipped) in rows.items():
        lines.append(f"| {name} | {passed} | {failed} | {skipped} |")
    totals = [sum(r[i] for r in rows.values()) for i in range(3)]
    lines.append(f"| **Total** | **{totals[0]}** | **{totals[1]}** | **{totals[2]}** |")
    return lines


def module_of(package: str) -> str:
    for module, prefix in MODULE_PACKAGES.items():
        if package == prefix or package.startswith(prefix + "/"):
            return module
    return "shared"


def coverage_table() -> list[str]:
    heading = ["### Coverage (Kover, tests run on the JVM; generated code excluded)", ""]
    if not KOVER_XML.exists():
        return heading + ["Not available: no Kover report (the tests failed, or the report task did not run)."]
    try:
        report = ET.parse(KOVER_XML).getroot()
    except ET.ParseError as e:
        return heading + [f"Not available: cannot parse the Kover report ({e})."]

    # counts[module][type] and per_package[package][type] = [covered, missed]
    counts = {m: {"LINE": [0, 0], "BRANCH": [0, 0]} for m in MODULES}
    per_package: dict[str, dict[str, list[int]]] = {}
    for package in report.findall("package"):
        name = package.get("name", "")
        module = module_of(name)
        package_counts = per_package.setdefault(name, {"LINE": [0, 0], "BRANCH": [0, 0]})
        for counter in package.findall("counter"):
            kind = counter.get("type")
            if kind in package_counts:
                for target in (counts[module][kind], package_counts[kind]):
                    target[0] += int(counter.get("covered", 0))
                    target[1] += int(counter.get("missed", 0))

    def cell(module_counts: dict, kind: str) -> str:
        covered, missed = module_counts[kind]
        return percent(covered, missed)

    total = {k: [sum(counts[m][k][i] for m in MODULES) for i in range(2)] for k in ("LINE", "BRANCH")}
    lines = heading + ["| | Line | Branch |", "|---|--:|--:|"]
    lines.append(f"| **Total** | **{cell(total, 'LINE')}** | **{cell(total, 'BRANCH')}** |")
    for module in MODULES:
        lines.append(f"| {module} | {cell(counts[module], 'LINE')} | {cell(counts[module], 'BRANCH')} |")
    lines += [
        "",
        "_Server coverage includes execution from `shared`'s end-to-end tests (the real server runs in-process there)._",
    ]
    return lines + package_table(per_package)


def package_table(per_package: dict[str, dict[str, list[int]]]) -> list[str]:
    def sort_key(item: tuple[str, dict[str, list[int]]]):
        name, kinds = item
        covered, missed = kinds["LINE"]
        total = covered + missed
        # Weakest first inside a module; a package without any line (n/a) goes last.
        return MODULES.index(module_of(name)), covered / total if total else float("inf"), name

    # Collapsed: the per-package detail is long, the module table above is what most people need.
    # The blank lines around the table are required for GitHub to render Markdown inside <details>.
    lines = [
        "",
        "<details><summary>Coverage per package</summary>",
        "",
        "| Package | Module | Line | Branch |",
        "|---|---|--:|--:|",
    ]
    for name, kinds in sorted(per_package.items(), key=sort_key):
        covered, missed = kinds["LINE"]
        total = covered + missed
        warn = "⚠️ " if total and covered / total * 100 < WARN_BELOW_LINE_PERCENT else ""
        lines.append(
            f"| {warn}{name.replace('/', '.')} | {module_of(name)} "
            f"| {percent(covered, missed)} | {percent(*kinds['BRANCH'])} |"
        )
    return lines + ["", "</details>"]


if __name__ == "__main__":
    print("\n".join(test_table() + [""] + coverage_table()))
