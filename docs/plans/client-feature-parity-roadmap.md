# Client Feature-Parity Roadmap

## Outcome / Decision

The 8 MockServer client libraries are **not** at feature parity with the Java client / core
model. The **stateful-scenario** gap has now been closed (typed scenario APIs in every client +
a Docker validation harness + runnable examples + docs — see the Stateful Scenarios work). This
roadmap tracks the **remaining** parity gaps, sized into later waves, each to be validated through
the new `examples/validate/run.sh` Docker harness.

## What was just delivered (scenarios — DONE)

- Typed scenario support in all 8 clients: `scenarioName`/`scenarioState`/`newScenarioState`,
  `httpResponses` + `responseMode`, `responseWeights`, `switchAfter`, `crossProtocolScenarios`,
  and a `scenario(name)` REST helper (`state`/`set`/`set-timed`/`trigger`) + `scenarios()` list.
- Core bug fixed: `crossProtocolScenarios` was modelled and handled at runtime but missing from the
  expectation validation schema, so the server rejected it (HTTP 400). Added to
  `expectation.json` + `mock-server-openapi-embedded-model.yaml`.
- `examples/<collection>/scenario/` runnable, self-asserting examples for all 10 collections,
  validated end-to-end by `examples/validate/run.sh` (every client passes against a freshly-built
  MockServer image).

## Gap re-verification (2026-06-22) — the breadth survey was substantially wrong

A precise, file:line re-verification of every claimed gap found **most were already complete**:

| Area | Survey said | Reality |
|------|-------------|---------|
| OpenAPI import / matcher | missing in Go, PHP | **already COMPLETE** in both (`openapi.go`, `OpenAPIExpectation.php`) |
| LLM mocking builders | missing in Go | **already COMPLETE** (`llm.go`) |
| Load-scenario injection | missing in PHP | **already COMPLETE** (`MockServerClient.php` loadScenario/status/stop + `LoadScenario.php`) |
| Breakpoint / debugging | missing in PHP | **infeasible by design** — PHP client is REST-only (no WebSocket); breakpoints need a bidirectional callback WS. Go/.NET/Rust/Python/Node/Java all have it. |
| `responseWeights`/`switchAfter` setters | confirm | done in the scenario work |

## DONE in the parity waves

- **Control-plane auth + TLS/mTLS** across Go, .NET, Rust, PHP, Node, Python: control-plane bearer
  token (static + supplier), CA trust, client cert (mTLS). Additive; unit-tested per client (header
  attach + cert/TLS wiring with real cert material).
- **Editor JSON Schema sync**: regenerated `mockserver-vscode` + `mockserver-jetbrains` schemas (now
  carry `crossProtocolScenarios`/`responseWeights`/`switchAfter`/`rateLimit`/full `responseMode`);
  fixed the generator's reference-file list (`rateLimit`, `conditionalRequestDefinition`, `recoverAfter`).

## Callbacks — DONE

Class callbacks (`httpResponseClassCallback`/`httpForwardClassCallback`) and object/closure
callbacks (`httpObjectCallback` over the callback WebSocket) are now implemented across all
applicable clients. PHP supports only class callbacks (REST-only; no WebSocket).

| Client | Class callback | Object callback |
|--------|---------------|-----------------|
| Java | DONE | DONE |
| Go | DONE (`callback.go`: `HttpClassCallback`, `MockWithCallback`) | DONE (`HttpObjectCallback`, `MockWithCallback`) |
| .NET | DONE (`RespondWithClassCallback`, `ForwardWithClassCallback`) | DONE (`BreakpointWebSocketClient`) |
| Rust | DONE (`HttpClassCallback` in `model.rs`) | DONE (`mock_with_callback` via `breakpoint.rs`) |
| Node | DONE (`respond(HttpClassCallback)`, `.callback(handler)`) | DONE (`.callback()` over WS) |
| Python | DONE (model + builder) | DONE (WS client) |
| Ruby | verify — no explicit check done | verify |
| PHP | DONE (`HttpClassCallback.php`) | infeasible (REST-only, no WS) |

Callback wire contract (from `httpClassCallback.json` / `httpObjectCallback.json` + `CallbackWebSocketServerHandler`): class = `{callbackClass, delay?, primary?}`; object = `{clientId, responseCallback, delay?, primary?}`; the WS registers a clientId, the server sends `{type:"org.mockserver.model.HttpRequest", value}` and the client replies `{type:"org.mockserver.model.HttpResponse", value}` carrying a `WebSocketCorrelationId` header. Reference: Node `webSocketClient.js`, Java `BreakpointWebSocketClient`.

## Optional consistency follow-up

- **Consumer OpenAPI** (`jekyll-www.mock-server.com/mockserver-openapi.yaml`) is a minimal control-plane
  spec; optionally extend its expectation model with `crossProtocolScenarios` for the generated
  Postman/Bruno collections.
- **Wire the editor-schema generator into the JetBrains gradle build** (VS Code already runs it on
  `vscode:prepublish`) so the schema can't drift again.

## Documentation navigation follow-ups (from the nav audit)

The Stateful Scenarios work also delivered a nav audit. Top fixes applied: a new self-navigating
Stateful Scenarios page; a feature-overview table added to `response_templates.html`. Remaining
high-value fixes:

| Page | Lines | Fix | Size |
|------|-------|-----|------|
| `configuration_properties.html` | ~2,418 | top-of-page searchable property index across the 12 includes | M |
| `chaos_testing.html` | ~1,770 | feature/overview matrix + wrap long example sequences in accordions | M |
| `using_openapi.html` | ~1,508 | capability feature table (generate / match / verify / clear × OpenAPI / WSDL) | S |
| `debugging_issues.html` | ~904 | top feature table of retrieval methods | S |
| `jekyll-www.mock-server.com/proxy/configuring_sut.html` | ~448 | anchor every h3 + brief proxy-type TOC | S |

## How to validate each wave

Use `examples/validate/run.sh <client>` — it builds `mockserver-under-test:local`, starts it on a
private Docker network, and runs that client's `examples/<lang>/...` against it inside the matching
toolchain container (mounting the host CA bundle so dependency fetches work behind a TLS-inspecting
proxy). Add a `scenario`-style runnable, self-asserting example for each new feature so the harness
gates it.
