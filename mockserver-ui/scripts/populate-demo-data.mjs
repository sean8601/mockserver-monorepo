#!/usr/bin/env node
/**
 * populate-demo-data.mjs
 * ----------------------
 * Populate a running MockServer with a rich, varied dataset so every dashboard
 * UI view can be exercised by hand:
 *
 *   - Active expectations    plain HTTP (varied verbs / status / bodies / headers
 *                            / query / cookies / delay / times / priority), a
 *                            forward, and LLM response mocks for every provider.
 *   - Traffic               recorded request/response pairs (matched + unmatched)
 *                            including one classified lane per LLM provider, each
 *                            carrying token usage so the cost detail populates.
 *   - Sessions / call graph  multi-turn agent loops sharing an isolation header,
 *                            grouped via a conversation expectation's scenarioName.
 *   - Predicate pills        a showcase conversation expectation exercising every
 *                            predicate type (incl. semanticMatchAgainst + a
 *                            normalization block) and a chaos profile.
 *
 * It talks to MockServer over its plain REST API (no extra dependencies — uses
 * the built-in global fetch in Node 18+). Safe to re-run: it resets first.
 *
 * Usage:
 *   node scripts/populate-demo-data.mjs [--url http://localhost:1080] [--quiet]
 *   MOCKSERVER_URL=http://localhost:1080 node scripts/populate-demo-data.mjs
 */

// ---------------------------------------------------------------------------
// Configuration & tiny CLI parsing
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = { url: process.env.MOCKSERVER_URL || 'http://localhost:1080', quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--url') {
      opts.url = argv[++i];
      if (opts.url == null) { console.error('ERROR: --url requires a value'); process.exit(1); }
    }
    else if (arg.startsWith('--url=')) opts.url = arg.slice('--url='.length);
    else if (arg === '--quiet') opts.quiet = true;
    else if (arg === '--help' || arg === '-h') {
      console.log('Usage: node scripts/populate-demo-data.mjs [--url <baseUrl>] [--quiet]');
      process.exit(0);
    }
  }
  opts.url = opts.url.replace(/\/+$/, '');
  return opts;
}

const { url: BASE, quiet } = parseArgs(process.argv.slice(2));

// Self address — used to forward /proxy/* paths back to this same MockServer so
// the demo can produce proxied (forwarded) traffic without an external upstream.
const TARGET = new URL(BASE);
const SELF_HOST = TARGET.hostname;
const SELF_PORT = Number(TARGET.port || (TARGET.protocol === 'https:' ? 443 : 80));
const SELF_SCHEME = TARGET.protocol === 'https:' ? 'HTTPS' : 'HTTP';

const counts = { expectations: 0, requests: 0, unmatched: 0 };
function log(msg) { if (!quiet) console.log(msg); }

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

async function api(method, path, body, headers = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
  });
  // Drain the body so the connection is freed (and SSE streams complete).
  await res.text().catch(() => undefined);
  return res;
}

async function expectation(label, expectationJson) {
  const res = await api('PUT', '/mockserver/expectation', expectationJson);
  if (!res.ok) throw new Error(`Failed to create expectation "${label}": HTTP ${res.status}`);
  counts.expectations++;
  log(`   + expectation  ${label}`);
}

async function traffic(label, method, path, { body, headers } = {}) {
  const res = await api(method, path, body, headers);
  if (res.status === 404) counts.unmatched++;
  counts.requests++;
  log(`   > ${method.padEnd(4)} ${path}  ->  ${res.status}   (${label})`);
}

// ---------------------------------------------------------------------------
// Provider catalogue (paths + request bodies + token usage)
// ---------------------------------------------------------------------------

const PROVIDERS = {
  ANTHROPIC: {
    model: 'claude-sonnet-4-20250514',
    path: '/v1/messages',
    usage: { inputTokens: 1840, outputTokens: 320 },
    request: {
      model: 'claude-sonnet-4-20250514',
      max_tokens: 1024,
      messages: [{ role: 'user', content: 'What is the weather in Paris?' }],
    },
  },
  OPENAI: {
    model: 'gpt-4o',
    path: '/v1/chat/completions',
    usage: { inputTokens: 920, outputTokens: 210 },
    request: {
      model: 'gpt-4o',
      messages: [{ role: 'user', content: 'Summarise the quarterly report.' }],
    },
  },
  OPENAI_RESPONSES: {
    model: 'gpt-4o',
    path: '/v1/responses',
    usage: { inputTokens: 1500, outputTokens: 540 },
    request: {
      model: 'gpt-4o',
      input: [{ role: 'user', content: 'Draft a release announcement.' }],
    },
  },
  GEMINI: {
    model: 'gemini-2.0-flash',
    path: '/v1beta/models/gemini-2.0-flash/generateContent',
    usage: { inputTokens: 2100, outputTokens: 410 },
    request: {
      contents: [{ role: 'user', parts: [{ text: 'Explain vector databases briefly.' }] }],
    },
  },
  OLLAMA: {
    model: 'llama3.2',
    path: '/api/chat',
    usage: { inputTokens: 300, outputTokens: 120 },
    request: {
      model: 'llama3.2',
      messages: [{ role: 'user', content: 'Give me a haiku about testing.' }],
    },
  },
};

const SCENARIO = '__llm_conv_weather_agent__iso=header:x-agent-id';

// ---------------------------------------------------------------------------
// 1. Plain HTTP expectations
// ---------------------------------------------------------------------------

async function plainHttpExpectations() {
  log('\n→ Plain HTTP expectations');

  await expectation('GET /api/users (JSON list)', {
    httpRequest: { method: 'GET', path: '/api/users' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'], 'x-demo': ['users'] },
      body: { json: [{ id: 1, name: 'Alice' }, { id: 2, name: 'Bob' }] },
    },
  });

  await expectation('GET /api/users/{id} (path + query)', {
    httpRequest: {
      method: 'GET',
      path: '/api/users/42',
      queryStringParameters: { expand: ['profile', 'orders'] },
    },
    httpResponse: {
      statusCode: 200,
      body: { json: { id: 42, name: 'Carol', profile: { tier: 'gold' } } },
    },
  });

  await expectation('POST /api/users (201 created)', {
    httpRequest: {
      method: 'POST',
      path: '/api/users',
      headers: { 'content-type': ['application/json'] },
    },
    httpResponse: {
      statusCode: 201,
      headers: { location: ['/api/users/99'] },
      body: { json: { id: 99, status: 'created' } },
    },
  });

  await expectation('PUT /api/users/42 (204 no content)', {
    httpRequest: { method: 'PUT', path: '/api/users/42' },
    httpResponse: { statusCode: 204 },
  });

  await expectation('DELETE /api/users/42 (limited times)', {
    httpRequest: { method: 'DELETE', path: '/api/users/42' },
    httpResponse: { statusCode: 200, body: 'deleted' },
    times: { remainingTimes: 2, unlimited: false },
  });

  await expectation('GET /api/report.xml (XML body + delay)', {
    httpRequest: { method: 'GET', path: '/api/report.xml' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['application/xml'] },
      body: '<report><total>128</total><status>ok</status></report>',
      delay: { timeUnit: 'MILLISECONDS', value: 400 },
    },
  });

  await expectation('GET /api/page.html (HTML body)', {
    httpRequest: { method: 'GET', path: '/api/page.html' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['text/html'] },
      body: '<html><body><h1>Demo</h1></body></html>',
    },
  });

  await expectation('POST /api/login (cookie + 401 variant)', {
    httpRequest: {
      method: 'POST',
      path: '/api/login',
      cookies: { session: 'expired' },
    },
    httpResponse: {
      statusCode: 401,
      body: { json: { error: 'session expired' } },
    },
  });

  await expectation('GET /api/flaky (500 error)', {
    httpRequest: { method: 'GET', path: '/api/flaky' },
    httpResponse: { statusCode: 500, body: { json: { error: 'internal error' } } },
  });

  await expectation('GET /api/priority (high priority override)', {
    priority: 100,
    httpRequest: { method: 'GET', path: '/api/priority' },
    httpResponse: { statusCode: 200, body: 'high-priority winner' },
  });

}

// ---------------------------------------------------------------------------
// 1b. Proxy / forward expectations + traffic (self-forwarded — no upstream needed)
// ---------------------------------------------------------------------------

// Forwarded ("proxied") request/response pairs power the dashboard's proxied
// traffic lane and proxied sessions. To produce them without an external
// upstream, each /proxy/* path is forwarded back to a mock on THIS MockServer
// (a path rewrite via httpOverrideForwardedRequest), so the demo works offline.
const PROXY_FORWARDS = [
  { from: '/proxy/users', to: '/api/users' },
  { from: '/proxy/report', to: '/api/report.xml' },
  { from: '/proxy/flaky', to: '/api/flaky' },
];

async function proxyExpectations() {
  log('\n→ Proxy / forward expectations (self-forwarded, no upstream needed)');
  for (const f of PROXY_FORWARDS) {
    await expectation(`forward ${f.from} -> ${f.to}`, {
      httpRequest: { method: 'GET', path: f.from },
      httpOverrideForwardedRequest: {
        httpRequest: {
          path: f.to,
          socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
        },
      },
    });
  }
}

async function proxyTraffic() {
  log('\n→ Proxied traffic (forwarded request/response pairs)');
  await traffic('proxied users', 'GET', '/proxy/users');
  await traffic('proxied report', 'GET', '/proxy/report');
  await traffic('proxied flaky (500)', 'GET', '/proxy/flaky');
  await traffic('proxied users (again)', 'GET', '/proxy/users');
}

// ---------------------------------------------------------------------------
// 2. LLM response expectations (one per provider + tool / streaming / chaos)
// ---------------------------------------------------------------------------

async function llmExpectations() {
  log('\n→ LLM response expectations');

  for (const [provider, p] of Object.entries(PROVIDERS)) {
    await expectation(`${provider} single-shot (${p.model})`, {
      httpRequest: { method: 'POST', path: p.path },
      httpLlmResponse: {
        provider,
        model: p.model,
        completion: {
          text: `Mocked ${provider} reply with realistic token usage.`,
          stopReason: 'end_turn',
          usage: p.usage,
        },
      },
    });
  }

  await expectation('ANTHROPIC tool-call (get_weather)', {
    // Higher priority so these query-scoped variants win over the catch-all
    // single-shot expectations that share the same path.
    priority: 20,
    httpRequest: { method: 'POST', path: '/v1/messages', queryStringParameters: { demo: ['tools'] } },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Let me look that up.',
        toolCalls: [{ id: 'toolu_demo1', name: 'get_weather', arguments: '{"city":"Paris"}' }],
        stopReason: 'tool_use',
        usage: { inputTokens: 640, outputTokens: 60 },
      },
    },
  });

  await expectation('OPENAI streaming (SSE physics)', {
    priority: 20,
    httpRequest: { method: 'POST', path: '/v1/chat/completions', queryStringParameters: { stream: ['true'] } },
    httpLlmResponse: {
      provider: 'OPENAI',
      model: 'gpt-4o',
      completion: {
        text: 'This response is streamed token by token so you can watch the SSE timeline render.',
        streaming: true,
        streamingPhysics: { tokensPerSecond: 40, jitter: 0.25 },
        usage: { inputTokens: 120, outputTokens: 60 },
      },
    },
  });

  await expectation('OPENAI chaos (429 + Retry-After)', {
    priority: 20,
    httpRequest: { method: 'POST', path: '/v1/chat/completions', queryStringParameters: { chaos: ['429'] } },
    httpLlmResponse: {
      provider: 'OPENAI',
      model: 'gpt-4o',
      completion: { text: 'You should not see this — chaos injects an error.', usage: { inputTokens: 50, outputTokens: 10 } },
      chaos: { errorStatus: 429, retryAfter: '30', errorProbability: 1.0, seed: 42 },
    },
  });
}

// ---------------------------------------------------------------------------
// 3. Conversation expectations (Sessions grouping + predicate pills)
// ---------------------------------------------------------------------------

async function conversationExpectations() {
  log('\n→ Conversation expectations (Sessions + predicate pills)');

  // Turn expectations that actually drive the agent loop below. turnIndex is the
  // count of prior user turns in the request, so 0/1/2 match successive calls.
  await expectation('weather agent · turn 0 (tool_use)', {
    scenarioName: SCENARIO,
    priority: 15,
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Let me search for the weather.',
        toolCalls: [{ id: 'toolu_w0', name: 'search_weather', arguments: '{"city":"Paris"}' }],
        stopReason: 'tool_use',
        usage: { inputTokens: 210, outputTokens: 40 },
      },
      conversationPredicates: { turnIndex: 0, latestMessageRole: 'USER' },
    },
  });

  await expectation('weather agent · turn 1 (final answer, after tool_result)', {
    scenarioName: SCENARIO,
    priority: 15,
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'It is 18°C and sunny in Paris.',
        stopReason: 'end_turn',
        usage: { inputTokens: 360, outputTokens: 24 },
      },
      conversationPredicates: { turnIndex: 1, containsToolResultFor: 'search_weather' },
    },
  });

  // Showcase expectation exercising EVERY predicate type + normalization + chaos,
  // so the dashboard renders the full set of predicate pills. A high turnIndex
  // keeps it from intercepting the loop traffic above — it is here to be seen.
  await expectation('predicate showcase (all pills + normalization + chaos)', {
    scenarioName: SCENARIO,
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Showcase response demonstrating every conversation predicate.',
        stopReason: 'end_turn',
        usage: { inputTokens: 800, outputTokens: 90 },
      },
      conversationPredicates: {
        turnIndex: 9,
        latestMessageContains: 'weather',
        latestMessageMatches: '^What is the.*Paris.*\\?$',
        latestMessageRole: 'USER',
        containsToolResultFor: 'search_weather',
        semanticMatchAgainst: 'the user is asking about the weather forecast',
        normalization: {
          collapseWhitespace: true,
          lowercase: false,
          sortJsonKeys: true,
          dropBuiltInVolatileFields: true,
          dropVolatileFields: ['request_id', 'timestamp'],
        },
      },
    },
  });
}

// ---------------------------------------------------------------------------
// 4. Recorded traffic (Traffic view, token/cost, unmatched diagnostics)
// ---------------------------------------------------------------------------

async function plainHttpTraffic() {
  log('\n→ Plain HTTP traffic');
  await traffic('list users', 'GET', '/api/users');
  await traffic('get user', 'GET', '/api/users/42?expand=profile&expand=orders');
  await traffic('create user', 'POST', '/api/users', { body: { name: 'Dave' } });
  await traffic('update user', 'PUT', '/api/users/42', { body: { name: 'Carol II' } });
  await traffic('xml report', 'GET', '/api/report.xml');
  await traffic('flaky 500', 'GET', '/api/flaky');
  // Unmatched requests — exercise the "no expectation matched" diagnostics.
  await traffic('unmatched', 'GET', '/api/does-not-exist');
  await traffic('unmatched', 'POST', '/api/unknown', { body: { foo: 'bar' } });
}

async function llmTraffic() {
  log('\n→ LLM traffic (one classified lane per provider, with token usage)');
  for (const [provider, p] of Object.entries(PROVIDERS)) {
    await traffic(`${provider} completion`, 'POST', p.path, {
      body: p.request,
      headers: { 'content-type': 'application/json' },
    });
  }
  await traffic('ANTHROPIC tool-call', 'POST', '/v1/messages?demo=tools', {
    body: PROVIDERS.ANTHROPIC.request,
  });
  await traffic('OPENAI streaming', 'POST', '/v1/chat/completions?stream=true', {
    body: PROVIDERS.OPENAI.request,
  });
  await traffic('OPENAI chaos 429', 'POST', '/v1/chat/completions?chaos=429', {
    body: PROVIDERS.OPENAI.request,
  });
}

async function agentLoops() {
  log('\n→ Agent loops (Sessions + call graph, grouped by x-agent-id)');

  // A full two-turn weather agent loop for agent-001: user → assistant tool_use
  // → user tool_result → assistant final answer. The request bodies carry the
  // growing history the call graph is reconstructed from.
  const turn1Body = {
    model: 'claude-sonnet-4-20250514',
    max_tokens: 1024,
    messages: [{ role: 'user', content: 'What is the weather in Paris?' }],
  };
  const turn2Body = {
    model: 'claude-sonnet-4-20250514',
    max_tokens: 1024,
    messages: [
      { role: 'user', content: 'What is the weather in Paris?' },
      {
        role: 'assistant',
        content: [
          { type: 'text', text: 'Let me search for the weather.' },
          { type: 'tool_use', id: 'toolu_w0', name: 'search_weather', input: { city: 'Paris' } },
        ],
      },
      { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_w0', content: '18C, sunny' }] },
    ],
  };

  for (const agent of ['agent-001', 'agent-002']) {
    await traffic(`${agent} turn 1`, 'POST', '/v1/messages', {
      body: turn1Body,
      headers: { 'x-agent-id': agent },
    });
    await traffic(`${agent} turn 2`, 'POST', '/v1/messages', {
      body: turn2Body,
      headers: { 'x-agent-id': agent },
    });
  }
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

async function main() {
  log(`\nPopulating MockServer demo data at ${BASE}\n`);

  // Fail fast with a clear message if the server is not reachable.
  let status;
  try {
    status = await api('PUT', '/mockserver/status');
  } catch (err) {
    console.error(`ERROR: cannot reach MockServer at ${BASE} (${err.message}).`);
    console.error('Start MockServer first, or pass --url <baseUrl>.');
    process.exit(1);
  }
  if (!status.ok) {
    console.error(`ERROR: MockServer at ${BASE} returned HTTP ${status.status} for /mockserver/status.`);
    process.exit(1);
  }

  log('→ Resetting MockServer');
  const reset = await api('PUT', '/mockserver/reset');
  if (!reset.ok) {
    console.error(`ERROR: reset failed (HTTP ${reset.status}) — is authentication enabled on this MockServer?`);
    process.exit(1);
  }

  await plainHttpExpectations();
  await proxyExpectations();
  await llmExpectations();
  await conversationExpectations();
  await plainHttpTraffic();
  await proxyTraffic();
  await llmTraffic();
  await agentLoops();

  log('\n========================================');
  log(' Demo data loaded');
  log('========================================');
  log(` Expectations created : ${counts.expectations}`);
  log(` Requests sent        : ${counts.requests} (incl. ~${counts.unmatched} intentionally unmatched)`);
  log('');
  log(' Try these views in the dashboard:');
  log('   Dashboard / Library — active expectations (HTTP, forward, LLM, conversation pills)');
  log('   Traffic            — recorded + proxied (forwarded) requests, incl. a lane per LLM provider + token/cost');
  log('   Sessions           — agent-001 / agent-002 loops + their call graphs');
  log('');
}

main().catch((err) => {
  console.error(`\nERROR: ${err?.message ?? String(err)}`);
  process.exit(1);
});
