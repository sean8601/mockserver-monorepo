/**
 * Client for MockServer's AsyncAPI broker-mocking control plane:
 *   PUT /mockserver/asyncapi  — load an AsyncAPI spec (JSON/YAML) or { spec, brokerConfig }.
 *   GET /mockserver/asyncapi  — current status (loaded channels / operations).
 * Both return 501 when the optional mockserver-async module is not on the server classpath.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export class AsyncApiUnavailableError extends Error {
  constructor() {
    super('AsyncAPI messaging module is not available — the server does not have mockserver-async on its classpath.');
    this.name = 'AsyncApiUnavailableError';
  }
}

async function jsonOrError(res: Response): Promise<Record<string, unknown>> {
  if (res.status === 501) throw new AsyncApiUnavailableError();
  const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) {
    throw new Error(typeof body.error === 'string' ? body.error : `HTTP ${res.status} ${res.statusText}`);
  }
  return body;
}

/** Load an AsyncAPI spec (raw JSON/YAML, or a { spec, brokerConfig } JSON object). */
export async function loadAsyncApi(params: ConnectionParams, specBody: string): Promise<Record<string, unknown>> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/asyncapi`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: specBody,
  });
  return jsonOrError(res);
}

/** Current AsyncAPI broker-mock status. Returns null when the module is unavailable (501). */
export async function getAsyncApiStatus(params: ConnectionParams, signal?: AbortSignal): Promise<Record<string, unknown> | null> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/asyncapi`, { signal });
  if (res.status === 501) return null;
  return jsonOrError(res);
}
