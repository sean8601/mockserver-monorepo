import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import LoadScenarioPanel from '../components/LoadScenarioPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.restoreAllMocks();
});

/** A fetch mock that returns `none` for GET and records PUT/DELETE calls. */
function stubFetch(getBody: unknown, status = 200) {
  const calls: Array<{ url: string; method: string; body?: unknown }> = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ url: String(url), method, body });
    if (method === 'GET') {
      return { ok: status === 200, status, json: async () => getBody } as unknown as Response;
    }
    return { ok: true, status: 200, json: async () => ({ status: method === 'DELETE' ? 'stopped' : 'started' }) } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

describe('LoadScenarioPanel', () => {
  it('renders the author form with VELOCITY/MUSTACHE template options', async () => {
    stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    expect(screen.getByLabelText(/Scenario name/)).toBeInTheDocument();
    // template type select offers only Velocity + Mustache
    expect(screen.getByText('Velocity')).toBeInTheDocument();
  });

  it('builds a valid PUT body from the form', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'checkout-load' } });
    fireEvent.change(screen.getByLabelText(/Virtual users/), { target: { value: '8' } });
    fireEvent.change(screen.getByLabelText(/Duration/), { target: { value: '20000' } });

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /Start load scenario/i }));

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT');
      expect(put).toBeTruthy();
    });
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url).toContain('/mockserver/loadScenario');
    expect(put.body).toMatchObject({
      name: 'checkout-load',
      templateType: 'VELOCITY',
      profile: { type: 'CONSTANT', vus: 8, durationMillis: 20000 },
      steps: [
        {
          request: {
            method: 'GET',
            path: '/api/item',
            socketAddress: { host: 'target.svc', port: 8080, scheme: 'HTTP' },
          },
        },
      ],
    });
    // No header rows were added — `headers` must be omitted to keep the body minimal.
    const builtStep = (put.body as { steps: Array<{ request: Record<string, unknown> }> }).steps[0]!;
    expect(builtStep.request).not.toHaveProperty('headers');
  });

  it('emits per-step request headers in MockServer KeyToMultiValue object-map shape', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'auth-load' } });
    fireEvent.change(screen.getByLabelText(/Virtual users/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/Duration/), { target: { value: '10000' } });

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'api.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '443' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/secure' } });

    fireEvent.click(within(step0).getByRole('button', { name: /Add header/i }));
    fireEvent.change(within(step0).getByLabelText('Header name'), { target: { value: 'Authorization' } });
    fireEvent.change(within(step0).getByLabelText('Header value'), { target: { value: 'Bearer xyz' } });

    fireEvent.click(screen.getByRole('button', { name: /Start load scenario/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({
      steps: [
        {
          request: {
            path: '/secure',
            headers: { Authorization: ['Bearer xyz'] },
          },
        },
      ],
    });
  });

  it('reloads a definition that already carries request headers into the header rows on Edit', async () => {
    stubFetch({
      state: 'running',
      name: 'header-run',
      runId: 'rH',
      currentVus: 2,
      requestsSent: 5,
      succeeded: 5,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 1000,
      definition: {
        name: 'header-run',
        templateType: 'VELOCITY',
        profile: { type: 'CONSTANT', vus: 2, durationMillis: 10000 },
        steps: [
          {
            request: {
              method: 'GET',
              path: '/secure',
              headers: { Authorization: ['Bearer abc'], 'X-Tenant': ['acme'] },
              socketAddress: { host: 'api.svc', port: 443, scheme: 'HTTPS' },
            },
          },
        ],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Edit running/i }));

    await waitFor(() =>
      expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('header-run'),
    );
    const step0 = screen.getByTestId('load-step-0');
    const names = within(step0).getAllByLabelText('Header name') as HTMLInputElement[];
    const values = within(step0).getAllByLabelText('Header value') as HTMLInputElement[];
    expect(names.map((n) => n.value)).toEqual(['Authorization', 'X-Tenant']);
    expect(values.map((v) => v.value)).toEqual(['Bearer abc', 'acme']);
  });

  it('shows live status from a mocked running GET', async () => {
    stubFetch({
      state: 'running',
      name: 'live-run',
      runId: 'r1',
      currentVus: 4,
      requestsSent: 1000,
      succeeded: 950,
      failed: 50,
      p50Millis: 12,
      p95Millis: 88,
      p99Millis: 140,
      elapsedMillis: 5000,
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());
    const live = screen.getByTestId('load-live-status');
    expect(within(live).getByText('1,000')).toBeInTheDocument(); // requests sent
    expect(within(live).getByText('5.0%')).toBeInTheDocument(); // error rate 50/1000
    expect(screen.getByRole('button', { name: /Stop/i })).toBeInTheDocument();
  });

  it('toggles a chart series on and off', async () => {
    stubFetch({
      state: 'running', name: 'r', runId: 'r1', currentVus: 2,
      requestsSent: 10, succeeded: 10, failed: 0, p50Millis: 5, p95Millis: 9, p99Millis: 12, elapsedMillis: 1000,
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-chart')).toBeInTheDocument());

    // p99 is not in the default-visible subset.
    const p99Toggle = screen.getByRole('checkbox', { name: 'p99 ms' }) as HTMLInputElement;
    expect(p99Toggle.checked).toBe(false);
    fireEvent.click(p99Toggle);
    expect((screen.getByRole('checkbox', { name: 'p99 ms' }) as HTMLInputElement).checked).toBe(true);

    // RPS is in the default subset — turning it off unchecks it.
    const rpsToggle = screen.getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement;
    expect(rpsToggle.checked).toBe(true);
    fireEvent.click(rpsToggle);
    expect((screen.getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement).checked).toBe(false);
  });

  it('loads the server-echoed definition into the form when editing a run started elsewhere', async () => {
    // A run this tab never started (no in-session lastSubmitted) — the server echoes its full
    // definition so "Edit running" can still populate the author form from it.
    stubFetch({
      state: 'running',
      name: 'foreign-run',
      runId: 'r9',
      currentVus: 3,
      requestsSent: 100,
      succeeded: 100,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 2000,
      definition: {
        name: 'foreign-run',
        templateType: 'VELOCITY',
        profile: { type: 'CONSTANT', vus: 7, durationMillis: 45000 },
        steps: [
          { request: { method: 'GET', path: '/echoed/path', socketAddress: { host: 'echoed.svc', port: 9090, scheme: 'HTTP' } } },
        ],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Edit running/i }));

    // the author form is now populated from the server-provided definition, not any in-tab state
    await waitFor(() =>
      expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('foreign-run'),
    );
    expect((screen.getByLabelText(/Virtual users/) as HTMLInputElement).value).toBe('7');
    expect((screen.getByLabelText(/Duration/) as HTMLInputElement).value).toBe('45000');
    const step0 = screen.getByTestId('load-step-0');
    expect((within(step0).getByLabelText('Path') as HTMLInputElement).value).toBe('/echoed/path');
    expect((within(step0).getByLabelText('Target host') as HTMLInputElement).value).toBe('echoed.svc');
    expect((within(step0).getByLabelText('Target port') as HTMLInputElement).value).toBe('9090');
  });

  it('shows the load-generation-disabled message on a 403', async () => {
    stubFetch({ error: 'load generation not enabled' }, 403);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-disabled-alert')).toBeInTheDocument());
    expect(screen.getByText(/loadGenerationEnabled=true/)).toBeInTheDocument();
    expect(screen.getByText(/MOCKSERVER_LOAD_GENERATION_ENABLED/)).toBeInTheDocument();
  });
});
