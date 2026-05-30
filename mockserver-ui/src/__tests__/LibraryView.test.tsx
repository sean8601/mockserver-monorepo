import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LibraryView from '../components/LibraryView';
import type { ConnectionParams } from '../hooks/useConnectionParams';

const connectionParams: ConnectionParams = { host: 'localhost', port: '1080', secure: false };

function downloadButton() {
  return screen.getByRole('button', { name: /Download/ });
}

describe('LibraryView export controls', () => {
  it('opens on the Export tab (first in the strip)', () => {
    render(<LibraryView connectionParams={connectionParams} />);
    const tabs = screen.getAllByRole('tab');
    expect(tabs[0]).toHaveTextContent('Export');
    expect(tabs[1]).toHaveTextContent('Cassettes');
    // Export controls are visible without switching tabs.
    expect(screen.getByText('What to export')).toBeInTheDocument();
  });

  it('lists Recorded requests first and selects it by default', () => {
    render(<LibraryView connectionParams={connectionParams} />);
    const radios = screen.getAllByRole('radio');
    expect(radios[0]).toHaveAccessibleName('Recorded requests');
    expect(screen.getByRole('radio', { name: 'Recorded requests' })).toBeChecked();
  });

  it('shows the best-effort caveat only for lossy expectation exports', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    // Default (recorded requests + JSON): no caveat.
    expect(screen.queryByText(/Best-effort export/)).not.toBeInTheDocument();
    // Active expectations + OpenAPI: the expectation-graph caveat appears.
    await user.click(screen.getByRole('radio', { name: 'Active expectations' }));
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    await user.click(screen.getByRole('option', { name: 'OpenAPI 3 spec' }));
    expect(screen.getByText(/Best-effort export/)).toBeInTheDocument();
  });

  it('shows a traffic-derived note for recorded-request spec exports', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    await user.click(screen.getByRole('option', { name: 'OpenAPI 3 spec' }));
    expect(screen.getByText(/Derived from the traffic/)).toBeInTheDocument();
    expect(screen.queryByText(/Best-effort export/)).not.toBeInTheDocument();
  });

  it('chooses scope with a radio and format with a dropdown (no combined list)', () => {
    render(<LibraryView connectionParams={connectionParams} />);
    expect(screen.getByRole('radio', { name: 'Active expectations' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Recorded requests' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Format' })).toBeInTheDocument();
    // Default selection: recorded requests + HAR.
    expect(downloadButton()).toHaveTextContent('mockserver-traffic.har');
  });

  it('updates the target file when the scope radio changes', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    await user.click(screen.getByRole('radio', { name: 'Active expectations' }));
    expect(downloadButton()).toHaveTextContent('mockserver-expectations.har');
  });

  it('updates the target file when the format dropdown changes', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    await user.click(screen.getByRole('radio', { name: 'Active expectations' }));
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    await user.click(screen.getByRole('option', { name: 'OpenAPI 3 spec' }));
    expect(downloadButton()).toHaveTextContent('mockserver-expectations.openapi.json');
  });

  it('offers JAVA only for active expectations', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    // Default scope = recorded requests: no Java DSL option.
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    expect(screen.queryByRole('option', { name: 'MockServer Java DSL' })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    // Switch to expectations: Java DSL appears.
    await user.click(screen.getByRole('radio', { name: 'Active expectations' }));
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    expect(screen.getByRole('option', { name: 'MockServer Java DSL' })).toBeInTheDocument();
  });

  it('offers LOG_ENTRIES only for recorded requests', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    // Default scope = recorded requests: log entries present.
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    expect(screen.getByRole('option', { name: 'Log entries (JSON)' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('radio', { name: 'Active expectations' }));
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    expect(screen.queryByRole('option', { name: 'Log entries (JSON)' })).not.toBeInTheDocument();
  });

  it('offers cURL only for recorded requests', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    expect(screen.getByRole('option', { name: 'cURL commands' })).toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: 'cURL commands' }));
    expect(downloadButton()).toHaveTextContent('mockserver-traffic.curl.sh');
  });

  it('resets an expectations-only format back to JSON when switching to requests', async () => {
    const user = userEvent.setup();
    render(<LibraryView connectionParams={connectionParams} />);
    await user.click(screen.getByRole('radio', { name: 'Active expectations' }));
    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    await user.click(screen.getByRole('option', { name: 'MockServer Java DSL' }));
    expect(downloadButton()).toHaveTextContent('mockserver-expectations.java');
    // Switching to requests (where Java is invalid) falls back to JSON.
    await user.click(screen.getByRole('radio', { name: 'Recorded requests' }));
    expect(downloadButton()).toHaveTextContent('mockserver-traffic.json');
  });
});
