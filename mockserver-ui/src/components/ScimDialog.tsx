import { useCallback, useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Switch from '@mui/material/Switch';
import FormControlLabel from '@mui/material/FormControlLabel';
import Button from '@mui/material/Button';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { createScimProvider, type ScimConfig, type ScimIdStrategy } from '../lib/scim';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { useDashboardStore } from '../store';

/**
 * Dialog to register a mock SCIM 2.0 provider via PUT /mockserver/scim — CRUD over
 * Users and Groups plus the SCIM discovery endpoints — as a set of expectations.
 * Like the OIDC and SAML provider dialogs, every field is optional: the server
 * supplies defaults, so an empty form produces a fully functional provider serving
 * `/scim/v2`.
 */
export default function ScimDialog({
  open,
  onClose,
  connectionParams,
}: {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const setNotification = useDashboardStore((s) => s.setNotification);
  const [basePath, setBasePath] = useState('');
  // Empty = use the server default (UUID); otherwise an explicit strategy.
  const [idStrategy, setIdStrategy] = useState<'' | ScimIdStrategy>('');
  const [enforceFilter, setEnforceFilter] = useState(true);
  const [enforcePatch, setEnforcePatch] = useState(true);
  const [requireBearerToken, setRequireBearerToken] = useState(false);
  const [expectedBearerToken, setExpectedBearerToken] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);

  const submit = useCallback(async () => {
    setBusy(true);
    setError(null);
    // Only send fields the user changed from the server default; blanks/defaults
    // are omitted so the server applies its own defaults.
    const config: ScimConfig = {};
    if (basePath.trim()) config.basePath = basePath.trim();
    if (idStrategy) config.idStrategy = idStrategy;
    // enforceFilter/enforcePatch default to true on the server; only send when toggled off.
    if (!enforceFilter) config.enforceFilter = false;
    if (!enforcePatch) config.enforcePatch = false;
    if (requireBearerToken) {
      config.requireBearerToken = true;
      if (expectedBearerToken.trim()) config.expectedBearerToken = expectedBearerToken.trim();
    }
    try {
      const created = await createScimProvider(connectionParams, config);
      setNotification({
        message: `Created ${created} expectation${created === 1 ? '' : 's'} for the mock SCIM provider.`,
        severity: 'success',
      });
      onClose();
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, basePath, idStrategy, enforceFilter, enforcePatch, requireBearerToken, expectedBearerToken, setNotification, onClose]);

  const handleClose = useCallback(() => {
    setBasePath('');
    setIdStrategy('');
    setEnforceFilter(true);
    setEnforcePatch(true);
    setRequireBearerToken(false);
    setExpectedBearerToken('');
    setError(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="scim-dialog-title">
      <DialogTitle id="scim-dialog-title">Mock SCIM provider</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Register a mock SCIM 2.0 provider — CRUD over Users and Groups plus the discovery
          endpoints (ServiceProviderConfig, ResourceTypes, Schemas) — as expectations. Leave a
          field at its default to let the server choose.
        </Typography>
        {error && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <TextField size="small" label="Base path" placeholder="/scim/v2" value={basePath} onChange={(e) => setBasePath(e.target.value)} sx={{ flex: 1 }} />
            <TextField
              size="small"
              select
              label="ID strategy"
              value={idStrategy}
              onChange={(e) => setIdStrategy(e.target.value as '' | ScimIdStrategy)}
              sx={{ width: { xs: '100%', sm: 180 } }}
            >
              <MenuItem value="">Default (UUID)</MenuItem>
              <MenuItem value="UUID">UUID</MenuItem>
              <MenuItem value="AUTO_INCREMENT">Auto-increment</MenuItem>
            </TextField>
          </Box>
          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
            <FormControlLabel control={<Switch size="small" checked={enforceFilter} onChange={(e) => setEnforceFilter(e.target.checked)} />} label={<Typography variant="body2">Enforce filter</Typography>} />
            <FormControlLabel control={<Switch size="small" checked={enforcePatch} onChange={(e) => setEnforcePatch(e.target.checked)} />} label={<Typography variant="body2">Enforce PATCH</Typography>} />
            <FormControlLabel control={<Switch size="small" checked={requireBearerToken} onChange={(e) => setRequireBearerToken(e.target.checked)} />} label={<Typography variant="body2">Require bearer token</Typography>} />
          </Box>
          {requireBearerToken && (
            <TextField
              size="small"
              label="Expected bearer token"
              placeholder="(any non-empty token if blank)"
              value={expectedBearerToken}
              onChange={(e) => setExpectedBearerToken(e.target.value)}
              helperText="Leave blank to accept any non-empty bearer token; set to pin a specific value."
            />
          )}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button variant="contained" disabled={busy} onClick={() => void submit()}>Create provider</Button>
      </DialogActions>
    </Dialog>
  );
}
