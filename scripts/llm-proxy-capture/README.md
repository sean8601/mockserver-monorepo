# Capturing coding-assistant LLM traffic with MockServer

Point a coding-assistant CLI at MockServer running as an HTTPS proxy and MockServer
**records and classifies** its LLM calls, so they appear in the dashboard's **Traffic**,
**LLM Traces**, and **LLM Optimise** views.

## TL;DR

```bash
# 1. start MockServer as a proxy (any port; 1080 used here)
java -jar mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar \
     -serverPort 1080 -logLevel INFO

# 2. run the smoke test — it drives whichever CLIs are installed + authed, then asserts capture
scripts/llm-proxy-capture/capture-smoke.sh

# 3. view it
open http://localhost:1080/mockserver/dashboard   # Traffic · LLM Traces · LLM Optimise
```

The script is **local-only**: it invokes real, interactively-authenticated CLIs and makes
small live calls to model providers / your org's gateway. It is **skipped on CI**. The
CI-safe equivalent is the fixture-driven `CodingCliLlmCaptureTest` (mockserver-core) and
`llmTraffic.test.ts` (mockserver-ui), which exercise the same detection with no network or
credentials.

## Interactive UX testing — `npm run capture`

The script above is an **automated assertion** (proxy → run → assert → exit). To instead
**watch traffic appear live in the dashboard while you use a tool by hand**, use the
companion launcher:

```bash
cd mockserver-ui
npm run capture -- opencode      # or: claude | tabnine   (omit a tool to just start the servers)
```

It starts MockServer (proxy) **and** the UI dev server, opens the dashboard, sets the proxy
env, and drops you straight into the chosen CLI — interactively — so every call it makes
streams into the Traffic / LLM Traces / LLM Optimise tabs in real time. The dashboard is
served from the dev server, so the latest UI (e.g. provider detection) is live without
rebuilding the jar. Exit the tool (or Ctrl+C) to tear the servers down. It is the
LLM-capture sibling of `npm run demo` (which loads synthetic data instead). See
`mockserver-ui/scripts/launch-with-llm-capture.sh --help`.

### A/B control: `--direct`

To isolate whether a problem (e.g. a request timeout) is the upstream **provider** or the
**MockServer proxy hop**, run the same prompt both ways and compare:

```bash
npm run capture -- opencode            # through MockServer (captured)
npm run capture -- opencode --direct   # straight to the provider — no proxy, no MockServer
```

`--direct` clears any proxy/CA env and runs the tool against its real provider over its own
default transport (e.g. HTTP/2). Nothing is captured — it is the control sample.

## What it checks, per tool

| Step | Meaning |
|------|---------|
| **CAPTURE** | the tool's LLM endpoint shows up in the recorded request log (proxy + TLS interception works) |
| **CLASSIFY** | that call appears in the LLM optimisation report with the expected provider (it will render in LLM Traces / Optimise) |

## Supported CLIs and the endpoints they use

| CLI | LLM endpoint | Wire format | Provider |
|-----|--------------|-------------|----------|
| **claude** (Claude Code) | `POST api.anthropic.com/v1/messages` | Anthropic Messages | `ANTHROPIC` |
| **opencode** (Codex) | `POST chatgpt.com/backend-api/codex/responses` | OpenAI Responses | `OPENAI_RESPONSES` |
| **tabnine** (Gemini-CLI fork) | `POST <gateway>/…/chat/completions` | OpenAI Chat Completions | `OPENAI` |

`opencode`'s OpenAI **Codex** backend serves the Responses API at a non-standard path
(`/backend-api/codex/responses`); MockServer recognises it the same as the hosted
`/v1/responses` endpoint. `tabnine` is matched by its `/chat/completions` path, so it works
regardless of which (possibly private) gateway host it is configured for.

## Why each CLI trusts the proxy

MockServer terminates TLS with its own CA, so each CLI must trust that CA and route through
the proxy. The script exports these for every tool it launches:

```bash
export HTTPS_PROXY=http://localhost:1080
export NODE_EXTRA_CA_CERTS=.../CertificateAuthorityCertificate.pem   # node CLIs (opencode, tabnine)
export SSL_CERT_FILE=.../CertificateAuthorityCertificate.pem          # curl/openssl clients
export REQUESTS_CA_BUNDLE=.../CertificateAuthorityCertificate.pem     # python clients
```

The default CA is MockServer's **public test CA** shipped in this repo
(`mockserver/mockserver-core/.../socket/CertificateAuthorityCertificate.pem`). For a
hardened setup, generate your own CA and point `MOCKSERVER_CA` at it.

## Configuration (env overrides)

| Variable | Default | Purpose |
|----------|---------|---------|
| `MOCKSERVER_URL` | `http://localhost:1080` | base URL of a **running** MockServer proxy |
| `MOCKSERVER_CA` | repo test CA | proxy CA certificate the CLIs must trust |
| `CAPTURE_PROMPT` | `Reply with exactly the single word: hello` | prompt sent to each CLI |
| `CAPTURE_TIMEOUT` | `120` | per-tool timeout (seconds) |
| `CAPTURE_TOOLS` | `claude opencode tabnine` | subset to consider |
| `FORCE` | _unset_ | set `1` to run even when a CI env is detected |

## No secrets

The script holds no API keys and names no private hosts. CLIs use their own stored
credentials; tabnine's gateway is matched by URL path, never hard-coded. Safe to commit.
