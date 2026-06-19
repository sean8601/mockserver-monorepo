import { describe, it, expect, vi, afterEach } from 'vitest';
import { importOpenApi, discoverNamedExamples } from '../lib/openapiImport';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, body: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => body,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('importOpenApi', () => {
  it('PUTs the spec wrapped as an OpenAPIExpectation and returns created expectations', async () => {
    const created = [{ id: 'openapi:foo:listPets' }, { id: 'openapi:foo:createPets' }];
    const calls = stubFetch(201, created);
    const result = await importOpenApi(params, '{"openapi":"3.0.0"}');
    expect(result).toEqual(created);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/openapi');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(String(calls[0]?.init?.body)).toContain('specUrlOrPayload');
  });

  it('returns an empty array when the response is not an array', async () => {
    stubFetch(201, { unexpected: true });
    expect(await importOpenApi(params, 'https://example.com/spec.yaml')).toEqual([]);
  });

  it('throws the server message on an invalid spec', async () => {
    stubFetch(400, 'unable to load API spec');
    await expect(importOpenApi(params, 'not a spec')).rejects.toThrow('unable to load API spec');
  });

  it('sends operationsAndResponses with statusCode and exampleName when selections are given', async () => {
    const calls = stubFetch(201, []);
    await importOpenApi(params, '{"openapi":"3.0.0"}', {
      listPets: { statusCode: '200', exampleName: 'two' },
    });
    const sent = JSON.parse(String(calls[0]?.init?.body)) as Array<Record<string, unknown>>;
    expect(sent[0]?.operationsAndResponses).toEqual({
      listPets: { statusCode: '200', exampleName: 'two' },
    });
  });

  it('omits operationsAndResponses when no selections are given', async () => {
    const calls = stubFetch(201, []);
    await importOpenApi(params, '{"openapi":"3.0.0"}', {});
    const sent = JSON.parse(String(calls[0]?.init?.body)) as Array<Record<string, unknown>>;
    expect(sent[0]).not.toHaveProperty('operationsAndResponses');
  });
});

const specWithNamedExamples = JSON.stringify({
  openapi: '3.0.0',
  paths: {
    '/pets': {
      get: {
        operationId: 'listPets',
        responses: {
          '200': {
            content: {
              'application/json': {
                examples: {
                  oneCat: { value: [{ name: 'cat' }] },
                  twoDogs: { value: [{ name: 'dog' }, { name: 'rex' }] },
                },
              },
            },
          },
        },
      },
      post: {
        operationId: 'createPet',
        responses: {
          '201': {
            content: {
              'application/json': {
                // only one named example — nothing to choose
                examples: { created: { value: { name: 'cat' } } },
              },
            },
          },
        },
      },
    },
  },
});

describe('discoverNamedExamples', () => {
  it('returns operations that declare two or more named examples', () => {
    const result = discoverNamedExamples(specWithNamedExamples);
    expect(result).toEqual([
      { operationId: 'listPets', statusCode: '200', exampleNames: ['oneCat', 'twoDogs'] },
    ]);
  });

  it('returns an empty list for a URL spec', () => {
    expect(discoverNamedExamples('https://example.com/spec.yaml')).toEqual([]);
  });

  it('returns an empty list for YAML or unparseable input', () => {
    expect(discoverNamedExamples('openapi: 3.0.0\npaths: {}')).toEqual([]);
    expect(discoverNamedExamples('{ not valid json')).toEqual([]);
  });

  it('returns an empty list when no operation has named examples', () => {
    const spec = JSON.stringify({
      openapi: '3.0.0',
      paths: { '/ping': { get: { operationId: 'ping', responses: { '200': {} } } } },
    });
    expect(discoverNamedExamples(spec)).toEqual([]);
  });

  it('only inspects the first media type, mirroring the server content selection', () => {
    // The server uses the first content entry, so a named example on a second
    // media type cannot be honoured and must not be offered in the picker.
    const spec = JSON.stringify({
      openapi: '3.0.0',
      paths: {
        '/pets': {
          get: {
            operationId: 'listPets',
            responses: {
              '200': {
                content: {
                  'application/json': { examples: { a: { value: 1 }, b: { value: 2 } } },
                  'application/xml': { examples: { xmlOnly: { value: '<x/>' } } },
                },
              },
            },
          },
        },
      },
    });
    expect(discoverNamedExamples(spec)).toEqual([
      { operationId: 'listPets', statusCode: '200', exampleNames: ['a', 'b'] },
    ]);
  });

  it('does not resolve $ref responses (no picker for $ref-only examples)', () => {
    const spec = JSON.stringify({
      openapi: '3.0.0',
      paths: {
        '/pets': {
          get: {
            operationId: 'listPets',
            responses: { '200': { $ref: '#/components/responses/PetList' } },
          },
        },
      },
      components: {
        responses: {
          PetList: {
            content: { 'application/json': { examples: { a: { value: 1 }, b: { value: 2 } } } },
          },
        },
      },
    });
    expect(discoverNamedExamples(spec)).toEqual([]);
  });
});
