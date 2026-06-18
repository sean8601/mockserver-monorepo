import { useCallback } from 'react';
import { usePolling } from './usePolling';

export interface UseAutoRefreshOptions {
  /** Refresh interval in milliseconds. Default 3000. */
  intervalMs?: number;
  /** Whether auto-refresh is active. Set false to pause. Default true. */
  enabled?: boolean;
}

/**
 * Small reusable auto-refresh hook so panels (Drift, Breakpoints, Async, MCP)
 * can poll on an interval instead of only refreshing on a manual button.
 *
 * A thin wrapper over {@link usePolling}: it inherits the self-rescheduling
 * loop (next tick scheduled only after the previous run completes — no
 * setInterval overlap), tab-visibility gating, and abort-on-unmount cleanup,
 * adapting the void-returning `fn` to usePolling's fetcher contract.
 *
 * @param fn   The refresh callback. May be sync or async; its result is ignored.
 * @param opts intervalMs (default 3000) and enabled (default true).
 */
export function useAutoRefresh(
  fn: () => void | Promise<void>,
  opts: UseAutoRefreshOptions = {},
): void {
  const { intervalMs = 3000, enabled = true } = opts;

  // usePolling owns rescheduling: it awaits the fetcher before scheduling the
  // next tick, so wrapping `fn` here gives us no-overlap polling for free.
  const fetcher = useCallback(async () => {
    await fn();
  }, [fn]);

  usePolling({ fetcher, intervalMs, enabled });
}
