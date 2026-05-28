#!/usr/bin/env bash
#
# Posts a Buildkite annotation summarising the just-finished Java build:
#   - per-module test pass/fail/skip totals
#   - per-module unit-test LINE coverage from jacoco.xml
#   - 10 slowest tests
#   - link to the downloadable jacoco-html-reports.tar.gz artifact
#
# Runs as a separate pipeline step after the :maven: build step uploads its
# artifacts; downloads them back into a temp dir and renders markdown.

set -euo pipefail

TMPDIR=$(mktemp -d)
trap 'rm -rf "${TMPDIR}"' EXIT

cd "${TMPDIR}"

# Pull the XML files we need. `|| true` so the step still posts an annotation
# if the maven step partially failed and only some XMLs exist.
buildkite-agent artifact download "**/target/surefire-reports/TEST-*.xml" . 2>/dev/null || true
buildkite-agent artifact download "**/target/failsafe-reports/TEST-*.xml" . 2>/dev/null || true
buildkite-agent artifact download "**/target/site/jacoco/jacoco.xml" . 2>/dev/null || true
buildkite-agent artifact download "**/target/site/jacoco-it/jacoco.xml" . 2>/dev/null || true

# Render markdown summary.
MARKDOWN=$(python3 - "${TMPDIR}" <<'PYEOF'
import collections
import glob
import os
import sys
import xml.etree.ElementTree as ET

tmpdir = sys.argv[1]


def module_from(path):
    # path looks like: mockserver/mockserver-core/target/surefire-reports/TEST-Foo.xml
    parts = path.split(os.sep)
    try:
        idx = parts.index("target")
        return parts[idx - 1]
    except ValueError:
        return "unknown"


test_stats = collections.defaultdict(lambda: {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0})
slowest = []
for f in glob.glob(f"{tmpdir}/**/target/*-reports/TEST-*.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    module = module_from(f)
    s = test_stats[module]
    s["tests"] += int(root.get("tests", 0))
    s["failures"] += int(root.get("failures", 0))
    s["errors"] += int(root.get("errors", 0))
    s["skipped"] += int(root.get("skipped", 0))
    s["time"] += float(root.get("time", 0))
    for tc in root.findall("testcase"):
        try:
            t = float(tc.get("time", 0))
        except ValueError:
            t = 0.0
        slowest.append((t, f"{tc.get('classname')}.{tc.get('name')}"))

coverage = {}
for f in glob.glob(f"{tmpdir}/**/target/site/jacoco/jacoco.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    module = module_from(f)
    for c in root.findall("counter"):
        if c.get("type") == "LINE":
            missed = int(c.get("missed", 0))
            covered = int(c.get("covered", 0))
            total = missed + covered
            coverage[module] = (covered / total) if total else 0.0
            break

# ---- emit markdown ----
lines = []

if test_stats:
    lines.append("### Test results")
    lines.append("")
    lines.append("| Module | Total | Pass | Fail | Skip | Time (s) |")
    lines.append("|---|---:|---:|---:|---:|---:|")
    tot = collections.Counter()
    for module in sorted(test_stats):
        s = test_stats[module]
        passed = s["tests"] - s["failures"] - s["errors"] - s["skipped"]
        fail = s["failures"] + s["errors"]
        lines.append(f"| {module} | {s['tests']} | {passed} | {fail} | {s['skipped']} | {s['time']:.1f} |")
        for k in ("tests", "failures", "errors", "skipped"):
            tot[k] += s[k]
        tot["time"] += s["time"]
    total_pass = tot["tests"] - tot["failures"] - tot["errors"] - tot["skipped"]
    total_fail = tot["failures"] + tot["errors"]
    lines.append(f"| **TOTAL** | **{tot['tests']}** | **{total_pass}** | **{total_fail}** | **{tot['skipped']}** | **{tot['time']:.1f}** |")
    lines.append("")

if coverage:
    lines.append("### Coverage (LINE, unit-test)")
    lines.append("")
    lines.append("| Module | LINE % |")
    lines.append("|---|---:|")
    for module in sorted(coverage):
        lines.append(f"| {module} | {coverage[module] * 100:.2f}% |")
    lines.append("")
    lines.append("Download the full HTML coverage report: `jacoco-html-reports.tar.gz` artifact on the :maven: build step.")
    lines.append("")

slowest.sort(reverse=True)
if slowest:
    lines.append("### 10 slowest tests")
    lines.append("")
    for t, name in slowest[:10]:
        lines.append(f"- `{name}` — {t:.2f}s")

print("\n".join(lines))
PYEOF
)

if [[ -z "${MARKDOWN}" ]]; then
    echo "No test or coverage data found - skipping annotation"
    exit 0
fi

# Pick annotation style: warning if the TOTAL row has any failures, otherwise success.
# The junit-annotate plugin already emits per-test failure annotations separately,
# this one is the at-a-glance roll-up.
if echo "${MARKDOWN}" | awk -F'|' '/\*\*TOTAL\*\*/ { gsub(/[* ]/, "", $5); if ($5+0 > 0) { exit 1 } }'; then
    STYLE=success
else
    STYLE=warning
fi

echo "${MARKDOWN}" | buildkite-agent annotate --style "${STYLE}" --context "java-summary"
