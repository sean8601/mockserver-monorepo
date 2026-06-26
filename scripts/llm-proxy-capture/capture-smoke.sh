#!/usr/bin/env bash
#
# capture-smoke.sh — LOCAL-ONLY smoke test for LLM-traffic capture via MockServer.
#
# Proves end-to-end that MockServer, used as an HTTPS proxy, both RECORDS and
# CLASSIFIES the LLM traffic of real coding-assistant CLIs — so the captured calls
# show up in the dashboard's Traffic view, the LLM Traces view, and the LLM Optimise
# view (the optimisation report).
#
# It drives whichever of the supported CLIs are installed AND already authenticated
# on this machine, sending each a single trivial prompt through the proxy, then
# asserts, per tool:
#   1. CAPTURE     — the tool's LLM endpoint appears in the recorded request log
#                    (proves the proxy + TLS interception works).
#   2. CLASSIFY    — that same call appears in the LLM optimisation report with the
#                    expected provider (proves it will render in Traces / Optimise).
#
# Supported tools and the LLM endpoint each is matched by:
#   claude   (Claude Code) — POST api.anthropic.com/v1/messages          -> ANTHROPIC
#   opencode (Codex)       — POST chatgpt.com/backend-api/codex/responses -> OPENAI_RESPONSES
#   tabnine  (Gemini fork) — POST <host>/.../chat/completions            -> OPENAI
#
# WHY LOCAL-ONLY: it invokes real, interactively-authenticated CLIs and makes real
# (small) calls to live model providers / your org's gateway. It therefore cannot
# run on CI and is skipped there. The CI-safe equivalent is the fixture-driven
# CodingCliLlmCaptureTest (mockserver-core) + llmTraffic.test.ts (mockserver-ui),
# which exercise the SAME detection without any network or credentials.
#
# NO SECRETS: this script contains no API keys and hard-codes no private hosts.
# The CLIs use their own stored credentials; tabnine's gateway host is whatever the
# tool itself is configured with (matched by path, never named here). The default CA
# is MockServer's public test Certificate Authority shipped in this repo.
#
# Usage:
#   scripts/llm-proxy-capture/capture-smoke.sh
#
# Environment overrides:
#   MOCKSERVER_URL    base URL of a RUNNING MockServer proxy (default http://localhost:1080)
#   MOCKSERVER_CA     path to the proxy CA cert (default: repo test CA, see below)
#   CAPTURE_PROMPT    the prompt sent to each CLI (default: a one-word reply)
#   CAPTURE_TIMEOUT   per-tool timeout in seconds (default 120)
#   CAPTURE_TOOLS     space-separated subset to consider (default: claude opencode tabnine)
#   FORCE             set to 1 to run even when a CI environment is detected
#
set -uo pipefail

# --- locate repo + defaults --------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

MOCKSERVER_URL="${MOCKSERVER_URL:-http://localhost:1080}"
MOCKSERVER_CA="${MOCKSERVER_CA:-$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem}"
CAPTURE_PROMPT="${CAPTURE_PROMPT:-Reply with exactly the single word: hello}"
CAPTURE_TIMEOUT="${CAPTURE_TIMEOUT:-120}"
CAPTURE_TOOLS="${CAPTURE_TOOLS:-claude opencode tabnine}"

note()  { printf '\033[36m[capture]\033[0m %s\n' "$*"; }
ok()    { printf '\033[32m[ ok  ]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[warn ]\033[0m %s\n' "$*"; }
fail()  { printf '\033[31m[fail ]\033[0m %s\n' "$*"; }

# --- refuse to run on CI unless forced ---------------------------------------
if [ "${FORCE:-}" != "1" ] && { [ -n "${CI:-}" ] || [ -n "${BUILDKITE:-}" ] || [ -n "${GITHUB_ACTIONS:-}" ]; }; then
  warn "CI environment detected — this is a LOCAL-ONLY test (drives real authenticated CLIs)."
  warn "The CI-safe coverage is CodingCliLlmCaptureTest + llmTraffic.test.ts. Set FORCE=1 to override."
  exit 0
fi

# --- portable timeout (no coreutils 'timeout' on stock macOS) ----------------
run_with_timeout() {
  local secs="$1"; shift
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill "$pid" 2>/dev/null ) &
  local watcher=$!
  wait "$pid" 2>/dev/null
  local rc=$?
  kill "$watcher" 2>/dev/null
  wait "$watcher" 2>/dev/null
  return $rc
}

# --- preconditions -----------------------------------------------------------
command -v curl >/dev/null    || { fail "curl not found"; exit 2; }
command -v python3 >/dev/null || { fail "python3 not found"; exit 2; }
[ -f "$MOCKSERVER_CA" ] || { fail "CA cert not found: $MOCKSERVER_CA"; exit 2; }

ms() { curl -s -X PUT "$MOCKSERVER_URL$1" -H 'Content-Type: application/json' --data "${2:-{}}"; }

note "MockServer proxy : $MOCKSERVER_URL"
note "CA cert          : $MOCKSERVER_CA"
if ! ms "/mockserver/retrieve?type=ACTIVE_EXPECTATIONS" >/dev/null 2>&1; then
  fail "Cannot reach MockServer at $MOCKSERVER_URL."
  cat <<EOF
Start it first, e.g.:
  java -jar mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar \\
       -serverPort 1080 -logLevel INFO
Then open the dashboard at $MOCKSERVER_URL/mockserver/dashboard
EOF
  exit 2
fi
ok "MockServer reachable"

note "Clearing recorded log so this run starts clean…"
ms "/mockserver/clear?type=LOG" >/dev/null

# --- proxy env exported to every CLI ----------------------------------------
export HTTPS_PROXY="$MOCKSERVER_URL"  HTTP_PROXY="$MOCKSERVER_URL"
export https_proxy="$MOCKSERVER_URL"  http_proxy="$MOCKSERVER_URL"
export NODE_EXTRA_CA_CERTS="$MOCKSERVER_CA"      # node CLIs (opencode, tabnine)
export SSL_CERT_FILE="$MOCKSERVER_CA"            # curl/openssl-based clients
export REQUESTS_CA_BUNDLE="$MOCKSERVER_CA"       # python-based clients
export NODE_USE_SYSTEM_CA=1 NODE_USE_ENV_PROXY=1 # tabnine honours these

# --- run a single tool -------------------------------------------------------
RAN_TOOLS=()
run_claude() {
  note "Running claude (Claude Code)…"
  run_with_timeout "$CAPTURE_TIMEOUT" claude -p "$CAPTURE_PROMPT" </dev/null >/dev/null 2>&1
}
run_opencode() {
  note "Running opencode…"
  run_with_timeout "$CAPTURE_TIMEOUT" opencode run "$CAPTURE_PROMPT" </dev/null >/dev/null 2>&1
}
run_tabnine() {
  note "Running tabnine…"
  run_with_timeout "$CAPTURE_TIMEOUT" \
    tabnine --prompt "$CAPTURE_PROMPT" --skip-trust --approval-mode plan --output-format text </dev/null >/dev/null 2>&1
}

for tool in $CAPTURE_TOOLS; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    warn "$tool not installed — skipping"
    continue
  fi
  case "$tool" in
    claude)   run_claude   ;;
    opencode) run_opencode ;;
    tabnine)  run_tabnine  ;;
    *) warn "unknown tool '$tool' — skipping"; continue ;;
  esac
  RAN_TOOLS+=("$tool")
done

if [ "${#RAN_TOOLS[@]}" -eq 0 ]; then
  warn "No supported CLI was installed — nothing to capture. Install claude, opencode, or tabnine."
  exit 0
fi

# Give async flushes a moment to land in the log.
sleep 2

# --- retrieve recorded traffic + optimisation report -------------------------
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ms "/mockserver/retrieve?type=REQUEST_RESPONSES" >"$work/traffic.json"
curl -s "$MOCKSERVER_URL/mockserver/llm/optimisationReport?format=json" >"$work/report.json"

# --- assert (capture + classification), per tool -----------------------------
RAN_CSV="$(IFS=,; echo "${RAN_TOOLS[*]}")"
python3 - "$work/traffic.json" "$work/report.json" "$RAN_CSV" <<'PY'
import json, sys

traffic = json.load(open(sys.argv[1]))
try:
    report = json.load(open(sys.argv[2]))
except Exception:
    report = {}
ran = [t for t in sys.argv[3].split(",") if t]

def host_of(rq):
    for k, v in (rq.get("headers") or {}).items():
        if k.lower() == "host":
            return v[0] if isinstance(v, list) else v
    sa = rq.get("socketAddress") or {}
    return sa.get("host")

# Each tool's LLM endpoint signature: (matches a captured request, expected provider)
def is_claude(rq):   return (rq.get("path") or "").endswith("/v1/messages")
def is_opencode(rq): return "/codex/responses" in (rq.get("path") or "")
def is_tabnine(rq):  return (rq.get("path") or "").endswith("/chat/completions") and "/codex/" not in (rq.get("path") or "")

SIG = {
    "claude":   (is_claude,   "ANTHROPIC"),
    "opencode": (is_opencode, "OPENAI_RESPONSES"),
    "tabnine":  (is_tabnine,  "OPENAI"),
}

calls = report.get("calls") or []
def classified(matcher, provider):
    for c in calls:
        if matcher({"path": c.get("path"), "headers": {}}) and (c.get("provider") or "").upper() == provider:
            return True
    return False

print()
print(f"{'TOOL':10} {'CAPTURED':9} {'PROVIDER':17} {'CLASSIFIED':11} RESULT")
print("-" * 62)
overall_ok = True
for tool in ran:
    matcher, provider = SIG[tool]
    captured = sum(1 for e in traffic if matcher(e.get("httpRequest") or {}))
    is_classified = classified(matcher, provider)
    tool_ok = captured >= 1 and is_classified
    overall_ok = overall_ok and tool_ok
    print(f"{tool:10} {str(captured)+' req':9} {provider:17} {('yes' if is_classified else 'NO'):11} "
          f"{'PASS' if tool_ok else 'FAIL'}")

print("-" * 62)
print(f"report.totals.callCount = {(report.get('totals') or {}).get('callCount')}, "
      f"providers = {(report.get('session') or {}).get('providers')}")
print()
if not overall_ok:
    print("FAILED: at least one tool's LLM traffic was not captured AND classified.")
    print("  - 'CAPTURED 0 req'  => proxy/TLS not intercepting this CLI (check HTTPS_PROXY + CA env).")
    print("  - 'CLASSIFIED NO'   => recorded but not recognised as LLM traffic (detection gap / wrong build).")
    sys.exit(1)
print("PASSED: every CLI that ran was captured and classified as LLM traffic.")
PY
rc=$?

echo
if [ "$rc" -eq 0 ]; then
  ok "Capture smoke test passed for: $RAN_CSV"
  note "View it: $MOCKSERVER_URL/mockserver/dashboard  →  Traffic / LLM Traces / LLM Optimise"
else
  fail "Capture smoke test failed — see table above."
fi
exit "$rc"
