# LLM & Agent Mocking — Roadmap

**Last updated:** 2026-05-27
**Purpose:** Track remaining work for first-class LLM/agent mocking. Designs that shipped are documented in the codebase (`docs/code/`, consumer docs, source) — this file only lists what is still open.

The original RFC (RFC-1 LLM Response Builder + RFC-2 Stateful Scripted Conversations) and its execution plan have been retired now that both shipped. Historical context lives in the git log around commits `fa2a5bb05` → `9e3efe0e2` (M0–M5 + post-M5 hardening) and `3f03bce33` → `adaed5a72` (dashboard UX U1–U4).

---

## Priority list status

### Tier 1 — foundational

| # | Item | Status |
|---|---|---|
| 1 | LLM response builder (`llmMock`) — RFC-1 | ✅ Shipped (M0–M5) |
| 2 | Stateful scripted conversations — RFC-2 Layer B | ✅ Shipped (M2) |
| 3 | Tool-call assertions (`verify_tool_call`) | ❌ Not started |
| 4 | Agent-run / LLM-session analysis (`explain_agent_run`) | ❌ Not started |

### Tier 2 — high value

| # | Item | Status |
|---|---|---|
| 5 | Token/cost analytics + budget assertions | ✅ Shipped (U3 — token/cost rollup tile + session inspector) |
| 6 | LLM fault/chaos profiles (429/529 + Retry-After, mid-stream truncation, malformed SSE, probabilistic error rates) | ❌ Not started (was U6, ~8–12 days) |
| 7 | VCR mode + strict mode + body redaction + field normalisation | 🟡 Partial — cassette manager shipped in U4; strict-mode, body redaction, and field normalisation still open |

### Tier 3 — valuable / specialised

| # | Item | Status |
|---|---|---|
| 8 | MCP/A2A conformance contract testing (`run_mcp_contract_test`) | ❌ Not started |
| 9 | Semantic / normalised prompt matching | ❌ Not started |
| 10 | OTel GenAI / OpenInference span export | ❌ Not started |
| 11 | Correlated agent-run session / call-graph view | ❌ Not started |
| 12 | Prompt-injection / adversarial-response harness | ❌ Not started |
| 13 | Drift detection (fixtures vs real API in CI) | ❌ Not started (was U5, ~5–8 days) |
| 14 | Run bisection / diff | 🟡 Partial — structural trajectory diff shipped in U4; full bisection workflow open |

---

## Known limitations on shipped work

Tracked separately in `docs/code/llm-security-audit.md`:
- Ollama codec emits SSE-shaped events instead of native NDJSON
- Bedrock codec emits plain Anthropic SSE rather than the `aws-chunked` binary envelope
- `whenContainsToolResultFor` E2E false-negative for Gemini/Ollama (unit tests pass; pipeline-level interaction issue)

---

## Suggested next steps

When picking the next milestone:
1. **#13 Drift detection (U5)** — closes the "cassettes go stale" maintenance gap. Pairs naturally with the cassette manager that already shipped in U4.
2. **#6 Chaos profiles (U6)** — declarative resilience testing. Larger backend feature.
3. **#3/#4 Tool-call assertions / agent-run analysis** — leverages the `ParsedConversation` produced by the existing codecs; no new transport work.
