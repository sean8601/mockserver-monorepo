# MockServer for VS Code

Author, validate, and run [MockServer](https://www.mock-server.com) expectations directly from Visual Studio Code, and start/stop a MockServer Docker container without leaving the editor.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running
- VS Code 1.80+

## Installation

Install from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=mockserver.mockserver) or search for "MockServer" in the Extensions view.

For Open VSX (Eclipse Theia, Gitpod, etc.), install from [open-vsx.org](https://open-vsx.org/extension/mockserver/mockserver).

## Commands

Open the Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`) and type "MockServer":

| Command | Description |
|---------|-------------|
| **MockServer: Start (Docker)** | Starts the `mockserver/mockserver` container (see Settings below) |
| **MockServer: Stop** | Stops the running MockServer container |
| **MockServer: Open Dashboard** | Opens the MockServer dashboard in your browser |
| **MockServer: Load Expectations Into Running Server** | Sends the current expectation file to the running server |
| **MockServer: Diff Expectations Against Live Server** | Opens a diff between the file and the server's active expectations |
| **MockServer: Save Recorded Expectations (JSON or Java)** | Opens expectations recorded from proxied traffic as JSON or Java DSL |
| **MockServer: Generate Expectations From OpenAPI Spec** | Turns the OpenAPI/Swagger spec in the active editor into expectations |
| **MockServer: Send Test Request** | Fires the request described by the active `*.mockserver-request.json` file at the running server and shows the response |

## Expectation file validation

Name an expectation file `*.mockserver.json` (for example `login.mockserver.json`) and the editor
validates it as you type — inline error squiggles, autocompletion of properties and enums, and hover
documentation. The schema is the same one MockServer validates against, so the editor is never stricter or
laxer than the server. A file may contain a single expectation **or** an array of expectations
(initialization JSON). `*.mockserver.jsonc` (JSON with comments) is also recognised.

## Expectation files as a live control surface

At the top of any `*.mockserver.json` file the extension shows two CodeLens actions:

- **Load into running MockServer** — sends the file's expectation(s) to the running server
  (`PUT /mockserver/expectation`) on the configured port, so you can go from editing to live in one click.
- **Diff against live** — fetches the server's currently-registered expectations and opens a side-by-side
  diff against your file, so you can see what's actually loaded versus what's in source.

Both use the `mockserver.port` setting to reach the server. They are also available from the Command Palette.

## Record real traffic into code

Run **MockServer: Save Recorded Expectations** to capture expectations the server has recorded while
proxying or forwarding to a real upstream, and open them in a new editor tab as either **JSON** (ready to
save as a `*.mockserver.json` file) or **Java** (MockServerClient DSL to paste into a test). If the server
has not proxied any traffic yet, the command tells you so rather than opening an empty file.

## Generate expectations from an OpenAPI spec

With an OpenAPI/Swagger spec (JSON or YAML) open in the editor, run **MockServer: Generate Expectations
From OpenAPI Spec**. The extension sends the spec to the running server (`PUT /mockserver/openapi`), which
generates expectations covering the spec's operations, and opens them in a new tab to review and save.

## Send a test request without leaving the editor

Create a file named `*.mockserver-request.json` (for example `ping.mockserver-request.json`) describing
a single HTTP request:

```json
{
  "method": "GET",
  "path": "/api/x",
  "headers": { "Accept": "application/json" },
  "body": ""
}
```

`method` and `path` are required; `headers` and `body` are optional. A **Send to MockServer** CodeLens
appears at the top of the file (the command is also available from the Command Palette as
**MockServer: Send Test Request**). Running it fires the request at the running server on the configured
`mockserver.port` and opens the response in a new tab as `HTTP <status>` followed by the body
(pretty-printed when it is JSON). Error responses are shown too, so you can probe both matched and
unmatched paths.

## Snippets

In any `.json` file, type:

- `mockserver-expectation` - inserts a full expectation template (request + response)
- `mockserver-forward` - inserts an expectation with a forward action
- `mockserver-verify` - inserts a verify request template

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `mockserver.dockerImage` | _(empty)_ | Docker image to start. Empty uses `mockserver/mockserver:<extension version>`, keeping the image tag in lockstep with this extension. |
| `mockserver.containerName` | `mockserver-vscode` | Name of the container started/stopped by the extension. |
| `mockserver.port` | `1080` | Host port mapped to the container's port 1080 (used for the dashboard URL and the run command). |

## Quick Start

1. Run **MockServer: Start (Docker)** from the Command Palette
2. Create a `login.mockserver.json` file and start typing — you get validation and completion for free
3. Create expectations by POSTing JSON to `http://localhost:1080/mockserver/expectation`
4. Run **MockServer: Open Dashboard** to inspect recorded requests
5. Run **MockServer: Stop** when done

## Try it locally

From the repo root, one command builds the extension, starts a local MockServer, drops in sample files,
and opens a sandboxed VS Code Extension Development Host (nothing to install or uninstall):

```bash
scripts/try-editor-extensions.sh           # VS Code dev host
scripts/try-editor-extensions.sh --install # or install the packaged .vsix into your VS Code
```

## Building from Source

```bash
npm install
npm run compile
npm test
```

## Packaging

```bash
npx vsce package
```

This produces a `.vsix` file you can install manually via `code --install-extension mockserver-*.vsix`.

## License

Apache-2.0
