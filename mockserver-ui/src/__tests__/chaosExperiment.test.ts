import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  startChaosExperiment,
  getChaosExperimentStatus,
  stopChaosExperiment,
  formatDuration,
  type ExperimentDefinitionDTO,
} from '../lib/chaosExperiment';

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

describe('startChaosExperiment', () => {
  it('PUTs the experiment definition to the correct endpoint', async () => {
    const calls = stubFetch(200, { status: 'started', name: 'test-exp', stages: 2, loop: false });
    const definition: ExperimentDefinitionDTO = {
      name: 'test-exp',
      loop: false,
      stages: [
        { durationMillis: 5000, profiles: { 'a.svc': { errorStatus: 503, errorProbability: 1.0 } } },
        { durationMillis: 10000, profiles: { 'a.svc': { latency: { timeUnit: 'MILLISECONDS', value: 500 } } } },
      ],
    };
    await startChaosExperiment(params, definition);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/chaosExperiment');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.headers).toEqual({ 'Content-Type': 'application/json' });
    const body = JSON.parse(String(calls[0]?.init?.body));
    expect(body.name).toBe('test-exp');
    expect(body.stages).toHaveLength(2);
    expect(body.stages[0].durationMillis).toBe(5000);
    expect(body.stages[0].profiles['a.svc'].errorStatus).toBe(503);
  });

  it('throws on server error with error message', async () => {
    stubFetch(400, { error: "'name' is required" });
    await expect(
      startChaosExperiment(params, { name: '', stages: [] }),
    ).rejects.toThrow("'name' is required");
  });
});

describe('getChaosExperimentStatus', () => {
  it('GETs the experiment status from the correct endpoint', async () => {
    const statusBody = {
      name: 'test-exp',
      status: 'running',
      currentStageIndex: 1,
      totalStages: 3,
      stageElapsedMillis: 2000,
      stageRemainingMillis: 3000,
      loopIteration: 0,
      totalElapsedMillis: 7000,
    };
    const calls = stubFetch(200, statusBody);
    const result = await getChaosExperimentStatus(params);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/chaosExperiment');
    expect(calls[0]?.init?.method).toBeUndefined(); // GET is the default
    expect(result.name).toBe('test-exp');
    expect(result.status).toBe('running');
    expect(result.currentStageIndex).toBe(1);
    expect(result.totalStages).toBe(3);
  });

  it('returns "none" status when no experiment has run', async () => {
    stubFetch(200, { status: 'none' });
    const result = await getChaosExperimentStatus(params);
    expect(result.status).toBe('none');
  });
});

describe('stopChaosExperiment', () => {
  it('DELETEs at the correct endpoint', async () => {
    const calls = stubFetch(200, { status: 'stopped' });
    await stopChaosExperiment(params);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/chaosExperiment');
    expect(calls[0]?.init?.method).toBe('DELETE');
  });
});

describe('formatDuration', () => {
  it('formats seconds, minutes and hours', () => {
    expect(formatDuration(0)).toBe('0s');
    expect(formatDuration(12_000)).toBe('12s');
    expect(formatDuration(65_000)).toBe('1m 05s');
    expect(formatDuration(3_725_000)).toBe('1h 02m');
    expect(formatDuration(-500)).toBe('0s');
  });
});
