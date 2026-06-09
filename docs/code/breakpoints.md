# Interactive Breakpoints

Interactive breakpoints let you pause proxied/forwarded exchanges at three phases:

1. **Request breakpoints** (A1a) — hold the outbound request before it reaches the upstream server
2. **Response breakpoints** (A1b) — hold the upstream response before it is written to the client
3. **Stream frame breakpoints** (A1c) — hold each individual frame of a forwarded streaming response (SSE / HTTP/1.1 chunked) before it is written to the client

Request and response breakpoints support inspect, modify, continue, and abort via the REST API.
Stream frame breakpoints support continue, modify, drop, inject, and close per frame.

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

- Hold point: `NettyResponseWriter.writeStreamingResponse`, inside the
  `StreamingBody.subscribe()` onChunk callback — each chunk is intercepted
  before being written as a `DefaultHttpContent` to the downstream client.
- Scope: HTTP/1.1 chunked and Server-Sent Events (SSE) forwarded responses.
  gRPC, HTTP/2, and WebSocket streams are NOT intercepted (follow-up A1d/A1e).
- Decision actions: CONTINUE (write original frame), MODIFY (write replacement
  body), DROP (discard frame), INJECT (write original + extra frame), CLOSE
  (send LastHttpContent and close the stream).
- **Backpressure:** when a frame is parked, `streamingBody.requestMore()` is NOT
  called — this stops the upstream from sending more chunks. After the frame
  decision is resolved, `requestMore()` is called to resume the upstream flow.
- **Stream-close eviction:** when the stream completes or errors, all held frames
  for that stream are auto-continued (preventing leaks and hanging futures).
- **Frame ordering:** frames within a stream are assigned monotonic sequence
  numbers. The registry enforces that frames are resolved in order — attempting
  to resolve a frame whose predecessor is still held is rejected.
- **ByteBuf discipline:** the chunk bytes are copied into a `byte[]` at park time.
  The original ByteBuf (owned by StreamingBody) is released normally by the
  caller. On resume, the decision handler allocates a new `Unpooled.wrappedBuffer`
  for writing. No ByteBuf is retained across the breakpoint hold period.
- **Event-loop safety:** the onChunk callback runs on the upstream channel's event
  loop. It NEVER blocks — it parks the frame and returns immediately. The
  decision callback is marshalled onto the downstream channel's event loop via
  `ctx.channel().eventLoop().execute(...)`.

## Safety rails

- **Timeout auto-continue:** each paused exchange or frame auto-continues if not
  resolved within `breakpointTimeoutMillis` (default 30 seconds).
- **Max-held cap:** when `breakpointMaxHeld` (default 50) exchanges/frames are
  held (request/response breakpoints and stream frames use separate registries
  but both check the same cap), new intercepts are skipped.
- **Default off:** `breakpointEnabled`, `breakpointResponseEnabled`, and
  `breakpointStreamEnabled` all default to `false` — zero overhead.

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
| `mockserver.breakpointStreamEnabled` | `false` | Enable stream-frame breakpoints (SSE/chunked) |
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
- `NettyResponseWriter.writeStreamingResponse` — choke point: onChunk callback intercepts frames
- `HttpState.handleStreamFrame*` — control-plane handlers for stream frame actions

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

- A1d: gRPC / HTTP/2 stream frame breakpoints
- A1e: WebSocket stream frame breakpoints
- Dashboard UI for stream frame breakpoints (frame list, per-frame actions)
