import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  fetchBreakpoints,
  continueBreakpoint,
  modifyBreakpoint,
  modifyBreakpointResponse,
  abortBreakpoint,
  fetchStreamFrames,
  continueStreamFrame,
  modifyStreamFrame,
  dropStreamFrame,
  injectStreamFrame,
  closeStreamFrame,
  type BreakpointListResponse,
  type StreamFrameListResponse,
} from '../lib/breakpoints';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('fetchBreakpoints', () => {
  it('fetches from GET /mockserver/breakpoint', async () => {
    const body: BreakpointListResponse = {
      count: 1,
      pausedExchanges: [{
        id: 'abc-123',
        ageMillis: 5000,
        expectationId: 'exp-1',
        request: { method: 'GET', path: '/api/users' },
      }],
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => body,
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchBreakpoints(params);

    expect(fetchMock).toHaveBeenCalledOnce();
    const url = fetchMock.mock.calls[0]![0] as string;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint');
    expect(result.count).toBe(1);
    expect(result.pausedExchanges[0]!.id).toBe('abc-123');
  });

  it('throws on non-OK status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      json: async () => ({ error: 'server is broken' }),
    }));

    await expect(fetchBreakpoints(params)).rejects.toThrow('server is broken');
  });

  it('throws with status line when error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      json: async () => { throw new SyntaxError('not JSON'); },
    }));

    await expect(fetchBreakpoints(params)).rejects.toThrow('HTTP 503 Service Unavailable');
  });

  it('passes the AbortSignal to fetch', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ pausedExchanges: [], count: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const controller = new AbortController();
    await fetchBreakpoints(params, controller.signal);

    expect(fetchMock.mock.calls[0]![1]).toEqual({ signal: controller.signal });
  });
});

describe('continueBreakpoint', () => {
  it('sends PUT to /mockserver/breakpoint/continue with id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await continueBreakpoint(params, 'abc-123');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/continue');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ id: 'abc-123' });
  });

  it('throws on error response with server error message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: async () => ({ error: 'no paused exchange found with id: xyz' }),
    }));

    await expect(continueBreakpoint(params, 'xyz')).rejects.toThrow(
      'no paused exchange found with id: xyz',
    );
  });
});

describe('modifyBreakpoint', () => {
  it('sends PUT to /mockserver/breakpoint/modify with id and httpRequest', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    const modified = { method: 'POST', path: '/modified' };
    await modifyBreakpoint(params, 'abc-123', modified);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/modify');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({
      id: 'abc-123',
      httpRequest: { method: 'POST', path: '/modified' },
    });
  });
});

describe('modifyBreakpointResponse', () => {
  it('sends PUT to /mockserver/breakpoint/modify with id and httpResponse', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    const modified = { statusCode: 200, body: 'modified response' };
    await modifyBreakpointResponse(params, 'abc-123', modified);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/modify');
    expect(init.method).toBe('PUT');
    const body = JSON.parse(init.body as string);
    expect(body).toEqual({
      id: 'abc-123',
      httpResponse: { statusCode: 200, body: 'modified response' },
    });
    // must NOT include httpRequest
    expect(body).not.toHaveProperty('httpRequest');
  });
});

describe('abortBreakpoint', () => {
  it('sends PUT to /mockserver/breakpoint/abort with id only', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await abortBreakpoint(params, 'abc-123');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/abort');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ id: 'abc-123' });
  });

  it('includes httpResponse when provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    const httpResponse = { statusCode: 503, body: 'Service Unavailable' };
    await abortBreakpoint(params, 'abc-123', httpResponse);

    const init = fetchMock.mock.calls[0]![1]!;
    expect(JSON.parse(init.body as string)).toEqual({
      id: 'abc-123',
      httpResponse: { statusCode: 503, body: 'Service Unavailable' },
    });
  });
});

// ---------------------------------------------------------------------------
// Stream frame breakpoints
// ---------------------------------------------------------------------------

describe('fetchStreamFrames', () => {
  it('fetches from GET /mockserver/breakpoint/streams', async () => {
    const body: StreamFrameListResponse = {
      totalHeldFrames: 2,
      streams: [{
        streamId: 'stream-1',
        frames: [
          { frameId: 'stream-1-frame-0', sequenceNumber: 0, ageMillis: 1000, bodyLength: 42, requestMethod: 'GET', requestPath: '/api/events', bodyPreview: 'data: hello' },
          { frameId: 'stream-1-frame-1', sequenceNumber: 1, ageMillis: 500, bodyLength: 20, requestMethod: 'GET', requestPath: '/api/events' },
        ],
      }],
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => body,
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchStreamFrames(params);

    expect(fetchMock).toHaveBeenCalledOnce();
    const url = fetchMock.mock.calls[0]![0] as string;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/streams');
    expect(result.totalHeldFrames).toBe(2);
    expect(result.streams).toHaveLength(1);
    expect(result.streams[0]!.streamId).toBe('stream-1');
    expect(result.streams[0]!.frames).toHaveLength(2);
  });

  it('throws on non-OK status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      json: async () => ({ error: 'registry error' }),
    }));

    await expect(fetchStreamFrames(params)).rejects.toThrow('registry error');
  });

  it('passes the AbortSignal to fetch', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ streams: [], totalHeldFrames: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const controller = new AbortController();
    await fetchStreamFrames(params, controller.signal);

    expect(fetchMock.mock.calls[0]![1]).toEqual({ signal: controller.signal });
  });
});

describe('continueStreamFrame', () => {
  it('sends PUT to /mockserver/breakpoint/stream/continue with id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await continueStreamFrame(params, 'stream-1-frame-0');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/stream/continue');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ id: 'stream-1-frame-0' });
  });

  it('throws on error response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: async () => ({ error: 'no held frame found with id: xyz' }),
    }));

    await expect(continueStreamFrame(params, 'xyz')).rejects.toThrow(
      'no held frame found with id: xyz',
    );
  });
});

describe('modifyStreamFrame', () => {
  it('sends PUT to /mockserver/breakpoint/stream/modify with id and body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await modifyStreamFrame(params, 'stream-1-frame-0', 'data: modified\n\n');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/stream/modify');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({
      id: 'stream-1-frame-0',
      body: 'data: modified\n\n',
    });
  });
});

describe('dropStreamFrame', () => {
  it('sends PUT to /mockserver/breakpoint/stream/drop with id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await dropStreamFrame(params, 'stream-1-frame-0');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/stream/drop');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ id: 'stream-1-frame-0' });
  });
});

describe('injectStreamFrame', () => {
  it('sends PUT to /mockserver/breakpoint/stream/inject with id and body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await injectStreamFrame(params, 'stream-1-frame-0', 'data: injected\n\n');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/stream/inject');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({
      id: 'stream-1-frame-0',
      body: 'data: injected\n\n',
    });
  });
});

describe('closeStreamFrame', () => {
  it('sends PUT to /mockserver/breakpoint/stream/close with id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await closeStreamFrame(params, 'stream-1-frame-0');

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/breakpoint/stream/close');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ id: 'stream-1-frame-0' });
  });
});
