import { useCallback, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import Collapse from '@mui/material/Collapse';
import CopyButton from './CopyButton';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import { parseCallGraph, toMermaid, type CallGraph } from '../lib/callGraph';

interface AgentRunGraphProps {
  connectionParams: { host: string; port: string; secure: boolean };
  provider: string;
  path: string | null;
}

/**
 * "Show Mermaid" link for a session: fetches the correlated agent-run call graph
 * via the explain_agent_run MCP tool and renders it as a Mermaid diagram below
 * the link. Read-only and deterministic — it visualises the recorded
 * conversation's structure (turns and the tool calls each made). Shown beneath
 * the chat-transcript Conversation view as a compact alternative representation.
 */
export default function AgentRunGraph({ connectionParams, provider, path }: AgentRunGraphProps) {
  const [graph, setGraph] = useState<CallGraph | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);

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

  // First click opens (and loads the graph on demand); second click hides it. The
  // graph is read-only/deterministic, so a re-open shows the cached result.
  const toggle = useCallback(() => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (!graph && !loading) {
      void load();
    }
  }, [open, graph, loading, load]);

  return (
    <Box sx={{ mt: 0.5 }}>
      <Button
        size="small"
        onClick={toggle}
        disabled={loading}
        sx={{ textTransform: 'none', fontSize: '0.7rem', px: 0.5, minWidth: 0 }}
      >
        {loading ? 'Loading…' : open ? 'Hide Mermaid' : 'Show Mermaid'}
      </Button>
      <Collapse in={open} unmountOnExit>
        {error ? (
          <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.5 }}>
            {error}
          </Typography>
        ) : graph ? (
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.5, mt: 0.5 }}>
            <Box
              component="pre"
              sx={{ fontSize: '0.7rem', overflow: 'auto', flex: 1, m: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1 }}
            >
              {toMermaid(graph)}
            </Box>
            <CopyButton text={toMermaid(graph)} />
          </Box>
        ) : null}
      </Collapse>
    </Box>
  );
}
