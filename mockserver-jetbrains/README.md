# MockServer JetBrains Plugin

MockServer integration for IntelliJ-based IDEs: expectation-file validation, plus Docker container controls.

## Features

- **Expectation file validation** — name a file `*.mockserver.json` (or `*.mockserver.jsonc`) and the IDE
  validates it as you type, with autocompletion and hover docs. The schema is the same one MockServer
  validates against, generated from `mockserver-core`. A single expectation or an array of expectations
  (initialization JSON) is accepted.
- **Open MockServer Dashboard** — launches the dashboard in your default browser (port from Settings)
- **Start MockServer (Docker)** — runs a MockServer container using the configured image, name, and port
- **Tool Window** — bottom panel with quick-access buttons for the above actions
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
