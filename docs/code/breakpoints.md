# Interactive Breakpoints

Interactive breakpoints let you pause proxied/forwarded exchanges at four phases:

1. **Request breakpoints** (A1a) — hold the outbound request before it reaches the upstream server
2. **Response breakpoints** (A1b) — hold the upstream response before it is written to the client
3. **Stream frame breakpoints** (A1c + A1d) — hold each individual frame of a streaming response before it is written to the client. Covers both forwarded upstream streams (SSE / HTTP/1.1 chunked) and mock-generated streams (mock SSE/chunked, gRPC server-streaming, WebSocket eager/scripted messages, WebSocket bidirectional responses, and GraphQL subscription pushes)
4. **Inbound frame breakpoints** (A1e) — hold each client-to-server frame on bidirectional/streaming connections (WebSocket, GraphQL-subscription) before MockServer processes them. Enables inspection, modification, dropping, injection, and connection close for inbound frames

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

### Request phase (`breakpointEnabled`)

- Hold point: `HttpActionHandler.handleUnmatchedProxyForward`, after pre-flight
  validation but before the upstream HTTP call.
- Decision actions: CONTINUE (forward original), MODIFY (forward replacement
  request), ABORT (write error response to client without forwarding).

### Response phase (`breakpointResponseEnabled`)

- Hold point: `HttpActionHandler.writeForwardActionResponse` (expectation-matched
  forwards) and `executeUnmatchedForward` (unmatched proxy forwards), after the
  upstream response is received but before it is written to the client.
- Non-streaming (buffered) responses only — streaming responses are written
  immediately (breakpoint is skipped).
- Decision actions: CONTINUE (write original response), MODIFY (write replacement
  response), ABORT (write error response).
- The upstream `HttpResponse` is a deserialized model object (no pooled ByteBuf),
  so parking does not risk use-after-free.

### Stream frame phase (`breakpointStreamEnabled`)

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
- Scope: all streaming response types (SSE/chunked forwarded AND mock-generated,
  gRPC server-streaming, WebSocket, and GraphQL subscriptions). HTTP/3 gRPC
  server-streaming is NOT yet intercepted (follow-up).
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

### Inbound frame phase (`breakpointInboundEnabled`)

- **Hold points:**
  - `BidirectionalWebSocketFrameHandler.channelRead0` — WebSocket bidirectional
    inbound frames (A1e). When enabled, each inbound WebSocket frame is copied to
    `byte[]`, the original `WebSocketFrame` is released, and the copy is parked
    in `StreamFrameBreakpointRegistry` with `direction=INBOUND`.
  - `GraphQLSubscriptionHandler.channelRead0` — GraphQL subscription inbound
    frames (A1e). The text content is copied to `byte[]` (UTF-8), the frame is
    released, and the copy is parked.
- **gRPC-bidi inbound:** NOT intercepted in this pass. The `GrpcBidiStreamHandler`
  operates above the HTTP/2 codec with explicit flow control (`autoRead=false`,
  manual `ctx.read()` after each frame). Holding an inbound gRPC frame would
  require careful HTTP/2 flow-control window management to avoid corrupting the
  connection-level window. This is deferred as a follow-up.
- Decision actions: CONTINUE (process the original frame), MODIFY (process a
  replacement frame), DROP (discard — do not process), INJECT (process original
  + extra frame), CLOSE (evict stream, close channel).
- **Backpressure:** when a frame is parked, `autoRead` is set to `false` on the
  channel, preventing further inbound frames from being read. On resume (any
  decision), `autoRead` is restored to `true` and `ctx.read()` is called to
  request the next frame.
- **ByteBuf discipline:** the original `WebSocketFrame`'s ByteBuf is copied to
  `byte[]` at park time and the frame is released immediately. Both handlers
  use `super(false)` (no auto-release), so the handler manages release explicitly.
  On resume, a new `WebSocketFrame` is reconstructed via
  `Unpooled.wrappedBuffer(byte[])` for matcher evaluation or pipeline forwarding.
  The reconstructed frame is released after use. No ByteBuf is retained across
  the breakpoint hold period.
- **Stream ID format:** inbound streams use `{correlationId}-ws-inbound` (distinct
  from outbound `{correlationId}-ws-stream`).
- **Direction field:** `PausedStreamFrame.direction` is `INBOUND` for client-to-server
  frames and `OUTBOUND` (default) for server-to-client frames. The REST API
  `/mockserver/breakpoint/streams` list endpoint includes a `direction` field.
- **Channel close eviction:** `channelInactive` in both handlers evicts all held
  inbound frames for the stream, preventing leaks and hanging futures.

## Safety rails

- **Timeout auto-continue:** each paused exchange or frame auto-continues if not
  resolved within `breakpointTimeoutMillis` (default 30 seconds).
- **Max-held cap:** when `breakpointMaxHeld` (default 50) exchanges/frames are
  held (request/response breakpoints and stream frames use separate registries
  but both check the same cap), new intercepts are skipped.
- **Default off:** `breakpointEnabled`, `breakpointResponseEnabled`,
  `breakpointStreamEnabled`, and `breakpointInboundEnabled` all default to
  `false` — zero overhead.

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

| Property | Default | Description |
|----------|---------|-------------|
| `mockserver.breakpointEnabled` | `false` | Enable request-phase breakpoints |
| `mockserver.breakpointResponseEnabled` | `false` | Enable response-phase breakpoints |
| `mockserver.breakpointStreamEnabled` | `false` | Enable stream-frame breakpoints (SSE/chunked, gRPC, WebSocket, GraphQL) |
| `mockserver.breakpointInboundEnabled` | `false` | Enable inbound frame breakpoints (WebSocket bidi, GraphQL subscription) |
| `mockserver.breakpointTimeoutMillis` | `30000` | Auto-continue timeout (shared) |
| `mockserver.breakpointMaxHeld` | `50` | Max concurrent paused exchanges/frames (shared) |

## Key classes

### Request/response breakpoints
- `BreakpointRegistry` — process-wide singleton managing paused exchanges
- `PausedExchange` — holds phase, captured request/response, `CompletableFuture<BreakpointDecision>`
- `BreakpointDecision` — CONTINUE / MODIFY (request or response) / ABORT resolution
- `HttpActionHandler.handleUnmatchedProxyForward` — request-phase breakpoint intercept
- `HttpActionHandler.writeForwardActionResponse` — response-phase breakpoint intercept (matched)
- `HttpActionHandler.executeUnmatchedForward` — response-phase breakpoint intercept (unmatched)
- `HttpState.handleBreakpointContinue/Modify/Abort` — control-plane handlers

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

## Future work

- HTTP/3 gRPC server-streaming breakpoints (`Http3GrpcResponseWriter`)
- gRPC-bidi inbound frame breakpoints (requires careful HTTP/2 flow-control window management)
- Dashboard UI for stream frame breakpoints (frame list, per-frame actions, direction badge)
- Dashboard UI for inbound frame breakpoints
