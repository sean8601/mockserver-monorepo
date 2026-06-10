import { useRef, useMemo } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { useDashboardStore } from '../store';
import { isLogGroup } from '../types';
import Panel from './Panel';
import LogEntry from './LogEntry';
import LogGroup from './LogGroup';
import { listRowSx } from './listRowSx';
import { matchesLogSearch } from '../lib/searchMatcher';

export default function LogPanel() {
  const logMessages = useDashboardStore((s) => s.logMessages);
  const search = useDashboardStore((s) => s.logSearch);
  const setSearch = useDashboardStore((s) => s.setLogSearch);
  const searchRef = useRef<HTMLInputElement>(null);

  const filtered = useMemo(
    () => (search ? logMessages.filter((m) => matchesLogSearch(m, search)) : logMessages),
    [logMessages, search],
  );

  return (
    <Panel
      title="Log Messages"
      count={logMessages.length}
      filteredCount={search ? filtered.length : undefined}
      searchValue={search}
      onSearchChange={setSearch}
      searchInputRef={searchRef}
    >
      {filtered.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
          {logMessages.length === 0 ? 'No log messages yet — server activity appears here as requests are handled.' : 'No matching log messages'}
        </Typography>
      ) : (
        filtered.map((message) => (
          <Box key={message.key} sx={listRowSx}>
            {isLogGroup(message) ? (
              <LogGroup group={message} />
            ) : (
              <LogEntry entry={message.value} divider collapsible />
            )}
          </Box>
        ))
      )}
    </Panel>
  );
}
