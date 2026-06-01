import { describe, it, expect, vi, afterEach } from 'vitest';
import { exportPact } from '../lib/pactExport';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, body: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => body,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('exportPact', () => {
  it('PUTs with consumer and provider query parameters', async () => {
    const pact = { consumer: { name: 'Web' }, provider: { name: 'Api' }, interactions: [] };
    const calls = stubFetch(200, pact);
    const result = await exportPact(params, 'Web', 'Api');
    expect(result).toEqual(pact);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/pact?consumer=Web&provider=Api');
    expect(calls[0]?.init?.method).toBe('PUT');
  });

  it('omits blank consumer/provider so the server defaults apply', async () => {
    const calls = stubFetch(200, {});
    await exportPact(params, '  ', '');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/pact');
  });

  it('throws on a non-2xx response', async () => {
    stubFetch(500, 'boom');
    await expect(exportPact(params, 'c', 'p')).rejects.toThrow('boom');
  });
});
