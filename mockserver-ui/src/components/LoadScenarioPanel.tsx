import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Card from '@mui/material/Card';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Link from '@mui/material/Link';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import LinearProgress from '@mui/material/LinearProgress';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import EditIcon from '@mui/icons-material/Edit';
import RefreshIcon from '@mui/icons-material/Refresh';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchLoadScenario,
  startLoadScenario,
  stopLoadScenario,
  errorRate,
  formatElapsed,
  LoadScenarioError,
  type LoadScenarioDTO,
  type LoadScenarioStatus,
  type LoadStepDTO,
  type LoadRequestDTO,
  type SocketAddressDTO,
} from '../lib/loadScenario';
import { useDashboardStore } from '../store';
import HumanErrorAlert from './HumanErrorAlert';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import MetricsLineChart from './MetricsLineChart';

interface LoadScenarioPanelProps {
  connectionParams: ConnectionParams;
}

const RUNNING_POLL_MS = 1000;
const IDLE_POLL_MS = 5000;
const MAX_SAMPLES = 120;

const responsiveWidth = (px: number) => ({ width: { xs: '100%', sm: px } });
// Responsive grid for author-form field rows — fields fill available width and wrap
// uniformly via CSS Grid auto-fit (matching ServiceChaosPanel's CHAOS_GRID), so columns
// line up across rows instead of each row stretching its own items independently.
const FIELD_GRID = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 1, alignItems: 'start' } as const;

/** Parse a trimmed numeric field, or undefined when blank/NaN. */
function num(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

interface KeyValueRow { id: number; key: string; value: string; }

interface StepFormState {
  id: number;
  name: string;
  method: string;
  path: string;
  host: string;
  port: string;
  scheme: 'HTTP' | 'HTTPS';
  headers: KeyValueRow[];
  body: string;
  thinkTimeMs: string;
}

interface FormState {
  name: string;
  templateType: 'VELOCITY' | 'MUSTACHE';
  maxRequests: string;
  profileType: 'CONSTANT' | 'LINEAR';
  vus: string;
  startVus: string;
  endVus: string;
  durationMillis: string;
  iterationPacingMillis: string;
  labels: KeyValueRow[];
  steps: StepFormState[];
}

let idCounter = 1;
const nextId = () => idCounter++;

function emptyStep(): StepFormState {
  return {
    id: nextId(), name: '', method: 'GET', path: '/', host: '', port: '', scheme: 'HTTP', headers: [], body: '', thinkTimeMs: '',
  };
}

const EMPTY_FORM: FormState = {
  name: '',
  templateType: 'VELOCITY',
  maxRequests: '',
  profileType: 'CONSTANT',
  vus: '5',
  startVus: '1',
  endVus: '10',
  durationMillis: '30000',
  iterationPacingMillis: '',
  labels: [],
  steps: [emptyStep()],
};

/** Build a LoadScenarioDTO from the form, or return a validation message. */
function buildScenario(form: FormState): { scenario: LoadScenarioDTO } | { error: string } {
  if (form.name.trim() === '') return { error: 'Scenario name is required' };

  const durationMillis = num(form.durationMillis);
  if (durationMillis == null || durationMillis <= 0) return { error: 'Duration (ms) must be greater than 0' };

  const profile: LoadScenarioDTO['profile'] = { type: form.profileType, durationMillis };
  if (form.profileType === 'CONSTANT') {
    const vus = num(form.vus);
    if (vus == null || vus < 1) return { error: 'Virtual users (VUs) must be at least 1' };
    profile.vus = vus;
  } else {
    const startVus = num(form.startVus);
    const endVus = num(form.endVus);
    if (startVus == null || startVus < 0) return { error: 'Start VUs must be 0 or greater' };
    if (endVus == null || endVus < 1) return { error: 'End VUs must be at least 1' };
    profile.startVus = startVus;
    profile.endVus = endVus;
  }
  const pacing = num(form.iterationPacingMillis);
  if (pacing != null) {
    if (pacing < 0) return { error: 'Iteration pacing (ms) must be 0 or greater' };
    profile.iterationPacingMillis = pacing;
  }

  if (form.steps.length === 0) return { error: 'At least one step is required' };
  const steps: LoadStepDTO[] = [];
  for (let i = 0; i < form.steps.length; i++) {
    const s = form.steps[i]!;
    if (s.host.trim() === '') return { error: `Step ${i + 1}: target host is required` };
    const port = num(s.port);
    if (port == null || port < 1 || port > 65535) return { error: `Step ${i + 1}: target port must be 1–65535` };
    if (s.path.trim() === '') return { error: `Step ${i + 1}: path is required` };

    const socketAddress: SocketAddressDTO = { host: s.host.trim(), port, scheme: s.scheme };
    const step: LoadStepDTO = {
      request: { method: s.method.trim() || 'GET', path: s.path.trim(), socketAddress },
    };
    const headers = headerRowsToMultiValue(s.headers);
    if (headers) step.request.headers = headers;
    if (s.body.trim() !== '') step.request.body = s.body;
    if (s.name.trim() !== '') step.name = s.name.trim();
    const thinkTimeMs = num(s.thinkTimeMs);
    if (thinkTimeMs != null) {
      if (thinkTimeMs < 0) return { error: `Step ${i + 1}: think time (ms) must be 0 or greater` };
      step.thinkTime = { timeUnit: 'MILLISECONDS', value: thinkTimeMs };
    }
    steps.push(step);
  }

  const scenario: LoadScenarioDTO = {
    name: form.name.trim(),
    templateType: form.templateType,
    profile,
    steps,
  };
  const maxRequests = num(form.maxRequests);
  if (maxRequests != null) {
    if (maxRequests < 1) return { error: 'Max requests must be at least 1 (or leave blank for unlimited)' };
    scenario.maxRequests = maxRequests;
  }
  const labels = labelRowsToObject(form.labels);
  if (labels) scenario.labels = labels;

  return { scenario };
}

function labelRowsToObject(rows: KeyValueRow[]): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key !== '') out[key] = row.value;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Convert the per-step header rows into MockServer's `KeyToMultiValue` object-map form
 * (`{ "Name": ["value"] }`), collecting repeated names into a single multi-value entry.
 * Returns undefined when no non-empty header name exists so the request body stays minimal.
 */
function headerRowsToMultiValue(rows: KeyValueRow[]): Record<string, string[]> | undefined {
  const out: Record<string, string[]> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key === '') continue;
    (out[key] ??= []).push(row.value);
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Read a step's request `headers` back into editor rows. Handles both MockServer
 * `KeyToMultiValue` shapes the server may echo: the object map `{ "Name": ["v1", "v2"] }`
 * and the array form `[{ name, values: ["v1"] }]`. Each value becomes its own row so the
 * round-trip through `headerRowsToMultiValue` is faithful.
 */
function headersToRows(headers: LoadRequestDTO['headers']): KeyValueRow[] {
  if (headers == null) return [];
  const rows: KeyValueRow[] = [];
  const push = (key: string, values: unknown) => {
    if (Array.isArray(values)) {
      for (const v of values) rows.push({ id: nextId(), key, value: String(v) });
    } else if (values != null) {
      rows.push({ id: nextId(), key, value: String(values) });
    }
  };
  if (Array.isArray(headers)) {
    for (const entry of headers as Array<{ name?: unknown; values?: unknown }>) {
      if (entry && typeof entry.name === 'string') push(entry.name, entry.values);
    }
  } else {
    for (const [key, values] of Object.entries(headers as Record<string, unknown>)) {
      if (key === 'keyMatchStyle') continue;
      push(key, values);
    }
  }
  return rows;
}

/** Reverse buildScenario: load a running/most-recent scenario back into the editor. */
function scenarioToForm(scenario: LoadScenarioDTO): FormState {
  const p = scenario.profile;
  return {
    name: scenario.name,
    templateType: scenario.templateType ?? 'VELOCITY',
    maxRequests: scenario.maxRequests != null ? String(scenario.maxRequests) : '',
    profileType: p.type,
    vus: p.vus != null ? String(p.vus) : '5',
    startVus: p.startVus != null ? String(p.startVus) : '1',
    endVus: p.endVus != null ? String(p.endVus) : '10',
    durationMillis: String(p.durationMillis),
    iterationPacingMillis: p.iterationPacingMillis != null ? String(p.iterationPacingMillis) : '',
    labels: Object.entries(scenario.labels ?? {}).map(([key, value]) => ({ id: nextId(), key, value })),
    steps: (scenario.steps.length > 0 ? scenario.steps : [{ request: {} }]).map((step) => ({
      id: nextId(),
      name: step.name ?? '',
      method: step.request.method ?? 'GET',
      path: step.request.path ?? '/',
      host: step.request.socketAddress?.host ?? '',
      port: step.request.socketAddress?.port != null ? String(step.request.socketAddress.port) : '',
      scheme: step.request.socketAddress?.scheme ?? 'HTTP',
      headers: headersToRows(step.request.headers),
      body: step.request.body ?? '',
      thinkTimeMs: step.thinkTime?.value != null ? String(step.thinkTime.value) : '',
    })),
  };
}

// --- Combined live chart series ---

interface Sample {
  at: number;
  requestsSent: number;
  succeeded: number;
  failed: number;
  currentVus: number;
  inFlight: number;
  p50: number;
  p95: number;
  p99: number;
}

type SeriesKey = 'rps' | 'vus' | 'inFlight' | 'p50' | 'p95' | 'p99' | 'errorRate';

interface SeriesDef {
  key: SeriesKey;
  label: string;
  /** axis group: latencies share a "ms" formatter; counts/rates are plain. */
  format: (v: number) => string;
  /** derive this series' value array from the accumulated samples. */
  derive: (samples: Sample[]) => number[];
}

/** RPS for each sample as Δsent / Δt against the previous sample (first point = 0). */
function ratePerSecond(samples: Sample[]): number[] {
  return samples.map((s, i) => {
    if (i === 0) return 0;
    const prev = samples[i - 1]!;
    const dt = (s.at - prev.at) / 1000;
    if (dt <= 0) return 0;
    return Math.max(0, (s.requestsSent - prev.requestsSent) / dt);
  });
}

const SERIES_DEFS: SeriesDef[] = [
  { key: 'rps', label: 'RPS', format: (v) => `${v.toFixed(1)}/s`, derive: ratePerSecond },
  { key: 'vus', label: 'Active VUs', format: (v) => Math.round(v).toLocaleString(), derive: (s) => s.map((x) => x.currentVus) },
  { key: 'inFlight', label: 'In-flight', format: (v) => Math.round(v).toLocaleString(), derive: (s) => s.map((x) => x.inFlight) },
  { key: 'p50', label: 'p50 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p50) },
  { key: 'p95', label: 'p95 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p95) },
  { key: 'p99', label: 'p99 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p99) },
  { key: 'errorRate', label: 'Error rate %', format: (v) => `${v.toFixed(1)}%`, derive: (s) => s.map((x) => (x.requestsSent > 0 ? (x.failed / x.requestsSent) * 100 : 0)) },
];

// Default-visible subset — RPS + p95 + active VUs (per the brief).
const DEFAULT_VISIBLE: SeriesKey[] = ['rps', 'p95', 'vus'];

export default function LoadScenarioPanel({ connectionParams }: LoadScenarioPanelProps) {
  const setView = useDashboardStore((s) => s.setView);

  const [status, setStatus] = useState<LoadScenarioStatus | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<HumanError | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);

  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [visible, setVisible] = useState<Set<SeriesKey>>(() => new Set(DEFAULT_VISIBLE));
  // The runId whose samples are currently accumulated — reset when a new run starts.
  const sampleRunId = useRef<string | null>(null);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Poll the load scenario status. Fast (1s) while running, slower when idle.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetchLoadScenario(connectionParams, controller.signal);
        if (cancelled) return;
        setStatus(result);
        setDisabled(false);
        setLoadError(null);
        // Accumulate a sample only while a run is active and identifiable.
        if (result.state === 'running' && result.runId) {
          setSamples((prev) => {
            const fresh = sampleRunId.current !== result.runId;
            if (fresh) sampleRunId.current = result.runId ?? null;
            const base = fresh ? [] : prev;
            const sent = result.requestsSent ?? 0;
            const succeeded = result.succeeded ?? 0;
            const failed = result.failed ?? 0;
            const sample: Sample = {
              at: Date.now(),
              requestsSent: sent,
              succeeded,
              failed,
              currentVus: result.currentVus ?? 0,
              // in-flight ≈ requests dispatched but not yet completed.
              inFlight: Math.max(0, sent - succeeded - failed),
              p50: result.p50Millis ?? 0,
              p95: result.p95Millis ?? 0,
              p99: result.p99Millis ?? 0,
            };
            return [...base, sample].slice(-MAX_SAMPLES);
          });
        }
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        if (e instanceof LoadScenarioError && e.status === 403) {
          setDisabled(true);
          setLoadError(null);
        } else {
          setLoadError(e instanceof Error ? e.message : String(e));
        }
      } finally {
        if (!cancelled) {
          const running = !cancelled && status?.state === 'running';
          timer = setTimeout(() => void poll(), running ? RUNNING_POLL_MS : IDLE_POLL_MS);
        }
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
    // status?.state intentionally drives the poll cadence (running → 1s).
  }, [connectionParams, refreshTick, status?.state]);

  const running = status?.state === 'running';
  const showSummary = status != null && (status.state === 'completed' || status.state === 'stopped');

  const runAction = useCallback(async (action: () => Promise<void>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      refresh();
    } catch (e) {
      if (e instanceof LoadScenarioError && e.status === 403) {
        setDisabled(true);
      } else {
        setActionError(humanizeError(e));
      }
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const handleStop = useCallback(() => {
    void runAction(async () => {
      await stopLoadScenario(connectionParams);
    });
  }, [connectionParams, runAction]);

  // Fallback for "Edit running" when the server does not echo a definition (older servers):
  // remember the last scenario this tab submitted so its full config can still be reloaded.
  const lastSubmitted = useRef<LoadScenarioDTO | null>(null);

  // Load the running scenario's config back into the editor for tweak-and-restart. Prefer the
  // server-echoed definition (works for ANY run — a client's or another tab's), and fall back to
  // this tab's last-submitted scenario only when the server did not provide one.
  const handleEditRunning = useCallback(() => {
    const definition = status?.definition ?? lastSubmitted.current;
    if (definition) {
      setForm(scenarioToForm(definition));
    }
    setActionError(null);
  }, [status?.definition]);

  // Validate + start, recording the submitted scenario so "Edit running" can reload it.
  const handleStartAndRemember = useCallback(() => {
    const built = buildScenario(form);
    if ('error' in built) {
      setActionError({ message: built.error });
      return;
    }
    lastSubmitted.current = built.scenario;
    void runAction(async () => {
      await startLoadScenario(connectionParams, built.scenario);
      setSamples([]);
      sampleRunId.current = null;
    });
  }, [connectionParams, form, runAction]);

  // --- form field setters ---
  const setField = <K extends keyof FormState>(field: K) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const setStepField = (index: number, field: keyof StepFormState) =>
    (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === index ? { ...s, [field]: e.target.value } : s)) }));

  const addStep = () => setForm((prev) => ({ ...prev, steps: [...prev.steps, emptyStep()] }));
  const removeStep = (index: number) => setForm((prev) => ({ ...prev, steps: prev.steps.filter((_, i) => i !== index) }));

  const updateStepHeaders = (stepIndex: number, fn: (headers: KeyValueRow[]) => KeyValueRow[]) =>
    setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === stepIndex ? { ...s, headers: fn(s.headers) } : s)) }));
  const addStepHeader = (stepIndex: number) =>
    updateStepHeaders(stepIndex, (headers) => [...headers, { id: nextId(), key: '', value: '' }]);
  const removeStepHeader = (stepIndex: number, headerIndex: number) =>
    updateStepHeaders(stepIndex, (headers) => headers.filter((_, i) => i !== headerIndex));
  const setStepHeaderField = (stepIndex: number, headerIndex: number, field: 'key' | 'value') =>
    (e: ChangeEvent<HTMLInputElement>) =>
      updateStepHeaders(stepIndex, (headers) => headers.map((h, i) => (i === headerIndex ? { ...h, [field]: e.target.value } : h)));

  const addLabel = () => setForm((prev) => ({ ...prev, labels: [...prev.labels, { id: nextId(), key: '', value: '' }] }));
  const removeLabel = (index: number) => setForm((prev) => ({ ...prev, labels: prev.labels.filter((_, i) => i !== index) }));
  const setLabelField = (index: number, field: 'key' | 'value') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, labels: prev.labels.map((l, i) => (i === index ? { ...l, [field]: e.target.value } : l)) }));

  const toggleSeries = (key: SeriesKey) =>
    setVisible((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  // Build the chart series from the accumulated samples, only for visible keys.
  const chartSeries = useMemo(
    () => SERIES_DEFS.filter((d) => visible.has(d.key)).map((d) => ({ label: d.label, data: d.derive(samples) })),
    [samples, visible],
  );
  const chartTimestamps = useMemo(() => samples.map((s) => s.at), [samples]);

  const statusColor = running ? 'success' : status?.state === 'completed' ? 'info' : status?.state === 'stopped' ? 'warning' : 'default';

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }} data-testid="load-scenario-panel">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Performance — Load Scenarios</Typography>
        <Chip size="small" label={status?.state ?? 'none'} color={statusColor} variant="outlined" />
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh load scenario status">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {disabled && (
        <Alert severity="info" sx={{ mb: 1.5 }} data-testid="load-disabled-alert">
          <AlertTitle>Load generation is disabled</AlertTitle>
          Load scenarios are off by default. Start MockServer with load generation enabled to drive
          traffic from here:
          <Box component="pre" sx={{ mt: 1, mb: 1, p: 1, bgcolor: 'action.hover', borderRadius: 1, typography: 'subtitle2', fontWeight: 400, overflow: 'auto' }}>
{`-Dmockserver.loadGenerationEnabled=true
# or environment variable:
MOCKSERVER_LOAD_GENERATION_ENABLED=true`}
          </Box>
          For deeper analysis, the{' '}
          <Link component="button" type="button" onClick={() => setView('metrics')}>Metrics tab</Link>{' '}
          surfaces the <code>mock_server_load_*</code> Prometheus/OTEL series. See the{' '}
          <Link href="https://www.mock-server.com/mock_server/load_injection.html" target="_blank" rel="noopener noreferrer">
            load injection docs
          </Link>.
        </Alert>
      )}

      {loadError && !disabled && (
        <Alert severity="error" sx={{ mb: 1.5 }} action={
          <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
        }>
          <AlertTitle>Could not load scenario status</AlertTitle>
          {loadError}
        </Alert>
      )}

      {actionError && (
        <HumanErrorAlert error={actionError} onClose={() => setActionError(null)} sx={{ mb: 1.5 }} data-testid="load-action-error" />
      )}

      {/* Live status while a run is active */}
      {running && status && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-live-status">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
            <Typography variant="caption" color="text.secondary">
              Running{status.name ? ` · ${status.name}` : ''}{status.runId ? ` · run ${status.runId}` : ''}
            </Typography>
            <Chip size="small" label={`${formatElapsed(status.elapsedMillis ?? 0)} elapsed`} variant="outlined" />
            <Box sx={{ flex: 1 }} />
            <Button size="small" variant="outlined" startIcon={<EditIcon />} disabled={busy} onClick={handleEditRunning}>
              Edit running
            </Button>
            <Button size="small" color="error" variant="contained" startIcon={<StopIcon />} disabled={busy} onClick={handleStop}>
              Stop
            </Button>
          </Box>
          <LinearProgress sx={{ mb: 1 }} />
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 1 }}>
            <Stat label="Active VUs" value={(status.currentVus ?? 0).toLocaleString()} />
            <Stat label="Requests sent" value={(status.requestsSent ?? 0).toLocaleString()} />
            <Stat label="Succeeded" value={(status.succeeded ?? 0).toLocaleString()} />
            <Stat label="Failed" value={(status.failed ?? 0).toLocaleString()} />
            <Stat label="Error rate" value={`${(errorRate(status) * 100).toFixed(1)}%`} />
            <Stat label="p50" value={`${(status.p50Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p95" value={`${(status.p95Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p99" value={`${(status.p99Millis ?? 0).toFixed(0)} ms`} />
          </Box>
          {status.labels && Object.keys(status.labels).length > 0 && (
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mt: 1 }}>
              {Object.entries(status.labels).map(([k, v]) => (
                <Chip key={k} size="small" variant="outlined" label={`${k}=${v}`} />
              ))}
            </Box>
          )}
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Re-submitting the form below (Start) replaces and restarts the active run.
          </Typography>
        </Paper>
      )}

      {/* Combined live chart with toggleable series */}
      {(running || samples.length > 0) && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-chart">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 0.5 }}>
            <Typography variant="caption" color="text.secondary">Live throughput &amp; latency</Typography>
          </Box>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mb: 0.5 }} role="group" aria-label="Chart series toggles">
            {SERIES_DEFS.map((d) => (
              <FormControlLabel
                key={d.key}
                sx={{ mr: 1 }}
                control={
                  <Checkbox
                    size="small"
                    checked={visible.has(d.key)}
                    onChange={() => toggleSeries(d.key)}
                  />
                }
                label={<Typography variant="caption">{d.label}</Typography>}
              />
            ))}
          </Box>
          {chartSeries.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
              Select at least one series to plot.
            </Typography>
          ) : (
            <MetricsLineChart series={chartSeries} timestamps={chartTimestamps} height={220} />
          )}
        </Paper>
      )}

      {/* Key metrics summary on stop/completion */}
      {showSummary && status && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-summary">
          <Typography variant="caption" color="text.secondary">
            {status.state === 'completed' ? 'Run complete' : 'Run stopped'} — key metrics
            {status.name ? ` · ${status.name}` : ''}
          </Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 1, mt: 1 }}>
            <Stat label="Requests sent" value={(status.requestsSent ?? 0).toLocaleString()} />
            <Stat label="Succeeded" value={(status.succeeded ?? 0).toLocaleString()} />
            <Stat label="Failed" value={(status.failed ?? 0).toLocaleString()} />
            <Stat label="Error rate" value={`${(errorRate(status) * 100).toFixed(1)}%`} />
            <Stat label="Peak VUs" value={peakVus(samples, status).toLocaleString()} />
            <Stat label="Throughput" value={`${throughput(status).toFixed(1)}/s`} />
            <Stat label="p50" value={`${(status.p50Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p95" value={`${(status.p95Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p99" value={`${(status.p99Millis ?? 0).toFixed(0)} ms`} />
          </Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Deeper analysis lives in the{' '}
            <Link component="button" type="button" onClick={() => setView('metrics')}>Metrics tab</Link>{' '}
            (the <code>mock_server_load_*</code> series).
          </Typography>
        </Paper>
      )}

      {/* Author form */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-author-form">
        <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
          {running ? 'Edit / restart scenario' : 'Create a load scenario'}
        </Typography>

        {/* Scenario-level fields in their own uniform grid (matching ServiceChaosPanel's
            CHAOS_GRID idiom). The profile fields follow as a separate labelled group using
            the same FIELD_GRID so column widths line up across both groups. */}
        <Box sx={FIELD_GRID}>
          <TextField
            label="Scenario name" size="small" required value={form.name}
            onChange={setField('name')} fullWidth
          />
          <TextField
            select label="Template type" size="small" value={form.templateType}
            onChange={(e) => setForm((p) => ({ ...p, templateType: e.target.value as FormState['templateType'] }))}
            helperText="Engine for rendering templated step path/body (whole scenario)"
            fullWidth
          >
            <MenuItem value="VELOCITY">Velocity</MenuItem>
            <MenuItem value="MUSTACHE">Mustache</MenuItem>
          </TextField>
          <TextField
            label="Max requests (optional)" size="small" value={form.maxRequests}
            onChange={setField('maxRequests')} fullWidth placeholder="unlimited"
          />
        </Box>

        {/* Profile */}
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, display: 'block', mt: 1.5, mb: 0.5 }}>Profile</Typography>
        <Box sx={FIELD_GRID}>
          <TextField
            select label="Profile type" size="small" value={form.profileType}
            onChange={(e) => setForm((p) => ({ ...p, profileType: e.target.value as FormState['profileType'] }))}
            fullWidth
          >
            <MenuItem value="CONSTANT">Constant</MenuItem>
            <MenuItem value="LINEAR">Linear ramp</MenuItem>
          </TextField>
          {form.profileType === 'CONSTANT' ? (
            <TextField label="Virtual users (VUs)" size="small" value={form.vus} onChange={setField('vus')} fullWidth />
          ) : (
            <>
              <TextField label="Start VUs" size="small" value={form.startVus} onChange={setField('startVus')} fullWidth />
              <TextField label="End VUs" size="small" value={form.endVus} onChange={setField('endVus')} fullWidth />
            </>
          )}
          <TextField label="Duration (ms)" size="small" value={form.durationMillis} onChange={setField('durationMillis')} fullWidth />
          <TextField label="Iteration pacing (ms)" size="small" value={form.iterationPacingMillis} onChange={setField('iterationPacingMillis')} fullWidth placeholder="none" />
        </Box>

        {/* Labels */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Labels (optional)</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addLabel}>Add label</Button>
        </Box>
        {form.labels.map((label, i) => (
          <Box key={label.id} sx={{ display: 'flex', gap: 1, mb: 0.5, alignItems: 'center', flexWrap: 'wrap' }}>
            <TextField label="Key" size="small" value={label.key} onChange={setLabelField(i, 'key')} sx={responsiveWidth(160)} />
            <TextField label="Value" size="small" value={label.value} onChange={setLabelField(i, 'value')} sx={responsiveWidth(160)} />
            <IconButton size="small" onClick={() => removeLabel(i)} aria-label={`Remove label ${i + 1}`}><DeleteIcon fontSize="small" /></IconButton>
          </Box>
        ))}

        {/* Steps */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Steps</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addStep}>Add step</Button>
        </Box>
        {form.steps.map((step, i) => (
          <Box key={step.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-step-${i}`}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">Step {i + 1}</Typography>
              <Box sx={{ flex: 1 }} />
              <IconButton size="small" onClick={() => removeStep(i)} aria-label={`Remove step ${i + 1}`} disabled={form.steps.length <= 1}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
            {/* All step fields flow into ONE uniform grid so columns line up
                instead of two independently-stretched sub-rows. */}
            <Box sx={FIELD_GRID}>
              <TextField label="Step name (optional)" size="small" value={step.name} onChange={setStepField(i, 'name')} fullWidth />
              <TextField select label="Method" size="small" value={step.method}
                onChange={(e) => setForm((p) => ({ ...p, steps: p.steps.map((s, j) => (j === i ? { ...s, method: e.target.value } : s)) }))}
                fullWidth>
                {['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map((m) => <MenuItem key={m} value={m}>{m}</MenuItem>)}
              </TextField>
              <TextField label="Path" size="small" value={step.path} onChange={setStepField(i, 'path')} fullWidth />
              <TextField label="Target host" size="small" value={step.host} onChange={setStepField(i, 'host')} fullWidth />
              <TextField label="Target port" size="small" value={step.port} onChange={setStepField(i, 'port')} fullWidth />
              <TextField select label="Scheme" size="small" value={step.scheme}
                onChange={(e) => setForm((p) => ({ ...p, steps: p.steps.map((s, j) => (j === i ? { ...s, scheme: e.target.value as 'HTTP' | 'HTTPS' } : s)) }))}
                fullWidth>
                <MenuItem value="HTTP">HTTP</MenuItem>
                <MenuItem value="HTTPS">HTTPS</MenuItem>
              </TextField>
              <TextField label="Delay after step (ms)" size="small" value={step.thinkTimeMs} onChange={setStepField(i, 'thinkTimeMs')} helperText="Wait this long after this step's request before the next step (a.k.a. think-time)" fullWidth placeholder="none" />
            </Box>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Request headers (optional)</Typography>
              <Button size="small" startIcon={<AddIcon />} onClick={() => addStepHeader(i)}>Add header</Button>
            </Box>
            {step.headers.map((header, h) => (
              <Box key={header.id} sx={{ display: 'flex', gap: 1, mb: 0.5, alignItems: 'center', flexWrap: 'wrap' }}>
                <TextField label="Header name" size="small" value={header.key} onChange={setStepHeaderField(i, h, 'key')} sx={responsiveWidth(180)} />
                <TextField label="Header value" size="small" value={header.value} onChange={setStepHeaderField(i, h, 'value')} sx={responsiveWidth(200)} />
                <IconButton size="small" onClick={() => removeStepHeader(i, h)} aria-label={`Remove header ${h + 1} of step ${i + 1}`}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Box>
            ))}
            <TextField
              label="Body (optional, templated)" size="small" value={step.body}
              onChange={setStepField(i, 'body')}
              fullWidth multiline minRows={2} sx={{ mt: 1 }}
            />
          </Box>
        ))}

        <Box sx={{ display: 'flex', gap: 1, mt: 1, flexWrap: 'wrap' }}>
          <Button
            variant="contained" color="primary" startIcon={<PlayArrowIcon />}
            disabled={busy || disabled} onClick={handleStartAndRemember}
          >
            {running ? 'Replace & restart' : 'Start load scenario'}
          </Button>
          {running && (
            <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>
              Start replaces the currently-running scenario.
            </Typography>
          )}
        </Box>
      </Paper>
    </Box>
  );
}

/** A small label/value stat cell used in the live + summary metric grids. */
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card elevation={0} sx={{ p: 1, bgcolor: 'action.hover' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2 }}>{value}</Typography>
    </Card>
  );
}

/** Peak active VUs observed across samples, falling back to the final status. */
function peakVus(samples: Sample[], status: LoadScenarioStatus): number {
  const fromSamples = samples.reduce((m, s) => Math.max(m, s.currentVus), 0);
  return Math.max(fromSamples, status.currentVus ?? 0);
}

/** Overall throughput (req/s) = requestsSent / elapsed seconds. */
function throughput(status: LoadScenarioStatus): number {
  const sent = status.requestsSent ?? 0;
  const seconds = (status.elapsedMillis ?? 0) / 1000;
  if (seconds <= 0) return 0;
  return sent / seconds;
}
