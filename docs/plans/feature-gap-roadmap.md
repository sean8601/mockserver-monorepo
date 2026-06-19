# MockServer Feature-Gap Roadmap — remaining tail

**Status:** Pruned 2026-06-19. The bulk of the originally-verified gap set has
shipped to `master`; this document now tracks only the **unshipped remainder**.
The full original analysis (WS1–WS7, ~80 candidate gaps reduced to a verified set)
is in git history (commit `01575471f` and its successors) if needed.

## TL;DR — what's left

Almost everything from WS1–WS7 is shipped (client parity, scenario state +
capture, template helpers incl. crypto/regex/csv/xml/html/yaml, Faker, import
redaction + dedup, OpenAPI per-field override, multipart / numeric-operator /
Accept-negotiation / JSON-schema-$ref / conditional / fuzzy matchers, dynamic
latency, conditional+chainable response modifiers, JSON-Patch on forwarded
responses, gRPC bidi templating, OAuth2 `/authorize`, graceful drain, webhook
after-action, multi-tenancy, WS/SSE scripting, per-expectation metrics, Pact
provider-state, GraphQL + AsyncAPI import). What remains is a short tail, mostly
larger or deliberately-deferred items.

| # | Remaining item | Effort | Why it's still open |
|---|----------------|--------|---------------------|
| WS1.3 | LLM + MCP fluent builders for **Ruby / Go / Rust / .NET / PHP** | L | Python + Node shipped; the rest are large fluent-builder ports. Tracked in parallel by `feature-improvement-roadmap.md` #5.5 — coordinate so it isn't built twice. |
| WS4.1 | **CLI `import` subcommand** + `MockServerClient` import/export methods | S–M | REST endpoints exist; needs a `mockserver import <file>` verb in `Main.java` and typed client wrappers. Touches the control-plane/CLI surface other sessions are editing — sequence it. |
| WS4.4 | **Baseline / snapshot response diffing** endpoint | M | `BaselineDiffer` exists for request-side structural diff; needs a response-shape baseline-compare endpoint so CI can fail on drift. REST-router change. |
| WS4.6 | Raw **`.proto`** import | M | AsyncAPI + GraphQL SDL import shipped. Raw `.proto` needs a `protoc` compile step / toolchain; compiled gRPC descriptors already import via `PUT /mockserver/grpc/descriptors`. |
| WS4.7 | **Consumer SDK code generation** (native) | L | Generates a typed *client* SDK from OpenAPI (opposite direction from MockServer's existing expectation/verification codegen). **Decision (2026-06-19): do NOT delegate to openapi-generator** (loss of control); a native generator is unscoped — scope deliberately before building. |
| WS5.4 | Dashboard **body-content request filter** | S | `FilterPanel` already has regex + saved presets; only a body-content filter field remains (`RequestFilter` extension + UI). |
| WS7.7 | **HTTP/2 server push** (PUSH_PROMISE / PRIORITY) | L | Explicitly **decided against** (deprecated, browser-removed) — listed only so it isn't re-scoped. AMQP/RabbitMQ, SAML 2.0 IdP, and gRPC Connect from this group already shipped. |

## Suggested order

If picked up: **WS4.1 (CLI import)** and **WS5.4 (body filter)** are the cheap
wins; **WS4.4 (baseline diff)** pairs naturally with the contract-testing work;
**WS1.3 (client LLM/MCP parity)** and **WS4.7 (native SDK-gen)** are the large
strategic items and each warrants its own scoping pass. **WS7.7 HTTP/2 push** is a
non-goal — drop it from any future slice unless a concrete demand appears.
