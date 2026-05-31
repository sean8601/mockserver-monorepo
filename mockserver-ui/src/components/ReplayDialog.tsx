import { useState, useCallback, useEffect, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import LinearProgress from '@mui/material/LinearProgress';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import Alert from '@mui/material/Alert';
import FormControlLabel from '@mui/material/FormControlLabel';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  startReplay,
  getReplayReport,
  type StartReplayBody,
  type ReplayReport,
} from '../lib/replay';

interface ReplayDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

const POLL_INTERVAL_MS = 2000;

export default function ReplayDialog({ open, onClose, connectionParams }: ReplayDialogProps) {
  const [ratePerSecond, setRatePerSecond] = useState('10');
  const [chaosEnabled, setChaosEnabled] = useState(false);
  const [chaosHost, setChaosHost] = useState('');
  const [errorStatus, setErrorStatus] = useState('');
  const [errorProbability, setErrorProbability] = useState('');
  const [latencyMs, setLatencyMs] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<ReplayReport | null>(null);
  const [replayId, setReplayId] = useState<string | null>(null);

  // Poll for replay progress once we have a replayId
  useEffect(() => {
    if (!replayId) return;
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const r = await getReplayReport(connectionParams, replayId!, controller.signal);
        if (cancelled) return;
        setReport(r);
        if (r.status === 'RUNNING') {
          timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
        } else {
          setBusy(false);
        }
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : String(e));
        setBusy(false);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, replayId]);

  const handleStart = useCallback(async () => {
    setError(null);
    setReport(null);
    setBusy(true);

    const rate = parseInt(ratePerSecond, 10);
    if (!Number.isFinite(rate) || rate < 1) {
      setError('Rate must be at least 1 request/second');
      setBusy(false);
      return;
    }

    const body: StartReplayBody = { ratePerSecond: rate };
    if (chaosEnabled) {
      const es = parseInt(errorStatus, 10);
      const ep = parseFloat(errorProbability);
      const lm = parseInt(latencyMs, 10);
      const overlay: StartReplayBody['chaosProfile'] = {};
      if (Number.isFinite(es) && es >= 100 && es <= 599) {
        overlay.errorStatus = es;
        if (Number.isFinite(ep) && ep >= 0 && ep <= 1) {
          overlay.errorProbability = ep;
        }
      }
      if (Number.isFinite(lm) && lm > 0) {
        overlay.latency = { timeUnit: 'MILLISECONDS', value: lm };
      }
      if (Object.keys(overlay).length > 0) {
        body.chaosProfile = overlay;
      }
    }

    try {
      const result = await startReplay(connectionParams, body);
      setReplayId(result.replayId);
      setReport({
        replayId: result.replayId,
        status: result.status,
        totalRequests: result.totalRequests,
        completedRequests: 0,
        successCount: 0,
        failureCount: 0,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  }, [connectionParams, ratePerSecond, chaosEnabled, errorStatus, errorProbability, latencyMs]);

  // Reset local state on close so reopening starts fresh (avoids set-state-in-effect).
  const handleClose = useCallback(() => {
    setError(null);
    setReport(null);
    setReplayId(null);
    setBusy(false);
    onClose();
  }, [onClose]);

  const isCompleted = report != null && report.status !== 'RUNNING';
  const progressPct = report && report.totalRequests > 0
    ? (report.completedRequests / report.totalRequests) * 100
    : 0;

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Replay Recorded Traffic</DialogTitle>
      <DialogContent>
        {!replayId && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Replays all recorded proxy traffic at the specified rate. Optionally overlay
              HTTP chaos faults during the replay.
            </Typography>
            <TextField
              size="small"
              label="Rate (requests/sec)"
              type="number"
              value={ratePerSecond}
              onChange={(e: ChangeEvent<HTMLInputElement>) => setRatePerSecond(e.target.value)}
              sx={{ maxWidth: 200 }}
            />
            <FormControlLabel
              control={
                <Switch
                  checked={chaosEnabled}
                  onChange={(e) => setChaosEnabled(e.target.checked)}
                  size="small"
                />
              }
              label="Enable chaos overlay"
            />
            {chaosEnabled && (
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                <TextField
                  size="small"
                  label="Chaos host"
                  placeholder="upstream.svc"
                  value={chaosHost}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => setChaosHost(e.target.value)}
                  sx={{ minWidth: 160 }}
                />
                <TextField
                  size="small"
                  label="Error status"
                  placeholder="503"
                  value={errorStatus}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => setErrorStatus(e.target.value)}
                  sx={{ width: 110 }}
                />
                <TextField
                  size="small"
                  label="Error prob"
                  placeholder="0.5"
                  value={errorProbability}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => setErrorProbability(e.target.value)}
                  sx={{ width: 100 }}
                />
                <TextField
                  size="small"
                  label="Latency ms"
                  placeholder="250"
                  value={latencyMs}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => setLatencyMs(e.target.value)}
                  sx={{ width: 100 }}
                />
              </Box>
            )}
          </Box>
        )}

        {replayId && report && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, mt: 1 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
              <Chip
                size="small"
                label={report.status}
                color={report.status === 'COMPLETED' ? 'success' : report.status === 'FAILED' ? 'error' : 'info'}
              />
              <Typography variant="body2" color="text.secondary">
                {report.completedRequests} / {report.totalRequests} requests
              </Typography>
            </Box>
            <LinearProgress
              variant="determinate"
              value={progressPct}
              sx={{ height: 8, borderRadius: 1 }}
            />
            {isCompleted && (
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                <Chip size="small" label={`${report.successCount} success`} color="success" variant="outlined" />
                <Chip size="small" label={`${report.failureCount} failures`} color={report.failureCount > 0 ? 'error' : 'default'} variant="outlined" />
              </Box>
            )}
          </Box>
        )}

        {error && (
          <Alert severity="error" sx={{ mt: 1.5 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} size="small">
          {isCompleted ? 'Close' : 'Cancel'}
        </Button>
        {!replayId && (
          <Button
            variant="contained"
            size="small"
            disabled={busy}
            onClick={() => void handleStart()}
          >
            Start Replay
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
