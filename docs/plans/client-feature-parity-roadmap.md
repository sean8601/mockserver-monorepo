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

## Remaining parity gaps (per the breadth survey)

| Area | Clients missing it | Notes | Size |
|------|--------------------|-------|------|
| Callbacks (class + closure/websocket) | Go, .NET, Rust, PHP | Node/Python/Ruby have closure callbacks only; Java full | L |
| OpenAPI import / matcher | Go, PHP | generate-from-spec + OpenAPI request matcher | M |
| Control-plane auth (Basic / mTLS) | Go, .NET, Rust, PHP | client-side auth headers + mTLS material | M |
| TLS / mTLS client config | Go (partial), .NET (partial), PHP (none) | secure transport + client certs | M |
| LLM mocking builders | Go | other clients have LLM builders | M |
| Load-scenario injection | PHP | every other client has it | S |
| Breakpoint / debugging | PHP | every other client has it | M |
| `responseWeights` / `switchAfter` fluent setters | Java fluent (added in scenario work) | confirm all clients expose typed setters, not just fields | S |

## Schema-consistency follow-ups (surfaced during the scenario work)

- **Editor-extension bundled schemas are stale**: `mockserver-vscode/schemas/mockserver-expectation.schema.json`
  and `mockserver-jetbrains/src/main/resources/schemas/mockserver-expectation.schema.json` (identical)
  use `additionalProperties: false` but lack `scenarioName`/`scenarioState`/`newScenarioState`,
  `responseMode`/`responseWeights`/`switchAfter`, and `crossProtocolScenarios`. A user authoring those
  fields in a `*.mockserver.json` gets false IDE validation errors. Regenerate these from the server
  `expectation.json` (single source of truth) so the IDE schema tracks the server.
- **Consumer OpenAPI** (`jekyll-www.mock-server.com/mockserver-openapi.yaml`) is a minimal control-plane
  spec (no `switchAfter` either); optionally extend its expectation model with `crossProtocolScenarios`
  so generated Postman/Bruno collections can demonstrate it.

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
| `proxy/configuring_sut.html` | ~448 | anchor every h3 + brief proxy-type TOC | S |

## How to validate each wave

Use `examples/validate/run.sh <client>` — it builds `mockserver-under-test:local`, starts it on a
private Docker network, and runs that client's `examples/<lang>/...` against it inside the matching
toolchain container (mounting the host CA bundle so dependency fetches work behind a TLS-inspecting
proxy). Add a `scenario`-style runnable, self-asserting example for each new feature so the harness
gates it.
