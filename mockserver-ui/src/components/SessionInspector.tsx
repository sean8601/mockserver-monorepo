import { useMemo, useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import InputAdornment from '@mui/material/InputAdornment';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import SearchIcon from '@mui/icons-material/Search';
import { useDashboardStore } from '../store';
import { groupBySession, shortenScenarioName, type Session, type SessionRequest } from '../lib/sessionGrouping';
import { getModelLabel, getTokenSummary } from '../lib/llmTraffic';
import { AnthropicConversationView, OpenAiConversationView } from './ConversationView';

// ---------------------------------------------------------------------------
// Status colour for request chips
// ---------------------------------------------------------------------------

function statusColor(code: number | null): 'success' | 'error' | 'warning' | 'default' {
  if (code === null) return 'default';
  if (code >= 200 && code < 300) return 'success';
  if (code >= 400) return 'error';
  if (code >= 300) return 'warning';
  return 'default';
}

// ---------------------------------------------------------------------------
// Request chip
// ---------------------------------------------------------------------------

interface RequestChipProps {
  request: SessionRequest;
  turnIndex: number;
  selected: boolean;
  onClick: () => void;
}

function RequestChip({ request, turnIndex, selected, onClick }: RequestChipProps) {
  const label = `[${turnIndex}] ${request.method ?? '?'} ${request.path ?? '/'} → ${request.statusCode ?? '?'}`;
  return (
    <Chip
      label={label}
      size="small"
      color={statusColor(request.statusCode)}
      variant={selected ? 'filled' : 'outlined'}
      onClick={onClick}
      sx={{
        height: 22,
        fontSize: '0.65rem',
        fontFamily: 'monospace',
        cursor: 'pointer',
        '& .MuiChip-label': { px: 0.75 },
      }}
    />
  );
}

// ---------------------------------------------------------------------------
// Request detail (expanded below the chip timeline)
// ---------------------------------------------------------------------------

function RequestDetail({ request }: { request: SessionRequest }) {
  const { parsed } = request;

  if (parsed.kind === 'anthropic') {
    return <AnthropicConversationView parsed={parsed} />;
  }
  if (parsed.kind === 'openai') {
    return <OpenAiConversationView parsed={parsed} />;
  }

  // OpenAI Responses, Gemini, and Ollama do not yet have dedicated rich viewers
  // (a follow-up will add per-provider views). Fall back to model + token chip
  // header plus a scrollable pretty-printed JSON, NOT a truncated slice — a 500-
  // byte cap silently hides most real conversation content.
  const model = getModelLabel(parsed);
  const tokens = getTokenSummary(parsed);

  return (
    <Box sx={{ p: 1 }}>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 0.5 }}>
        {model && <Chip label={`Model: ${model}`} size="small" variant="outlined" sx={{ height: 18, fontSize: '0.6rem' }} />}
        {tokens && (
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.65rem' }}>
            {tokens}
          </Typography>
        )}
      </Box>
      <Box
        component="pre"
        sx={{
          fontFamily: 'monospace',
          fontSize: '0.65rem',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          maxHeight: 400,
          overflowY: 'auto',
          m: 0,
          p: 1,
          backgroundColor: 'action.hover',
          borderRadius: 1,
        }}
      >
        {JSON.stringify(request.item.value, null, 2)}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Session lane
// ---------------------------------------------------------------------------

interface SessionLaneProps {
  session: Session;
}

function SessionLane({ session }: SessionLaneProps) {
  const [expandedRequest, setExpandedRequest] = useState<number | null>(null);

  const displayName = shortenScenarioName(session.scenarioName);
  const isUnscoped = session.scenarioName === '<unscoped>';

  const handleChipClick = useCallback(
    (index: number) => {
      setExpandedRequest(expandedRequest === index ? null : index);
    },
    [expandedRequest],
  );

  return (
    <Paper
      variant="outlined"
      sx={{ mb: 1 }}
    >
      {/* Lane header */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          px: 1.5,
          py: 0.75,
          borderBottom: 1,
          borderColor: 'divider',
          flexWrap: 'wrap',
        }}
      >
        <Typography
          variant="subtitle2"
          sx={{
            fontWeight: 600,
            fontSize: '0.8rem',
            fontFamily: isUnscoped ? undefined : 'monospace',
            fontStyle: isUnscoped ? 'italic' : 'normal',
          }}
        >
          {isUnscoped
            ? 'Unscoped requests'
            : `${displayName} / ${session.isolationKey}`
          }
        </Typography>
        <Chip
          label={`${session.requests.length} request${session.requests.length !== 1 ? 's' : ''}`}
          size="small"
          variant="outlined"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      </Box>

      {/* Horizontal request timeline */}
      <Box
        sx={{
          display: 'flex',
          gap: 0.5,
          px: 1.5,
          py: 0.75,
          overflowX: 'auto',
          flexWrap: 'wrap',
        }}
      >
        {session.requests.map((req, i) => (
          <RequestChip
            key={req.item.key}
            request={req}
            turnIndex={i}
            selected={expandedRequest === i}
            onClick={() => handleChipClick(i)}
          />
        ))}
      </Box>

      {/* Expanded detail */}
      {expandedRequest !== null && session.requests[expandedRequest] && (
        <>
          <Divider />
          <Box sx={{ maxHeight: 400, overflowY: 'auto' }}>
            <RequestDetail request={session.requests[expandedRequest]} />
          </Box>
        </>
      )}
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// Main SessionInspector component
// ---------------------------------------------------------------------------

export default function SessionInspector() {
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);
  const [search, setSearch] = useState('');

  const sessions = useMemo(
    () => groupBySession(proxiedRequests, activeExpectations),
    [proxiedRequests, activeExpectations],
  );

  const filteredSessions = useMemo(() => {
    if (!search) return sessions;
    const lower = search.toLowerCase();
    return sessions.filter((s) => {
      const name = shortenScenarioName(s.scenarioName).toLowerCase();
      const key = s.isolationKey.toLowerCase();
      return name.includes(lower) || key.includes(lower);
    });
  }, [sessions, search]);

  const hasLlmTraffic = sessions.length > 0;

  return (
    <Box
      sx={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        p: 1,
        overflow: 'hidden',
        minHeight: 0,
      }}
    >
      {/* Summary bar */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          mb: 1,
          flexShrink: 0,
          flexWrap: 'wrap',
        }}
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.85rem' }}>
          Sessions
        </Typography>
        {hasLlmTraffic && (
          <Chip
            label={`Active sessions: ${sessions.length}`}
            size="small"
            color="primary"
            variant="outlined"
            sx={{ height: 20, fontSize: '0.65rem', '& .MuiChip-label': { px: 0.75 } }}
          />
        )}
        <TextField
          size="small"
          placeholder="Filter sessions..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
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
            maxWidth: 250,
            '& .MuiInputBase-root': { height: 28, fontSize: '0.75rem' },
            '& .MuiSvgIcon-root': { fontSize: '0.875rem' },
          }}
        />
      </Box>

      {/* Session lanes */}
      <Box sx={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
        {!hasLlmTraffic ? (
          <Box sx={{ textAlign: 'center', py: 4 }}>
            <Typography variant="body2" color="text.secondary">
              No LLM traffic captured yet
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
              Configure your application to proxy through MockServer to see session groupings.
            </Typography>
          </Box>
        ) : filteredSessions.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 2 }}>
            No sessions match the current filter
          </Typography>
        ) : (
          filteredSessions.map((session) => (
            <SessionLane
              key={`${session.scenarioName}::${session.isolationKey}`}
              session={session}
            />
          ))
        )}
      </Box>
    </Box>
  );
}
