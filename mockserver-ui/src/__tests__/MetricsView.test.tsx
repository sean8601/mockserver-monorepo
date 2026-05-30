import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import MetricsView from '../components/MetricsView';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function stubFetch(status: number, body = '') {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      status,
      ok: status >= 200 && status < 300,
      statusText: 'stub',
      text: async () => body,
    })),
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('MetricsView', () => {
  it('shows the disabled guidance when /mockserver/metrics returns 404', async () => {
    stubFetch(404);
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText(/Metrics are disabled/i)).toBeInTheDocument());
    expect(screen.getByText(/MOCKSERVER_METRICS_ENABLED=true/)).toBeInTheDocument();
  });

  it('renders summary panels and values from parsed metrics', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\nexpectations_not_matched_count 3.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Requests received')).toBeInTheDocument());
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('Matched')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('surfaces the MockServer version from build_info', async () => {
    stubFetch(200, 'requests_received_count 1.0\nmock_server_build_info{version="6.1.0"} 1.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MockServer 6.1.0')).toBeInTheDocument());
  });
});
