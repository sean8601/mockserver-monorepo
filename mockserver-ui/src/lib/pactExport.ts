/**
 * Client for MockServer's Pact export endpoint (`PUT /mockserver/pact`).
 * Exports the active response expectations as a Pact v3 consumer contract.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Export the active response expectations as a Pact v3 consumer contract.
 *
 * @param consumer optional consumer name (server defaults to "consumer")
 * @param provider optional provider name (server defaults to "provider")
 * @returns the parsed Pact contract JSON
 */
export async function exportPact(
  params: ConnectionParams,
  consumer: string,
  provider: string,
): Promise<unknown> {
  const query = new URLSearchParams();
  if (consumer.trim()) query.set('consumer', consumer.trim());
  if (provider.trim()) query.set('provider', provider.trim());
  const suffix = query.toString() ? `?${query.toString()}` : '';
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/pact${suffix}`, {
    method: 'PUT',
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<unknown>;
}
