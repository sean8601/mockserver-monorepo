/**
 * Client for MockServer's load-injection (Load Scenario / performance testing)
 * control-plane endpoint (`/mockserver/loadScenario`). Drive API load against a
 * target with a ramp profile and templated steps — a pure SLI producer. Off by
 * default: the server returns 403 until `loadGenerationEnabled=true`
 * (`MOCKSERVER_LOAD_GENERATION_ENABLED`). See the Load Injection docs.
 *
 * Control plane:
 *  - PUT    starts (or replaces) the active scenario
 *  - GET    returns the current/most-recent scenario status
 *  - DELETE stops the current scenario (idempotent)
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** A MockServer Delay (used for thinkTime / iteration pauses). */
export interface DelayDTO {
  timeUnit: string;
  value: number;
}

/** Target socket address for a step's request. */
export interface SocketAddressDTO {
  host: string;
  port: number;
  scheme?: 'HTTP' | 'HTTPS';
}

/** The minimal HttpRequest shape a load step needs (templated path/body). */
export interface LoadRequestDTO {
  method?: string;
  path?: string;
  headers?: Record<string, string[]>;
  body?: string;
  socketAddress?: SocketAddressDTO;
}

/** A single templated request step in a load scenario. */
export interface LoadStepDTO {
  name?: string;
  request: LoadRequestDTO;
  thinkTime?: DelayDTO;
  labels?: Record<string, string>;
}

/** The kind of a load stage. */
export type LoadStageType = 'VU' | 'RATE' | 'PAUSE';

/** Interpolation curve used to ramp a value across a ramp stage. */
export type RampCurve = 'LINEAR' | 'EXPONENTIAL' | 'QUADRATIC';

/**
 * One stage of a staged load profile (Load Profile v2). Stages run in sequence.
 *  - VU stage: holds `vus`, or ramps `startVus`→`endVus` along `curve`.
 *  - RATE stage: holds `rate`, or ramps `startRate`→`endRate` (iterations/second) along `curve`;
 *    optional `maxVus` caps the auto-scaling VU pool.
 *  - PAUSE stage: drives no load for `durationMillis`.
 */
export interface LoadStageDTO {
  type: LoadStageType;
  durationMillis: number;
  curve?: RampCurve;
  // VU-stage fields.
  vus?: number;
  startVus?: number;
  endVus?: number;
  // RATE-stage fields (iterations per second).
  rate?: number;
  startRate?: number;
  endRate?: number;
  maxVus?: number;
}

/** Staged load profile: an ordered list of stages run one after another. */
export interface LoadProfileDTO {
  stages: LoadStageDTO[];
}

/** An API-driven load scenario. */
export interface LoadScenarioDTO {
  name: string;
  templateType?: 'VELOCITY' | 'MUSTACHE';
  maxRequests?: number;
  labels?: Record<string, string>;
  profile: LoadProfileDTO;
  steps: LoadStepDTO[];
}

export type LoadState = 'none' | 'running' | 'completed' | 'stopped';

/** Live status of the current/most-recent load scenario (GET response). */
export interface LoadScenarioStatus {
  name?: string;
  state: LoadState;
  elapsedMillis?: number;
  currentVus?: number;
  /** 0-based index of the currently-running stage (omitted when not running). */
  stageIndex?: number;
  /** Type of the currently-running stage (omitted when not running). */
  stageType?: LoadStageType;
  /** Current setpoint for the running stage: target VUs (VU), target rate iterations/s (RATE), or 0 (PAUSE). */
  currentTarget?: number;
  requestsSent?: number;
  succeeded?: number;
  failed?: number;
  p50Millis?: number;
  p95Millis?: number;
  p99Millis?: number;
  runId?: string;
  startedAt?: number;
  endedAt?: number;
  labels?: Record<string, string>;
  /**
   * The full scenario definition this run was started with — echoed by the server whenever a run
   * exists (omitted only when state is "none"). Lets any tab/client load the exact LoadScenario back
   * into the author form, even one started elsewhere, and re-submit it verbatim as a PUT body.
   */
  definition?: LoadScenarioDTO;
}

/**
 * Error thrown when the control plane responds. Carries the HTTP status so the
 * UI can special-case 403 (load generation disabled) from other failures.
 */
export class LoadScenarioError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = 'LoadScenarioError';
    this.status = status;
  }
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/loadScenario`;
}

/** Throws a LoadScenarioError carrying the status (and server {error} when present). */
async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new LoadScenarioError(message, res.status);
}

/**
 * Fetch the current load scenario status. A 403 means load generation is
 * disabled — surfaced as a LoadScenarioError with status 403 so the panel can
 * render the enablement help instead of a generic error.
 */
export async function fetchLoadScenario(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<LoadScenarioStatus> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<LoadScenarioStatus>;
  return { ...body, state: body.state ?? 'none' };
}

/** Start (or replace) the active load scenario. Returns when the server accepts it. */
export async function startLoadScenario(
  params: ConnectionParams,
  scenario: LoadScenarioDTO,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scenario),
  });
  await ensureOk(res);
}

/** Stop the current load scenario. Idempotent — 200 whether or not one was running. */
export async function stopLoadScenario(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), { method: 'DELETE' });
  await ensureOk(res);
}

/** Whether the status represents an active (still-running) scenario. */
export function isRunning(status: LoadScenarioStatus | null): boolean {
  return status?.state === 'running';
}

/** Error rate (0..1) from the counts, or 0 when nothing has been sent. */
export function errorRate(status: LoadScenarioStatus): number {
  const sent = status.requestsSent ?? 0;
  if (sent <= 0) return 0;
  return (status.failed ?? 0) / sent;
}

/** Format a millisecond duration as a compact elapsed string (e.g. "1m 05s", "12.3s"). */
export function formatElapsed(millis: number): string {
  if (!Number.isFinite(millis) || millis < 0) return '—';
  const totalSeconds = millis / 1000;
  if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.round(totalSeconds % 60);
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}
