# MockServer Ruby Client

Hand-written Ruby client for [MockServer](https://www.mock-server.com) with full REST API, fluent builder DSL, and WebSocket callback support.

## Installation

Add to your Gemfile:

```ruby
gem 'mockserver-client', '~> 5.16'
```

Or install directly:

```bash
gem install mockserver-client
```

## Quick Start

```ruby
require 'mockserver-client'

# Create a client
client = MockServer::Client.new('localhost', 1080)

# Set up an expectation using the fluent API
client.when(
  MockServer::HttpRequest.request(path: '/hello')
    .with_method('GET')
).respond(
  MockServer::HttpResponse.response(body: 'world', status_code: 200)
)

# Verify a request was received
client.verify(
  MockServer::HttpRequest.request(path: '/hello'),
  times: MockServer::VerificationTimes.at_least(1)
)

# Clean up
client.reset
client.close
```

## Block Form

```ruby
MockServer::Client.new('localhost', 1080) do |client|
  client.when(
    MockServer::HttpRequest.request(path: '/api/test')
  ).respond(
    MockServer::HttpResponse.response(body: '{"status":"ok"}', status_code: 200)
  )
end
# Client is automatically closed when the block exits
```

## WebSocket Callbacks

```ruby
client = MockServer::Client.new('localhost', 1080)

# Response callback - dynamically generate responses
client.mock_with_callback(
  MockServer::HttpRequest.request(path: '/dynamic'),
  ->(request) {
    MockServer::HttpResponse.new(
      status_code: 200,
      body: "Echo: #{request.path}"
    )
  }
)

# Forward callback - modify requests before forwarding
client.mock_with_forward_callback(
  MockServer::HttpRequest.request(path: '/proxy'),
  ->(request) {
    request.with_header('X-Proxied', 'true')
  }
)

client.close
```

## Models

All 25 domain model classes are available under the `MockServer` module:

- `Delay`, `Times`, `TimeToLive`
- `KeyToMultiValue`, `Body`, `SocketAddress`
- `HttpRequest`, `HttpResponse`, `HttpForward`, `HttpTemplate`
- `HttpClassCallback`, `HttpObjectCallback`, `HttpError`
- `HttpOverrideForwardedRequest`, `HttpRequestAndHttpResponse`
- `ConnectionOptions`
- `Expectation`, `ExpectationId`
- `OpenAPIDefinition`, `OpenAPIExpectation`
- `Verification`, `VerificationSequence`, `VerificationTimes`
- `Ports`
- `RequestDefinition` (alias for `HttpRequest`)

## Interactive Breakpoints

The client supports matcher-driven interactive breakpoints over the callback WebSocket. Register a breakpoint matcher to pause forwarded/proxied exchanges at specific phases and inspect/modify/continue them via callback handlers.

### Register a breakpoint

```ruby
client = MockServer::Client.new('localhost', 1080)

# REQUEST phase only
bp_id = client.add_request_breakpoint(
  MockServer::HttpRequest.new(path: '/api/.*'),
  ->(request) { request }  # continue unchanged (or return HttpResponse to abort)
)

# REQUEST + RESPONSE
bp_id = client.add_request_and_response_breakpoint(
  MockServer::HttpRequest.new(path: '/api/.*'),
  ->(request) { request },                      # REQUEST handler
  ->(request, response) { response }            # RESPONSE handler
)

# All phases with stream frame handler
bp_id = client.add_breakpoint(
  MockServer::HttpRequest.new(path: '/stream/.*'),
  %w[REQUEST RESPONSE RESPONSE_STREAM INBOUND_STREAM],
  request_handler: ->(request) { request },
  response_handler: ->(request, response) { response },
  stream_frame_handler: ->(frame) { { 'action' => 'CONTINUE' } }
  # Other actions: MODIFY (with body), DROP, INJECT (with body), CLOSE
)
```

### Manage breakpoints

```ruby
# List all matchers
matchers = client.list_breakpoint_matchers  # {"matchers" => [...]}

# Remove a specific matcher
client.remove_breakpoint_matcher(bp_id)

# Clear all matchers
client.clear_breakpoint_matchers
```

## Start / Launch MockServer

The Ruby client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```ruby
require 'mockserver-client'

# Download (first run) and start MockServer on port 1080
handle = MockServer::BinaryLauncher.start(port: 1080)
puts "MockServer running on port #{handle.port}, PID #{handle.pid}"

# ... use MockServer ...

handle.stop
```

### Just ensure the binary is present

```ruby
launcher_path = MockServer::BinaryLauncher.ensure_launcher
```

### Specify a version

```ruby
handle = MockServer::BinaryLauncher.start(port: 1080, version: '7.0.0')
```

### API reference

| Method / Class | Description |
|---|---|
| `MockServer::BinaryLauncher.ensure_launcher(version:, log:)` | Download, verify, cache, and return the launcher path. Defaults to `MockServer::VERSION`. |
| `MockServer::BinaryLauncher.start(port:, version:, extra_args:, log:)` | Ensure the binary and start MockServer. Returns a `ServerHandle`. |
| `MockServer::BinaryLauncher::ServerHandle` | Handle to the running process. Methods: `stop(timeout:)`, `running?`. Attributes: `pid`, `port`, `launcher`. |

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

By default the launcher downloads the MockServer version matching this client gem (currently `MockServer::VERSION` from `lib/mockserver/version.rb`). Pass an explicit `version:` keyword to override.

## License

Apache-2.0

## AI Assistant Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that enables AI coding assistants to create expectations, verify requests, and debug HTTP traffic programmatically.

- **MCP Endpoint:** `http://localhost:1080/mockserver/mcp`
- **AI Documentation:** [llms.txt](https://www.mock-server.com/llms.txt)
- **Setup Guide:** [AI Integration](https://www.mock-server.com/mock_server/ai_mcp_setup.html)
