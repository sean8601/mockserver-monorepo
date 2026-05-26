import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  listCassettes,
  addCassette,
  removeCassette,
  updateLastLoaded,
  clearCassettes,
} from '../lib/cassetteRegistry';

// ---------------------------------------------------------------------------
// Setup: mock localStorage
// ---------------------------------------------------------------------------

const store: Record<string, string> = {};

beforeEach(() => {
  for (const key of Object.keys(store)) {
    delete store[key];
  }
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => { store[key] = value; },
    removeItem: (key: string) => { delete store[key]; },
  });
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('cassetteRegistry', () => {
  it('returns empty list when no cassettes exist', () => {
    expect(listCassettes()).toEqual([]);
  });

  it('adds a cassette and retrieves it', () => {
    const entry = addCassette('/tmp/test.json', 5, 'recorded');

    expect(entry.filename).toBe('test.json');
    expect(entry.path).toBe('/tmp/test.json');
    expect(entry.expectationCount).toBe(5);
    expect(entry.origin).toBe('recorded');

    const list = listCassettes();
    expect(list).toHaveLength(1);
    expect(list[0]?.path).toBe('/tmp/test.json');
  });

  it('updates existing cassette when adding with the same path', () => {
    addCassette('/tmp/test.json', 5, 'recorded');
    addCassette('/tmp/test.json', 10, 'loaded');

    const list = listCassettes();
    expect(list).toHaveLength(1);
    expect(list[0]?.expectationCount).toBe(10);
    expect(list[0]?.origin).toBe('loaded');
  });

  it('removes a cassette by path', () => {
    addCassette('/tmp/a.json', 3, 'recorded');
    addCassette('/tmp/b.json', 7, 'loaded');

    const removed = removeCassette('/tmp/a.json');
    expect(removed).toBe(true);

    const list = listCassettes();
    expect(list).toHaveLength(1);
    expect(list[0]?.path).toBe('/tmp/b.json');
  });

  it('returns false when removing a non-existent cassette', () => {
    const removed = removeCassette('/tmp/does-not-exist.json');
    expect(removed).toBe(false);
  });

  it('updates lastLoaded timestamp', () => {
    addCassette('/tmp/test.json', 5, 'recorded');

    // Wait a tiny bit so timestamps differ
    const before = listCassettes()[0]?.lastUsed;

    const updated = updateLastLoaded('/tmp/test.json');
    expect(updated).not.toBeNull();
    expect(updated?.lastUsed).toBeDefined();
    // The timestamp should be at least as recent as the original
    expect(new Date(updated!.lastUsed).getTime()).toBeGreaterThanOrEqual(
      new Date(before!).getTime(),
    );
  });

  it('returns null when updating lastLoaded for non-existent cassette', () => {
    const result = updateLastLoaded('/tmp/does-not-exist.json');
    expect(result).toBeNull();
  });

  it('sorts cassettes by most recently used first', () => {
    // Seed localStorage with controlled timestamps to ensure deterministic ordering
    const entries = [
      { filename: 'old.json', path: '/tmp/old.json', expectationCount: 1, lastUsed: '2024-01-01T00:00:00.000Z', origin: 'recorded' },
      { filename: 'new.json', path: '/tmp/new.json', expectationCount: 2, lastUsed: '2025-06-01T00:00:00.000Z', origin: 'recorded' },
    ];
    store['mockserver-cassettes'] = JSON.stringify(entries);

    const list = listCassettes();
    expect(list).toHaveLength(2);
    expect(list[0]?.path).toBe('/tmp/new.json');
    expect(list[1]?.path).toBe('/tmp/old.json');
  });

  it('clears all cassettes', () => {
    addCassette('/tmp/a.json', 1, 'recorded');
    addCassette('/tmp/b.json', 2, 'loaded');

    clearCassettes();
    expect(listCassettes()).toEqual([]);
  });

  it('handles corrupted localStorage gracefully', () => {
    store['mockserver-cassettes'] = 'not valid json';
    expect(listCassettes()).toEqual([]);
  });

  it('handles non-array localStorage value gracefully', () => {
    store['mockserver-cassettes'] = '{"notAnArray": true}';
    expect(listCassettes()).toEqual([]);
  });

  it('extracts filename from path with forward slashes', () => {
    const entry = addCassette('/home/user/fixtures/my-cassette.json', 3, 'recorded');
    expect(entry.filename).toBe('my-cassette.json');
  });

  it('extracts filename from path with backslashes', () => {
    const entry = addCassette('C:\\Users\\test\\cassette.json', 3, 'recorded');
    expect(entry.filename).toBe('cassette.json');
  });
});
