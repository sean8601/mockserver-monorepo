/**
 * Client for MockServer's PUT /mockserver/scim — stands up a mock SCIM 2.0 provider
 * (CRUD over Users and Groups plus the discovery endpoints ServiceProviderConfig,
 * ResourceTypes, and Schemas) as a set of expectations. All fields are optional;
 * the server (see ScimProviderConfiguration) fills in sensible defaults, so an empty
 * body produces a fully functional provider serving `/scim/v2`.
 *
 * Field names mirror the server's ScimProviderConfiguration exactly.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** Identifier-generation strategy for created SCIM resources. Matches CrudExpectationsDefinition.IdStrategy. */
export type ScimIdStrategy = 'UUID' | 'AUTO_INCREMENT';

export interface ScimConfig {
  /** Base path the provider is served under. Default: `/scim/v2`. */
  basePath?: string;
  /** How new resource ids are generated. Default: `UUID`. */
  idStrategy?: ScimIdStrategy;
  /** Whether `filter` query parameters are enforced. Default: true. */
  enforceFilter?: boolean;
  /** Whether PATCH operation semantics are enforced. Default: true. */
  enforcePatch?: boolean;
  /** Whether endpoints require an `Authorization: Bearer <token>` header. Default: false. */
  requireBearerToken?: boolean;
  /**
   * The exact bearer token requests must present when {@link requireBearerToken} is on.
   * Left unset with requireBearerToken=true means presence-only (any non-empty token).
   * Write-only on the server — it is never echoed back in the response.
   */
  expectedBearerToken?: string;
}

/**
 * Create the mock SCIM provider. Returns the number of expectations the server registered.
 *
 * On failure throws an Error whose message carries the `MockServer returned <status>: <body>`
 * shape so callers can pass it through {@link import('./errorMessage').humanizeError}. The server
 * reports invalid configuration (e.g. a basePath overlapping `/mockserver`) as a `400` with a
 * `text/plain` body.
 */
export async function createScimProvider(params: ConnectionParams, config: ScimConfig): Promise<number> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/scim`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`MockServer returned ${res.status}: ${text}`);
  }
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? body.length : 0;
}
