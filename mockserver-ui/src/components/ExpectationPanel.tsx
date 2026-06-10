import { useMemo } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { useDashboardStore } from '../store';
import Panel from './Panel';
import JsonListItemComponent from './JsonListItem';
import { listRowSx } from './listRowSx';
import { applyClientFilters } from '../lib/clientFilters';
import { matchesItemSearch } from '../lib/searchMatcher';
import { buildTurnPositionMap } from '../lib/scenarioState';

export default function ExpectationPanel() {
  const expectations = useDashboardStore((s) => s.activeExpectations);
  const search = useDashboardStore((s) => s.expectationSearch);
  const setSearch = useDashboardStore((s) => s.setExpectationSearch);
  const filterEnabled = useDashboardStore((s) => s.filterEnabled);
  const actionTypeFilter = useDashboardStore((s) => s.actionTypeFilter);
  const llmProviderFilter = useDashboardStore((s) => s.llmProviderFilter);

  // Compute turn N of M across the FULL set (not the filtered subset) so the
  // total stays meaningful even when search hides siblings.
  const turnPositions = useMemo(() => buildTurnPositionMap(expectations), [expectations]);

  const clientFiltered = useMemo(
    () => filterEnabled ? applyClientFilters(expectations, actionTypeFilter, llmProviderFilter) : expectations,
    [expectations, filterEnabled, actionTypeFilter, llmProviderFilter],
  );

  const filtered = useMemo(
    () => (search ? clientFiltered.filter((e) => matchesItemSearch(e.value, search)) : clientFiltered),
    [clientFiltered, search],
  );

  return (
    <Panel
      title="Active Expectations"
      count={expectations.length}
      filteredCount={filtered.length !== expectations.length ? filtered.length : undefined}
      searchValue={search}
      onSearchChange={setSearch}
    >
      {filtered.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
          {expectations.length === 0
            ? 'No active expectations — add one in the Mocks tab, or import an OpenAPI/WSDL spec from the tools menu.'
            : 'No matching expectations'}
        </Typography>
      ) : (
        filtered.map((item, index) => (
          <Box key={item.key} sx={listRowSx}>
            <JsonListItemComponent
              item={item}
              index={index + 1}
              turnPosition={turnPositions.get(item.key)}
            />
          </Box>
        ))
      )}
    </Panel>
  );
}
