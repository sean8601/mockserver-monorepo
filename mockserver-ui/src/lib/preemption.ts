/**
 * Client for MockServer's server-scoped preemption (graceful-shutdown / node-drain
 * chaos) control-plane endpoint (`/mockserver/preemption`). A preemption cordons the
 * whole server: new exchanges are turned away (503 + Retry-After + Connection: close)
 * and/or HTTP/2 clients are told to drain with a GOAWAY, in-flight requests are drained
 * for a bounded window, and the cordon auto-clears after `ttlMillis` (a dead-man's
 * switch) or on an explicit clear. It is a simulation only — it never stops the JVM.
 *
 * Server-scoped: there is no host key. Modelled on serviceChaos.ts.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * How new exchanges arriving while cordoned are turned away, and how HTTP/2 clients
 * are told to drain. Mirrors the server's {@code PreemptionRequest.Mode}.
 * - `reject503` — reject new exchanges with 503 + Retry-After + Connection: close (no GOAWAY).
 * - `goaway` — emit an HTTP/2 GOAWAY so clients stop opening streams; HTTP/1.1 degrades to a 503 close.
 * - `both` — reject new exchanges with 503 and emit GOAWAY on HTTP/2.
 */
export type PreemptionMode = 'reject503' | 'goaway' | 'both';

/** Mirror of the server's PreemptionRequest (all fields optional; an empty body = defaults). */
export interface PreemptionRequestDTO {
  mode?: PreemptionMode;
  /** How long in-flight requests are drained before the cordon is fully "drained" (ms). */
  drainMillis?: number;
  /** Dead-man's switch: auto-uncordon after this many ms (0/omitted = no auto-uncordon). */
  ttlMillis?: number;
  /** Optional HTTP/2 GOAWAY last-stream-id. */
  lastStreamId?: number;
}

/** Mirror of the server's preemption status node. */
export interface PreemptionStatus {
  /** `inactive` (not cordoned), `draining` (within the drain window), or `drained`. */
  state: string;
  /** Live count of in-flight requests still draining. */
  inFlight: number;
  /** Milliseconds remaining in the drain window (0 once drained). */
  drainRemainingMillis: number;
  /** The active mode, present only while cordoned. */
  mode?: PreemptionMode;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/preemption`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  // the server returns {"error": "..."} on a 4xx
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

function normaliseStatus(body: Partial<PreemptionStatus>): PreemptionStatus {
  return {
    state: typeof body.state === 'string' ? body.state : 'inactive',
    inFlight: typeof body.inFlight === 'number' ? body.inFlight : 0,
    drainRemainingMillis: typeof body.drainRemainingMillis === 'number' ? body.drainRemainingMillis : 0,
    mode: body.mode,
  };
}

/** Fetch the current preemption status (`GET /mockserver/preemption`). */
export async function getPreemption(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<PreemptionStatus> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<PreemptionStatus>;
  return normaliseStatus(body);
}

/**
 * Register (start) a preemption simulation (`PUT /mockserver/preemption`). An empty
 * request starts with server defaults (drain = stopDrainMillis, mode = both). Returns
 * the resulting status node.
 */
export async function registerPreemption(
  params: ConnectionParams,
  request: PreemptionRequestDTO = {},
): Promise<PreemptionStatus> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  await ensureOk(res);
  const body = (await res.json()) as Partial<PreemptionStatus>;
  return normaliseStatus(body);
}

/** Clear (uncordon) any active preemption (`DELETE /mockserver/preemption`). Idempotent. */
export async function clearPreemption(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), { method: 'DELETE' });
  await ensureOk(res);
}
