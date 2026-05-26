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

type ExportFormat = 'har' | 'expectations_json' | 'requests_json';

interface ExportFormatMeta {
  value: ExportFormat;
  label: string;
  description: string;
  filename: string;
  retrievePath: string;
}

const EXPORT_FORMATS: ExportFormatMeta[] = [
  {
    value: 'har',
    label: 'HAR (HTTP Archive)',
    description: 'Browser-tooling-compatible archive of every request/response pair MockServer has logged.',
    filename: 'mockserver-traffic.har',
    retrievePath: '/mockserver/retrieve?type=REQUEST_RESPONSES&format=HAR',
  },
  {
    value: 'expectations_json',
    label: 'Active expectations (JSON)',
    description: 'Every currently-registered expectation as a JSON array. Re-import via PUT /mockserver/expectation.',
    filename: 'mockserver-expectations.json',
    retrievePath: '/mockserver/retrieve?type=ACTIVE_EXPECTATIONS&format=JSON',
  },
  {
    value: 'requests_json',
    label: 'Recorded requests (JSON)',
    description: 'Every received request as a JSON array. Useful for replay / debugging.',
    filename: 'mockserver-requests.json',
    retrievePath: '/mockserver/retrieve?type=REQUESTS&format=JSON',
  },
];

function ExportTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [format, setFormat] = useState<ExportFormat>('har');
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const meta = EXPORT_FORMATS.find((f) => f.value === format)!;

  const handleDownload = useCallback(async () => {
    setDownloading(true);
    setError(null);
    try {
      const protocol = connectionParams.secure ? 'https' : 'http';
      const base = `${protocol}://${connectionParams.host}:${connectionParams.port}`;
      const res = await fetch(`${base}${meta.retrievePath}`, { method: 'PUT' });
      if (!res.ok) throw new Error(`MockServer returned ${res.status}: ${res.statusText}`);
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = meta.filename;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(objectUrl);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setDownloading(false);
    }
  }, [meta, connectionParams]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
      <Typography variant="body2" color="text.secondary">
        Download captured traffic or registered expectations as a file. The file is
        produced by the running MockServer instance — what you see in the dashboard
        is what you get.
      </Typography>
      <TextField
        label="Format"
        size="small"
        select
        value={format}
        onChange={(e) => setFormat(e.target.value as ExportFormat)}
        helperText={meta.description}
      >
        {EXPORT_FORMATS.map((f) => (
          <MenuItem key={f.value} value={f.value}>
            {f.label}
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
          {downloading ? 'Downloading…' : `Download ${meta.filename}`}
        </Button>
      </Box>
      {error && <Alert severity="error" variant="outlined">{error}</Alert>}
      <Alert severity="info" variant="outlined" sx={{ fontSize: '0.8rem' }}>
        OpenAPI and Postman exports are planned for a follow-up — they require additional
        codecs that derive a schema or collection from the captured traffic.
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
