import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import AppBar from '../components/AppBar';
import { useDashboardStore } from '../store';
import * as http3StatusModule from '../lib/http3Status';

function renderAppBar(overrides = {}) {
  const defaults = {
    onClearServer: vi.fn().mockResolvedValue(undefined),
    onClearLogs: vi.fn().mockResolvedValue(undefined),
    onClearExpectations: vi.fn().mockResolvedValue(undefined),
  };
  const props = { ...defaults, ...overrides };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AppBar {...props} />
      </ThemeProvider>,
    ),
    props,
  };
}

describe('AppBar', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      connectionStatus: 'connected',
      themeMode: 'dark',
      autoScroll: true,
    });
  });

  it('displays the MockServer title', () => {
    renderAppBar();
    expect(screen.getByText('MockServer')).toBeInTheDocument();
  });

  it('shows connection status chip', () => {
    renderAppBar();
    expect(screen.getByText('connected')).toBeInTheDocument();
  });

  it('shows different connection statuses', () => {
    useDashboardStore.setState({ connectionStatus: 'error' });
    renderAppBar();
    expect(screen.getByText('error')).toBeInTheDocument();
  });

  it('toggles theme when theme button is clicked', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const themeButtons = screen.getAllByRole('button');
    const themeButton = themeButtons.find((b) => b.getAttribute('aria-label')?.includes('light') || b.querySelector('[data-testid="LightModeIcon"]'));

    if (themeButton) {
      await user.click(themeButton);
      expect(useDashboardStore.getState().themeMode).toBe('light');
    }
  });

  it('opens clear menu and calls clear server on reset after confirmation', async () => {
    const user = userEvent.setup();
    const { props } = renderAppBar();

    const clearButton = screen.getAllByRole('button').find(
      (b) => b.querySelector('[data-testid="DeleteSweepIcon"]'),
    );
    expect(clearButton).toBeDefined();

    await user.click(clearButton!);
    expect(screen.getByText('Reset server (all)')).toBeInTheDocument();

    // Reset is destructive — it opens a confirmation dialog rather than firing immediately.
    await user.click(screen.getByText('Reset server (all)'));
    expect(props.onClearServer).not.toHaveBeenCalled();
    expect(screen.getByText('Reset the entire server?')).toBeInTheDocument();

    // Confirm in the dialog.
    await user.click(screen.getByRole('button', { name: 'Reset server' }));
    expect(props.onClearServer).toHaveBeenCalledOnce();
  });

  it('shows the Mocks tab label (not Composer)', () => {
    renderAppBar();
    expect(screen.getByText('Mocks')).toBeInTheDocument();
    expect(screen.queryByText('Composer')).not.toBeInTheDocument();
  });

  it('does not show a standalone MCP tab', () => {
    renderAppBar();
    // The MCP tab was removed and folded into the Mocks page
    const mcpButton = screen.queryByRole('button', { name: /MCP tools view/i });
    expect(mcpButton).not.toBeInTheDocument();
  });

  it('calls onClearLogs when clear server logs is clicked', async () => {
    const user = userEvent.setup();
    const { props } = renderAppBar();

    const clearButton = screen.getAllByRole('button').find(
      (b) => b.querySelector('[data-testid="DeleteSweepIcon"]'),
    );
    await user.click(clearButton!);
    await user.click(screen.getByText('Clear server logs'));

    expect(props.onClearLogs).toHaveBeenCalledOnce();
    expect(props.onClearServer).not.toHaveBeenCalled();
  });

  it('shows HTTP/3 status chip when H3 is enabled', async () => {
    vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockResolvedValue({
      enabled: true,
      port: 8443,
      activeConnections: 2,
    });

    renderAppBar();

    await waitFor(() => {
      expect(screen.getByText('H3 :8443 (2)')).toBeInTheDocument();
    });
  });

  it('does not show HTTP/3 chip when H3 is disabled', async () => {
    const spy = vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockResolvedValue({
      enabled: false,
      port: -1,
      activeConnections: 0,
    });

    renderAppBar();

    // Wait for the H3 status effect to complete before asserting absence.
    // Using waitFor on the spy ensures the async effect has settled.
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(screen.queryByText(/^H3 :/)).not.toBeInTheDocument();
  });

  it('does not show HTTP/3 chip when endpoint is unavailable', async () => {
    const spy = vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockRejectedValue(
      new Error('Not Found'),
    );

    renderAppBar();

    // Wait for the H3 status effect to complete before asserting absence.
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(screen.queryByText(/^H3 :/)).not.toBeInTheDocument();
  });

  it('opens the keyboard shortcuts dialog from the keyboard icon', async () => {
    const user = userEvent.setup();
    renderAppBar();

    await user.click(screen.getByRole('button', { name: 'Keyboard shortcuts' }));
    expect(screen.getByText('Focus the log search field')).toBeInTheDocument();
  });

  it('opens the SAML dialog from the tools menu', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const toolsButton = screen.getAllByRole('button').find(
      (b) => b.getAttribute('aria-label') === 'Import / export tools',
    );
    expect(toolsButton).toBeDefined();
    await user.click(toolsButton!);

    await user.click(screen.getByText('Mock SAML provider…'));
    // The dialog title appears once the SAML dialog opens.
    expect(screen.getByText('Mock SAML provider')).toBeInTheDocument();
  });

  it('opens the baseline compare dialog from the tools menu', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const toolsButton = screen.getAllByRole('button').find(
      (b) => b.getAttribute('aria-label') === 'Import / export tools',
    );
    expect(toolsButton).toBeDefined();
    await user.click(toolsButton!);

    await user.click(screen.getByText('Compare against baseline…'));
    // The dialog heading appears once the baseline compare dialog opens.
    expect(screen.getByRole('heading', { name: 'Compare against baseline' })).toBeInTheDocument();
  });
});

/**
 * Drive the AppBar at a narrow viewport by stubbing matchMedia so every media
 * query matches — this makes `useMediaQuery(theme.breakpoints.down('lg'))`
 * resolve to true and the nav collapses into the "hamburger" Menu.
 */
function stubMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

describe('AppBar responsive navigation', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      connectionStatus: 'connected',
      themeMode: 'dark',
      autoScroll: true,
      view: 'dashboard',
    });
  });

  afterEach(() => {
    // @ts-expect-error allow deleting the optional stub so other suites see desktop
    delete window.matchMedia;
  });

  it('renders the primary inline tabs plus a More overflow on wide screens (no hamburger)', () => {
    // jsdom default: no matchMedia → useMediaQuery returns false → wide layout.
    renderAppBar();
    // Primary tabs are present inline as toggle buttons.
    expect(screen.getByRole('button', { name: 'Dashboard view' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mocks view' })).toBeInTheDocument();
    // Less-common tabs (e.g. Metrics) are NOT inline — they live in the More menu.
    expect(screen.queryByRole('button', { name: 'Metrics view' })).not.toBeInTheDocument();
    // The More overflow button is present, the hamburger is not.
    expect(screen.getByRole('button', { name: 'More views' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open navigation menu' })).not.toBeInTheDocument();
  });

  it('opens the More overflow menu and navigates to a less-common view', async () => {
    const user = userEvent.setup();
    renderAppBar();

    await user.click(screen.getByRole('button', { name: 'More views' }));
    // Overflow views — including the new gRPC view — are reachable from the menu.
    expect(screen.getByRole('menuitem', { name: 'gRPC services view' })).toBeInTheDocument();
    const metricsItem = screen.getByRole('menuitem', { name: 'Metrics view' });
    expect(metricsItem).toBeInTheDocument();

    await user.click(metricsItem);
    expect(useDashboardStore.getState().view).toBe('metrics');
  });

  it('labels the More button with the active overflow view so the selection stays visible', () => {
    useDashboardStore.setState({ view: 'grpc' });
    renderAppBar();
    // When the active view lives in the overflow group the button shows its label.
    expect(screen.getByRole('button', { name: 'More views' })).toHaveTextContent('gRPC');
  });

  it('navigates to the gRPC view from the hamburger menu on narrow screens', async () => {
    stubMatchMedia(true);
    const user = userEvent.setup();
    renderAppBar();

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }));
    const grpcItem = screen.getByRole('menuitem', { name: 'gRPC services view' });
    await user.click(grpcItem);
    expect(useDashboardStore.getState().view).toBe('grpc');
  });

  it('collapses the nav into a hamburger Menu on narrow screens', async () => {
    stubMatchMedia(true);
    const user = userEvent.setup();
    renderAppBar();

    // The inline tab strip is gone; a hamburger button takes its place.
    const navButton = screen.getByRole('button', { name: 'Open navigation menu' });
    expect(navButton).toBeInTheDocument();
    // The current view label is still shown so the active view stays visible.
    expect(screen.getByText('Dashboard')).toBeInTheDocument();

    // The full tab list is reachable from the menu, including the last tab.
    await user.click(navButton);
    const metricsItem = screen.getByRole('menuitem', { name: 'Metrics view' });
    expect(metricsItem).toBeInTheDocument();

    // Selecting a tab from the menu changes the active view.
    await user.click(metricsItem);
    expect(useDashboardStore.getState().view).toBe('metrics');
  });
});
