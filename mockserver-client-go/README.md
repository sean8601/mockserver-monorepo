# MockServer Go Client

An idiomatic Go client for the [MockServer](https://www.mock-server.com) control-plane REST API.

Zero third-party dependencies — uses only the Go standard library (`net/http` + `encoding/json`).

## Installation

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-client-go
```

## Quick Start

```go
package main

import (
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go"
)

func main() {
    client := mockserver.New("localhost", 1080)

    // Create an expectation
    _, err := client.When(
        mockserver.Request().Method("GET").Path("/hello"),
    ).Respond(
        mockserver.Response().StatusCode(200).Body("world"),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Verify the request was received at least once
    err = client.Verify(
        mockserver.Request().Path("/hello"),
        mockserver.AtLeast(1),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Reset all expectations and logs
    if err := client.Reset(); err != nil {
        log.Fatal(err)
    }
}
```

## API

### Client Construction

```go
// By host and port
client := mockserver.New("localhost", 1080)

// From a full URL
client := mockserver.NewFromURL("http://mockserver.internal:1080")

// With options
client := mockserver.New("localhost", 1080,
    mockserver.WithContextPath("/myapp"),
    mockserver.WithTimeout(10 * time.Second),
)
```

### Creating Expectations

```go
// Simple response
client.When(
    mockserver.Request().Method("GET").Path("/api/users"),
).Respond(
    mockserver.Response().StatusCode(200).JSONBody(`[{"id":1}]`),
)

// With times and TTL
client.When(
    mockserver.Request().Method("POST").Path("/api/orders"),
    mockserver.WithTimes(mockserver.Once()),
    mockserver.WithTimeToLive(mockserver.TTL("SECONDS", 60)),
).Respond(
    mockserver.Response().StatusCode(201).WithDelay("MILLISECONDS", 100),
)

// Forward to another host
client.When(
    mockserver.Request().Path("/proxy/.*"),
).Forward(
    mockserver.Forward().Host("backend.local").Port(8080).Scheme("HTTP"),
)

// With expectation ID for deduplication
client.When(
    mockserver.Request().Path("/stable"),
).WithID("my-stable-exp").Respond(
    mockserver.Response().StatusCode(200),
)
```

### Verification

```go
// At least N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtLeast(1))

// At most N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtMost(5))

// Exactly N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.ExactlyTimes(3))

// Between min and max
client.Verify(mockserver.Request().Path("/hello"), mockserver.Between(1, 5))

// Sequence verification
client.VerifySequence(
    mockserver.Request().Path("/login"),
    mockserver.Request().Path("/dashboard"),
)
```

### Retrieval

```go
// Active expectations
expectations, _ := client.RetrieveActiveExpectations(nil)

// Recorded requests (optionally filtered)
requests, _ := client.RetrieveRecordedRequests(mockserver.Request().Path("/api/.*"))

// Recorded expectations
recorded, _ := client.RetrieveRecordedExpectations(nil)

// Active expectations as generated MockServer SDK setup code
// (format: FormatJava, FormatJavaScript, FormatPython, FormatGo,
//  FormatCSharp, FormatRuby, FormatRust or FormatPHP)
code, _ := client.RetrieveExpectationsAsCode(nil, mockserver.FormatJava)

// Recorded request/response pairs as generated SDK setup code
recordedCode, _ := client.RetrieveRecordedExpectationsAsCode(nil, mockserver.FormatGo)

// Log messages
logs, _ := client.RetrieveLogMessages(nil)
```

### Control

```go
// Clear specific expectations
client.Clear(mockserver.Request().Path("/old"), mockserver.ClearExpectations)

// Clear by expectation ID
client.ClearByID("exp-123", mockserver.ClearAll)

// Reset everything
client.Reset()

// Check server status
status, _ := client.Status()
fmt.Println(status.Ports)

// Check if running
if client.IsRunning() { ... }

// Bind additional ports
ports, _ := client.Bind(1081, 1082)
```

### Interactive Breakpoints

Register breakpoint matchers to pause forwarded/proxied traffic at REQUEST, RESPONSE, RESPONSE_STREAM, or INBOUND_STREAM phases. A callback WebSocket connection is opened automatically to receive paused items.

```go
// REQUEST-only breakpoint
id, _ := client.AddRequestBreakpoint(
    mockserver.Request().Path("/api/.*"),
    func(req map[string]interface{}) interface{} {
        // Inspect/modify the request, or return a response to abort
        return req // continue with original
    },
)

// REQUEST + RESPONSE breakpoint
id, _ := client.AddRequestResponseBreakpoint(
    mockserver.Request().Path("/api/.*"),
    func(req map[string]interface{}) interface{} { return req },
    func(req, resp map[string]interface{}) map[string]interface{} { return resp },
)

// Streaming breakpoint (RESPONSE_STREAM / INBOUND_STREAM)
id, _ := client.AddStreamBreakpoint(
    mockserver.Request().Path("/stream/.*"),
    []mockserver.BreakpointPhase{mockserver.PhaseResponseStream},
    func(frame *mockserver.PausedStreamFrame) *mockserver.StreamFrameDecision {
        d := mockserver.ContinueFrame(frame.CorrelationID)
        return &d
    },
)

// List, remove, clear matchers
list, _ := client.ListBreakpointMatchers()
client.RemoveBreakpointMatcher(id)
client.ClearBreakpointMatchers()
client.CloseBreakpointWebSocket()
```

**Stream frame decisions:** `ContinueFrame`, `ModifyFrame`, `DropFrame`, `InjectFrame`, `CloseFrame`.

## Start / Launch MockServer

The Go client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```go
package main

import (
    "fmt"
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go"
)

func main() {
    handle, err := mockserver.StartServer(1080, "", nil)
    if err != nil {
        log.Fatal(err)
    }
    defer handle.Stop()

    fmt.Printf("MockServer running on port %d\n", handle.Port)
    // ... use MockServer ...
}
```

### Just ensure the binary is present

```go
launcherPath, err := mockserver.EnsureBinary(mockserver.Version, nil)
if err != nil {
    log.Fatal(err)
}
fmt.Println("Launcher at:", launcherPath)
```

### Specify a version

```go
handle, err := mockserver.StartServer(1080, "7.1.0", nil)
```

### API reference

| Function / Type | Description |
|---|---|
| `EnsureBinary(version string, opts *EnsureOptions) (string, error)` | Download, verify, cache, and return the launcher path. |
| `StartServer(port int, version string, opts *EnsureOptions) (*ServerHandle, error)` | Ensure the binary and start MockServer. Pass `""` for version to use the default. |
| `ServerHandle` | Handle to the running process. Fields: `Port`, `Launcher`. Methods: `Stop() error`, `Wait() error`. |
| `Version` | The default MockServer version this client targets (from embedded `VERSION` file). |
| `ResolvePlatform() (PlatformInfo, error)` | Detect the current OS and architecture. |
| `CacheDir() string` | Return the binary cache directory path. |

### Supported platforms

| OS | Architecture |
|---|---|
| Linux | x86_64, aarch64 |
| macOS (darwin) | x86_64, aarch64 |
| Windows | x86_64, aarch64 |

### Environment variables

| Variable | Purpose |
|---|---|
| `MOCKSERVER_BINARY_BASE_URL` | Mirror host for the release assets (corporate / air-gapped networks) |
| `MOCKSERVER_BINARY_CACHE` | Override the cache directory (default: `~/.cache/mockserver/binaries` on Unix) |
| `MOCKSERVER_SKIP_BINARY_DOWNLOAD` | Fail instead of downloading (use with a pre-seeded cache in CI) |

### Version

By default the launcher downloads the MockServer version embedded in the `VERSION` file at build time. The release pipeline updates this file automatically. Pass an explicit version string to `StartServer` or `EnsureBinary` to override.

## Build & Test

```bash
go test ./...
go vet ./...
```

Integration tests require `MOCKSERVER_URL` environment variable:

```bash
MOCKSERVER_URL=http://localhost:1080 go test ./... -v
```

## Requirements

- Go 1.21 or later
- `github.com/gorilla/websocket` (for breakpoint callback WebSocket)
