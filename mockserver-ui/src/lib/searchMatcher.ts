/**
 * Shared search-matching utilities. Match against extracted searchable fields
 * rather than raw JSON.stringify, which matches structural keys (e.g. "value",
 * "type") and re-serialises on every keystroke.
 *
 * The TrafficInspector already used a field-based approach; this module
 * generalises it for all panels.
 */

/**
 * Extract searchable text fields from a generic item value object.
 * Walks one level of known keys and collects string values.
 */
export function extractSearchableFields(value: Record<string, unknown>): string[] {
  const fields: string[] = [];

  const addString = (v: unknown) => {
    if (typeof v === 'string' && v.length > 0) fields.push(v);
  };

  const addNumber = (v: unknown) => {
    if (typeof v === 'number') fields.push(String(v));
  };

  // Top-level string/number fields
  addString(value['id']);
  addString(value['description']);
  addString(value['scenarioName']);
  addString(value['scenarioState']);
  addString(value['newScenarioState']);

  // httpRequest fields
  const req = value['httpRequest'];
  if (req && typeof req === 'object' && !Array.isArray(req)) {
    const r = req as Record<string, unknown>;
    addString(r['method']);
    addString(r['path']);
    // Handle NottableString for method/path
    if (r['method'] && typeof r['method'] === 'object') {
      addString((r['method'] as Record<string, unknown>)['value']);
    }
    if (r['path'] && typeof r['path'] === 'object') {
      addString((r['path'] as Record<string, unknown>)['value']);
    }
  }

  // httpResponse fields
  const res = value['httpResponse'];
  if (res && typeof res === 'object' && !Array.isArray(res)) {
    const r = res as Record<string, unknown>;
    addNumber(r['statusCode']);
    addString(r['reasonPhrase']);
  }

  // Action type keys
  for (const key of ['httpResponse', 'httpForward', 'httpLlmResponse', 'httpSseResponse', 'httpError', 'httpClassCallback', 'httpObjectCallback']) {
    if (key in value) fields.push(key);
  }

  // LLM-specific fields
  const llm = value['httpLlmResponse'];
  if (llm && typeof llm === 'object' && !Array.isArray(llm)) {
    const l = llm as Record<string, unknown>;
    addString(l['provider']);
    addString(l['model']);
    const completion = l['completion'];
    if (completion && typeof completion === 'object') {
      addString((completion as Record<string, unknown>)['text']);
    }
  }

  return fields;
}

/**
 * Check if a generic item matches a search term by comparing against
 * extracted searchable fields. Falls back to JSON.stringify only if
 * no fields matched — this avoids false positives on structural keys.
 */
export function matchesItemSearch(value: Record<string, unknown>, term: string): boolean {
  const lower = term.toLowerCase();
  const fields = extractSearchableFields(value);
  if (fields.some((f) => f.toLowerCase().includes(lower))) return true;
  // Fallback to full JSON for deep nested values the field extractor misses
  return JSON.stringify(value).toLowerCase().includes(lower);
}

/**
 * Check if a log message matches a search term. Log messages have a different
 * shape (array of strings or objects with group structure).
 */
export function matchesLogSearch(message: { value?: unknown; key: string; messages?: unknown[] }, term: string): boolean {
  const lower = term.toLowerCase();
  // Check key
  if (message.key.toLowerCase().includes(lower)) return true;
  // Check value (log entry text)
  if (message.value != null) {
    if (typeof message.value === 'string' && message.value.toLowerCase().includes(lower)) return true;
    if (typeof message.value === 'object') {
      const v = message.value as Record<string, unknown>;
      for (const field of ['message', 'description', 'type', 'logLevel']) {
        const fv = v[field];
        if (typeof fv === 'string' && fv.toLowerCase().includes(lower)) return true;
      }
    }
  }
  // Fallback to JSON stringify
  return JSON.stringify(message).toLowerCase().includes(lower);
}
