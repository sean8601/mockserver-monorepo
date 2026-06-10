import { useContext, useLayoutEffect, useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import { useVirtualizer } from '@tanstack/react-virtual';
import { PanelScrollContext } from './PanelScrollContext';
import { listRowSx } from './listRowSx';

interface VirtualListProps {
  /** Number of rows. */
  count: number;
  /** Stable key for the row at `index` (used as React + virtualizer item key). */
  getKey: (index: number) => string;
  /** Render the row at `index`. VirtualList owns the row's positioning wrapper. */
  renderRow: (index: number) => ReactNode;
  /** Rough starting height (px) for an unmeasured row; real heights are measured. */
  estimateSize?: number;
}

/**
 * Windows a long dashboard list so only the rows near the viewport are mounted,
 * using the enclosing Panel's scroll container (via PanelScrollContext) as the
 * scroll element. Row heights are dynamic — each rendered row is measured, so
 * expanding a row reflows correctly.
 *
 * Fallback: when there is no measurable viewport (before first layout, or in a
 * non-layout environment such as jsdom where clientHeight is 0), every row is
 * rendered instead, each wrapped in `listRowSx` so the browser can still skip
 * off-screen paint. This keeps unit tests (which can't lay out a scroll
 * viewport) rendering the full list.
 */
export default function VirtualList({ count, getKey, renderRow, estimateSize = 44 }: VirtualListProps) {
  const scrollEl = useContext(PanelScrollContext);
  const [viewportHeight, setViewportHeight] = useState(0);

  // useLayoutEffect so the measured height is applied before paint — the first
  // visible frame is already windowed rather than showing the full list.
  useLayoutEffect(() => {
    if (!scrollEl) return;
    const measure = () => setViewportHeight(scrollEl.clientHeight);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(scrollEl);
    return () => observer.disconnect();
  }, [scrollEl]);

  // useVirtualizer returns instance methods the React Compiler can't safely
  // memoize, so it declines to compile this component — expected and harmless
  // here (the virtualizer manages its own subscriptions/measurements).
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count,
    getScrollElement: () => scrollEl,
    estimateSize: () => estimateSize,
    overscan: 10,
    getItemKey: (index) => getKey(index),
  });

  if (!scrollEl || viewportHeight === 0) {
    return (
      <>
        {Array.from({ length: count }, (_, i) => (
          <Box key={getKey(i)} sx={listRowSx}>
            {renderRow(i)}
          </Box>
        ))}
      </>
    );
  }

  return (
    <Box sx={{ position: 'relative', width: '100%', height: virtualizer.getTotalSize() }}>
      {virtualizer.getVirtualItems().map((vi) => (
        <Box
          key={vi.key}
          data-index={vi.index}
          ref={virtualizer.measureElement}
          sx={{
            position: 'absolute',
            top: 0,
            left: 0,
            width: '100%',
            transform: `translateY(${vi.start}px)`,
          }}
        >
          {renderRow(vi.index)}
        </Box>
      ))}
    </Box>
  );
}
