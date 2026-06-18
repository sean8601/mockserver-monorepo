import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAutoRefresh } from '../hooks/useAutoRefresh';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

describe('useAutoRefresh', () => {
  it('runs fn immediately then on the default ~3000ms interval', async () => {
    const fn = vi.fn();
    renderHook(() => useAutoRefresh(fn));

    await flush();
    expect(fn).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('honours a custom interval', async () => {
    const fn = vi.fn();
    renderHook(() => useAutoRefresh(fn, { intervalMs: 1000 }));

    await flush();
    expect(fn).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('does not run when disabled', async () => {
    const fn = vi.fn();
    renderHook(() => useAutoRefresh(fn, { enabled: false }));

    await flush();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(fn).not.toHaveBeenCalled();
  });

  it('does not overlap a slow async fn — waits for completion before scheduling', async () => {
    let resolveFirst!: () => void;
    const fn = vi
      .fn()
      .mockImplementationOnce(() => new Promise<void>((r) => { resolveFirst = r; }))
      .mockResolvedValue(undefined);

    renderHook(() => useAutoRefresh(fn, { intervalMs: 1000 }));
    await flush();
    expect(fn).toHaveBeenCalledTimes(1);

    // Advance past several intervals while the first call is still in flight —
    // no second call should fire because the next tick is scheduled only after
    // the current run resolves.
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(fn).toHaveBeenCalledTimes(1);

    await act(async () => { resolveFirst(); await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('stops running after unmount', async () => {
    const fn = vi.fn();
    const { unmount } = renderHook(() => useAutoRefresh(fn, { intervalMs: 1000 }));

    await flush();
    expect(fn).toHaveBeenCalledTimes(1);

    unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(fn).toHaveBeenCalledTimes(1);
  });
});
