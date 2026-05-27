import { useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import DownloadIcon from '@mui/icons-material/Download';
import { CassetteManagerBody } from './CassetteManager';
import { CompareRunsBody } from './CompareRunsDialog';
import type { ConnectionParams } from '../hooks/useConnectionParams';

// ---------------------------------------------------------------------------
// Export sub-tab — download captured content in various formats
// ---------------------------------------------------------------------------

// Two axes: what to export (scope) and the file format. Every combination
// the server supports is enumerated explicitly so the dropdown order matches
// the user's mental hierarchy ("first pick the scope, then the format").

type ExportScope = 'expectations' | 'requests';
type ExportFormat = 'json' | 'har' | 'openapi' | 'postman' | 'bruno';

interface ExportOption {
  value: string; // composite "<scope>:<format>"
  scope: ExportScope;
  format: ExportFormat;
  label: string;
  description: string;
  filename: string;
}

const SCOPE_TO_TYPE: Record<ExportScope, string> = {
  expectations: 'ACTIVE_EXPECTATIONS',
  requests: 'REQUEST_RESPONSES',
};

const EXPORT_OPTIONS: ExportOption[] = [
  // -------- Active expectations (matchers you've registered) --------
  {
    value: 'expectations:json',
    scope: 'expectations',
    format: 'json',
    label: 'Active expectations · MockServer JSON',
    description: 'Round-trippable JSON of every registered expectation. Re-import via PUT /mockserver/expectation.',
    filename: 'mockserver-expectations.json',
  },
  {
    value: 'expectations:openapi',
    scope: 'expectations',
    format: 'openapi',
    label: 'Active expectations · OpenAPI 3 spec',
    description: 'One operation per (method, path) with the registered response body as an example.',
    filename: 'mockserver-expectations.openapi.json',
  },
  {
    value: 'expectations:postman',
    scope: 'expectations',
    format: 'postman',
    label: 'Active expectations · Postman collection v2.1',
    description: 'Postman collection of every expectation as a request item with example response.',
    filename: 'mockserver-expectations.postman.json',
  },
  {
    value: 'expectations:bruno',
    scope: 'expectations',
    format: 'bruno',
    label: 'Active expectations · Bruno collection (.zip)',
    description: 'Bruno collection — one .bru file per expectation, packaged as a zip archive.',
    filename: 'mockserver-expectations.bruno.zip',
  },
  {
    value: 'expectations:har',
    scope: 'expectations',
    format: 'har',
    label: 'Active expectations · HAR archive',
    description: 'HAR-formatted archive of each expectation as a synthetic request/response pair.',
    filename: 'mockserver-expectations.har',
  },
  // -------- Recorded requests (traffic MockServer has handled) --------
  {
    value: 'requests:json',
    scope: 'requests',
    format: 'json',
    label: 'Recorded requests · MockServer JSON',
    description: 'Every request/response pair MockServer has logged, as a JSON array.',
    filename: 'mockserver-traffic.json',
  },
  {
    value: 'requests:har',
    scope: 'requests',
    format: 'har',
    label: 'Recorded requests · HAR (HTTP Archive)',
    description: 'Browser-tooling-compatible archive of every captured request/response pair.',
    filename: 'mockserver-traffic.har',
  },
  {
    value: 'requests:openapi',
    scope: 'requests',
    format: 'openapi',
    label: 'Recorded requests · OpenAPI 3 spec',
    description: 'OpenAPI spec derived from observed traffic; useful for capturing a real API shape.',
    filename: 'mockserver-traffic.openapi.json',
  },
  {
    value: 'requests:postman',
    scope: 'requests',
    format: 'postman',
    label: 'Recorded requests · Postman collection v2.1',
    description: 'Postman collection where each item is a captured request with its observed response.',
    filename: 'mockserver-traffic.postman.json',
  },
  {
    value: 'requests:bruno',
    scope: 'requests',
    format: 'bruno',
    label: 'Recorded requests · Bruno collection (.zip)',
    description: 'Bruno collection (one .bru per captured request) packaged as a zip archive.',
    filename: 'mockserver-traffic.bruno.zip',
  },
];

function retrievePath(option: ExportOption): string {
  const type = SCOPE_TO_TYPE[option.scope];
  return `/mockserver/retrieve?type=${type}&format=${option.format.toUpperCase()}`;
}

function ExportTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [selection, setSelection] = useState<string>('requests:har');
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const option = EXPORT_OPTIONS.find((o) => o.value === selection)!;

  const handleDownload = useCallback(async () => {
    setDownloading(true);
    setError(null);
    try {
      const protocol = connectionParams.secure ? 'https' : 'http';
      const base = `${protocol}://${connectionParams.host}:${connectionParams.port}`;
      const res = await fetch(`${base}${retrievePath(option)}`, { method: 'PUT' });
      if (!res.ok) throw new Error(`MockServer returned ${res.status}: ${res.statusText}`);
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = option.filename;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(objectUrl);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setDownloading(false);
    }
  }, [option, connectionParams]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
      <Typography variant="body2" color="text.secondary">
        Download captured traffic or registered expectations as a file. The file is
        produced by the running MockServer instance — what you see in the dashboard
        is what you get.
      </Typography>
      <TextField
        label="Export"
        size="small"
        select
        value={selection}
        onChange={(e) => setSelection(e.target.value)}
        helperText={option.description}
      >
        {EXPORT_OPTIONS.map((o) => (
          <MenuItem key={o.value} value={o.value}>
            {o.label}
          </MenuItem>
        ))}
      </TextField>
      <Box>
        <Button
          variant="contained"
          size="small"
          startIcon={<DownloadIcon sx={{ fontSize: '0.875rem' }} />}
          onClick={() => void handleDownload()}
          disabled={downloading}
        >
          {downloading ? 'Downloading…' : `Download ${option.filename}`}
        </Button>
      </Box>
      {error && <Alert severity="error" variant="outlined">{error}</Alert>}
      <Alert severity="info" variant="outlined" sx={{ fontSize: '0.8rem' }}>
        OpenAPI / Postman / Bruno exports are best-effort. Positive-string matchers
        round-trip cleanly; NottableString negation, regex bodies, and dynamic actions
        (forward / template / callback / error / LLM) are exported as placeholders
        because those formats describe client requests + example responses, not the
        full MockServer expectation graph.
      </Alert>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main view — tab strip across Cassettes / Runs / Export
// ---------------------------------------------------------------------------

export interface LibraryViewProps {
  connectionParams: ConnectionParams;
}

const TABS = ['Cassettes', 'Runs', 'Export'];

export default function LibraryView({ connectionParams }: LibraryViewProps) {
  const [tab, setTab] = useState(0);

  return (
    <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', p: 1 }}>
      <Paper variant="outlined" sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <Tabs
          value={tab}
          onChange={(_, v: number) => setTab(v)}
          sx={{ borderBottom: 1, borderColor: 'divider', minHeight: 36, '& .MuiTab-root': { minHeight: 36, py: 0.5, fontSize: '0.8rem' } }}
        >
          {TABS.map((label) => (
            <Tab key={label} label={label} />
          ))}
        </Tabs>
        <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }}>
          {tab === 0 && <CassetteManagerBody connectionParams={connectionParams} />}
          {tab === 1 && <CompareRunsBody />}
          {tab === 2 && <ExportTab connectionParams={connectionParams} />}
        </Box>
      </Paper>
    </Box>
  );
}
