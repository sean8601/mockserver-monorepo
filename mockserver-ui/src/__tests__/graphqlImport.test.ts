import { describe, it, expect, vi, afterEach } from 'vitest';
import { importGraphql } from '../lib/graphqlImport';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

const SDL = 'type Query { hello: String }';

describe('importGraphql', () => {
  it('PUTs the schema verbatim as the body to /mockserver/graphql and returns the created expectations', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [{ id: '1' }, { id: '2' }] });
    vi.stubGlobal('fetch', fetchMock);

    const created = await importGraphql(params, { schema: SDL });
    expect(created).toHaveLength(2);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/graphql');
    expect((init as RequestInit).method).toBe('PUT');
    expect((init as RequestInit).headers).toEqual({ 'Content-Type': 'text/plain' });
    // Schema is sent verbatim, NOT wrapped in a JSON envelope.
    expect((init as RequestInit).body).toBe(SDL);
  });

  it('passes a non-blank path as the ?path= query parameter (url-encoded)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);

    await importGraphql(params, { schema: SDL, path: '/api/graphql' });
    const [url] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/graphql?path=%2Fapi%2Fgraphql');
  });

  it('omits the path query parameter when path is blank or whitespace', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);

    await importGraphql(params, { schema: SDL, path: '   ' });
    const [url] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/graphql');
  });

  it('throws a humanizable "MockServer returned <status>: <body>" error on a 400', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      text: async () => 'Unable to parse GraphQL schema: invalid syntax',
    }));
    await expect(importGraphql(params, { schema: 'not a schema' }))
      .rejects.toThrow('MockServer returned 400: Unable to parse GraphQL schema: invalid syntax');
  });

  it('returns an empty array when the server body is not an array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    expect(await importGraphql(params, { schema: SDL })).toEqual([]);
  });
});
