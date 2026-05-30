import { describe, it, expect } from 'vitest';
import { gaugeSeries, gaugeSeriesByLabel, ratePerSecond, latestRate, type MetricsSnapshot } from '../lib/metricsDerive';

function snap(at: number, received: number): MetricsSnapshot {
  return { at, samples: [{ name: 'requests_received_count', labels: {}, value: received }] };
}

describe('metricsDerive', () => {
  it('gaugeSeries extracts a gauge across snapshots', () => {
    const history = [snap(0, 10), snap(1000, 25), snap(2000, 40)];
    expect(gaugeSeries(history, 'requests_received_count')).toEqual([10, 25, 40]);
  });

  it('gaugeSeries returns 0 for snapshots missing the metric', () => {
    const history: MetricsSnapshot[] = [{ at: 0, samples: [] }];
    expect(gaugeSeries(history, 'requests_received_count')).toEqual([0]);
  });

  it('ratePerSecond computes per-second deltas and yields N-1 points', () => {
    // +15 over 1s, +30 over 2s => 15 rps, 15 rps
    const history = [snap(0, 10), snap(1000, 25), snap(3000, 55)];
    expect(ratePerSecond(history, 'requests_received_count')).toEqual([15, 15]);
  });

  it('ratePerSecond clamps negative deltas (server reset) to 0', () => {
    const history = [snap(0, 100), snap(1000, 5)];
    expect(ratePerSecond(history, 'requests_received_count')).toEqual([0]);
  });

  it('ratePerSecond is empty for fewer than 2 snapshots', () => {
    expect(ratePerSecond([snap(0, 10)], 'requests_received_count')).toEqual([]);
    expect(ratePerSecond([], 'requests_received_count')).toEqual([]);
  });

  it('gaugeSeriesByLabel extracts a label-scoped gauge across snapshots', () => {
    const history: MetricsSnapshot[] = [
      { at: 0, samples: [{ name: 'jvm_memory_used_bytes', labels: { area: 'heap' }, value: 100 }] },
      { at: 1000, samples: [{ name: 'jvm_memory_used_bytes', labels: { area: 'heap' }, value: 150 }] },
    ];
    expect(gaugeSeriesByLabel(history, 'jvm_memory_used_bytes', 'area', 'heap')).toEqual([100, 150]);
    expect(gaugeSeriesByLabel(history, 'jvm_memory_used_bytes', 'area', 'nonheap')).toEqual([0, 0]);
  });

  it('latestRate returns the most recent rate or 0', () => {
    const history = [snap(0, 0), snap(1000, 10), snap(2000, 35)];
    expect(latestRate(history, 'requests_received_count')).toBe(25);
    expect(latestRate([snap(0, 0)], 'requests_received_count')).toBe(0);
  });
});
