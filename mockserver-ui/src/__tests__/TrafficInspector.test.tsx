import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';

function renderTrafficInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

describe('TrafficInspector — ScriptedTurnsPanel wiring', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficIndex: null,
    });
  });

  it('shows Scripted Turns tab when LLM request is selected and conversation expectations exist', async () => {
    const user = userEvent.setup();

    // Set up a proxied LLM request
    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-1',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              headers: [{ name: 'host', values: ['api.anthropic.com'] }],
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  model: 'claude-sonnet-4-20250514',
                  messages: [{ role: 'user', content: 'Hello' }],
                }),
              },
            },
            httpResponse: {
              statusCode: 200,
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  content: [{ type: 'text', text: 'Hi there!' }],
                  usage: { input_tokens: 5, output_tokens: 3 },
                }),
              },
            },
          },
        },
      ],
      // Two-turn conversation expectations sharing a scenarioName
      activeExpectations: [
        {
          key: 'exp-turn0',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4-20250514',
              scenarioName: 'weather_conversation',
              scenarioState: 'Started',
              newScenarioState: 'turn_1',
              conversationPredicates: {
                turnIndex: 0,
              },
              completion: {
                text: '',
                toolCalls: [{ name: 'get_weather', arguments: '{"city":"London"}' }],
                stopReason: 'tool_use',
              },
            },
          },
        },
        {
          key: 'exp-turn1',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4-20250514',
              scenarioName: 'weather_conversation',
              scenarioState: 'turn_1',
              newScenarioState: '__done',
              conversationPredicates: {
                turnIndex: 1,
                containsToolResultFor: 'get_weather',
              },
              completion: {
                text: 'The weather in London is sunny, 22C.',
                stopReason: 'end_turn',
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Click on the proxied request row to select it
    // The path shows as "api.anthropic.com/v1/messages" in a single text node
    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // The Scripted Turns tab should be visible
    expect(screen.getByRole('tab', { name: 'Scripted Turns' })).toBeInTheDocument();

    // Click on the Scripted Turns tab
    await user.click(screen.getByRole('tab', { name: 'Scripted Turns' }));

    // The scripted turns content should be visible
    expect(screen.getByText('Scripted Conversation Turns')).toBeInTheDocument();
    expect(screen.getByText('Turn 0')).toBeInTheDocument();
    expect(screen.getByText('Turn 1')).toBeInTheDocument();
    expect(screen.getByText('Started')).toBeInTheDocument();
    expect(screen.getByText('__done')).toBeInTheDocument();
  });

  it('does not show Scripted Turns tab when no conversation expectations exist', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-1',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  model: 'claude-sonnet-4-20250514',
                  messages: [{ role: 'user', content: 'Hello' }],
                }),
              },
            },
            httpResponse: {
              statusCode: 200,
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  content: [{ type: 'text', text: 'Hi!' }],
                  usage: { input_tokens: 3, output_tokens: 1 },
                }),
              },
            },
          },
        },
      ],
      activeExpectations: [],
    });

    renderTrafficInspector();

    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // No Scripted Turns tab when no conversation expectations
    expect(screen.queryByRole('tab', { name: 'Scripted Turns' })).not.toBeInTheDocument();
  });
});
