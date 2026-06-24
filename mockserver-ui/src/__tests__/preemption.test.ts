import { describe, it, expect, vi, afterEach } from 'vitest';
import { getPreemption, registerPreemption, clearPreemption } from '../lib/preemption';

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

describe('getPreemption', () => {
  it('GETs /mockserver/preemption and normalises the status', async () => {
    const calls = stubFetch(200, { state: 'draining', inFlight: 3, drainRemainingMillis: 1200, mode: 'both' });
    const status = await getPreemption(params);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/preemption');
    expect(calls[0]?.init?.method).toBeUndefined();
    expect(status).toEqual({ state: 'draining', inFlight: 3, drainRemainingMillis: 1200, mode: 'both' });
  });

  it('defaults missing fields to inactive / 0', async () => {
    stubFetch(200, {});
    expect(await getPreemption(params)).toEqual({ state: 'inactive', inFlight: 0, drainRemainingMillis: 0, mode: undefined });
  });
});

describe('registerPreemption', () => {
  it('PUTs mode/drainMillis/ttlMillis and returns the status', async () => {
    const calls = stubFetch(200, { state: 'draining', inFlight: 0, drainRemainingMillis: 1000, mode: 'reject503' });
    const status = await registerPreemption(params, { mode: 'reject503', drainMillis: 1000, ttlMillis: 60000 });
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/preemption');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect((calls[0]?.init?.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ mode: 'reject503', drainMillis: 1000, ttlMillis: 60000 });
    expect(status.state).toBe('draining');
    expect(status.mode).toBe('reject503');
  });

  it('PUTs an empty object when no request is given (server defaults)', async () => {
    const calls = stubFetch(200, { state: 'draining', inFlight: 0, drainRemainingMillis: 0 });
    await registerPreemption(params);
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({});
  });

  it('surfaces the server error message on a 4xx', async () => {
    stubFetch(400, { error: 'invalid preemption request: bad mode' });
    await expect(registerPreemption(params, { drainMillis: -1 })).rejects.toThrow(
      'invalid preemption request: bad mode',
    );
  });
});

describe('clearPreemption', () => {
  it('DELETEs /mockserver/preemption', async () => {
    const calls = stubFetch(200, { state: 'inactive' });
    await clearPreemption(params);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/preemption');
    expect(calls[0]?.init?.method).toBe('DELETE');
  });

  it('surfaces the server error message on a non-2xx', async () => {
    stubFetch(400, { error: 'failed to process preemption request' });
    await expect(clearPreemption(params)).rejects.toThrow('failed to process preemption request');
  });
});
