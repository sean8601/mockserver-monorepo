import { useCallback, useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import TextField from '@mui/material/TextField';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Accordion from '@mui/material/Accordion';
import AccordionSummary from '@mui/material/AccordionSummary';
import AccordionDetails from '@mui/material/AccordionDetails';
import RefreshIcon from '@mui/icons-material/Refresh';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import DeleteIcon from '@mui/icons-material/Delete';
import AddIcon from '@mui/icons-material/Add';
import StopIcon from '@mui/icons-material/Stop';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import CallMadeIcon from '@mui/icons-material/CallMade';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchBreakpoints,
  continueBreakpoint,
  modifyBreakpoint,
  modifyBreakpointResponse,
  abortBreakpoint,
  fetchStreamFrames,
  continueStreamFrame,
  modifyStreamFrame,
  dropStreamFrame,
  injectStreamFrame,
  closeStreamFrame,
  type PausedExchange,
  type BreakpointListResponse,
  type StreamFrameListResponse,
  type StreamFrame,
} from '../lib/breakpoints';

interface BreakpointsPanelProps {
  connectionParams: ConnectionParams;
}

const POLL_INTERVAL_MS = 2000;

function formatAge(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const secs = Math.round(ms / 1000);
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  const remainSecs = secs % 60;
  return `${mins}m ${remainSecs}s`;
}

export default function BreakpointsPanel({ connectionParams }: BreakpointsPanelProps) {
  const [tab, setTab] = useState(0);
  const [data, setData] = useState<BreakpointListResponse>({ pausedExchanges: [], count: 0 });
  const [streamData, setStreamData] = useState<StreamFrameListResponse>({ streams: [], totalHeldFrames: 0 });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [streamLoadError, setStreamLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [busy, setBusy] = useState(false);

  // Modify dialog state (request/response breakpoints)
  const [modifyTarget, setModifyTarget] = useState<PausedExchange | null>(null);
  const [modifyJson, setModifyJson] = useState('');
  const [modifyError, setModifyError] = useState<string | null>(null);

  // Stream frame dialogs
  const [streamModifyTarget, setStreamModifyTarget] = useState<StreamFrame | null>(null);
  const [streamModifyBody, setStreamModifyBody] = useState('');
  const [streamModifyError, setStreamModifyError] = useState<string | null>(null);

  const [streamInjectTarget, setStreamInjectTarget] = useState<StreamFrame | null>(null);
  const [streamInjectBody, setStreamInjectBody] = useState('');
  const [streamInjectError, setStreamInjectError] = useState<string | null>(null);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Poll breakpoints list (exchanges).
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const response = await fetchBreakpoints(connectionParams, controller.signal);
        if (cancelled) return;
        setData(response);
        setLoadError(null);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setLoadError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick]);

  // Poll stream frames.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const response = await fetchStreamFrames(connectionParams, controller.signal);
        if (cancelled) return;
        setStreamData(response);
        setStreamLoadError(null);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setStreamLoadError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick]);

  // --- Exchange actions ---

  const handleContinue = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await continueBreakpoint(connectionParams, id);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const handleAbort = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await abortBreakpoint(connectionParams, id);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const openModifyDialog = useCallback((exchange: PausedExchange) => {
    setModifyTarget(exchange);
    const prefill = exchange.phase === 'RESPONSE'
      ? (exchange.response ?? {})
      : (exchange.request ?? {});
    setModifyJson(JSON.stringify(prefill, null, 2));
    setModifyError(null);
  }, []);

  const handleModifySubmit = useCallback(async () => {
    if (!modifyTarget) return;
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(modifyJson) as Record<string, unknown>;
    } catch {
      setModifyError('Invalid JSON');
      return;
    }
    setBusy(true);
    setModifyError(null);
    try {
      if (modifyTarget.phase === 'RESPONSE') {
        await modifyBreakpointResponse(connectionParams, modifyTarget.id, parsed);
      } else {
        await modifyBreakpoint(connectionParams, modifyTarget.id, parsed);
      }
      setModifyTarget(null);
      refresh();
    } catch (e) {
      setModifyError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, modifyTarget, modifyJson, refresh]);

  // --- Stream frame actions ---

  const handleStreamContinue = useCallback(
    async (frameId: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await continueStreamFrame(connectionParams, frameId);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const handleStreamDrop = useCallback(
    async (frameId: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await dropStreamFrame(connectionParams, frameId);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const handleStreamClose = useCallback(
    async (frameId: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await closeStreamFrame(connectionParams, frameId);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const openStreamModifyDialog = useCallback((frame: StreamFrame) => {
    setStreamModifyTarget(frame);
    setStreamModifyBody(frame.bodyPreview ?? '');
    setStreamModifyError(null);
  }, []);

  const handleStreamModifySubmit = useCallback(async () => {
    if (!streamModifyTarget) return;
    if (!streamModifyBody) {
      setStreamModifyError('Body is required');
      return;
    }
    setBusy(true);
    setStreamModifyError(null);
    try {
      await modifyStreamFrame(connectionParams, streamModifyTarget.frameId, streamModifyBody);
      setStreamModifyTarget(null);
      refresh();
    } catch (e) {
      setStreamModifyError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, streamModifyTarget, streamModifyBody, refresh]);

  const openStreamInjectDialog = useCallback((frame: StreamFrame) => {
    setStreamInjectTarget(frame);
    setStreamInjectBody('');
    setStreamInjectError(null);
  }, []);

  const handleStreamInjectSubmit = useCallback(async () => {
    if (!streamInjectTarget) return;
    if (!streamInjectBody) {
      setStreamInjectError('Body is required');
      return;
    }
    setBusy(true);
    setStreamInjectError(null);
    try {
      await injectStreamFrame(connectionParams, streamInjectTarget.frameId, streamInjectBody);
      setStreamInjectTarget(null);
      refresh();
    } catch (e) {
      setStreamInjectError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, streamInjectTarget, streamInjectBody, refresh]);

  const totalCount = data.count + streamData.totalHeldFrames;

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Breakpoints
        </Typography>
        <Chip
          size="small"
          label={`${totalCount} paused`}
          color={totalCount > 0 ? 'warning' : 'default'}
          variant="outlined"
        />
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh breakpoints">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Tabs value={tab} onChange={(_, v: number) => setTab(v)} sx={{ mb: 1.5 }}>
        <Tab
          label={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
              Exchanges
              {data.count > 0 && (
                <Chip size="small" label={data.count} color="warning" sx={{ height: 18, fontSize: '0.65rem' }} />
              )}
            </Box>
          }
        />
        <Tab
          label={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
              Streams
              {streamData.totalHeldFrames > 0 && (
                <Chip size="small" label={streamData.totalHeldFrames} color="warning" sx={{ height: 18, fontSize: '0.65rem' }} />
              )}
            </Box>
          }
        />
      </Tabs>

      {actionError && (
        <Alert severity="warning" sx={{ mb: 1.5 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}

      {/* ============ TAB 0: Exchanges ============ */}
      {tab === 0 && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Exchanges paused by breakpoint expectations. Continue, modify, or abort each exchange.
          </Typography>

          {loadError && (
            <Alert
              severity={loadError.includes('404') || loadError.includes('Not Found') ? 'info' : 'error'}
              sx={{ mb: 1.5 }}
              action={
                <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry">
                  <RefreshIcon fontSize="small" />
                </IconButton>
              }
            >
              <AlertTitle>
                {loadError.includes('404') || loadError.includes('Not Found')
                  ? 'Breakpoints not available'
                  : 'Could not load paused exchanges'}
              </AlertTitle>
              {loadError.includes('404') || loadError.includes('Not Found')
                ? 'The connected server does not support breakpoints. This feature requires a newer version of MockServer.'
                : loadError}
            </Alert>
          )}

          <Paper variant="outlined" sx={{ p: 1.25 }}>
            {data.pausedExchanges.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
                No paused exchanges. Breakpoint expectations pause matching requests or responses so you can inspect and modify them.
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Phase</TableCell>
                      <TableCell>Method / Status</TableCell>
                      <TableCell>Path / Reason</TableCell>
                      <TableCell>Age</TableCell>
                      <TableCell>ID</TableCell>
                      <TableCell>Expectation</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.pausedExchanges.map((exchange) => {
                      const isResponse = exchange.phase === 'RESPONSE';
                      return (
                        <TableRow key={exchange.id}>
                          <TableCell>
                            <Chip
                              size="small"
                              label={exchange.phase ?? 'REQUEST'}
                              color={isResponse ? 'secondary' : 'default'}
                              variant="outlined"
                              sx={{ height: 20, fontSize: '0.65rem' }}
                            />
                          </TableCell>
                          <TableCell>
                            <Chip
                              size="small"
                              label={isResponse
                                ? String(exchange.response?.statusCode ?? '?')
                                : (exchange.request?.method ?? '?')}
                              color={isResponse ? 'secondary' : 'primary'}
                              variant="outlined"
                              sx={{ height: 20, fontSize: '0.65rem' }}
                            />
                          </TableCell>
                          <TableCell>
                            <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                              {isResponse
                                ? (exchange.response?.reasonPhrase ?? '-')
                                : (exchange.request?.path ?? '/')}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="caption">
                              {formatAge(exchange.ageMillis)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="caption" sx={{ fontFamily: 'monospace', maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' }}>
                              {exchange.id}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                              {exchange.expectationId ?? '-'}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">
                            <Box sx={{ display: 'flex', gap: 0.5, justifyContent: 'flex-end' }}>
                              <Tooltip title="Continue (forward unchanged)">
                                <span>
                                  <IconButton
                                    size="small"
                                    color="success"
                                    disabled={busy}
                                    onClick={() => void handleContinue(exchange.id)}
                                    aria-label={`Continue ${exchange.id}`}
                                  >
                                    <PlayArrowIcon fontSize="small" />
                                  </IconButton>
                                </span>
                              </Tooltip>
                              <Tooltip title={isResponse ? 'Modify response before returning' : 'Modify request before forwarding'}>
                                <span>
                                  <IconButton
                                    size="small"
                                    color="info"
                                    disabled={busy}
                                    onClick={() => openModifyDialog(exchange)}
                                    aria-label={`Modify ${exchange.id}`}
                                  >
                                    <EditIcon fontSize="small" />
                                  </IconButton>
                                </span>
                              </Tooltip>
                              <Tooltip title="Abort (do not forward)">
                                <span>
                                  <IconButton
                                    size="small"
                                    color="error"
                                    disabled={busy}
                                    onClick={() => void handleAbort(exchange.id)}
                                    aria-label={`Abort ${exchange.id}`}
                                  >
                                    <BlockIcon fontSize="small" />
                                  </IconButton>
                                </span>
                              </Tooltip>
                            </Box>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </>
      )}

      {/* ============ TAB 1: Streams ============ */}
      {tab === 1 && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Forwarded streaming response frames held at breakpoints. Continue, modify, drop, inject, or close each frame.
          </Typography>

          {streamLoadError && (
            <Alert
              severity={streamLoadError.includes('404') || streamLoadError.includes('Not Found') ? 'info' : 'error'}
              sx={{ mb: 1.5 }}
              action={
                <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry streams">
                  <RefreshIcon fontSize="small" />
                </IconButton>
              }
            >
              <AlertTitle>
                {streamLoadError.includes('404') || streamLoadError.includes('Not Found')
                  ? 'Stream breakpoints not available'
                  : 'Could not load stream frames'}
              </AlertTitle>
              {streamLoadError.includes('404') || streamLoadError.includes('Not Found')
                ? 'The connected server does not support stream breakpoints. This feature requires a newer version of MockServer.'
                : streamLoadError}
            </Alert>
          )}

          <Paper variant="outlined" sx={{ p: 1.25 }}>
            {streamData.streams.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
                No held stream frames. Stream breakpoints pause individual frames from forwarded streaming responses (SSE, chunked transfer) so you can inspect and manipulate them.
              </Typography>
            ) : (
              streamData.streams.map((stream) => (
                <Accordion key={stream.streamId} defaultExpanded disableGutters>
                  <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        Stream
                      </Typography>
                      <Chip
                        size="small"
                        label={stream.streamId}
                        variant="outlined"
                        sx={{ height: 20, fontSize: '0.65rem', fontFamily: 'monospace' }}
                      />
                      <Chip
                        size="small"
                        label={`${stream.frames.length} frame${stream.frames.length !== 1 ? 's' : ''}`}
                        color="warning"
                        variant="outlined"
                        sx={{ height: 20, fontSize: '0.65rem' }}
                      />
                    </Box>
                  </AccordionSummary>
                  <AccordionDetails sx={{ p: 0 }}>
                    <TableContainer>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Seq</TableCell>
                            <TableCell>Direction</TableCell>
                            <TableCell>Method</TableCell>
                            <TableCell>Path</TableCell>
                            <TableCell>Body Preview</TableCell>
                            <TableCell>Size</TableCell>
                            <TableCell>Age</TableCell>
                            <TableCell align="right">Actions</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {stream.frames.map((frame) => (
                            <TableRow key={frame.frameId}>
                              <TableCell>
                                <Chip
                                  size="small"
                                  label={`#${frame.sequenceNumber}`}
                                  variant="outlined"
                                  sx={{ height: 20, fontSize: '0.65rem' }}
                                />
                              </TableCell>
                              <TableCell>
                                <Chip
                                  size="small"
                                  icon={frame.direction === 'INBOUND' ? <CallReceivedIcon /> : <CallMadeIcon />}
                                  label={frame.direction === 'INBOUND' ? 'Inbound' : 'Outbound'}
                                  color={frame.direction === 'INBOUND' ? 'info' : 'default'}
                                  variant="outlined"
                                  sx={{ height: 20, fontSize: '0.65rem' }}
                                />
                              </TableCell>
                              <TableCell>
                                <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                                  {frame.requestMethod ?? '-'}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                                  {frame.requestPath ?? '-'}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography
                                  variant="caption"
                                  sx={{
                                    fontFamily: 'monospace',
                                    maxWidth: 200,
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                    display: 'block',
                                  }}
                                >
                                  {frame.bodyPreview ?? '-'}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography variant="caption">
                                  {frame.bodyLength}B
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography variant="caption">
                                  {formatAge(frame.ageMillis)}
                                </Typography>
                              </TableCell>
                              <TableCell align="right">
                                <Box sx={{ display: 'flex', gap: 0.5, justifyContent: 'flex-end' }}>
                                  <Tooltip title="Continue (write frame unchanged)">
                                    <span>
                                      <IconButton
                                        size="small"
                                        color="success"
                                        disabled={busy}
                                        onClick={() => void handleStreamContinue(frame.frameId)}
                                        aria-label={`Continue ${frame.frameId}`}
                                      >
                                        <PlayArrowIcon fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                  <Tooltip title="Modify frame body">
                                    <span>
                                      <IconButton
                                        size="small"
                                        color="info"
                                        disabled={busy}
                                        onClick={() => openStreamModifyDialog(frame)}
                                        aria-label={`Modify ${frame.frameId}`}
                                      >
                                        <EditIcon fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                  <Tooltip title="Drop (discard frame)">
                                    <span>
                                      <IconButton
                                        size="small"
                                        color="error"
                                        disabled={busy}
                                        onClick={() => void handleStreamDrop(frame.frameId)}
                                        aria-label={`Drop ${frame.frameId}`}
                                      >
                                        <DeleteIcon fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                  <Tooltip title="Inject extra frame after this one">
                                    <span>
                                      <IconButton
                                        size="small"
                                        color="primary"
                                        disabled={busy}
                                        onClick={() => openStreamInjectDialog(frame)}
                                        aria-label={`Inject ${frame.frameId}`}
                                      >
                                        <AddIcon fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                  <Tooltip title="Close stream">
                                    <span>
                                      <IconButton
                                        size="small"
                                        color="warning"
                                        disabled={busy}
                                        onClick={() => void handleStreamClose(frame.frameId)}
                                        aria-label={`Close ${frame.frameId}`}
                                      >
                                        <StopIcon fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                </Box>
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </AccordionDetails>
                </Accordion>
              ))
            )}
          </Paper>
        </>
      )}

      {/* Modify dialog (request/response breakpoints) */}
      <Dialog open={modifyTarget !== null} onClose={() => setModifyTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>
          {modifyTarget?.phase === 'RESPONSE' ? 'Modify Response' : 'Modify Request'}
        </DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {modifyTarget?.phase === 'RESPONSE'
              ? 'Edit the response JSON, then send the modified response.'
              : 'Edit the request JSON, then send the modified request.'}
          </Typography>
          {modifyError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {modifyError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={6}
            maxRows={20}
            fullWidth
            value={modifyJson}
            onChange={(e) => setModifyJson(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setModifyTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={() => void handleModifySubmit()}>
            Send Modified
          </Button>
        </DialogActions>
      </Dialog>

      {/* Stream frame modify dialog */}
      <Dialog open={streamModifyTarget !== null} onClose={() => setStreamModifyTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Modify Stream Frame</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Edit the frame body, then send the modified frame.
          </Typography>
          {streamModifyError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {streamModifyError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={4}
            maxRows={16}
            fullWidth
            value={streamModifyBody}
            onChange={(e) => setStreamModifyBody(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setStreamModifyTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={() => void handleStreamModifySubmit()}>
            Send Modified Frame
          </Button>
        </DialogActions>
      </Dialog>

      {/* Stream frame inject dialog */}
      <Dialog open={streamInjectTarget !== null} onClose={() => setStreamInjectTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Inject Extra Frame</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Enter the body for the extra frame to inject after the held frame.
          </Typography>
          {streamInjectError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {streamInjectError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={4}
            maxRows={16}
            fullWidth
            value={streamInjectBody}
            onChange={(e) => setStreamInjectBody(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setStreamInjectTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={() => void handleStreamInjectSubmit()}>
            Inject Frame
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
