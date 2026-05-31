/**
 * Client for MockServer's traffic replay endpoint (`/mockserver/replay`).
 * Start a replay of recorded proxy traffic at a given rate and optionally
 * apply an HTTP chaos overlay, then poll for progress.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface ReplayChaosOverlay {
  errorStatus?: number;
  errorProbability?: number;
  latency?: { timeUnit: string; value: number };
}

export interface StartReplayBody {
  ratePerSecond: number;
  chaosProfile?: ReplayChaosOverlay;
}

export interface StartReplayResponse {
  replayId: string;
  totalRequests: number;
  status: string;
}

export interface ReplayReportResult {
  method?: string;
  path?: string;
  statusCode?: number;
  success?: boolean;
  error?: string;
}

export interface ReplayReport {
  replayId: string;
  status: string; // RUNNING, COMPLETED, FAILED
  totalRequests: number;
  completedRequests: number;
  successCount: number;
  failureCount: number;
  results?: ReplayReportResult[];
}

/** Start a replay of recorded proxy traffic. */
export async function startReplay(
  params: ConnectionParams,
  body: StartReplayBody,
): Promise<StartReplayResponse> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/replay`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      const errBody = (await res.json()) as { error?: string };
      if (errBody?.error) message = errBody.error;
    } catch {
      // keep status-line
    }
    throw new Error(message);
  }
  return res.json();
}

/** Get the replay report for a given replayId. */
export async function getReplayReport(
  params: ConnectionParams,
  replayId: string,
  signal?: AbortSignal,
): Promise<ReplayReport> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/replay/${encodeURIComponent(replayId)}`, { signal });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      const errBody = (await res.json()) as { error?: string };
      if (errBody?.error) message = errBody.error;
    } catch {
      // keep status-line
    }
    throw new Error(message);
  }
  return res.json();
}
