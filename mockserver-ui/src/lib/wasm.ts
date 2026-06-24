/**
 * Client for MockServer's WASM custom rule module endpoints
 * (`/mockserver/wasm/modules`). Upload, list, and delete WASM modules.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/wasm/modules`;
}

/** List all loaded WASM module names. */
export async function listWasmModules(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<string[]> {
  const res = await fetch(endpoint(params), { signal });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }
  const body = await res.json();
  return Array.isArray(body) ? body : [];
}

/** Upload a WASM module with the given name. */
export async function uploadWasmModule(
  params: ConnectionParams,
  name: string,
  fileBytes: ArrayBuffer,
): Promise<void> {
  const url = `${endpoint(params)}?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: fileBytes,
  });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      message = await res.text();
    } catch {
      // keep status-line message
    }
    throw new Error(message);
  }
}

/** A sample request to evaluate a WASM module against (server's WasmRequest shape). */
export interface WasmTestRequest {
  method?: string;
  path?: string;
  /** header name → one value or a list of values */
  headers?: Record<string, string | string[]>;
  body?: string;
}

/** Parameters for {@link testWasmModule}: pick exactly one of `moduleName` or `module`. */
export interface WasmTestParams {
  /** Name of an already-loaded module to test. */
  moduleName?: string;
  /** Base64-encoded WASM bytes to test ad-hoc (without loading the module). */
  module?: string;
  /** Optional sample request the module's match function is run against. */
  request?: WasmTestRequest;
}

/** Result of a WASM module dry-run: whether the module's match function returned true. */
export interface WasmTestResult {
  matched: boolean;
}

/**
 * Dry-run a WASM module against a sample request via `POST /mockserver/wasm/test`.
 * Pass `moduleName` to test a loaded module, or `module` (base64 bytes) to test
 * ad-hoc bytes. Returns the rule decision; throws the server's error message on a
 * non-2xx ({@code { "error": "..." }}).
 */
export async function testWasmModule(
  params: ConnectionParams,
  test: WasmTestParams,
): Promise<WasmTestResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/wasm/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(test),
  });
  if (!res.ok) {
    // the server returns {"error": "..."} (JSON) on a 4xx; fall back to the status line
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      const body = (await res.json()) as { error?: unknown };
      if (body && typeof body.error === 'string') message = body.error;
    } catch {
      // non-JSON body — keep the status-line message
    }
    throw new Error(message);
  }
  const body = (await res.json()) as Partial<WasmTestResult>;
  return { matched: body.matched === true };
}

/** Delete a WASM module by name. */
export async function deleteWasmModule(
  params: ConnectionParams,
  name: string,
): Promise<void> {
  const url = `${endpoint(params)}?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      message = await res.text();
    } catch {
      // keep status-line message
    }
    throw new Error(message);
  }
}
