import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ScenarioPanel from '../components/ScenarioPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ScenarioPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ScenarioPanel — Trigger confirmation', () => {
  it('does not call the trigger endpoint until the confirmation is accepted', async () => {
    const user = userEvent.setup();
    let triggerCalled = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: { method?: string }) => {
        const urlStr = String(url);
        if (urlStr.includes('/trigger') && init?.method === 'PUT') {
          triggerCalled = true;
          return { ok: true, status: 200, json: async () => ({ scenarioName: 'checkout', currentState: 'paid' }) };
        }
        // GET /mockserver/scenario list
        return { ok: true, status: 200, json: async () => ({ scenarios: [] }) };
      }),
    );

    renderPanel();

    // Fill scenario name and trigger state so the Trigger button enables.
    const nameInput = screen.getByPlaceholderText('Scenario name');
    await user.type(nameInput, 'checkout');
    const triggerInput = screen.getByPlaceholderText('New state');
    await user.type(triggerInput, 'paid');

    const triggerBtn = screen.getByRole('button', { name: 'Trigger' });
    await user.click(triggerBtn);

    // A confirmation dialog appears; the endpoint has NOT been called yet.
    await waitFor(() => {
      expect(screen.getByText('Trigger scenario transition?')).toBeInTheDocument();
    });
    expect(triggerCalled).toBe(false);

    // Confirm — now the trigger endpoint is called.
    await user.click(screen.getByRole('button', { name: /Trigger transition/i }));

    await waitFor(() => {
      expect(triggerCalled).toBe(true);
    });
  });

  it('does not trigger when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    let triggerCalled = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: { method?: string }) => {
        const urlStr = String(url);
        if (urlStr.includes('/trigger') && init?.method === 'PUT') {
          triggerCalled = true;
          return { ok: true, status: 200, json: async () => ({ scenarioName: 'checkout', currentState: 'paid' }) };
        }
        return { ok: true, status: 200, json: async () => ({ scenarios: [] }) };
      }),
    );

    renderPanel();

    await user.type(screen.getByPlaceholderText('Scenario name'), 'checkout');
    await user.type(screen.getByPlaceholderText('New state'), 'paid');

    await user.click(screen.getByRole('button', { name: 'Trigger' }));

    await waitFor(() => {
      expect(screen.getByText('Trigger scenario transition?')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Cancel/i }));

    await waitFor(() => {
      expect(screen.queryByText('Trigger scenario transition?')).not.toBeInTheDocument();
    });
    expect(triggerCalled).toBe(false);
  });
});
