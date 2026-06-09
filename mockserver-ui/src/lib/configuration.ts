/**
 * Client for MockServer's live configuration control plane (GET/PUT /mockserver/configuration).
 * GET returns the full configuration; PUT applies a partial ConfigurationDTO (only the supplied
 * fields are changed), so the dashboard can tweak a setting like the log level at runtime.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type Configuration = Record<string, unknown>;

export async function getConfiguration(params: ConnectionParams, signal?: AbortSignal): Promise<Configuration> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/configuration`, { signal });
  if (!res.ok) throw new Error(`Failed to load configuration (HTTP ${res.status} ${res.statusText})`);
  return (await res.json()) as Configuration;
}

/** Apply a partial configuration change (only the supplied keys are modified server-side). */
export async function updateConfiguration(params: ConnectionParams, partial: Configuration): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/configuration`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(partial),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Failed to update configuration (HTTP ${res.status} ${res.statusText})`);
  }
}

export const LOG_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF'] as const;

// ---------------------------------------------------------------------------
// Editable property descriptors
// ---------------------------------------------------------------------------

/** The control type rendered for an editable property. */
export type EditableType = 'boolean' | 'string' | 'number';

/**
 * Descriptor for a runtime-mutable configuration property that should be
 * editable in the dashboard.  Adding an entry here is all that is needed to
 * expose a new toggle / field — ConfigurationDialog drives its controls from
 * this list.
 */
export interface EditablePropertyDescriptor {
  /** The JSON key in ConfigurationDTO. */
  key: string;
  /** Human-readable label shown next to the control. */
  label: string;
  /** Control type. */
  type: EditableType;
  /** Short tooltip / help text. */
  help: string;
  /** Logical group (controls are rendered grouped). */
  group: string;
}

/**
 * Declarative list of runtime-mutable properties beyond the original three
 * (logLevel, detailedMatchFailures, metricsEnabled — which keep their
 * bespoke controls).
 *
 * Order within each group determines render order.
 */
export const EDITABLE_PROPERTIES: readonly EditablePropertyDescriptor[] = [
  // Developer / data
  {
    key: 'devMode',
    label: 'Developer mode',
    type: 'boolean',
    help: 'Enable additional debug output and relaxed validation.',
    group: 'Developer / data',
  },
  {
    key: 'generateRealisticExampleValues',
    label: 'Realistic example values',
    type: 'boolean',
    help: 'Generate realistic (Faker-style) example values in stubs instead of fixed placeholders.',
    group: 'Developer / data',
  },
  {
    key: 'attachMismatchDiagnosticToResponse',
    label: 'Mismatch diagnostic in response',
    type: 'boolean',
    help: 'Attach detailed mismatch diagnostics to unmatched-request responses.',
    group: 'Developer / data',
  },

  // Validation proxy
  {
    key: 'validateProxyOpenAPISpec',
    label: 'OpenAPI spec (URL / path)',
    type: 'string',
    help: 'OpenAPI specification URL, file path, or inline JSON/YAML used to validate proxied traffic.',
    group: 'Validation proxy',
  },
  {
    key: 'validateProxyEnforce',
    label: 'Enforce validation',
    type: 'boolean',
    help: 'When enabled, proxied requests that violate the OpenAPI spec are rejected.',
    group: 'Validation proxy',
  },

  // Chaos auto-halt
  {
    key: 'chaosAutoHaltEnabled',
    label: 'Auto-halt enabled',
    type: 'boolean',
    help: 'Automatically halt chaos injection when the error threshold is exceeded.',
    group: 'Chaos auto-halt',
  },
  {
    key: 'chaosAutoHaltErrorThreshold',
    label: 'Error threshold',
    type: 'number',
    help: 'Maximum number of errors within the window before chaos is halted.',
    group: 'Chaos auto-halt',
  },
  {
    key: 'chaosAutoHaltWindowMillis',
    label: 'Window (ms)',
    type: 'number',
    help: 'Sliding window in milliseconds over which errors are counted for auto-halt.',
    group: 'Chaos auto-halt',
  },

] as const;
