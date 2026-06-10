import type { SxProps, Theme } from '@mui/material/styles';

/**
 * Applied to each dashboard list row so the browser can skip layout, style and
 * paint for rows scrolled out of view (`content-visibility: auto`) while still
 * keeping them in the DOM. `contain-intrinsic-size: auto <h>` lets the browser
 * reuse each row's last-rendered height as its placeholder size, so expanded
 * rows don't cause scrollbar jumps when they scroll off-screen. Browsers that
 * don't support these properties simply ignore them and render every row as
 * before, so this degrades gracefully.
 *
 * This complements (and is harmless alongside) the @tanstack/react-virtual
 * windowing the panels use when the viewport is measurable: windowed rows are
 * on-screen so content-visibility is inactive for them, but on the render-all
 * fallback path (very short lists, or non-layout environments like jsdom) these
 * properties still cut the off-screen paint/layout cost.
 */
export const listRowSx: SxProps<Theme> = {
  contentVisibility: 'auto',
  containIntrinsicSize: 'auto 44px',
};
