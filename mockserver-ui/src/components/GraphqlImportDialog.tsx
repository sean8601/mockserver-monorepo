import { useCallback, useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import { importGraphql } from '../lib/graphqlImport';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { monospaceFontFamily } from '../theme';
import { useDashboardStore } from '../store';
import type { ConnectionParams } from '../hooks/useConnectionParams';

interface GraphqlImportDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

/**
 * Dialog to import a GraphQL schema via PUT /mockserver/graphql, generating one mock
 * expectation per root operation type (query / mutation / subscription) the schema
 * defines — a sibling of the OpenAPI and WSDL importers.
 *
 * The schema is pasted inline as SDL text or an introspection JSON result; the server
 * reads the request body verbatim as the schema (it has no remote-URL fetch for
 * GraphQL, unlike OpenAPI). An optional path field selects the request path the
 * generated mocks match (the server defaults to `/graphql` when blank).
 */
export default function GraphqlImportDialog({ open, onClose, connectionParams }: GraphqlImportDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const setNotification = useDashboardStore((s) => s.setNotification);
  const [schema, setSchema] = useState('');
  const [path, setPath] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);

  const handleImport = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const created = await importGraphql(connectionParams, { schema: schema.trim(), path });
      setNotification({
        message: `Imported ${created.length} GraphQL expectation${created.length === 1 ? '' : 's'}.`,
        severity: 'success',
      });
      onClose();
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, schema, path, setNotification, onClose]);

  const handleClose = useCallback(() => {
    setSchema('');
    setPath('');
    setError(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="graphql-dialog-title">
      <DialogTitle id="graphql-dialog-title">Import GraphQL schema</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Paste a GraphQL schema as SDL text or an introspection JSON result. MockServer generates
          one mock expectation per root operation type (query, mutation, subscription) the schema
          defines; each synthesises a schema-valid response from the actual query at request time.
        </Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <TextField
            value={schema}
            onChange={(e) => setSchema(e.target.value)}
            label="GraphQL schema (SDL or introspection JSON)"
            multiline
            minRows={10}
            fullWidth
            spellCheck={false}
            slotProps={{ input: { sx: { fontFamily: monospaceFontFamily } } }}
          />
          <TextField
            size="small"
            label="Request path"
            placeholder="/graphql"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            helperText="The path the generated mocks match. Leave blank for the server default (/graphql)."
          />
        </Box>
        {error !== null && <HumanErrorAlert error={error} sx={{ mt: 1.5 }} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button
          variant="contained"
          onClick={() => void handleImport()}
          disabled={busy || schema.trim().length === 0}
        >
          {busy ? 'Importing…' : 'Import'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
