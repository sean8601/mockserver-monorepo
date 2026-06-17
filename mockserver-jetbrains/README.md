# MockServer JetBrains Plugin

MockServer integration for IntelliJ-based IDEs: expectation-file validation, plus Docker container controls.

## Features

- **Expectation file validation** — name a file `*.mockserver.json` (or `*.mockserver.jsonc`) and the IDE
  validates it as you type, with autocompletion and hover docs. The schema is the same one MockServer
  validates against, generated from `mockserver-core`. A single expectation or an array of expectations
  (initialization JSON) is accepted.
- **Open MockServer Dashboard** — launches the dashboard in your default browser (port from Settings)
- **Open MockServer Dashboard in IDE** — embeds the live dashboard inside the IDE using the bundled JCEF
  (Chromium) engine in a dedicated right-hand tool window, with Reload and Open-in-Browser controls. When
  JCEF is unavailable (e.g. a runtime without the JCEF engine) it falls back to opening the external browser
- **Start MockServer (Docker)** — runs a MockServer container using the configured image, name, and port
- **Load Expectations Into Running Server** — sends the active editor file to `PUT /mockserver/expectation`
  on the running server (a single expectation or an array of expectations is accepted)
- **Save Recorded Expectations** — retrieves the expectations MockServer recorded from proxied/forwarded
  traffic (`PUT /mockserver/retrieve?type=recorded_expectations`) and opens them in a new JSON tab
- **Generate Expectations From OpenAPI Spec** — sends the active editor's OpenAPI/Swagger spec (JSON or YAML)
  to `PUT /mockserver/openapi` and opens the generated expectations in a new JSON tab
- **Send Test Request** — sends the ad-hoc HTTP request described in the active editor
  (a JSON object `{ "method", "path", "headers"?, "body"? }`) at the running server on the configured port
  and opens the response (`HTTP <status>` plus the body, pretty-printed when JSON) in a new editor tab
- **Show Drift Report** — fetches MockServer's mock-drift records (`GET /mockserver/drift`) — how real
  upstream responses have drifted from your stub expectations — and opens a readable text report in a new
  tab (one line per drift: type, field, expected vs actual value, confidence, and the affected expectation)
- **Reset MockServer** — clears all expectations and recorded logs on the running server
  (`PUT /mockserver/reset`); asks for confirmation first
- **Tool Window** — bottom panel that surfaces every action as a one-click button, grouped into
  *Server* (Open Dashboard in IDE, Open Dashboard in Browser, Start (Docker), Reset) and *Editor actions*
  (Load Expectations, Save Recorded, Generate From OpenAPI, Send Test Request, Show Drift Report), so the
  full action set is reachable without opening the **Tools > MockServer** menu
- **Settings** — configure the Docker image, container name, and port under **Settings | Tools | MockServer**

## Requirements

- IntelliJ IDEA 2024.3+ (or any JetBrains IDE based on IntelliJ Platform build 243+)
- Docker (for the "Start MockServer" action)

## Installation

### From JetBrains Marketplace (once published)

1. Open **Settings > Plugins > Marketplace**
2. Search for "MockServer"
3. Click **Install**

### From local build

```bash
cd mockserver-jetbrains
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/mockserver-jetbrains-<version>.zip`. Install via **Settings > Plugins > gear icon > Install Plugin from Disk**.

## Usage

1. Create an expectation file named `*.mockserver.json` and start typing — validation and completion are automatic
2. Go to **Tools > MockServer > Open MockServer Dashboard** to view the dashboard
3. Go to **Tools > MockServer > Start MockServer (Docker)** to launch a container
4. The **MockServer** tool window (bottom bar) provides the same actions as buttons
5. Adjust the image, container name, and port under **Settings | Tools | MockServer**

## Building

```bash
./gradlew buildPlugin
```

## Running tests

```bash
./gradlew test
```

## Try it locally

From the repo root, one command builds the plugin and launches it in a sandbox IDE (and starts a local
MockServer so the actions work):

```bash
scripts/try-editor-extensions.sh jetbrains
```

## Running in a sandbox IDE

```bash
./gradlew runIde
```

## Development

- **Language:** Kotlin
- **Build system:** Gradle with IntelliJ Platform Gradle Plugin 2.x
- **Minimum platform:** IntelliJ Platform 2024.3 (build 243)
- **Java:** 17+
- **Expectation schema:** generated from `mockserver-core` by `scripts/generate-editor-expectation-schema.mjs` into `src/main/resources/schemas/`; re-run that script after changing the core schemas.
