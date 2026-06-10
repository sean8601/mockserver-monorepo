# Interactive Breakpoints

Interactive breakpoints let you pause proxied/forwarded exchanges at four phases:

1. **Request breakpoints** (A1a) — hold the outbound request before it reaches the upstream server
2. **Response breakpoints** (A1b) — hold the upstream response before it is written to the client
3. **Stream frame breakpoints** (A1c + A1d) — hold each individual frame of a streaming response before it is written to the client. Covers both forwarded upstream streams (SSE / HTTP/1.1 chunked) and mock-generated streams (mock SSE/chunked, gRPC server-streaming, WebSocket eager/scripted messages, WebSocket bidirectional responses, and GraphQL subscription pushes)
4. **Inbound frame breakpoints** (A1e) — hold each client-to-server frame on bidirectional/streaming connections (WebSocket, GraphQL-subscription, gRPC-bidi) before MockServer processes them. Enables inspection, modification, dropping, injection, and connection close for inbound frames

Request and response breakpoints support inspect, modify, continue, and abort via the REST API.
Stream frame breakpoints and inbound frame breakpoints support continue, modify, drop, inject, and close per frame.

## Non-blocking architecture

The breakpoint mechanism is fully asynchronous. When a breakpoint matches:

1. A `PausedExchange` is registered in the `BreakpointRegistry` (a process-wide
   singleton backed by a `ConcurrentHashMap`).
2. The scheduler worker thread returns immediately. The continuation (forward the
   request / write the response, or write an abort response) is chained onto the
   `CompletableFuture<BreakpointDecision>` via `thenAcceptAsync(..., schedulerExecutor)`.
3. The control-plane endpoint (or the timeout scheduler) completes the future,
   which triggers the continuation on a scheduler pool thread.

No thread is blocked while waiting for the decision. This avoids exhausting the
`ScheduledThreadPoolExecutor` pool, which uses `CallerRunsPolicy` and would
otherwise run tasks on the Netty event-loop thread (causing a self-inflicted DoS).

## Phases

### Request phase

- Hold point: `HttpActionHandler.handleUnmatchedProxyForward`, after pre-flight
  validation but before the upstream HTTP call.
- Decision actions: CONTINUE (forward original), MODIFY (forward replacement
  request), ABORT (write error response to client without forwarding).

### Response phase

- Hold point: `HttpActionHandler.writeForwardActionResponse` (expectation-matched
  forwards) and `executeUnmatchedForward` (unmatched proxy forwards), after the
  upstream response is received but before it is written to the client.
- Non-streaming (buffered) responses only — streaming responses are written
  immediately (breakpoint is skipped).
- Decision actions: CONTINUE (write original response), MODIFY (write replacement
  response), ABORT (write error response).
- The upstream `HttpResponse` is a deserialized model object (no pooled ByteBuf),
  so parking does not risk use-after-free.

### Stream frame phase

- **Hold points:**
  - `NettyResponseWriter.writeStreamingResponse` — SSE/chunked forwarded and mock
    responses (A1c). The `StreamingBody.subscribe()` onChunk callback intercepts
    each chunk before writing as a `DefaultHttpContent`.
  - `GrpcStreamResponseActionHandler.scheduleMessages` — gRPC server-streaming mock
    responses (A1d). Each gRPC message frame is intercepted before `ctx.writeAndFlush`.
  - `HttpWebSocketResponseActionHandler.scheduleMessages` — WebSocket eager/scripted
    messages (A1d). Each frame is intercepted before writing.
  - `HttpWebSocketResponseActionHandler.installBidirectionalHandler` — WebSocket
    bidirectional response frames (A1d). The FrameSender intercepts each response frame.
  - `HttpWebSocketResponseActionHandler.installGraphQLSubscriptionHandler` — GraphQL
    subscription push frames (A1d). The FrameSender intercepts each `next` message.
  - `Http3GrpcResponseWriter.scheduleStreamMessages` — gRPC server-streaming mock
    responses over HTTP/3 (QUIC). Each gRPC message DATA frame is intercepted before
    `ctx.writeAndFlush`. Uses stream-id suffix `-h3-grpc-stream` (distinct from
    HTTP/2's `-grpc-stream`). Decision callbacks run on the QUIC stream's event loop.
- Scope: all streaming response types (SSE/chunked forwarded AND mock-generated,
  gRPC server-streaming over HTTP/2 and HTTP/3, WebSocket, and GraphQL subscriptions).
- Decision actions: CONTINUE (write original frame), MODIFY (write replacement
  body), DROP (discard frame), INJECT (write original + extra frame), CLOSE
  (send stream-end signal and close the stream).
- **Backpressure:** for SSE/chunked streams, when a frame is parked,
  `streamingBody.requestMore()` is NOT called — this stops the upstream from
  sending more chunks. For gRPC server-streaming and WebSocket eager/scripted
  mock-generated streams (via `scheduleMessages`), the next message in the
  sequence is not scheduled until the current frame's decision is resolved
  (inherent backpressure via the recursive schedule chain). **Note:**
  GraphQL-subscription (`pushNextSequence`) and WebSocket-bidirectional
  response paths are fire-and-forget — they park ALL frames simultaneously
  as they arrive (driven by inbound client messages or the subscription
  sequence), which means they can hit `breakpointMaxHeld` under high
  throughput. There is no inherent backpressure in these paths because the
  frame sender is invoked per inbound event rather than chained sequentially.
- **Stream-close eviction:** when a stream completes, errors, or is explicitly
  closed, all held frames for that stream are auto-continued/dropped (preventing
  leaks and hanging futures).
- **Frame ordering:** frames within a stream are assigned monotonic sequence
  numbers. The registry enforces that frames are resolved in order — attempting
  to resolve a frame whose predecessor is still held is rejected.
- **ByteBuf discipline:** frame bytes are copied into a `byte[]` at park time.
  For SSE/chunked, the original ByteBuf (owned by StreamingBody) is released
  normally by the caller. For gRPC, frames are already `byte[]` from
  `GrpcStreamMessageEncoder.encode()`. For WebSocket, text is encoded to `byte[]`
  via UTF-8. On resume, the decision handler allocates a new
  `Unpooled.wrappedBuffer` for writing. No ByteBuf is retained across the
  breakpoint hold period.
- **gRPC framing constraint:** for gRPC streams, MODIFY and INJECT replacement
  bytes must be a valid gRPC length-prefixed frame (1-byte compressed flag +
  4-byte big-endian message length + message bytes), otherwise the client will
  see a protocol error. The breakpoint engine passes bytes through opaquely --
  it does not validate or re-frame the content.
- **Event-loop safety:** all hold-point callbacks run on the Netty event loop.
  They NEVER block — they park the frame and return immediately. The decision
  callback is marshalled onto the channel's event loop via
  `ctx.channel().eventLoop().execute(...)`.
- **Stream ID format:** forwarded streams use `{correlationId}-stream`, gRPC
  streams use `{correlationId}-grpc-stream`, WebSocket/GraphQL streams use
  `{correlationId}-ws-stream`.

### Inbound frame phase

- **Hold points:**
  - `BidirectionalWebSocketFrameHandler.channelRead0` — WebSocket bidirectional
    inbound frames (A1e). When enabled, each inbound WebSocket frame is copied to
    `byte[]`, the original `WebSocketFrame` is released, and the copy is parked
    in `StreamFrameBreakpointRegistry` with `direction=INBOUND`.
  - `GraphQLSubscriptionHandler.channelRead0` — GraphQL subscription inbound
    frames (A1e). The text content is copied to `byte[]` (UTF-8), the frame is
    released, and the copy is parked.
- **gRPC-bidi inbound:** `GrpcBidiStreamHandler.handleData` — gRPC bidirectional
  streaming inbound DATA frames. When enabled, the handler copies the DATA frame
  bytes to `byte[]` and releases the `Http2DataFrame` IMMEDIATELY (refunding the
  HTTP/2 flow-control window), then parks the byte copy in the registry. The
  existing pull-based model (`autoRead=false` + explicit `ctx.read()`) provides
  backpressure: withholding `ctx.read()` prevents the next DATA frame from being
  delivered. This decouples flow-control window refund (immediate) from
  backpressure (deferred), so other streams on the same connection are NOT stalled.
  `GrpcBidiRouterHandler` generates a per-stream inbound stream ID and passes it
  along with the `Configuration` to the handler constructor.
- Decision actions: CONTINUE (process the original frame), MODIFY (process a
  replacement frame), DROP (discard — do not process), INJECT (process original
  + extra frame), CLOSE (evict stream, send CANCELLED trailer, close stream).
- **Backpressure:** WebSocket and GraphQL handlers use `autoRead=false` while
  a frame is parked. The gRPC-bidi handler uses its existing pull-based model
  (withholding `ctx.read()`) — no `autoRead` toggling needed since it is already
  `false` from `handlerAdded`. On resume (any decision), `ctx.read()` is called
  to request the next frame (or `finish()` is called if `endStream`).
- **ByteBuf discipline:** the original `WebSocketFrame`'s ByteBuf is copied to
  `byte[]` at park time and the frame is released immediately. Both WebSocket
  handlers use `super(false)` (no auto-release), so the handler manages release
  explicitly. For gRPC-bidi, the `Http2DataFrame` is released in a `finally`
  block immediately after byte copy — this refunds the HTTP/2 flow-control
  window before any breakpoint parking delay. On resume, WebSocket frames are
  reconstructed via `Unpooled.wrappedBuffer(byte[])` for matcher evaluation;
  gRPC frames are passed as `byte[]` to the decoder. No ByteBuf is retained
  across the breakpoint hold period.
- **Stream ID format:** WebSocket inbound streams use `{correlationId}-ws-inbound`;
  gRPC-bidi inbound streams use `grpc-bidi-inbound-{path}-{uuid}`.
- **Direction field:** `PausedStreamFrame.direction` is `INBOUND` for client-to-server
  frames and `OUTBOUND` (default) for server-to-client frames. The REST API
  `/mockserver/breakpoint/streams` list endpoint includes a `direction` field.
- **Channel close eviction:** `channelInactive` in all handlers (WebSocket,
  GraphQL, and gRPC-bidi) evicts all held inbound frames for the stream,
  preventing leaks and hanging futures.

## Safety rails

- **Timeout auto-continue:** each paused exchange or frame auto-continues if not
  resolved within `breakpointTimeoutMillis` (default 30 seconds).
- **Max-held cap:** when `breakpointMaxHeld` (default 50) exchanges/frames are
  held (request/response breakpoints and stream frames use separate registries
  but both check the same cap), new intercepts are skipped.
- **Default off:** breakpoints are inactive until a matcher is registered via
  `PUT /mockserver/breakpoint/matcher` — zero overhead until then.

## Breakpoint matcher registry

A breakpoint is active when at least one matcher is registered. Register a matcher by sending the request definition (identical in shape to an expectation request matcher) and the set of phases to intercept:

```
PUT /mockserver/breakpoint/matcher
{
  "httpRequest": { "method": "GET", "path": "/api/.*" },
  "phases": ["REQUEST", "RESPONSE"]
}
```

Any forwarded/proxied request that matches `httpRequest` will be paused at the specified phases. Registrations persist until explicitly removed or until `/mockserver/reset` is called (which clears all matchers).

### Matcher-registry endpoints

| Method | Path | Body | Description |
|--------|------|------|-------------|
| PUT | `/mockserver/breakpoint/matcher` | `{httpRequest, phases}` | Register a matcher; returns `{id, phases}` |
| GET/PUT | `/mockserver/breakpoint/matchers` | — | List all registered matchers: `{matchers:[{id,httpRequest,phases}]}` |
| PUT | `/mockserver/breakpoint/matcher/remove` | `{id}` | Remove a matcher by id; returns `{status:"removed",id}` or 404 |
| PUT | `/mockserver/breakpoint/matcher/clear` | — | Remove all matchers; returns `{status:"cleared",count}` |

**Validation:** `httpRequest` and `phases` are required; `phases` must be non-empty and contain only `REQUEST`, `RESPONSE`, `RESPONSE_STREAM`, or `INBOUND_STREAM` — unknown values return 400. The registry is cleared on `/mockserver/reset`.

**Matching semantics:** the `httpRequest` body uses the same matcher fields as an expectation request matcher (`method`, `path`, `headers`, `queryStringParameters`, `body`, etc.). An exchange pauses at a phase if any registered matcher matches the request for that phase.

## WebSocket callback resolution (optional `clientId`)

Breakpoint matchers support an optional `clientId` field that, when present,
dispatches matched exchanges over the existing callback WebSocket
(`/_mockserver_callback_websocket`) to the owning client for interactive
resolution — reusing the same `WebSocketClientRegistry` dispatch primitives
that the object-callback (`forwardObject` / `responseObject`) feature uses.

When `clientId` is absent (null), the breakpoint is resolved via the existing
REST-park path (`BreakpointRegistry` + `/mockserver/breakpoint/continue|modify|abort`).
Both paths coexist: the dashboard and REST-only flows keep working unchanged
while connected WS clients (any language client or future dashboard WS client)
can resolve breakpoints interactively.

### Registration with `clientId`

```
PUT /mockserver/breakpoint/matcher
{
  "httpRequest": { "method": "GET", "path": "/api/.*" },
  "phases": ["REQUEST", "RESPONSE"],
  "clientId": "my-ws-client-id"
}
```

The list endpoint (`GET /mockserver/breakpoint/matchers`) includes `clientId`
in each entry when present.

### Resolution protocol

- **REQUEST phase:** the paused request is sent to the client over the callback
  WS (with a `WebSocketCorrelationId` header). The client replies with either:
  - An `HttpRequest` — forward that request (MODIFY if different, CONTINUE if
    identical to the original).
  - An `HttpResponse` — ABORT (write that response to the downstream client,
    do not forward upstream).

- **RESPONSE phase:** the paused request+response are sent to the client. The
  client replies with an `HttpResponse` — the server writes it to the
  downstream client (MODIFY/CONTINUE).

### Safety rails

- **Timeout auto-continue:** if the client does not reply within
  `breakpointTimeoutMillis`, the exchange auto-continues with the original
  request/response.
- **Max-held cap:** WS-callback dispatches share the `breakpointMaxHeld` cap
  with the REST-park registry. When the cap is reached, new breakpoints are
  skipped.
- **Disconnect cleanup:** when a callback client disconnects, all its registered
  breakpoint matchers are removed and any in-flight dispatches are auto-completed
  to CONTINUE (no hung exchanges).

### Dispatcher

`BreakpointCallbackDispatcher` is the process-wide singleton that manages
WS-callback breakpoint dispatch for buffered REQUEST/RESPONSE phases. It tracks
in-flight dispatches per correlation id, schedules timeouts, and provides
`autoCompleteForClient(clientId)` for disconnect cleanup. It is called from the
hold-point gate sites in `HttpActionHandler` when the matched breakpoint has a
non-null `clientId`.

### Per-frame WS protocol (RESPONSE_STREAM / INBOUND_STREAM)

When a stream-frame breakpoint matcher has a non-null `clientId` and the
owning client's callback WebSocket is connected, each held frame is dispatched
over the WS for interactive resolution -- using the same
`/_mockserver_callback_websocket` endpoint as the buffered request/response
dispatch. This is the **frozen contract** that all language clients and the
dashboard UI implement.

`StreamFrameCallbackDispatcher` is the process-wide singleton that manages
per-frame WS dispatch. It mirrors `BreakpointCallbackDispatcher` with the same
safety rails (timeout auto-continue, max-held cap, disconnect cleanup) and
the same non-blocking, event-loop-safe dispatch pattern. The dispatcher is
stateless with respect to server identity: the per-server
`WebSocketClientRegistry` is passed as a parameter at each dispatch site
(obtained from `HttpState` at core sites, or from a channel
`AttributeKey<WebSocketClientRegistry>` at netty sites), so multiple
`HttpState`/server instances in the same JVM dispatch to their own clients.

When `clientId` is absent (null), the frame is parked in
`StreamFrameBreakpointRegistry` for REST-poll resolution (the existing path).
Both paths coexist.

#### Server-to-client: `PausedStreamFrameDTO`

Sent over the callback WS to the owning client when a frame is held. The
client inspects the frame and replies with a `StreamFrameDecisionDTO`.

| Field | Type | Description |
|-------|------|-------------|
| `correlationId` | String | Unique ID; client MUST echo in reply |
| `streamId` | String | Stream this frame belongs to |
| `sequenceNumber` | int | 0-based monotonic index within the stream |
| `direction` | String | `"INBOUND"` or `"OUTBOUND"` |
| `phase` | String | `"RESPONSE_STREAM"` or `"INBOUND_STREAM"` |
| `body` | String | Frame payload, Base64-encoded (RFC 4648 section 4) |
| `requestMethod` | String (nullable) | HTTP method of the original request |
| `requestPath` | String (nullable) | Path of the original request |

**Encoding:** the `body` is standard Base64 with no line breaks. Frames are
arbitrary bytes (gRPC length-prefixed, WebSocket text/binary, SSE/chunked).

#### Client-to-server: `StreamFrameDecisionDTO`

The client's reply carrying the resolution decision.

| Field | Type | Description |
|-------|------|-------------|
| `correlationId` | String | MUST match the `PausedStreamFrameDTO` |
| `action` | String | `"CONTINUE"`, `"MODIFY"`, `"DROP"`, `"INJECT"`, or `"CLOSE"` |
| `body` | String (nullable) | Base64-encoded replacement/injected bytes; required for `MODIFY` and `INJECT` |

**Action semantics:**

| Action | Effect |
|--------|--------|
| `CONTINUE` | Write the original frame unchanged |
| `MODIFY` | Write the `body` bytes instead of the original |
| `DROP` | Discard the frame (do not write / do not process inbound) |
| `INJECT` | Write the original frame AND an additional frame with `body` bytes |
| `CLOSE` | End the stream (drop frame, send stream-end signal, evict remaining) |

#### Safety rails

- **Timeout auto-continue:** if the client does not reply within
  `breakpointTimeoutMillis`, the frame auto-continues with the original bytes.
- **Max-held cap:** WS stream-frame dispatches share the `breakpointMaxHeld`
  cap with all other breakpoint registries and dispatchers. When the cap is
  reached, new frames are written immediately (no breakpoint).
- **Disconnect cleanup:** when a callback client disconnects, all its in-flight
  stream-frame dispatches are auto-completed to CONTINUE via
  `StreamFrameCallbackDispatcher.autoCompleteForClient(clientId)`.
- **Frame ordering:** ordering is preserved by the existing backpressure
  mechanisms (streaming body `requestMore()`, `autoRead=false`, withhold
  `ctx.read()`). The next frame is not delivered until the current one resolves.
- **Event-loop safety:** the WS dispatch future's `thenAccept` callback is
  marshalled onto the channel's event loop via `ctx.channel().eventLoop().execute()`.
  No blocking occurs on the Netty event loop.

## Control-plane endpoints

### Request/response breakpoints

| Method | Path | Description |
|--------|------|-------------|
| GET/PUT | `/mockserver/breakpoint` | List all currently paused exchanges (includes `phase` field) |
| PUT | `/mockserver/breakpoint/continue` | Continue a paused exchange |
| PUT | `/mockserver/breakpoint/modify` | Modify: `{id, httpRequest}` for request phase, `{id, httpResponse}` for response phase |
| PUT | `/mockserver/breakpoint/abort` | Abort: write error response to client |

The list endpoint includes a `phase` field (`REQUEST` or `RESPONSE`) and, for
response-phase exchanges, a `response` summary with `statusCode` and `reasonPhrase`.

### Stream frame breakpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/mockserver/breakpoint/streams` | List all held frames grouped by stream |
| PUT | `/mockserver/breakpoint/stream/continue` | Continue a held frame: `{id}` |
| PUT | `/mockserver/breakpoint/stream/modify` | Modify a frame: `{id, body}` — writes replacement body |
| PUT | `/mockserver/breakpoint/stream/drop` | Drop a frame: `{id}` — discards without writing |
| PUT | `/mockserver/breakpoint/stream/inject` | Inject after a frame: `{id, body}` — writes original + extra |
| PUT | `/mockserver/breakpoint/stream/close` | Close stream at frame: `{id}` — sends LastHttpContent, evicts remaining |

The list endpoint returns `{streams: [{streamId, frames: [{frameId, sequenceNumber, ageMillis, bodyLength, bodyPreview, requestMethod, requestPath}]}], totalHeldFrames}`.

## Configuration properties

Breakpoint activation is driven by the matcher registry (see "Breakpoint matcher registry" below), not by global flags. The two remaining properties are safety rails only:

| Property | Default | Description |
|----------|---------|-------------|
| `mockserver.breakpointTimeoutMillis` | `30000` | Auto-continue timeout in milliseconds (shared across all phases) |
| `mockserver.breakpointMaxHeld` | `50` | Max concurrent paused exchanges/frames (shared across all registries) |

## Key classes

### Breakpoint matcher registry
- `BreakpointMatcher` — a registered breakpoint: request matcher, phases, optional `clientId`
- `BreakpointMatcherRegistry` — process-wide singleton registry of breakpoint matchers with `findMatch`, `removeByClientId`
- `BreakpointPhase` — enum: REQUEST, RESPONSE, RESPONSE_STREAM, INBOUND_STREAM

### Request/response breakpoints (REST-park path)
- `BreakpointRegistry` — process-wide singleton managing paused exchanges (REST resolution)
- `PausedExchange` — holds phase, captured request/response, `CompletableFuture<BreakpointDecision>`
- `BreakpointDecision` — CONTINUE / MODIFY (request or response) / ABORT resolution
- `HttpActionHandler.handleUnmatchedProxyForward` — request-phase breakpoint intercept
- `HttpActionHandler.writeForwardActionResponse` — response-phase breakpoint intercept (matched)
- `HttpActionHandler.executeUnmatchedForward` — response-phase breakpoint intercept (unmatched)
- `HttpState.handleBreakpointContinue/Modify/Abort` — control-plane handlers

### Request/response breakpoints (WS-callback path)
- `BreakpointCallbackDispatcher` — process-wide singleton for WS-callback breakpoint dispatch; dispatches to owning client via `WebSocketClientRegistry`, manages in-flight tracking, timeouts, and disconnect cleanup
- `WebSocketClientRegistry.unregisterClient` — on disconnect, calls `BreakpointMatcherRegistry.removeByClientId`, `BreakpointCallbackDispatcher.autoCompleteForClient`, and `StreamFrameCallbackDispatcher.autoCompleteForClient` to clean up the client's breakpoints and in-flight dispatches
- `HttpActionHandler.attemptResponseBreakpoint` — helper that branches between WS-callback and REST-park for RESPONSE phase

### Stream frame breakpoints (WS-callback path)
- `StreamFrameCallbackDispatcher` — process-wide singleton for per-frame WS-callback dispatch; dispatches `PausedStreamFrameDTO` to owning client, receives `StreamFrameDecisionDTO` replies, manages in-flight tracking, timeouts, and disconnect cleanup
- `PausedStreamFrameDTO` — server-to-client WS message: correlationId, streamId, sequenceNumber, direction, phase, body (Base64), requestMethod, requestPath
- `StreamFrameDecisionDTO` — client-to-server WS reply: correlationId, action (CONTINUE/MODIFY/DROP/INJECT/CLOSE), optional body (Base64)
- `WebSocketClientRegistry.sendStreamFrameMessage` — sends a `PausedStreamFrameDTO` to a client
- `WebSocketClientRegistry.registerStreamFrameCallbackHandler` — registers a callback for `StreamFrameDecisionDTO` replies by correlationId

### Stream frame breakpoints
- `StreamFrameBreakpointRegistry` — process-wide singleton managing paused stream frames
- `PausedStreamFrame` — holds streamId, sequence number, captured bytes, `CompletableFuture<StreamFrameDecision>`
- `StreamFrameDecision` — CONTINUE / MODIFY / DROP / INJECT / CLOSE resolution
- `NettyResponseWriter.writeStreamingResponse` — hold point for SSE/chunked streams (forwarded + mock)
- `GrpcStreamResponseActionHandler.scheduleMessages` — hold point for gRPC server-streaming mock responses
- `HttpWebSocketResponseActionHandler.scheduleMessages` — hold point for WebSocket eager/scripted messages
- `HttpWebSocketResponseActionHandler.installBidirectionalHandler` — hold point for WebSocket bidi responses
- `HttpWebSocketResponseActionHandler.installGraphQLSubscriptionHandler` — hold point for GraphQL subscription pushes
- `HttpState.handleStreamFrame*` — control-plane handlers for stream frame actions

### Inbound frame breakpoints
- `BidirectionalWebSocketFrameHandler.channelRead0` — hold point for WebSocket bidi inbound frames
- `GraphQLSubscriptionHandler.channelRead0` — hold point for GraphQL subscription inbound frames
- `GrpcBidiStreamHandler.handleData` — hold point for gRPC bidi inbound DATA frames
- `GrpcBidiRouterHandler` — routes gRPC bidi streams to `GrpcBidiStreamHandler`
- Uses the same `StreamFrameBreakpointRegistry` with `direction=INBOUND`

## Behavioural notes

- **Response chaos-latency is not re-applied after manual resolution.** When a
  response breakpoint is resolved (CONTINUE, MODIFY, or ABORT), any configured
  response chaos-latency for the matched expectation is bypassed. The manual
  resolution supersedes automatic chaos injection because the user has already
  inspected and approved (or replaced) the response.
- **`httpResponse` takes precedence in the modify endpoint.** If a client sends
  both `httpRequest` and `httpResponse` fields in a modify payload, the
  `httpResponse` field is used (response-phase modify). The `httpRequest` field
  is silently ignored for response-phase exchanges.
- **Phase guards prevent type-confusion.** `resolveModify(id, httpRequest)` is
  rejected (returns false) if the exchange is in RESPONSE phase, and
  `resolveModifyResponse(id, httpResponse)` is rejected if the exchange is in
  REQUEST phase. This prevents completing a decision future with the wrong type,
  which would cause a downstream NPE.

## Dashboard UI

The Breakpoints panel in the dashboard is phase-aware:

- Each paused exchange displays a **Phase** chip (`REQUEST` or `RESPONSE`).
- For **request-phase** exchanges: the table shows the HTTP method and path;
  the Modify dialog edits the request JSON and sends `{id, httpRequest}`.
- For **response-phase** exchanges: the table shows the status code and reason
  phrase; the Modify dialog edits the response JSON and sends `{id, httpResponse}`.
- Continue and Abort work identically for both phases.

## Java client

`MockServerClient` provides typed methods for both phases:

- `modifyBreakpoint(String id, HttpRequest modifiedRequest)` — request-phase modify
- `modifyBreakpointResponse(String id, HttpResponse modifiedResponse)` — response-phase modify

