# MockServer Feature-Gap Roadmap — remaining tail

**Status:** Re-audited 2026-06-19 against `master`. The bulk of the
originally-verified gap set has shipped; this document now tracks only the
**unshipped remainder**. The full original analysis (WS1–WS7, ~80 candidate gaps
reduced to a verified set) is in git history (commit `01575471f` and its
successors) if needed.

## TL;DR — what's left

Almost everything from WS1–WS7 is shipped (client parity, scenario state +
capture, template helpers incl. crypto/regex/csv/xml/html/yaml, Faker, import
redaction + dedup, OpenAPI per-field override, multipart / numeric-operator /
Accept-negotiation / JSON-schema-$ref / conditional / fuzzy matchers, dynamic
latency, conditional+chainable response modifiers, JSON-Patch on forwarded
responses, gRPC bidi templating, OAuth2 `/authorize`, graceful drain, webhook
after-action, multi-tenancy, WS/SSE scripting, per-expectation metrics, Pact
provider-state, GraphQL + AsyncAPI import).

Two more items shipped since the last pruning and have been removed from the
open list:

- **WS4.4 — baseline / snapshot response diffing — SHIPPED.** `PUT
  /mockserver/baseline/compare` is wired in `HttpState` (router branch at
  `HttpState.java:1953`), backed by `org.mockserver.mock.diff.BaselineDiffer`
  which diffs **both** request fields (via `TrafficDiffEngine`) **and** response
  *structure* — status code, headers, and JSON body *shape* (value-insensitive)
  — reported per-interaction in `BaselineDiffReport` / `InteractionDiff`
  (`requestDiffs` + `responseDiffs`). The dashboard "Compare against baseline…"
  tool is live (`mockserver-ui/src/lib/baseline.ts` +
  `components/BaselineCompareDialog.tsx`). A CI fail-threshold/exit-code wrapper
  is the only conceivable follow-on and is out of scope here.
- **WS4.6 — raw `.proto` import — SHIPPED.** `org.mockserver.grpc.GrpcProtoFileCompiler`
  shells out to `protoc` (`--descriptor_set_out --include_imports`) and exposes
  `compile(file)`, `compileSource(string)`, and `compileDirectory(...)`. It is
  wired into server startup in `HttpState` (`HttpState.java:296`): when
  `mockserver.grpcProtoDirectory` is set, every `*.proto` in that directory is
  compiled and loaded into the gRPC descriptor store, with the toolchain path
  configurable via `mockserver.grpcProtocPath`. (Raw `.proto` is imported at
  startup from config; it is not yet exposed as a `PUT` REST endpoint the way
  pre-compiled descriptors are at `PUT /mockserver/grpc/descriptors` — a small,
  optional follow-on, not a gap in `.proto` support itself.)

What remains is a short tail, mostly larger or deliberately-deferred items.

| # | Remaining item | Effort | Why it's still open |
|---|----------------|--------|---------------------|
| WS1.3 | LLM + MCP fluent builders for **Ruby / Go / Rust / .NET / PHP** | L | Verified open: only **Node** (`mockserver-client-node/llm.js`, `mcpMockBuilder.js`) and **Python** (`mockserver-client-python/mockserver/llm.py`, `mcp.py`) ship builders; the other five client dirs have none. The rest are large fluent-builder ports. Tracked in parallel by `feature-improvement-roadmap.md` #5.5 — coordinate so it isn't built twice. |
| WS4.1 | **CLI `import` subcommand** + `MockServerClient` import/export methods | S–M | Verified open: `Main.java` registers only `run / ui / proxy / openapi / version / help` subcommands — no `import` verb. REST endpoints exist; needs a `mockserver import <file>` verb in `Main.java` and typed client wrappers. Touches the control-plane/CLI surface other sessions are editing — sequence it. |
| WS4.7 | **Consumer SDK code generation** (native) | L | Verified open: no SDK generator exists in `mockserver/` or `mockserver-ui/`. Generates a typed *client* SDK from OpenAPI (opposite direction from MockServer's existing expectation/verification codegen). **Decision (2026-06-19): do NOT delegate to openapi-generator** (loss of control); a native generator is unscoped — scope deliberately before building. |
| WS5.4 | Dashboard **body-content request filter** | S | Verified open: `FilterPanel.tsx` exposes only method / path / headers / query / cookies / keepAlive / secure, the `RequestFilter` type (`types.ts`) has no `body` field, and `searchMatcher.ts` does not extract request/response bodies. `FilterPanel` already has regex + saved presets; only a body-content filter field remains (`RequestFilter` extension + UI). |
| WS7.7 | **HTTP/2 server push** (PUSH_PROMISE / PRIORITY) | L | Verified absent (no `PUSH_PROMISE` impl) — but explicitly **decided against** (deprecated, browser-removed); listed only so it isn't re-scoped. AMQP/RabbitMQ, SAML 2.0 IdP, and gRPC Connect from this group already shipped. |

## Suggested order

If picked up: **WS4.1 (CLI import)** and **WS5.4 (body filter)** are the cheap
wins; **WS1.3 (client LLM/MCP parity)** and **WS4.7 (native SDK-gen)** are the
large strategic items and each warrants its own scoping pass. **WS7.7 HTTP/2
push** is a non-goal — drop it from any future slice unless a concrete demand
appears.
