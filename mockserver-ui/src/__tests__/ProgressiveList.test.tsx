import { describe, it, expect, vi } from 'vitest';
import { act } from 'react';
import { render, screen } from '@testing-library/react';
import ProgressiveList from '../components/ProgressiveList';

function rows() {
  return screen.queryAllByTestId('row');
}

describe('ProgressiveList', () => {
  it('renders the whole list immediately when it is no longer than the initial batch', () => {
    render(
      <ProgressiveList
        count={3}
        initial={15}
        getKey={(i) => `r${i}`}
        renderRow={(i) => <div data-testid="row">row {i}</div>}
      />,
    );
    expect(rows()).toHaveLength(3);
  });

  it('renders an initial batch first, then fills in the rest during idle time', () => {
    // jsdom has no requestIdleCallback, so ProgressiveList uses its setTimeout
    // fallback — drive it with fake timers.
    vi.useFakeTimers();
    try {
      render(
        <ProgressiveList
          count={20}
          initial={5}
          step={5}
          getKey={(i) => `r${i}`}
          renderRow={(i) => <div data-testid="row">row {i}</div>}
        />,
      );
      // First paint: only the initial batch is mounted.
      expect(rows()).toHaveLength(5);
      expect(screen.getByText('row 0')).toBeInTheDocument();

      // Each idle tick appends another `step` rows until the full list is mounted.
      act(() => { vi.advanceTimersByTime(20); });
      expect(rows()).toHaveLength(10);
      act(() => { vi.advanceTimersByTime(20); });
      expect(rows()).toHaveLength(15);
      act(() => { vi.advanceTimersByTime(20); });
      expect(rows()).toHaveLength(20);

      // Once the whole list is mounted no further growth is scheduled.
      act(() => { vi.advanceTimersByTime(100); });
      expect(rows()).toHaveLength(20);
    } finally {
      vi.useRealTimers();
    }
  });
});
