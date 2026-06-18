import Box from '@mui/material/Box';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import { useDashboardStore } from '../store';
import LogPanel from './LogPanel';
import ExpectationPanel from './ExpectationPanel';
import RequestPanel from './RequestPanel';

export default function DashboardGrid() {
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const receivedSearch = useDashboardStore((s) => s.receivedSearch);
  const proxiedSearch = useDashboardStore((s) => s.proxiedSearch);
  const setReceivedSearch = useDashboardStore((s) => s.setReceivedSearch);
  const setProxiedSearch = useDashboardStore((s) => s.setProxiedSearch);

  const theme = useTheme();
  // On small screens the hard 2x2 grid squashes each quadrant to an unreadable
  // sliver. Collapse to a single stacked column and let the container scroll
  // vertically (the app root is height:100vh; overflow:hidden) so each panel
  // gets a usable minimum height instead of being crushed.
  const stacked = useMediaQuery(theme.breakpoints.down('md'));

  return (
    <Box
      sx={
        stacked
          ? {
              flex: 1,
              display: 'grid',
              gridTemplateColumns: '1fr',
              // Auto-size rows to a usable minimum and scroll the whole grid.
              gridAutoRows: 'minmax(320px, auto)',
              gap: 1,
              p: 1,
              overflowY: 'auto',
              overflowX: 'hidden',
              minHeight: 0,
            }
          : {
              flex: 1,
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gridTemplateRows: '1fr 1fr',
              gap: 1,
              p: 1,
              overflow: 'hidden',
              minHeight: 0,
            }
      }
    >
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <LogPanel />
      </Box>
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <ExpectationPanel />
      </Box>
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <RequestPanel
          title="Received Requests"
          items={recordedRequests}
          searchValue={receivedSearch}
          onSearchChange={setReceivedSearch}
        />
      </Box>
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <RequestPanel
          title="Proxied Requests"
          items={proxiedRequests}
          searchValue={proxiedSearch}
          onSearchChange={setProxiedSearch}
        />
      </Box>
    </Box>
  );
}
