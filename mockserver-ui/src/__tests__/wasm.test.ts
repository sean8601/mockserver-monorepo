import { describe, it, expect, vi, afterEach } from 'vitest';
import { testWasmModule } from '../lib/wasm';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, jsonBody: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => jsonBody,
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('testWasmModule', () => {
  it('POSTs to /mockserver/wasm/test with moduleName + request and returns matched', async () => {
    const calls = stubFetch(200, { matched: true });
    const result = await testWasmModule(params, {
      moduleName: 'my-rule',
      request: { method: 'GET', path: '/health' },
    });
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/wasm/test');
    expect(calls[0]?.init?.method).toBe('POST');
    expect((calls[0]?.init?.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({
      moduleName: 'my-rule',
      request: { method: 'GET', path: '/health' },
    });
    expect(result).toEqual({ matched: true });
  });

  it('coerces a missing/non-true matched field to false', async () => {
    stubFetch(200, {});
    expect(await testWasmModule(params, { moduleName: 'm' })).toEqual({ matched: false });
    stubFetch(200, { matched: 'yes' });
    expect(await testWasmModule(params, { moduleName: 'm' })).toEqual({ matched: false });
  });

  it('sends ad-hoc base64 module bytes when given module', async () => {
    const calls = stubFetch(200, { matched: false });
    await testWasmModule(params, { module: 'AGFzbQ==' });
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ module: 'AGFzbQ==' });
  });

  it('surfaces the server error message on a 4xx', async () => {
    stubFetch(400, { error: "'module' must be base64-encoded WASM bytes" });
    await expect(testWasmModule(params, { module: 'not-base64' })).rejects.toThrow(
      "'module' must be base64-encoded WASM bytes",
    );
  });

  it('surfaces a 404 (module not found) error message', async () => {
    stubFetch(404, { error: "WASM module 'missing' not found" });
    await expect(testWasmModule(params, { moduleName: 'missing' })).rejects.toThrow(
      "WASM module 'missing' not found",
    );
  });

  it('falls back to the status line when the error body is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => {
          throw new Error('not json');
        },
      })),
    );
    await expect(testWasmModule(params, { moduleName: 'm' })).rejects.toThrow('HTTP 403 Forbidden');
  });
});
