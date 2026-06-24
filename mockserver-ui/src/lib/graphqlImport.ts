/**
 * Client for MockServer's GraphQL schema import endpoint (`PUT /mockserver/graphql`).
 * Generates one mock expectation per root operation type (query / mutation /
 * subscription) the schema defines, a sibling of the OpenAPI and WSDL importers.
 *
 * Unlike the OpenAPI importer, the server reads the request body VERBATIM as the
 * schema (`HttpState` → `GraphQLExpectationGenerator.generate(getBodyAsString(), path)`,
 * see HttpState.java:1798) — it is SDL text or an introspection JSON result, not a
 * JSON envelope and not a URL. So this wrapper sends the schema as the raw body and
 * does NOT support fetching a remote URL (the server has no URL-fetch for GraphQL).
 *
 * The optional `path` selects the request path the generated mocks match (the
 * server defaults to `/graphql` when blank); it is sent as the `?path=` query
 * parameter the handler reads via `getFirstQueryStringParameter("path")`.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface GraphqlImportOptions {
  /** SDL text or an introspection JSON result. Sent verbatim as the request body. */
  schema: string;
  /** Request path the generated mocks match. Blank → server default `/graphql`. */
  path?: string;
}

/**
 * Import a GraphQL schema, generating one expectation per root operation type.
 * Returns the list of created/updated expectations the server reports.
 *
 * On failure throws an Error whose message carries the
 * `MockServer returned <status>: <body>` shape so callers can pass it through
 * {@link import('./errorMessage').humanizeError}. The server reports schema-parse
 * failures as a `400` with a `text/plain` body (the parser's message), which
 * humanizeError surfaces as "The request was rejected as invalid" with the raw
 * message kept in details.
 */
export async function importGraphql(
  params: ConnectionParams,
  options: GraphqlImportOptions,
): Promise<unknown[]> {
  const path = options.path?.trim();
  const query = path ? `?path=${encodeURIComponent(path)}` : '';
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/graphql${query}`, {
    method: 'PUT',
    // The schema is raw text (SDL or introspection JSON); the server reads the
    // body verbatim, so send text/plain rather than a JSON envelope.
    headers: { 'Content-Type': 'text/plain' },
    body: options.schema,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`MockServer returned ${res.status}: ${text}`);
  }
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? body : [];
}
