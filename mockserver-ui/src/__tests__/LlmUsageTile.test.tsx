import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import LlmUsageTile from '../components/LlmUsageTile';
import { useDashboardStore } from '../store';

function renderTile() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <LlmUsageTile />
    </ThemeProvider>,
  );
}

function makeAnthropicRequest(model: string, inputTokens: number, outputTokens: number, key = 'req-1') {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: [{ name: 'host', values: ['api.anthropic.com'] }],
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model,
            messages: [{ role: 'user', content: 'Hello' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model,
            content: [{ type: 'text', text: 'Hi!' }],
            usage: { input_tokens: inputTokens, output_tokens: outputTokens },
            stop_reason: 'end_turn',
          }),
        },
      },
    },
  };
}

function makeOpenAiRequest(model: string, promptTokens: number, completionTokens: number, key = 'req-2') {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/chat/completions',
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model,
            messages: [{ role: 'user', content: 'Hello' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model,
            choices: [{ message: { role: 'assistant', content: 'Hi!' } }],
            usage: { prompt_tokens: promptTokens, completion_tokens: completionTokens },
          }),
        },
      },
    },
  };
}

describe('LlmUsageTile', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
    });
  });

  it('renders empty state when no proxied LLM requests exist', () => {
    renderTile();
    expect(screen.getByText('No LLM traffic captured yet')).toBeInTheDocument();
    expect(screen.getByText(/proxy through MockServer/)).toBeInTheDocument();
  });

  it('renders per-provider rollup with cost', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('claude-sonnet-4-20250514', 1000, 500, 'req-1'),
        makeOpenAiRequest('gpt-4o', 2000, 1000, 'req-2'),
      ],
    });

    renderTile();

    // Should show the LLM Usage header
    expect(screen.getByText('LLM Usage')).toBeInTheDocument();

    // Should render Anthropic and OpenAI rows
    expect(screen.getByText('Anthropic')).toBeInTheDocument();
    expect(screen.getByText('OpenAI')).toBeInTheDocument();

    // Models should appear
    expect(screen.getByText('claude-sonnet-4-20250514')).toBeInTheDocument();
    expect(screen.getByText('gpt-4o')).toBeInTheDocument();
  });

  it('sorts rows by cost descending', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        // Cheaper request first in the data
        makeAnthropicRequest('claude-haiku-4-20250514', 100, 50, 'req-1'),
        // More expensive request second
        makeAnthropicRequest('claude-opus-4-20250514', 100, 50, 'req-2'),
      ],
    });

    renderTile();

    // Get all table body rows
    const rows = screen.getAllByRole('row');
    // First data row (rows[1], since rows[0] is header) should be the more expensive model
    const firstDataRow = rows[1]!;
    expect(within(firstDataRow).getByText('claude-opus-4-20250514')).toBeInTheDocument();
  });

  it('shows total cost matching sum of individual costs', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        // claude-sonnet-4: (1M in * 3.00 + 1M out * 15.00) / 1M = 18.00
        // But we use 1000 in, 500 out: (1000/1M)*3 + (500/1M)*15 = 0.003 + 0.0075 = 0.0105
        // formatCost rounds to 2 decimal places since >= 0.01
        makeAnthropicRequest('claude-sonnet-4-20250514', 1000, 500, 'req-1'),
      ],
    });

    renderTile();

    // The total cost appears in both the header and the row Est. Cost column
    const costElements = screen.getAllByText('$0.01');
    // Header total + row cost = at least 2
    expect(costElements.length).toBeGreaterThanOrEqual(2);
  });

  it('displays "—" for unknown models in the cost column', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('claude-ancient-1', 100, 50, 'req-1'),
      ],
    });

    renderTile();

    // Unknown model cost shows as em-dash
    const costCells = screen.getAllByText('—');
    expect(costCells.length).toBeGreaterThanOrEqual(1);
  });

  it('aggregates multiple requests for the same provider and model', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('claude-sonnet-4-20250514', 500, 200, 'req-1'),
        makeAnthropicRequest('claude-sonnet-4-20250514', 300, 100, 'req-2'),
      ],
    });

    renderTile();

    // Should have only one row for claude-sonnet-4
    const rows = screen.getAllByRole('row');
    // Header + 1 data row = 2 rows total
    expect(rows).toHaveLength(2);

    // Request count should be 2
    expect(screen.getByText('2')).toBeInTheDocument();
  });
});
