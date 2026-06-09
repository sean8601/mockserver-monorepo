/**
 * Client for MockServer's interactive request breakpoints endpoints
 * (`/mockserver/breakpoint`). Lists paused exchanges and provides continue,
 * modify, and abort actions. See the Breakpoints docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface PausedExchangeRequest {
  method?: string;
  path?: string;
}

export interface PausedExchangeResponse {
  statusCode?: number;
  reasonPhrase?: string;
}

export type BreakpointPhase = 'REQUEST' | 'RESPONSE';

export interface PausedExchange {
  id: string;
  /** The phase at which the exchange is paused (REQUEST or RESPONSE). Defaults to REQUEST when omitted. */
  phase?: BreakpointPhase;
  ageMillis: number;
  expectationId?: string;
  request?: PausedExchangeRequest;
  /** Response summary, present only for RESPONSE-phase exchanges. */
  response?: PausedExchangeResponse;
}

export interface BreakpointListResponse {
  pausedExchanges: PausedExchange[];
  count: number;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/breakpoint`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/** Fetch the list of currently paused exchanges. */
export async function fetchBreakpoints(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<BreakpointListResponse> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  return res.json() as Promise<BreakpointListResponse>;
}

/** Resume a paused exchange unchanged. */
export async function continueBreakpoint(
  params: ConnectionParams,
  id: string,
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/continue`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  await ensureOk(res);
}

/** Resume a paused exchange with a modified request. */
export async function modifyBreakpoint(
  params: ConnectionParams,
  id: string,
  httpRequest: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/modify`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, httpRequest }),
  });
  await ensureOk(res);
}

/** Resume a response-phase paused exchange with a modified response. */
export async function modifyBreakpointResponse(
  params: ConnectionParams,
  id: string,
  httpResponse: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(`${endpoint(params)}/modify`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, httpResponse }),
  });
  await ensureOk(res);
}

/** Abort a paused exchange, optionally returning a custom response. */
export async function abortBreakpoint(
  params: ConnectionParams,
  id: string,
  httpResponse?: Record<string, unknown>,
): Promise<void> {
  const body: Record<string, unknown> = { id };
  if (httpResponse) body.httpResponse = httpResponse;
  const res = await fetch(`${endpoint(params)}/abort`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  await ensureOk(res);
}

// ---------------------------------------------------------------------------
// Stream frame breakpoints (forwarded-stream frame-level inspection)
// ---------------------------------------------------------------------------

export interface StreamFrame {
  frameId: string;
  sequenceNumber: number;
  ageMillis: number;
  bodyLength: number;
  requestMethod?: string;
  requestPath?: string;
  bodyPreview?: string;
  /** Frame direction: INBOUND (client-to-server) or OUTBOUND (server-to-client). */
  direction?: 'INBOUND' | 'OUTBOUND';
}

export interface StreamGroup {
  streamId: string;
  frames: StreamFrame[];
}

export interface StreamFrameListResponse {
  streams: StreamGroup[];
  totalHeldFrames: number;
}

function streamEndpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/breakpoint/stream`;
}

/** Fetch the list of currently held stream frames, grouped by stream. */
export async function fetchStreamFrames(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<StreamFrameListResponse> {
  const res = await fetch(`${streamEndpoint(params)}s`, { signal });
  await ensureOk(res);
  return res.json() as Promise<StreamFrameListResponse>;
}

/** Continue a held stream frame (write the original frame body). */
export async function continueStreamFrame(
  params: ConnectionParams,
  id: string,
): Promise<void> {
  const res = await fetch(`${streamEndpoint(params)}/continue`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  await ensureOk(res);
}

/** Modify a held stream frame (write a replacement body). */
export async function modifyStreamFrame(
  params: ConnectionParams,
  id: string,
  body: string,
): Promise<void> {
  const res = await fetch(`${streamEndpoint(params)}/modify`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, body }),
  });
  await ensureOk(res);
}

/** Drop a held stream frame (discard without writing). */
export async function dropStreamFrame(
  params: ConnectionParams,
  id: string,
): Promise<void> {
  const res = await fetch(`${streamEndpoint(params)}/drop`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  await ensureOk(res);
}

/** Inject an extra frame after continuing the held frame. */
export async function injectStreamFrame(
  params: ConnectionParams,
  id: string,
  body: string,
): Promise<void> {
  const res = await fetch(`${streamEndpoint(params)}/inject`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, body }),
  });
  await ensureOk(res);
}

/** Close a stream (drop the frame and end the stream). */
export async function closeStreamFrame(
  params: ConnectionParams,
  id: string,
): Promise<void> {
  const res = await fetch(`${streamEndpoint(params)}/close`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  await ensureOk(res);
}
