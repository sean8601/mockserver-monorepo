import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Shared polling hook. Replaces the near-identical hand-rolled poll loops in
 * BreakpointsPanel, DriftPanel, ServiceChaosPanel, and AppBar intervals.
 *
 * Matches the existing useMetricsPolling shape: poll immediately, schedule
 * the next poll after the fetch completes (not setInterval), and clean up
 * on unmount. Preserves abort/cancel semantics.
 */
export interface UsePollingOptions<T> {
  /** Async fetcher. Receives an AbortSignal. */
  fetcher: (signal: AbortSignal) => Promise<T>;
  /** Poll interval in milliseconds. */
  intervalMs: number;
  /** Whether polling is active. Set false to pause. Default true. */
  enabled?: boolean;
}

export interface UsePollingResult<T> {
  data: T | null;
  error: string | null;
  /** Force an immediate re-poll. */
  refresh: () => void;
}

export function usePolling<T>({
  fetcher,
  intervalMs,
  enabled = true,
}: UsePollingOptions<T>): UsePollingResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const fetcherRef = useRef(fetcher);

  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetcherRef.current(controller.signal);
        if (cancelled) return;
        setData(result);
        setError(null);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) timer = setTimeout(() => void poll(), intervalMs);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [intervalMs, enabled, refreshTick]);

  return { data, error, refresh };
}
