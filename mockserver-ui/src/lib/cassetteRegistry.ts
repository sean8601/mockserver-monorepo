/**
 * Client-side registry for tracked cassette files.
 *
 * Cassettes are JSON fixture files containing MockServer expectations.
 * The registry stores metadata about known cassettes in localStorage,
 * without storing the file contents themselves.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface CassetteEntry {
  /** User-visible file name (e.g. "my-cassette.json") */
  filename: string;
  /** Full path on the server filesystem */
  path: string;
  /** Number of expectations in the cassette (-1 if unknown) */
  expectationCount: number;
  /** ISO timestamp of last record or load operation */
  lastUsed: string;
  /** Whether this cassette was created via record or load */
  origin: 'recorded' | 'loaded';
}

// ---------------------------------------------------------------------------
// localStorage key
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'mockserver-cassettes';

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function readStorage(): CassetteEntry[] {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    // Validate each entry has at minimum the required fields
    return parsed.filter(
      (e): e is CassetteEntry =>
        typeof e === 'object' &&
        e !== null &&
        typeof (e as Record<string, unknown>)['filename'] === 'string' &&
        typeof (e as Record<string, unknown>)['path'] === 'string',
    );
  } catch {
    return [];
  }
}

function writeStorage(entries: CassetteEntry[]): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // localStorage may not be available
  }
}

function extractFilename(filePath: string): string {
  const parts = filePath.split(/[/\\]/);
  return parts[parts.length - 1] ?? filePath;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * List all tracked cassettes, most recently used first.
 */
export function listCassettes(): CassetteEntry[] {
  const entries = readStorage();
  return entries.sort((a, b) => {
    const dateA = a.lastUsed ? new Date(a.lastUsed).getTime() : 0;
    const dateB = b.lastUsed ? new Date(b.lastUsed).getTime() : 0;
    return dateB - dateA;
  });
}

/**
 * Add or update a cassette in the registry.
 *
 * If a cassette with the same `path` already exists, it is updated
 * instead of duplicated.
 */
export function addCassette(
  path: string,
  expectationCount: number,
  origin: 'recorded' | 'loaded',
): CassetteEntry {
  const entries = readStorage();
  const filename = extractFilename(path);
  const now = new Date().toISOString();

  const existing = entries.find((e) => e.path === path);
  if (existing) {
    existing.expectationCount = expectationCount;
    existing.lastUsed = now;
    existing.origin = origin;
    writeStorage(entries);
    return existing;
  }

  const entry: CassetteEntry = {
    filename,
    path,
    expectationCount,
    lastUsed: now,
    origin,
  };
  entries.push(entry);
  writeStorage(entries);
  return entry;
}

/**
 * Remove a cassette from the registry by path.
 *
 * Returns true if a cassette was removed, false if not found.
 */
export function removeCassette(path: string): boolean {
  const entries = readStorage();
  const filtered = entries.filter((e) => e.path !== path);
  if (filtered.length === entries.length) return false;
  writeStorage(filtered);
  return true;
}

/**
 * Update the lastUsed timestamp for a cassette (e.g. on load).
 *
 * Returns the updated entry or null if not found.
 */
export function updateLastLoaded(path: string): CassetteEntry | null {
  const entries = readStorage();
  const entry = entries.find((e) => e.path === path);
  if (!entry) return null;
  entry.lastUsed = new Date().toISOString();
  writeStorage(entries);
  return entry;
}

/**
 * Clear all cassette entries from the registry.
 */
export function clearCassettes(): void {
  writeStorage([]);
}
