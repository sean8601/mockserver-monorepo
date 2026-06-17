# MockServer for VS Code

Author, validate, and inspect [MockServer](https://www.mock-server.com) expectations without
leaving VS Code. The extension covers the full workflow: write expectation files with
schema-validated autocompletion, push them to a running server in one click, generate
expectations from an OpenAPI spec or by recording live traffic, inspect the request log, track
mock drift, correlate distributed traces, manage WASM custom rules, and open the live dashboard
inside the editor. Docker start/stop is included for local development.

## Quick start

1. Run **MockServer: Start (Docker)** from the Command Palette to launch a local server
2. Create `login.mockserver.json` — the editor validates the schema as you type
3. Click **Load into running MockServer** (the CodeLens at the top of the file) to push it live
4. Click **Diff against live** to compare your file to what the server has loaded
5. Run **MockServer: Open Dashboard (in editor)** to watch requests arrive in a VS Code tab
6. Run **MockServer: Stop** when done

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for **Start / Stop**;
  all other commands work with any running MockServer instance)
- VS Code 1.80+

## Installation

Install from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=mockserver.mockserver)
or search for **MockServer** in the Extensions view.

For Open VSX (Eclipse Theia, Gitpod, etc.), install from
[open-vsx.org](https://open-vsx.org/extension/mockserver/mockserver).

## Features

### Expectation files — schema validation, completion, and CodeLens

Name a file `*.mockserver.json` or `*.mockserver.jsonc` and the editor validates it against the
same JSON schema MockServer uses internally — so you get inline error squiggles, property
autocompletion, enum suggestions, and hover documentation without ever running the server. A file
may contain a single expectation object or an array (initialization JSON). JSONC files support
comments and trailing commas.

At the top of every expectation file two CodeLens actions appear:

| CodeLens | What it does |
|----------|-------------|
| **Load into running MockServer** | Sends the file's expectation(s) to `PUT /mockserver/expectation` on the configured port |
| **Diff against live** | Fetches the server's active expectations and opens a side-by-side diff against your file |

Both are also available as Command Palette commands.

**Snippets** — in any `.json` file, type a prefix and press Tab:

| Prefix | Inserts |
|--------|---------|
| `mockserver-expectation` | Request matcher + HTTP response stub |
| `mockserver-forward` | Request matcher + forward action |
| `mockserver-verify` | Verification request template |

### Generate and record expectations

| Command | What it does |
|---------|-------------|
| **MockServer: Generate Expectations From OpenAPI Spec** | With an OpenAPI/Swagger spec (JSON or YAML) open in the editor, sends it to `PUT /mockserver/openapi` and opens the generated expectations in a new tab. Save as `*.mockserver.json` to keep them. |
| **MockServer: Save Recorded Expectations (JSON or Java)** | Retrieves expectations the server recorded while proxying real traffic (`PUT /mockserver/retrieve?type=recorded_expectations`). Choose **JSON** (loadable as `*.mockserver.json`) or **Java** (MockServerClient DSL). If nothing has been proxied yet, the command says so rather than opening an empty file. |

### Inspect and control

| Command | What it does |
|---------|-------------|
| **MockServer: View Request Log** | Opens the log of requests the server has received as pretty JSON in a new tab (`PUT /mockserver/retrieve?type=requests&format=json`). |
| **MockServer: Find Requests by Trace** | Prompts for a W3C `traceparent` value or bare 32-hex trace id, then opens every request belonging to that distributed trace in a new JSON tab. |
| **MockServer: Send Test Request** | Fires the request described by a `*.mockserver-request.json` file at the running server and shows `HTTP <status>` + body (pretty-printed when JSON) in a new tab. A **Send to MockServer** CodeLens appears at the top of the file. |
| **MockServer: Show Drift Report** | Fetches drift records (`GET /mockserver/drift`) and shows a readable one-line-per-record report in a new tab. Drift is captured when MockServer proxies traffic and a matching stub expectation differs structurally from the real upstream response. |
| **MockServer: Show Drift as Diagnostics** | Maps each drift record to the matching expectation line in the open `*.mockserver.json` file as an inline diagnostic (error / warning / info). Re-run to refresh; no drift clears them. |
| **MockServer: Reset (Clear Expectations & Logs)** | Clears all expectations and the request log (`PUT /mockserver/reset`) after a confirmation prompt. |

### Dashboard

| Command | What it does |
|---------|-------------|
| **MockServer: Open Dashboard** | Opens `http://localhost:<port>/mockserver/dashboard` in the system browser. |
| **MockServer: Open Dashboard (in editor)** | Opens the same URL in a VS Code webview tab — full in-editor view without switching applications. Re-running the command reveals the existing panel rather than creating a duplicate. |

### WASM custom rules

MockServer supports WebAssembly custom-rule modules for body matching (requires `wasmEnabled=true`
on the server). The extension lets you manage modules without leaving VS Code:

| Command | What it does |
|---------|-------------|
| **MockServer: Upload WASM Module** | Opens a file picker for a `.wasm` file, prompts for a module name (defaulting to the file's basename), and uploads it via `PUT /mockserver/wasm/modules?name=<name>`. |
| **MockServer: List WASM Modules** | Retrieves the names of all registered modules (`GET /mockserver/wasm/modules`) and opens them as JSON in a new tab. |

Once uploaded, reference a module in an expectation body matcher:

```json
{
  "httpRequest": {
    "body": { "type": "WASM", "moduleName": "myRule" }
  },
  "httpResponse": { "statusCode": 200 }
}
```

If WASM support is disabled on the server, the server's own error message is surfaced verbatim.

### Docker lifecycle

| Command | What it does |
|---------|-------------|
| **MockServer: Start (Docker)** | Runs `docker run -d --rm --name <containerName> -p <port>:1080 <image>`. Checks that Docker is running and that the container is not already active. |
| **MockServer: Stop** | Stops the named container with `docker stop <containerName>`. |

## Commands reference

All commands are available from the Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`) by typing "MockServer".

| Command | Title in palette |
|---------|-----------------|
| `mockserver.start` | MockServer: Start (Docker) |
| `mockserver.stop` | MockServer: Stop |
| `mockserver.openDashboard` | MockServer: Open Dashboard |
| `mockserver.openDashboardInEditor` | MockServer: Open Dashboard (in editor) |
| `mockserver.loadExpectations` | MockServer: Load Expectations Into Running Server |
| `mockserver.diffAgainstLive` | MockServer: Diff Expectations Against Live Server |
| `mockserver.saveRecorded` | MockServer: Save Recorded Expectations (JSON or Java) |
| `mockserver.generateFromOpenApi` | MockServer: Generate Expectations From OpenAPI Spec |
| `mockserver.sendRequest` | MockServer: Send Test Request |
| `mockserver.showDrift` | MockServer: Show Drift Report |
| `mockserver.showDriftDiagnostics` | MockServer: Show Drift as Diagnostics |
| `mockserver.viewRequestLog` | MockServer: View Request Log |
| `mockserver.findByTrace` | MockServer: Find Requests by Trace |
| `mockserver.reset` | MockServer: Reset (Clear Expectations & Logs) |
| `mockserver.uploadWasm` | MockServer: Upload WASM Module |
| `mockserver.listWasm` | MockServer: List WASM Modules |

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `mockserver.dockerImage` | _(empty)_ | Docker image for **Start (Docker)**. Empty defaults to `mockserver/mockserver:<extension-version>`, keeping the image tag in lockstep with the extension release. |
| `mockserver.containerName` | `mockserver-vscode` | Name of the container started and stopped by the extension. |
| `mockserver.port` | `1080` | Host port mapped to the container's port 1080. Used for the dashboard URL and all API commands. |

## File conventions

| File pattern | Purpose |
|--------------|---------|
| `*.mockserver.json` | Expectation file — JSON schema validation, CodeLens (Load + Diff), snippet support |
| `*.mockserver.jsonc` | Same as above, but allows comments and trailing commas |
| `*.mockserver-request.json` | Scratch request file — CodeLens (**Send to MockServer**), used by **MockServer: Send Test Request** |

**Scratch request file format** (`*.mockserver-request.json`):

```json
{
  "method": "GET",
  "path": "/api/resource",
  "headers": { "Accept": "application/json" },
  "body": ""
}
```

`method` and `path` are required; `headers` and `body` are optional.

## Try it locally

From the repo root, one command builds the extension, starts a local MockServer, loads sample
files, and opens a sandboxed VS Code Extension Development Host — nothing to install or uninstall:

```bash
scripts/try-editor-extensions.sh           # VS Code Extension Development Host
scripts/try-editor-extensions.sh --install # or install the packaged .vsix into your VS Code
```

## Building from source

```bash
npm install
npm run compile
npm test
```

## Packaging

```bash
npx vsce package
```

Produces a `.vsix` file installable via `code --install-extension mockserver-*.vsix`.

## License

Apache-2.0
