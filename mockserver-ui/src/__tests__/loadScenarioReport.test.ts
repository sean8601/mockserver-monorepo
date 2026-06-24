import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchLoadScenarioReport, LoadScenarioError } from '../lib/loadScenario';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, blobText: string): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => ({ error: 'load generation failed' }),
        blob: async () => new Blob([blobText], { type: 'application/octet-stream' }),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('fetchLoadScenarioReport', () => {
  it('GETs the report URL and returns the blob with a .json filename by default', async () => {
    const calls = stubFetch(200, '{"ok":true}');
    const { blob, filename } = await fetchLoadScenarioReport(params, 'checkout-load');
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe(
      'http://127.0.0.1:1080/mockserver/loadScenario/checkout-load/report',
    );
    expect(filename).toBe('load-scenario-checkout-load-report.json');
    expect(await blob.text()).toBe('{"ok":true}');
  });

  it('adds ?format=junit and an .xml filename for the junit format', async () => {
    const calls = stubFetch(200, '<testsuite/>');
    const { filename } = await fetchLoadScenarioReport(params, 'checkout-load', 'junit');
    expect(calls[0]?.url).toBe(
      'http://127.0.0.1:1080/mockserver/loadScenario/checkout-load/report?format=junit',
    );
    expect(filename).toBe('load-scenario-checkout-load-report.xml');
  });

  it('URL-encodes the name in the path and sanitizes it for the filename', async () => {
    const calls = stubFetch(200, '{}');
    const { filename } = await fetchLoadScenarioReport(params, 'a b/c?d');
    expect(calls[0]?.url).toContain('/loadScenario/a%20b%2Fc%3Fd/report');
    expect(filename).toBe('load-scenario-a_b_c_d-report.json');
  });

  it('throws LoadScenarioError on a non-2xx response (surfaces the server error)', async () => {
    stubFetch(500, '');
    await expect(fetchLoadScenarioReport(params, 'x')).rejects.toBeInstanceOf(LoadScenarioError);
  });
});
