import { Fragment, useEffect, useState, type ReactNode } from 'react';

interface ProgressiveListProps {
  /** Number of rows. */
  count: number;
  /** Stable React key for the row at `index`. */
  getKey: (index: number) => string;
  /** Render the row at `index`. */
  renderRow: (index: number) => ReactNode;
  /** Rows rendered on the first paint (enough to fill the visible area). */
  initial?: number;
  /** Rows added per idle tick until the whole list is mounted. */
  step?: number;
}

/**
 * Renders a long list cheaply on first paint, then fills in the rest in the
 * background so scrolling stays smooth.
 *
 * Only `initial` rows are mounted synchronously (roughly what's visible); the
 * remaining rows are appended in `step`-sized batches during browser idle time.
 * The end state is the full list fully laid out, so — unlike windowing — the
 * browser does no per-scroll mounting or repositioning and native scrolling
 * stays smooth. Spreading the initial mount across idle callbacks keeps the
 * first frame fast.
 *
 * In a non-layout environment (jsdom) or for lists no longer than `initial`,
 * every row renders immediately.
 */
function scheduleIdle(cb: () => void): () => void {
  if (typeof window !== 'undefined' && typeof window.requestIdleCallback === 'function') {
    const id = window.requestIdleCallback(cb, { timeout: 250 });
    return () => window.cancelIdleCallback(id);
  }
  const id = setTimeout(cb, 16);
  return () => clearTimeout(id);
}

export default function ProgressiveList({ count, getKey, renderRow, initial = 15, step = 25 }: ProgressiveListProps) {
  const [limit, setLimit] = useState(initial);

  useEffect(() => {
    if (limit >= count) return;
    return scheduleIdle(() => setLimit((l) => l + step));
  }, [limit, count, step]);

  const rendered = Math.min(limit, count);
  return (
    <>
      {Array.from({ length: rendered }, (_, i) => (
        <Fragment key={getKey(i)}>{renderRow(i)}</Fragment>
      ))}
    </>
  );
}
