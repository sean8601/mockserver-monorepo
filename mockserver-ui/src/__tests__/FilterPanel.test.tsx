import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import FilterPanel from '../components/FilterPanel';
import { applyClientFilters, getActionType, getLlmProvider } from '../lib/clientFilters';
import { useDashboardStore } from '../store';

function renderFilterPanel(onFilterChange = vi.fn()) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <FilterPanel onFilterChange={onFilterChange} />
    </ThemeProvider>,
  );
}

describe('FilterPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      filterEnabled: false,
      filterExpanded: false,
      logShowForwarded: true,
    });
  });

  it('renders the filter header', () => {
    renderFilterPanel();
    expect(screen.getByText('Request Filter')).toBeInTheDocument();
  });

  it('expands when header is clicked', async () => {
    const user = userEvent.setup();
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));
    expect(screen.getByText('Enabled')).toBeInTheDocument();
  });

  it('shows method, path, and toggle fields when expanded', async () => {
    const user = userEvent.setup();
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.getByLabelText('Enabled')).toBeInTheDocument();
    expect(screen.getByLabelText('Path')).toBeInTheDocument();
    expect(screen.getByLabelText('Secure')).toBeInTheDocument();
    expect(screen.getByLabelText('Keep-Alive')).toBeInTheDocument();
  });

  it('calls onFilterChange with empty filter when disabled', async () => {
    const onFilterChange = vi.fn();
    renderFilterPanel(onFilterChange);

    await waitFor(() => {
      expect(onFilterChange).toHaveBeenCalledWith({});
    });
  });

  it('includes method and path in filter when enabled', async () => {
    const user = userEvent.setup();
    const onFilterChange = vi.fn();
    useDashboardStore.setState({ filterExpanded: true });
    renderFilterPanel(onFilterChange);

    await user.click(screen.getByLabelText('Enabled'));

    const pathInput = screen.getByLabelText('Path');
    await user.type(pathInput, '/api');

    await waitFor(() => {
      const lastCall = onFilterChange.mock.calls[onFilterChange.mock.calls.length - 1]![0];
      expect(lastCall).toHaveProperty('path', '/api');
    });
  });

  it('includes the body-content filter when enabled', async () => {
    const user = userEvent.setup();
    const onFilterChange = vi.fn();
    useDashboardStore.setState({ filterExpanded: true });
    renderFilterPanel(onFilterChange);

    await user.click(screen.getByLabelText('Enabled'));

    const bodyInput = screen.getByLabelText('Body contains');
    await user.type(bodyInput, 'order-123');

    await waitFor(() => {
      const lastCall = onFilterChange.mock.calls[onFilterChange.mock.calls.length - 1]![0];
      expect(lastCall).toHaveProperty('body', 'order-123');
    });
  });

  it('toggles the "Show forwarded" log display switch through the store', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ filterExpanded: true, logShowForwarded: true });
    renderFilterPanel();

    const toggle = screen.getByLabelText('Show forwarded');
    expect(toggle).toBeChecked();

    await user.click(toggle);
    expect(useDashboardStore.getState().logShowForwarded).toBe(false);
  });

  it('shows Action Type chip cluster when expanded', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ filterExpanded: false });
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.getByText('Action Type (expectations only)')).toBeInTheDocument();
    expect(screen.getByText('httpResponse')).toBeInTheDocument();
    expect(screen.getByText('httpLlmResponse')).toBeInTheDocument();
  });

  it('shows LLM Provider filter only when LLM expectations exist', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      filterExpanded: false,
      activeExpectations: [
        { key: 'e1', value: { httpLlmResponse: { provider: 'ANTHROPIC' } } },
      ],
    });
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.getByText('LLM Provider (expectations only)')).toBeInTheDocument();
    expect(screen.getByText('Anthropic')).toBeInTheDocument();
    expect(screen.getByText('Gemini')).toBeInTheDocument();
  });

  it('does not show LLM Provider filter when no LLM expectations exist', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      filterExpanded: false,
      activeExpectations: [
        { key: 'e1', value: { httpResponse: { statusCode: 200 } } },
      ],
    });
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.queryByText('LLM Provider (expectations only)')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Client-side filter utilities
// ---------------------------------------------------------------------------

describe('getActionType', () => {
  it('returns the action type key from an expectation value', () => {
    expect(getActionType({ httpResponse: { statusCode: 200 } })).toBe('httpResponse');
    expect(getActionType({ httpLlmResponse: { provider: 'ANTHROPIC' } })).toBe('httpLlmResponse');
    expect(getActionType({ httpForward: { host: 'example.com' } })).toBe('httpForward');
    expect(getActionType({ httpSseResponse: {} })).toBe('httpSseResponse');
  });

  it('returns null when no action key is found', () => {
    expect(getActionType({ httpRequest: { method: 'GET' } })).toBeNull();
  });
});

describe('getLlmProvider', () => {
  it('returns the provider from httpLlmResponse', () => {
    expect(getLlmProvider({ httpLlmResponse: { provider: 'ANTHROPIC' } })).toBe('ANTHROPIC');
    expect(getLlmProvider({ httpLlmResponse: { provider: 'OPENAI' } })).toBe('OPENAI');
  });

  it('returns null when no httpLlmResponse', () => {
    expect(getLlmProvider({ httpResponse: {} })).toBeNull();
  });
});

describe('applyClientFilters', () => {
  const items = [
    { key: '1', value: { httpResponse: { statusCode: 200 } } },
    { key: '2', value: { httpLlmResponse: { provider: 'ANTHROPIC' } } },
    { key: '3', value: { httpLlmResponse: { provider: 'OPENAI' } } },
    { key: '4', value: { httpForward: { host: 'example.com' } } },
    { key: '5', value: { httpLlmResponse: { provider: 'GEMINI' } } },
  ];

  it('returns all items when no filters are set', () => {
    expect(applyClientFilters(items, [], [])).toEqual(items);
  });

  it('filters by action type (OR within dimension)', () => {
    const result = applyClientFilters(items, ['httpLlmResponse'], []);
    expect(result).toHaveLength(3);
    expect(result.map((r) => r.key)).toEqual(['2', '3', '5']);
  });

  it('filters by multiple action types (OR within)', () => {
    const result = applyClientFilters(items, ['httpResponse', 'httpForward'], []);
    expect(result).toHaveLength(2);
    expect(result.map((r) => r.key)).toEqual(['1', '4']);
  });

  it('filters by LLM provider (OR within dimension)', () => {
    const result = applyClientFilters(items, [], ['ANTHROPIC', 'OPENAI']);
    expect(result).toHaveLength(2);
    expect(result.map((r) => r.key)).toEqual(['2', '3']);
  });

  it('applies AND across dimensions', () => {
    // action type = httpLlmResponse AND provider = ANTHROPIC
    const result = applyClientFilters(items, ['httpLlmResponse'], ['ANTHROPIC']);
    expect(result).toHaveLength(1);
    expect(result[0]!.key).toBe('2');
  });

  it('returns empty when no items match AND condition', () => {
    // action type = httpResponse AND provider = ANTHROPIC => no match
    const result = applyClientFilters(items, ['httpResponse'], ['ANTHROPIC']);
    expect(result).toHaveLength(0);
  });
});
