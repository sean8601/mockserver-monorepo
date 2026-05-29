import { useCallback, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Typography from '@mui/material/Typography';
import Collapse from '@mui/material/Collapse';
import CopyButton from './CopyButton';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import {
  buildRenderModel,
  parseCallGraph,
  toMermaid,
  type CallGraph,
  type RenderStep,
} from '../lib/callGraph';

interface AgentRunGraphProps {
  connectionParams: { host: string; port: string; secure: boolean };
  provider: string;
  path: string | null;
}

const ROLE_COLORS: Record<string, 'default' | 'primary' | 'secondary' | 'info' | 'success'> = {
  USER: 'primary',
  ASSISTANT: 'secondary',
  SYSTEM: 'default',
  TOOL: 'info',
};

/**
 * Fetches and renders the correlated agent-run call graph for a session via the
 * explain_agent_run MCP tool. Read-only and deterministic — it visualises the
 * recorded conversation's structure (turns, the tool calls each made, and which
 * returned a result).
 */
export default function AgentRunGraph({ connectionParams, provider, path }: AgentRunGraphProps) {
  const [graph, setGraph] = useState<CallGraph | null>(null);
  const [steps, setSteps] = useState<RenderStep[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showMermaid, setShowMermaid] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const args: Record<string, unknown> = { provider };
      if (path) {
        args['path'] = path;
      }
      const result = await callMcpTool(baseUrl, 'explain_agent_run', args);
      if (result.ok && result.result) {
        const parsed = parseCallGraph(result.result['callGraph']);
        if (parsed && parsed.nodes.length > 0) {
          setGraph(parsed);
          setSteps(buildRenderModel(parsed));
        } else {
          setError('No call graph available for this session.');
        }
      } else {
        setError(typeof result.error === 'string' ? result.error : 'Failed to load call graph');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, provider, path]);

  return (
    <Box sx={{ mt: 1 }}>
      <Button size="small" variant="outlined" onClick={load} disabled={loading} sx={{ textTransform: 'none' }}>
        {loading ? 'Loading…' : 'Call graph'}
      </Button>
      {error && (
        <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.5 }}>
          {error}
        </Typography>
      )}
      {steps.length > 0 && (
        <Box sx={{ mt: 1, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
          {steps.map((step, i) => (
            <Box key={step.node.id} sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 0.5 }}>
              <Typography variant="caption" color="text.secondary" sx={{ width: 18 }}>{i + 1}.</Typography>
              <Chip
                size="small"
                color={ROLE_COLORS[step.node.kind] ?? 'default'}
                label={`${step.node.kind}${step.node.label ? ': ' + step.node.label : ''}`}
              />
              {step.toolCalls.map((tc) => (
                <Chip
                  key={tc.node.id}
                  size="small"
                  variant="outlined"
                  label={`🔧 ${tc.node.label}${tc.hasResult ? ' ✓' : ''}`}
                  title={tc.hasResult ? 'tool result returned' : 'no tool result recorded'}
                />
              ))}
            </Box>
          ))}
          {graph && (
            <Box sx={{ mt: 0.5 }}>
              <Button size="small" onClick={() => setShowMermaid((v) => !v)} sx={{ textTransform: 'none' }}>
                {showMermaid ? 'Hide' : 'Show'} Mermaid
              </Button>
              <Collapse in={showMermaid} unmountOnExit>
                <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.5, mt: 0.5 }}>
                  <Box component="pre" sx={{ fontSize: '0.7rem', overflow: 'auto', flex: 1, m: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1 }}>
                    {toMermaid(graph)}
                  </Box>
                  <CopyButton text={toMermaid(graph)} />
                </Box>
              </Collapse>
            </Box>
          )}
        </Box>
      )}
    </Box>
  );
}
