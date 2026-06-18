import { useMemo, useRef, useState, useCallback } from 'react';
import Typography from '@mui/material/Typography';
import { useDashboardStore } from '../store';
import Panel from './Panel';
import JsonListItemComponent from './JsonListItem';
import ProgressiveList from './ProgressiveList';
import ConfirmDialog from './ConfirmDialog';
import { useExpansion } from '../hooks/useExpansion';
import { useConnectionParams } from '../hooks/useConnectionParams';
import { applyClientFilters } from '../lib/clientFilters';
import { matchesItemSearch } from '../lib/searchMatcher';
import { buildTurnPositionMap } from '../lib/scenarioState';
import { deleteExpectation } from '../lib/expectations';
import { humanizeError } from '../lib/errorMessage';
import type { JsonListItem } from '../types';

/** Pull the expectation id out of a row value, when present. */
function expectationIdOf(item: JsonListItem): string | null {
  const id = item.value['id'];
  return typeof id === 'string' ? id : null;
}

export default function ExpectationPanel() {
  const params = useConnectionParams();
  const expectations = useDashboardStore((s) => s.activeExpectations);
  const search = useDashboardStore((s) => s.expectationSearch);
  const setSearch = useDashboardStore((s) => s.setExpectationSearch);
  const filterEnabled = useDashboardStore((s) => s.filterEnabled);
  const actionTypeFilter = useDashboardStore((s) => s.actionTypeFilter);
  const llmProviderFilter = useDashboardStore((s) => s.llmProviderFilter);
  const setNotification = useDashboardStore((s) => s.setNotification);
  const editExpectation = useDashboardStore((s) => s.editExpectation);

  // Pending single-expectation delete, awaiting confirmation. Null when no
  // delete is in flight.
  const [pendingDelete, setPendingDelete] = useState<JsonListItem | null>(null);

  // Guards the confirm handler against re-entrancy: the ConfirmDialog button
  // fires onConfirm() before the close re-render lands, so a fast double-click
  // could otherwise dispatch two DELETE requests for the same expectation.
  const deletingRef = useRef(false);

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

  const expansion = useExpansion();

  const handleEdit = useCallback(
    (item: JsonListItem) => {
      editExpectation(item.value);
    },
    [editExpectation],
  );

  const handleConfirmDelete = useCallback(async () => {
    if (!pendingDelete) return;
    const id = expectationIdOf(pendingDelete);
    if (!id) return;
    // No-op if a delete is already in flight (re-entrant double-click).
    if (deletingRef.current) return;
    deletingRef.current = true;
    try {
      await deleteExpectation(params, id);
      // Optimistically drop the row; the next WebSocket push will reconcile.
      useDashboardStore.setState((s) => ({
        activeExpectations: s.activeExpectations.filter((e) => e.key !== pendingDelete.key),
      }));
      setNotification({ message: `Expectation ${id} deleted`, severity: 'success' });
    } catch (e) {
      setNotification({ message: humanizeError(e).message, severity: 'error' });
    } finally {
      deletingRef.current = false;
    }
  }, [pendingDelete, params, setNotification]);

  return (
    <>
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
          <ProgressiveList
            count={filtered.length}
            getKey={(i) => filtered[i]!.key}
            renderRow={(i) => {
              const item = filtered[i]!;
              // Per-row actions are only meaningful for expectations that carry
              // an id (every Active Expectations row does); guard anyway so a
              // malformed row never offers a no-op delete.
              const hasId = expectationIdOf(item) !== null;
              return (
                <JsonListItemComponent
                  item={item}
                  index={i + 1}
                  turnPosition={turnPositions.get(item.key)}
                  expanded={expansion.isExpanded(item.key)}
                  onToggleExpand={expansion.toggle}
                  onEdit={hasId ? handleEdit : undefined}
                  onDelete={hasId ? setPendingDelete : undefined}
                />
              );
            }}
          />
        )}
      </Panel>
      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete this expectation?"
        message={
          pendingDelete
            ? `Remove expectation ${expectationIdOf(pendingDelete) ?? ''} from the server. Recorded requests and logs are kept. This cannot be undone.`
            : ''
        }
        confirmLabel="Delete"
        onConfirm={() => { void handleConfirmDelete(); }}
        onClose={() => setPendingDelete(null)}
      />
    </>
  );
}
