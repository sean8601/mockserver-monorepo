import { useMemo, useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import InputAdornment from '@mui/material/InputAdornment';
import Chip from '@mui/material/Chip';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Divider from '@mui/material/Divider';
import SearchIcon from '@mui/icons-material/Search';
import { useDashboardStore } from '../store';
import JsonViewer from './JsonViewer';
import { AnthropicConversationView, OpenAiConversationView } from './ConversationView';
import type { JsonListItem } from '../types';
import {
  summarizeTraffic,
  getModelLabel,
  getTokenSummary,
  type TrafficSummary,
  type ParsedTraffic,
  type AnthropicParsed,
  type OpenAiParsed,
  type McpParsed,
  type SseEvent,
} from '../lib/llmTraffic';

// ---------------------------------------------------------------------------
// Helper: status color
// ---------------------------------------------------------------------------

function statusColor(code: number | null): 'success' | 'error' | 'warning' | 'default' {
  if (code === null) return 'default';
  if (code >= 200 && code < 300) return 'success';
  if (code >= 400) return 'error';
  if (code >= 300) return 'warning';
  return 'default';
}

function kindLabel(parsed: ParsedTraffic): string {
  switch (parsed.kind) {
    case 'anthropic': return 'Anthropic';
    case 'openai': return 'OpenAI';
    case 'mcp': return 'MCP';
    case 'generic': return 'HTTP';
  }
}

function kindColor(parsed: ParsedTraffic): 'primary' | 'secondary' | 'info' | 'default' {
  switch (parsed.kind) {
    case 'anthropic': return 'primary';
    case 'openai': return 'secondary';
    case 'mcp': return 'info';
    case 'generic': return 'default';
  }
}

// ---------------------------------------------------------------------------
// Search match helper
// ---------------------------------------------------------------------------

function matchesSearch(item: JsonListItem, summary: TrafficSummary, term: string): boolean {
  const lower = term.toLowerCase();
  const parts = [
    summary.host,
    summary.method,
    summary.path,
    summary.statusCode?.toString(),
    getModelLabel(summary.parsed),
    kindLabel(summary.parsed),
  ].filter(Boolean);
  if (parts.some((p) => p!.toLowerCase().includes(lower))) return true;
  return JSON.stringify(item.value).toLowerCase().includes(lower);
}

// ---------------------------------------------------------------------------
// Master list row
// ---------------------------------------------------------------------------

interface TrafficRowProps {
  summary: TrafficSummary;
  index: number;
  selected: boolean;
  onClick: () => void;
}

function TrafficRow({ summary, index, selected, onClick }: TrafficRowProps) {
  const model = getModelLabel(summary.parsed);
  const tokens = getTokenSummary(summary.parsed);

  return (
    <Box
      onClick={onClick}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 0.75,
        px: 1,
        py: 0.5,
        cursor: 'pointer',
        bgcolor: selected ? 'action.selected' : 'transparent',
        '&:hover': { bgcolor: selected ? 'action.selected' : 'action.hover' },
        borderBottom: 1,
        borderColor: 'divider',
        minHeight: 36,
        flexWrap: 'wrap',
      }}
    >
      <Typography
        variant="caption"
        sx={{ fontFamily: 'monospace', color: 'text.secondary', minWidth: 24, flexShrink: 0 }}
      >
        {index}
      </Typography>
      <Chip
        label={kindLabel(summary.parsed)}
        size="small"
        color={kindColor(summary.parsed)}
        variant="outlined"
        sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
      />
      <Typography
        variant="caption"
        sx={{
          fontFamily: 'monospace',
          fontWeight: 600,
          color: 'primary.main',
          flexShrink: 0,
        }}
      >
        {summary.method ?? '?'}
      </Typography>
      <Typography
        variant="caption"
        noWrap
        sx={{
          fontFamily: 'monospace',
          flex: 1,
          minWidth: 80,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {summary.host ? `${summary.host}` : ''}{summary.path ?? ''}
      </Typography>
      {summary.statusCode !== null && (
        <Chip
          label={summary.statusCode}
          size="small"
          color={statusColor(summary.statusCode)}
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
      {model && (
        <Chip
          label={model}
          size="small"
          variant="outlined"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
      {tokens && (
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.6rem', flexShrink: 0 }}>
          {tokens}
        </Typography>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: Anthropic
// ---------------------------------------------------------------------------

function AnthropicMessagesPanel({ parsed }: { parsed: AnthropicParsed }) {
  return (
    <Box>
      {parsed.system != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>System</Typography>
          <Box sx={{ mt: 0.5 }}>
            {typeof parsed.system === 'string' ? (
              <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.75rem', whiteSpace: 'pre-wrap' }}>
                {parsed.system}
              </Typography>
            ) : (
              <JsonViewer data={parsed.system as Record<string, unknown>} collapsed={2} />
            )}
          </Box>
        </Box>
      )}
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.tools && parsed.tools.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Tools ({parsed.tools.length})</Typography>
          <JsonViewer data={parsed.tools as unknown[]} collapsed={1} />
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.maxTokens !== null && <Chip label={`Max tokens: ${parsed.maxTokens}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: OpenAI
// ---------------------------------------------------------------------------

function OpenAiMessagesPanel({ parsed }: { parsed: OpenAiParsed }) {
  return (
    <Box>
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.tools && parsed.tools.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Tools ({parsed.tools.length})</Typography>
          <JsonViewer data={parsed.tools as unknown[]} collapsed={1} />
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// MCP panel (content only, no wrapping tabs)
// ---------------------------------------------------------------------------

function McpDetailPanel({ parsed }: { parsed: McpParsed }) {
  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 1 }}>
        <Chip label="MCP JSON-RPC" size="small" color="info" variant="outlined" />
        {parsed.method && <Chip label={`Method: ${parsed.method}`} size="small" variant="outlined" />}
        {parsed.id != null && <Chip label={`ID: ${String(parsed.id)}`} size="small" variant="outlined" />}
      </Box>
      {parsed.params != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Params</Typography>
          <JsonViewer data={parsed.params as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
      {parsed.result != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Result</Typography>
          <JsonViewer data={parsed.result as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
      {parsed.error != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="error" sx={{ fontWeight: 600 }}>Error</Typography>
          <JsonViewer data={parsed.error as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// SSE Timeline
// ---------------------------------------------------------------------------

function SseTimeline({ events }: { events: SseEvent[] }) {
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);

  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, mb: 0.5, display: 'block' }}>
        SSE Events ({events.length})
      </Typography>
      {events.map((evt, i) => {
        const parsedData = (() => {
          if (evt.data === '[DONE]') return null;
          try { return JSON.parse(evt.data); } catch { return null; }
        })();

        return (
          <Box
            key={i}
            sx={{
              borderLeft: 2,
              borderColor: evt.event === 'error' ? 'error.main' : 'primary.main',
              pl: 1,
              py: 0.25,
              mb: 0.25,
              cursor: 'pointer',
              '&:hover': { bgcolor: 'action.hover' },
            }}
            onClick={() => setExpandedIndex(expandedIndex === i ? null : i)}
          >
            <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
              <Typography variant="caption" sx={{ fontFamily: 'monospace', color: 'text.secondary', minWidth: 24 }}>
                {i + 1}
              </Typography>
              {evt.event && (
                <Chip
                  label={evt.event}
                  size="small"
                  variant="outlined"
                  sx={{ height: 16, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
                />
              )}
              <Typography
                variant="caption"
                noWrap
                sx={{ fontFamily: 'monospace', fontSize: '0.65rem', color: 'text.secondary', flex: 1 }}
              >
                {evt.data.length > 80 ? evt.data.slice(0, 80) + '...' : evt.data}
              </Typography>
            </Box>
            {expandedIndex === i && parsedData && (
              <Box sx={{ mt: 0.5, ml: 3 }}>
                <JsonViewer data={parsedData} collapsed={2} />
              </Box>
            )}
            {expandedIndex === i && !parsedData && (
              <Typography variant="body2" sx={{ mt: 0.5, ml: 3, fontFamily: 'monospace', fontSize: '0.7rem', whiteSpace: 'pre-wrap' }}>
                {evt.data}
              </Typography>
            )}
          </Box>
        );
      })}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Detail pane wrapper — single-level, adaptive tab row
// ---------------------------------------------------------------------------

interface DetailPaneProps {
  item: JsonListItem;
  summary: TrafficSummary;
}

/** Build the tab list dynamically from the traffic kind. */
function buildTabs(parsed: ParsedTraffic): string[] {
  switch (parsed.kind) {
    case 'anthropic':
    case 'openai': {
      const tabs = ['Messages', 'Conversation'];
      if (parsed.sseEvents) tabs.push('SSE Timeline');
      tabs.push('Raw JSON');
      return tabs;
    }
    case 'mcp':
      return ['MCP', 'Raw JSON'];
    case 'generic':
      return []; // no tabs — render Raw JSON directly
  }
}

function DetailPane({ item, summary }: DetailPaneProps) {
  const tabs = buildTabs(summary.parsed);
  const [detailTab, setDetailTab] = useState(0);

  // For generic traffic, render Raw JSON directly — no tab bar needed
  if (tabs.length === 0) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
        <Box sx={{ px: 1, py: 0.5, flexShrink: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, fontSize: '0.75rem' }}>
            Raw JSON
          </Typography>
        </Box>
        <Divider />
        <Box sx={{ flex: 1, overflowY: 'auto', p: 1 }}>
          <JsonViewer data={item.value} collapsed={2} />
        </Box>
      </Box>
    );
  }

  // Clamp tab index defensively (e.g. if tabs array shrank before the effect runs)
  const safeTab = Math.min(detailTab, tabs.length - 1);
  const activeLabel = tabs[safeTab];

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      <Tabs
        value={safeTab}
        onChange={(_, v: number) => setDetailTab(v)}
        variant="scrollable"
        scrollButtons="auto"
        sx={{ flexShrink: 0, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.75rem' } }}
      >
        {tabs.map((label) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>
      <Divider />
      <Box sx={{ flex: 1, overflowY: 'auto', p: 1, minHeight: 0 }}>
        {activeLabel === 'Messages' && summary.parsed.kind === 'anthropic' && (
          <AnthropicMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'openai' && (
          <OpenAiMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'anthropic' && (
          <AnthropicConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'openai' && (
          <OpenAiConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'SSE Timeline' && (summary.parsed.kind === 'anthropic' || summary.parsed.kind === 'openai') && summary.parsed.sseEvents && (
          <SseTimeline events={summary.parsed.sseEvents} />
        )}
        {activeLabel === 'MCP' && summary.parsed.kind === 'mcp' && (
          <McpDetailPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Raw JSON' && (
          <JsonViewer data={item.value} collapsed={2} />
        )}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main TrafficInspector component
// ---------------------------------------------------------------------------

export default function TrafficInspector() {
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const trafficSearch = useDashboardStore((s) => s.trafficSearch);
  const setTrafficSearch = useDashboardStore((s) => s.setTrafficSearch);
  const selectedIndex = useDashboardStore((s) => s.selectedTrafficIndex);
  const setSelectedIndex = useDashboardStore((s) => s.setSelectedTrafficIndex);

  // Build summaries for all proxied requests
  const summaries = useMemo(
    () => proxiedRequests.map((item) => ({ item, summary: summarizeTraffic(item.value) })),
    [proxiedRequests],
  );

  // Filter by search
  const filtered = useMemo(
    () =>
      trafficSearch
        ? summaries.filter(({ item, summary }) => matchesSearch(item, summary, trafficSearch))
        : summaries,
    [summaries, trafficSearch],
  );

  const selectedEntry = useMemo(() => {
    if (selectedIndex === null) return null;
    return filtered.find((_, i) => i === selectedIndex) ?? null;
  }, [filtered, selectedIndex]);

  const handleRowClick = useCallback(
    (index: number) => {
      setSelectedIndex(selectedIndex === index ? null : index);
    },
    [selectedIndex, setSelectedIndex],
  );

  return (
    <Box
      sx={{
        flex: 1,
        display: 'flex',
        gap: 1,
        p: 1,
        overflow: 'hidden',
        minHeight: 0,
      }}
    >
      {/* Master list */}
      <Paper
        variant="outlined"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          width: selectedEntry ? '40%' : '100%',
          minWidth: 300,
          overflow: 'hidden',
          transition: 'width 0.2s ease',
        }}
      >
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            px: 1,
            py: 0.25,
            borderBottom: 1,
            borderColor: 'divider',
            flexShrink: 0,
          }}
        >
          <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.79rem' }}>
            Traffic
          </Typography>
          {proxiedRequests.length > 0 && (
            <Chip
              label={proxiedRequests.length > 999 ? '999+' : proxiedRequests.length}
              color="primary"
              size="small"
              sx={{ height: 18, fontSize: '0.65rem', '& .MuiChip-label': { px: 0.75 } }}
            />
          )}
          <TextField
            id="traffic-inspector-search"
            size="small"
            placeholder="Search..."
            value={trafficSearch}
            onChange={(e) => setTrafficSearch(e.target.value)}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" />
                  </InputAdornment>
                ),
              },
            }}
            sx={{
              ml: 'auto',
              maxWidth: 200,
              '& .MuiInputBase-root': { height: 28, fontSize: '0.75rem' },
              '& .MuiSvgIcon-root': { fontSize: '0.875rem' },
            }}
          />
        </Box>
        <Box sx={{ flex: 1, overflowY: 'auto', bgcolor: 'background.default' }}>
          {filtered.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
              {proxiedRequests.length === 0 ? 'No proxied requests' : 'No matching requests'}
            </Typography>
          ) : (
            filtered.map(({ item, summary }, index) => (
              <TrafficRow
                key={item.key}
                summary={summary}
                index={filtered.length - index}
                selected={selectedIndex === index}
                onClick={() => handleRowClick(index)}
              />
            ))
          )}
        </Box>
      </Paper>

      {/* Detail pane */}
      {selectedEntry && (
        <Paper
          variant="outlined"
          sx={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
            minWidth: 0,
          }}
        >
          <DetailPane key={selectedEntry.item.key} item={selectedEntry.item} summary={selectedEntry.summary} />
        </Paper>
      )}
    </Box>
  );
}
