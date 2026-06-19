# Editor Extensions Value Roadmap (VS Code & JetBrains) — remaining tail

**Status:** Re-audited 2026-06-19 against merged code (origin/master). Phases 0–6
have largely shipped for **both** the VS Code and JetBrains extensions, and
**trace correlation has now shipped in both editors** (removed from the tail
below); this document tracks only the **unshipped remainder**. The full original
design (the no-LSP / shared-schema architecture, audience analysis, and per-phase
rationale) is in git history if needed.

## TL;DR — what shipped, what's left

The extensions are no longer thin Docker launchers. **Shipped (both editors unless
noted):**

- **Phase 0–1** — version-derived Docker image tag, configurable container/port,
  bundled `expectation.schema.json` + `*.mockserver.json` validation.
- **Phase 2** — generate-stubs-from-OpenAPI, scratch-request client with
  match / nearest-miss analysis, CodeLens/actions Load / Verify / Diff / Delete.
- **Phase 3** — **record-to-code** (write recorded expectations into a workspace
  file as JSON or DSL).
- **Phase 4** — drift quick-fix (lightbulb/intention mapping `GET /mockserver/drift`
  records to expectation `id`s) + code-aware run gutters (`MockServerClient` /
  `@MockServerSettings` / `@MockServerTest` / Testcontainers `MockServerContainer`).
- **Phase 5** — **in-IDE breakpoint debugger** over the callback WebSocket
  (set matcher by method+path/phases, list paused exchanges, Continue / Modify /
  Abort — Abort REQUEST-phase only). VS Code also ships per-frame stream editing.
- **Phase 6** — chaos panel (`GET/PUT/DELETE /mockserver/chaosExperiment`); VS Code
  also ships LLM authoring completion, agent-run call-graph (Mermaid), and the
  contract/resiliency runner.
- **Trace correlation** — given a W3C trace id (or full `traceparent`), retrieves
  received requests and opens those carrying that trace as JSON. Shipped in
  **both** (JetBrains `FindRequestsByTraceAction` / action `MockServer.FindByTrace`;
  VS Code `mockserver.findByTrace` / `filterRequestsByTrace`).

## Remaining tail

| Item | Editor(s) | Effort | Note |
|------|-----------|--------|------|
| **LLM authoring completion + agent-run call-graph (Mermaid)** | JetBrains | M | Shipped in VS Code; JetBrains lacks the graph-rendering UI infra — needs a Mermaid/diagram surface in the tool window. |
| **Contract / resiliency runner** (per-operation pass/fail on the spec file) | JetBrains | M | Shipped in VS Code; JetBrains needs the same `PUT /mockserver/contractTest` wiring + a results view. |
| **Stream-frame breakpoint editing** (`RESPONSE_STREAM` / `INBOUND_STREAM` Continue/Modify/Drop/Inject/Close) | JetBrains | M | Shipped in VS Code's debugger; deferred in JetBrains. |
| **WASM "test this module against a sample body"** | both | S–M | **Blocked:** no server endpoint exists today (only `PUT/GET/DELETE /mockserver/wasm/modules` — verified in `HttpState.java`). Needs a server-side `wasm/test` (or similar) endpoint first — out of the extensions' scope. |

## Notes

- The two extensions are independently valuable at their current state — the
  remaining items bring JetBrains to VS Code parity (call-graph + contract runner
  + stream-frame breakpoint editing) and add the niche WASM-test surface. Sequence
  JetBrains call-graph + contract runner first (closes the widest parity gap);
  WASM-test is gated on a new server endpoint.
- Architecture unchanged: **no LSP**, the shared asset is the published JSON
  Schema, everything talks to a local/configured MockServer, nothing phones home.
