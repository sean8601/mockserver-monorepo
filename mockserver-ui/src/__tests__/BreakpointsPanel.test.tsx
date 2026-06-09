import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import BreakpointsPanel from '../components/BreakpointsPanel';
import type { BreakpointListResponse, StreamFrameListResponse } from '../lib/breakpoints';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <BreakpointsPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

/** Stub fetch to respond to both breakpoints and stream frame endpoints. */
function stubBothEndpoints(
  exchanges: BreakpointListResponse,
  streams: StreamFrameListResponse,
) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (String(url).endsWith('/mockserver/breakpoint/streams')) {
        return { ok: true, status: 200, json: async () => streams };
      }
      // default: the breakpoints endpoint
      return { ok: true, status: 200, json: async () => exchanges };
    }),
  );
}

const emptyExchanges: BreakpointListResponse = { pausedExchanges: [], count: 0 };
const emptyStreams: StreamFrameListResponse = { streams: [], totalHeldFrames: 0 };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('BreakpointsPanel — Exchanges tab', () => {
  it('renders title and description', () => {
    stubBothEndpoints(emptyExchanges, emptyStreams);
    renderPanel();
    expect(screen.getByText('Breakpoints')).toBeInTheDocument();
    expect(screen.getByText(/Exchanges paused by breakpoint expectations/)).toBeInTheDocument();
  });

  it('shows the empty state when no paused exchanges exist', async () => {
    stubBothEndpoints(emptyExchanges, emptyStreams);
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('0 paused')).toBeInTheDocument();
    });
    expect(screen.getByText(/No paused exchanges/)).toBeInTheDocument();
  });

  it('renders paused exchanges in a table when populated', async () => {
    stubBothEndpoints(
      {
        count: 1,
        pausedExchanges: [{
          id: 'abc-123',
          ageMillis: 5000,
          expectationId: 'exp-1',
          request: { method: 'GET', path: '/api/users' },
        }],
      },
      emptyStreams,
    );
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 paused')).toBeInTheDocument();
    });

    expect(screen.getByText('GET')).toBeInTheDocument();
    expect(screen.getByText('/api/users')).toBeInTheDocument();
    expect(screen.getByText('5s')).toBeInTheDocument();
    expect(screen.getByText('abc-123')).toBeInTheDocument();
    expect(screen.getByText('exp-1')).toBeInTheDocument();
  });

  it('calls continue endpoint when Continue button is clicked', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'continued', id: 'abc-123' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true, status: 200,
            json: async () => ({ count: 1, pausedExchanges: [{ id: 'abc-123', ageMillis: 2000, request: { method: 'POST', path: '/test' } }] }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await waitFor(() => { expect(screen.getByText('1 paused')).toBeInTheDocument(); });

    const continueBtn = screen.getByRole('button', { name: /Continue abc-123/ });
    await user.click(continueBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/continue') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({ id: 'abc-123' });

    await waitFor(() => { expect(screen.getByText('0 paused')).toBeInTheDocument(); });
  });

  it('calls abort endpoint when Abort button is clicked', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'aborted', id: 'abc-123' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true, status: 200,
            json: async () => ({ count: 1, pausedExchanges: [{ id: 'abc-123', ageMillis: 3000, request: { method: 'DELETE', path: '/remove' } }] }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await waitFor(() => { expect(screen.getByText('1 paused')).toBeInTheDocument(); });

    const abortBtn = screen.getByRole('button', { name: /Abort abc-123/ });
    await user.click(abortBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/abort') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({ id: 'abc-123' });
  });

  it('opens modify dialog and sends modified request on submit', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'modified', id: 'abc-123' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true, status: 200,
            json: async () => ({ count: 1, pausedExchanges: [{ id: 'abc-123', ageMillis: 1000, request: { method: 'GET', path: '/original' } }] }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await waitFor(() => { expect(screen.getByText('1 paused')).toBeInTheDocument(); });

    const modifyBtn = screen.getByRole('button', { name: /Modify abc-123/ });
    await user.click(modifyBtn);

    await waitFor(() => { expect(screen.getByText('Modify Request')).toBeInTheDocument(); });

    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue(JSON.stringify({ method: 'GET', path: '/original' }, null, 2));

    fireEvent.change(textarea, { target: { value: '{"method":"POST","path":"/modified"}' } });

    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/modify') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    const putBody = JSON.parse((putCall![1] as RequestInit).body as string);
    expect(putBody.id).toBe('abc-123');
    expect(putBody.httpRequest).toEqual({ method: 'POST', path: '/modified' });
  });

  it('shows an error alert when the fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        throw new Error('Connection refused');
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Could not load paused exchanges/)).toBeInTheDocument();
    });
    expect(screen.getByText('Connection refused')).toBeInTheDocument();
  });

  it('shows an error alert when the server returns a non-OK status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        return {
          ok: false,
          status: 500,
          statusText: 'Internal Server Error',
          json: async () => ({ error: 'breakpoint registry unavailable' }),
        };
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Could not load paused exchanges/)).toBeInTheDocument();
    });
    expect(screen.getByText('breakpoint registry unavailable')).toBeInTheDocument();
  });

  it('has a Refresh button that triggers a new poll', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => emptyExchanges,
    }));
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderPanel();

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    const callsBefore = fetchMock.mock.calls.length;

    const refreshBtn = screen.getByRole('button', { name: 'Refresh breakpoints' });
    await user.click(refreshBtn);

    await waitFor(() => {
      expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore);
    });
  });

  it('renders response-phase exchange with status code and phase chip', async () => {
    stubBothEndpoints(
      {
        count: 1,
        pausedExchanges: [{
          id: 'resp-456',
          phase: 'RESPONSE',
          ageMillis: 3000,
          expectationId: 'exp-2',
          request: { method: 'GET', path: '/api/data' },
          response: { statusCode: 200, reasonPhrase: 'OK' },
        }],
      },
      emptyStreams,
    );
    renderPanel();

    await waitFor(() => { expect(screen.getByText('1 paused')).toBeInTheDocument(); });

    expect(screen.getByText('RESPONSE')).toBeInTheDocument();
    expect(screen.getByText('200')).toBeInTheDocument();
    expect(screen.getByText('OK')).toBeInTheDocument();
    expect(screen.getByText('resp-456')).toBeInTheDocument();
  });

  it('opens modify dialog with response JSON for response-phase exchange', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'modified', id: 'resp-456' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true, status: 200,
            json: async () => ({
              count: 1,
              pausedExchanges: [{
                id: 'resp-456',
                phase: 'RESPONSE',
                ageMillis: 1000,
                request: { method: 'GET', path: '/api/data' },
                response: { statusCode: 200, reasonPhrase: 'OK' },
              }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await waitFor(() => { expect(screen.getByText('1 paused')).toBeInTheDocument(); });

    const modifyBtn = screen.getByRole('button', { name: /Modify resp-456/ });
    await user.click(modifyBtn);

    await waitFor(() => { expect(screen.getByText('Modify Response')).toBeInTheDocument(); });

    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue(JSON.stringify({ statusCode: 200, reasonPhrase: 'OK' }, null, 2));

    fireEvent.change(textarea, { target: { value: '{"statusCode":503,"body":"Service Unavailable"}' } });
    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/modify') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    const putBody = JSON.parse((putCall![1] as RequestInit).body as string);
    expect(putBody.id).toBe('resp-456');
    expect(putBody.httpResponse).toEqual({ statusCode: 503, body: 'Service Unavailable' });
    expect(putBody).not.toHaveProperty('httpRequest');
  });

  it('shows modify dialog validation error for invalid JSON', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        return {
          ok: true, status: 200,
          json: async () => ({
            count: 1,
            pausedExchanges: [{ id: 'abc-123', ageMillis: 1000, request: { method: 'GET', path: '/test' } }],
          }),
        };
      }),
    );

    renderPanel();
    await waitFor(() => { expect(screen.getByText('1 paused')).toBeInTheDocument(); });

    const modifyBtn = screen.getByRole('button', { name: /Modify abc-123/ });
    await user.click(modifyBtn);

    await waitFor(() => { expect(screen.getByText('Modify Request')).toBeInTheDocument(); });

    const textarea = screen.getByRole('textbox');
    fireEvent.change(textarea, { target: { value: 'not valid json' } });

    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    await waitFor(() => {
      expect(screen.getByText('Invalid JSON')).toBeInTheDocument();
    });
  });
});

// ---------------------------------------------------------------------------
// Streams tab
// ---------------------------------------------------------------------------

describe('BreakpointsPanel — Streams tab', () => {
  async function switchToStreamsTab() {
    const user = userEvent.setup();
    // Click on the "Streams" tab
    const streamsTab = screen.getByRole('tab', { name: /Streams/ });
    await user.click(streamsTab);
  }

  it('shows empty state when no held stream frames', async () => {
    stubBothEndpoints(emptyExchanges, emptyStreams);
    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText(/No held stream frames/)).toBeInTheDocument();
    });
  });

  it('renders held stream frames grouped by stream', async () => {
    stubBothEndpoints(emptyExchanges, {
      totalHeldFrames: 2,
      streams: [{
        streamId: 'stream-abc',
        frames: [
          { frameId: 'stream-abc-frame-0', sequenceNumber: 0, ageMillis: 3000, bodyLength: 42, requestMethod: 'GET', requestPath: '/events', bodyPreview: 'data: hello' },
          { frameId: 'stream-abc-frame-1', sequenceNumber: 1, ageMillis: 1000, bodyLength: 20, requestMethod: 'GET', requestPath: '/events', bodyPreview: 'data: world' },
        ],
      }],
    });
    renderPanel();
    await switchToStreamsTab();

    // Stream grouping
    await waitFor(() => {
      expect(screen.getByText('stream-abc')).toBeInTheDocument();
    });
    expect(screen.getByText('2 frames')).toBeInTheDocument();

    // Frame details
    expect(screen.getByText('#0')).toBeInTheDocument();
    expect(screen.getByText('#1')).toBeInTheDocument();
    expect(screen.getByText('data: hello')).toBeInTheDocument();
    expect(screen.getByText('data: world')).toBeInTheDocument();
    expect(screen.getByText('42B')).toBeInTheDocument();
    expect(screen.getByText('20B')).toBeInTheDocument();
    expect(screen.getByText('3s')).toBeInTheDocument();
    expect(screen.getByText('1s')).toBeInTheDocument();
  });

  it('shows singular "frame" text for single-frame stream', async () => {
    stubBothEndpoints(emptyExchanges, {
      totalHeldFrames: 1,
      streams: [{
        streamId: 'stream-single',
        frames: [
          { frameId: 'stream-single-frame-0', sequenceNumber: 0, ageMillis: 500, bodyLength: 10, requestMethod: 'POST', requestPath: '/api' },
        ],
      }],
    });
    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText('1 frame')).toBeInTheDocument();
    });
  });

  it('calls continue endpoint for stream frame', async () => {
    const user = userEvent.setup();
    let streamCallIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const urlStr = String(url);
        if (urlStr.includes('/breakpoint/stream/continue') && init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'continued', id: 'stream-1-frame-0' }) };
        }
        if (urlStr.endsWith('/mockserver/breakpoint/streams')) {
          streamCallIndex++;
          if (streamCallIndex === 1) {
            return {
              ok: true, status: 200,
              json: async () => ({
                totalHeldFrames: 1,
                streams: [{ streamId: 'stream-1', frames: [{ frameId: 'stream-1-frame-0', sequenceNumber: 0, ageMillis: 500, bodyLength: 10, bodyPreview: 'data: hi' }] }],
              }),
            };
          }
          return { ok: true, status: 200, json: async () => emptyStreams };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => { expect(screen.getByText('stream-1')).toBeInTheDocument(); });

    const continueBtn = screen.getByRole('button', { name: /Continue stream-1-frame-0/ });
    await user.click(continueBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/stream/continue') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({ id: 'stream-1-frame-0' });
  });

  it('calls drop endpoint for stream frame', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const urlStr = String(url);
        if (urlStr.includes('/breakpoint/stream/drop') && init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'dropped', id: 'frame-0' }) };
        }
        if (urlStr.endsWith('/mockserver/breakpoint/streams')) {
          return {
            ok: true, status: 200,
            json: async () => ({
              totalHeldFrames: 1,
              streams: [{ streamId: 's1', frames: [{ frameId: 'frame-0', sequenceNumber: 0, ageMillis: 100, bodyLength: 5, bodyPreview: 'x' }] }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const dropBtn = screen.getByRole('button', { name: /Drop frame-0/ });
    await user.click(dropBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/stream/drop') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({ id: 'frame-0' });
  });

  it('calls close endpoint for stream frame', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const urlStr = String(url);
        if (urlStr.includes('/breakpoint/stream/close') && init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'closed', id: 'frame-0' }) };
        }
        if (urlStr.endsWith('/mockserver/breakpoint/streams')) {
          return {
            ok: true, status: 200,
            json: async () => ({
              totalHeldFrames: 1,
              streams: [{ streamId: 's1', frames: [{ frameId: 'frame-0', sequenceNumber: 0, ageMillis: 100, bodyLength: 5, bodyPreview: 'x' }] }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const closeBtn = screen.getByRole('button', { name: /Close frame-0/ });
    await user.click(closeBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/stream/close') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({ id: 'frame-0' });
  });

  it('opens modify dialog and sends modified frame body', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const urlStr = String(url);
        if (urlStr.includes('/breakpoint/stream/modify') && init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'modified', id: 'frame-0' }) };
        }
        if (urlStr.endsWith('/mockserver/breakpoint/streams')) {
          return {
            ok: true, status: 200,
            json: async () => ({
              totalHeldFrames: 1,
              streams: [{ streamId: 's1', frames: [{ frameId: 'frame-0', sequenceNumber: 0, ageMillis: 100, bodyLength: 11, bodyPreview: 'data: hello' }] }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const modifyBtn = screen.getByRole('button', { name: /Modify frame-0/ });
    await user.click(modifyBtn);

    await waitFor(() => { expect(screen.getByText('Modify Stream Frame')).toBeInTheDocument(); });

    // Pre-filled with bodyPreview
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue('data: hello');

    fireEvent.change(textarea, { target: { value: 'data: modified' } });

    const sendBtn = screen.getByRole('button', { name: /Send Modified Frame/ });
    await user.click(sendBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/stream/modify') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      id: 'frame-0',
      body: 'data: modified',
    });
  });

  it('opens inject dialog and sends injected frame body', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const urlStr = String(url);
        if (urlStr.includes('/breakpoint/stream/inject') && init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'injected', id: 'frame-0' }) };
        }
        if (urlStr.endsWith('/mockserver/breakpoint/streams')) {
          return {
            ok: true, status: 200,
            json: async () => ({
              totalHeldFrames: 1,
              streams: [{ streamId: 's1', frames: [{ frameId: 'frame-0', sequenceNumber: 0, ageMillis: 100, bodyLength: 5, bodyPreview: 'data: x' }] }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const injectBtn = screen.getByRole('button', { name: /Inject frame-0/ });
    await user.click(injectBtn);

    await waitFor(() => { expect(screen.getByText('Inject Extra Frame')).toBeInTheDocument(); });

    // Inject dialog body starts empty
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue('');

    fireEvent.change(textarea, { target: { value: 'data: injected\n\n' } });

    const sendBtn = screen.getByRole('button', { name: /Inject Frame/ });
    await user.click(sendBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/stream/inject') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      id: 'frame-0',
      body: 'data: injected\n\n',
    });
  });

  it('shows stream load error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          throw new Error('Stream fetch failed');
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    const user = userEvent.setup();
    const streamsTab = screen.getByRole('tab', { name: /Streams/ });
    await user.click(streamsTab);

    await waitFor(() => {
      expect(screen.getByText(/Could not load stream frames/)).toBeInTheDocument();
    });
    expect(screen.getByText('Stream fetch failed')).toBeInTheDocument();
  });

  it('shows modify dialog validation error when body is empty', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return {
            ok: true, status: 200,
            json: async () => ({
              totalHeldFrames: 1,
              streams: [{ streamId: 's1', frames: [{ frameId: 'frame-0', sequenceNumber: 0, ageMillis: 100, bodyLength: 0 }] }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    const streamsTab = screen.getByRole('tab', { name: /Streams/ });
    await user.click(streamsTab);

    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const modifyBtn = screen.getByRole('button', { name: /Modify frame-0/ });
    await user.click(modifyBtn);

    await waitFor(() => { expect(screen.getByText('Modify Stream Frame')).toBeInTheDocument(); });

    // Body is empty (no bodyPreview on the frame), submit should show error
    const sendBtn = screen.getByRole('button', { name: /Send Modified Frame/ });
    await user.click(sendBtn);

    await waitFor(() => {
      expect(screen.getByText('Body is required')).toBeInTheDocument();
    });
  });

  it('shows inject dialog validation error when body is empty', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).endsWith('/mockserver/breakpoint/streams')) {
          return {
            ok: true, status: 200,
            json: async () => ({
              totalHeldFrames: 1,
              streams: [{ streamId: 's1', frames: [{ frameId: 'frame-0', sequenceNumber: 0, ageMillis: 100, bodyLength: 5, bodyPreview: 'hi' }] }],
            }),
          };
        }
        return { ok: true, status: 200, json: async () => emptyExchanges };
      }),
    );

    renderPanel();
    const streamsTab = screen.getByRole('tab', { name: /Streams/ });
    await user.click(streamsTab);

    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const injectBtn = screen.getByRole('button', { name: /Inject frame-0/ });
    await user.click(injectBtn);

    await waitFor(() => { expect(screen.getByText('Inject Extra Frame')).toBeInTheDocument(); });

    // Submit with empty body
    const sendBtn = screen.getByRole('button', { name: /Inject Frame/ });
    await user.click(sendBtn);

    await waitFor(() => {
      expect(screen.getByText('Body is required')).toBeInTheDocument();
    });
  });

  it('renders Inbound direction chip for INBOUND frames', async () => {
    stubBothEndpoints(emptyExchanges, {
      totalHeldFrames: 1,
      streams: [{
        streamId: 'stream-dir',
        frames: [
          { frameId: 'stream-dir-frame-0', sequenceNumber: 0, ageMillis: 500, bodyLength: 10, requestMethod: 'GET', requestPath: '/ws', direction: 'INBOUND' },
        ],
      }],
    });
    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText('stream-dir')).toBeInTheDocument();
    });
    expect(screen.getByText('Inbound')).toBeInTheDocument();
  });

  it('renders Outbound direction chip for OUTBOUND frames', async () => {
    stubBothEndpoints(emptyExchanges, {
      totalHeldFrames: 1,
      streams: [{
        streamId: 'stream-out',
        frames: [
          { frameId: 'stream-out-frame-0', sequenceNumber: 0, ageMillis: 500, bodyLength: 10, requestMethod: 'GET', requestPath: '/sse', direction: 'OUTBOUND' },
        ],
      }],
    });
    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText('stream-out')).toBeInTheDocument();
    });
    expect(screen.getByText('Outbound')).toBeInTheDocument();
  });

  it('defaults to Outbound when direction is absent', async () => {
    stubBothEndpoints(emptyExchanges, {
      totalHeldFrames: 1,
      streams: [{
        streamId: 'stream-nodir',
        frames: [
          { frameId: 'stream-nodir-frame-0', sequenceNumber: 0, ageMillis: 500, bodyLength: 10, requestMethod: 'GET', requestPath: '/events' },
        ],
      }],
    });
    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText('stream-nodir')).toBeInTheDocument();
    });
    // Should fall back to Outbound
    expect(screen.getByText('Outbound')).toBeInTheDocument();
  });

  it('renders both direction chips when stream has mixed directions', async () => {
    stubBothEndpoints(emptyExchanges, {
      totalHeldFrames: 2,
      streams: [{
        streamId: 'stream-mixed',
        frames: [
          { frameId: 'stream-mixed-frame-0', sequenceNumber: 0, ageMillis: 500, bodyLength: 10, requestMethod: 'GET', requestPath: '/ws', direction: 'OUTBOUND' },
          { frameId: 'stream-mixed-frame-1', sequenceNumber: 1, ageMillis: 300, bodyLength: 8, requestMethod: 'GET', requestPath: '/ws', direction: 'INBOUND' },
        ],
      }],
    });
    renderPanel();
    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText('stream-mixed')).toBeInTheDocument();
    });
    expect(screen.getByText('Outbound')).toBeInTheDocument();
    expect(screen.getByText('Inbound')).toBeInTheDocument();
  });

  it('shows total count combining exchanges and streams in the header badge', async () => {
    stubBothEndpoints(
      { count: 2, pausedExchanges: [
        { id: 'e1', ageMillis: 100, request: { method: 'GET', path: '/a' } },
        { id: 'e2', ageMillis: 200, request: { method: 'POST', path: '/b' } },
      ] },
      { totalHeldFrames: 3, streams: [{ streamId: 's1', frames: [
        { frameId: 'f0', sequenceNumber: 0, ageMillis: 100, bodyLength: 5, bodyPreview: 'x' },
        { frameId: 'f1', sequenceNumber: 1, ageMillis: 50, bodyLength: 3, bodyPreview: 'y' },
        { frameId: 'f2', sequenceNumber: 2, ageMillis: 20, bodyLength: 2, bodyPreview: 'z' },
      ] }] },
    );
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('5 paused')).toBeInTheDocument();
    });
  });
});
