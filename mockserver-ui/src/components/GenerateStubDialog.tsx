import { useCallback, useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { registerExpectation } from '../lib/generateStub';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import { useDashboardStore } from '../store';
import HumanErrorAlert from './HumanErrorAlert';
import JsonViewer from './JsonViewer';

interface GenerateStubDialogProps {
  open: boolean;
  onClose: () => void;
  suggestions: Record<string, unknown>[];
  confidence: number;
  connectionParams: ConnectionParams;
}

export default function GenerateStubDialog({
  open,
  onClose,
  suggestions,
  confidence,
  connectionParams,
}: GenerateStubDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const setNotification = useDashboardStore((s) => s.setNotification);
  const editExpectation = useDashboardStore((s) => s.editExpectation);
  const [registering, setRegistering] = useState(false);
  const [registered, setRegistered] = useState<Set<number>>(new Set());
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [error, setError] = useState<HumanError | null>(null);

  const handleRegister = useCallback(async () => {
    if (suggestions.length === 0 || selectedIndex >= suggestions.length) return;
    setRegistering(true);
    setError(null);
    try {
      await registerExpectation(connectionParams, suggestions[selectedIndex]!);
      setRegistered((prev) => new Set(prev).add(selectedIndex));
      setNotification({ message: 'Expectation registered', severity: 'success' });
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setRegistering(false);
    }
  }, [connectionParams, suggestions, selectedIndex, setNotification]);

  const handleClose = useCallback(() => {
    setRegistered(new Set());
    setSelectedIndex(0);
    setError(null);
    onClose();
  }, [onClose]);

  // After registering, let the user keep refining the generated expectation in
  // the Composer instead of dead-ending on the "Registered" state. editExpectation
  // switches the view to the Composer, so close this dialog as it hands off.
  const handleOpenInComposer = useCallback(() => {
    const suggestion = suggestions[selectedIndex];
    if (!suggestion) return;
    editExpectation(suggestion);
    handleClose();
  }, [suggestions, selectedIndex, editExpectation, handleClose]);

  const currentRegistered = registered.has(selectedIndex);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="generate-stub-dialog-title">
      <DialogTitle id="generate-stub-dialog-title" sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        Generated Expectation
        <Chip
          size="small"
          label={`${Math.round(confidence * 100)}% confidence`}
          color={confidence >= 0.8 ? 'success' : confidence >= 0.5 ? 'warning' : 'error'}
          variant="outlined"
        />
      </DialogTitle>
      <DialogContent dividers>
        {error && (
          <HumanErrorAlert error={error} sx={{ mb: 1.5 }} onClose={() => setError(null)} />
        )}
        {registered.has(selectedIndex) && (
          <Alert severity="success" sx={{ mb: 1.5 }}>
            Suggestion {selectedIndex + 1} registered successfully.
          </Alert>
        )}
        {suggestions.length === 0 ? (
          <Box>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
              No suggestions to generate an expectation from.
            </Typography>
            <Typography variant="caption" color="text.secondary">
              This usually means no unmatched traffic has been captured yet. Send a request
              that does not match any expectation, then try again from its log entry.
            </Typography>
          </Box>
        ) : (
          <Box>
            {suggestions.length > 1 && (
              <Box sx={{ display: 'flex', gap: 0.5, mb: 1.5, flexWrap: 'wrap' }}>
                {suggestions.map((_, i) => (
                  <Chip
                    key={i}
                    label={`Suggestion ${i + 1}`}
                    size="small"
                    variant={i === selectedIndex ? 'filled' : 'outlined'}
                    color={registered.has(i) ? 'success' : i === selectedIndex ? 'primary' : 'default'}
                    onClick={() => setSelectedIndex(i)}
                    sx={{ cursor: 'pointer' }}
                  />
                ))}
              </Box>
            )}
            <JsonViewer data={suggestions[selectedIndex]!} collapsed={3} />
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{currentRegistered ? 'Done' : 'Cancel'}</Button>
        {currentRegistered ? (
          <Button
            variant="contained"
            startIcon={<EditOutlinedIcon />}
            onClick={handleOpenInComposer}
          >
            Open in Composer
          </Button>
        ) : (
          <Button
            variant="contained"
            disabled={registering || suggestions.length === 0}
            onClick={() => void handleRegister()}
            startIcon={registering ? <CircularProgress size={16} /> : undefined}
          >
            Register Now
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
