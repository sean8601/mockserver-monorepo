'use strict';

/*
 * Pure unit tests for the LLM mocking builder API (llm.js). These assert the
 * exact expectation wire JSON produced by the builders — no running server is
 * required — to guarantee byte-for-byte parity with the Java client builders.
 */

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');

var llm = require('../../llm');

// Round-trip through JSON.stringify/parse so toJSON() is applied and
// null/undefined fields are dropped, exactly as the over-the-wire body would be.
function wire(value) {
    return JSON.parse(JSON.stringify(value));
}

// =========================================================================
describe('llm builder — basic completion mock', function () {
    it('produces the expected httpLlmResponse expectation JSON', function () {
        var expectation = llm.llmMock('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                llm.completion()
                    .withText('The capital of France is Paris.')
                    .withStopReason('end_turn')
                    .withUsage(llm.usage().withInputTokens(42).withOutputTokens(8))
            )
            .build();

        assert.deepEqual(wire(expectation), {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            httpLlmResponse: {
                provider: 'ANTHROPIC',
                model: 'claude-sonnet-4',
                completion: {
                    text: 'The capital of France is Paris.',
                    stopReason: 'end_turn',
                    usage: { inputTokens: 42, outputTokens: 8 }
                }
            }
        });
    });

    it('omits null fields (usage with only input tokens)', function () {
        var expectation = llm.llmMock('/v1/chat/completions')
            .withProvider(llm.Provider.OPENAI)
            .withModel('gpt-4o')
            .respondingWith(llm.completion().withText('hi').withUsage(llm.inputTokens(5)))
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse.completion, {
            text: 'hi',
            usage: { inputTokens: 5 }
        });
    });

    it('supports an embedding response', function () {
        var expectation = llm.llmMock('/v1/embeddings')
            .withProvider(llm.Provider.OPENAI)
            .withModel('text-embedding-3-small')
            .respondingWith(llm.embedding().withDimensions(1536).withDeterministicFromInput(true))
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse, {
            provider: 'OPENAI',
            model: 'text-embedding-3-small',
            embedding: { dimensions: 1536, deterministicFromInput: true }
        });
    });
});

// =========================================================================
describe('llm builder — tool-use mock', function () {
    it('encodes tool calls with id/name/arguments', function () {
        var expectation = llm.llmMock('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                llm.completion()
                    .withStopReason('tool_use')
                    .withToolCall(
                        llm.toolUse('get_weather')
                            .withId('toolu_01')
                            .withArguments('{"city":"Paris"}')
                    )
            )
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse.completion, {
            stopReason: 'tool_use',
            toolCalls: [
                { id: 'toolu_01', name: 'get_weather', arguments: '{"city":"Paris"}' }
            ]
        });
    });

    it('accepts an object for arguments and serialises it to a JSON string', function () {
        var completion = llm.completion().withToolCall(
            llm.toolUse('search').withArguments({ q: 'mockserver' })
        );
        assert.equal(wire(completion).toolCalls[0].arguments, '{"q":"mockserver"}');
    });
});

// =========================================================================
describe('llm builder — streaming physics', function () {
    it('encodes streaming + timeToFirstToken as a Delay, tokensPerSecond, jitter', function () {
        var expectation = llm.llmMock('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                llm.completion()
                    .withText('streamed')
                    .streaming()
                    .withStreamingPhysics(
                        llm.streamingPhysics()
                            .withTimeToFirstToken(llm.timeToFirstToken(500, 'MILLISECONDS'))
                            .withTokensPerSecond(50)
                            .withJitter(0.2)
                    )
            )
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse.completion, {
            text: 'streamed',
            streaming: true,
            streamingPhysics: {
                timeToFirstToken: { timeUnit: 'MILLISECONDS', value: 500 },
                tokensPerSecond: 50,
                jitter: 0.2
            }
        });
    });
});

// =========================================================================
describe('llm builder — multi-turn conversation', function () {
    it('produces one scenario-stateful expectation per turn', function () {
        var expectations = llm.conversation()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .turn()
                .whenTurnIndex(0)
                .respondingWith(llm.completion().withToolCall(llm.toolUse('search').withArguments('{}')))
            .andThen()
            .turn()
                .whenContainsToolResultFor('search')
                .respondingWith(llm.completion().withText('The answer is 42.'))
            .andThen()
            .build();

        assert.equal(expectations.length, 2);

        var wired = wire(expectations);

        // shared, auto-generated scenario name with the reserved prefix
        var scenarioName = wired[0].scenarioName;
        assert.match(scenarioName, /^__llm_conv_[0-9a-f-]{36}$/);
        assert.equal(wired[1].scenarioName, scenarioName);

        // turn 0: Started -> turn_1
        assert.deepEqual(wired[0], {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            scenarioName: scenarioName,
            scenarioState: 'Started',
            newScenarioState: 'turn_1',
            httpLlmResponse: {
                provider: 'ANTHROPIC',
                model: 'claude-sonnet-4',
                completion: { toolCalls: [{ name: 'search', arguments: '{}' }] },
                conversationPredicates: { turnIndex: 0 }
            }
        });

        // turn 1: turn_1 -> __done
        assert.deepEqual(wired[1], {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            scenarioName: scenarioName,
            scenarioState: 'turn_1',
            newScenarioState: '__done',
            httpLlmResponse: {
                provider: 'ANTHROPIC',
                model: 'claude-sonnet-4',
                completion: { text: 'The answer is 42.' },
                conversationPredicates: { containsToolResultFor: 'search' }
            }
        });
    });

    it('encodes the isolation source into the scenario name', function () {
        var expectations = llm.conversation()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .isolateBy(llm.header('x-session-id'))
            .turn().whenTurnIndex(0).respondingWith(llm.completion().withText('hi'))
            .andThen()
            .build();

        assert.match(expectations[0].scenarioName, /^__llm_conv_[0-9a-f-]{36}__iso=header:x-session-id$/);
    });
});

// =========================================================================
describe('llm builder — provider failover', function () {
    it('coalesces same-status failures then appends an unlimited success', function () {
        var expectations = llm.llmFailover()
            .withPath('/v1/chat/completions')
            .withProvider(llm.Provider.OPENAI)
            .withModel('gpt-4o')
            .failWith(503, 2)
            .failWith(429)
            .thenRespondWith(llm.completion().withText('recovered'))
            .build();

        var wired = wire(expectations);
        assert.equal(wired.length, 3);

        // two coalesced 503s
        assert.deepEqual(wired[0].times, { remainingTimes: 2, unlimited: false });
        assert.equal(wired[0].httpResponse.statusCode, 503);
        assert.equal(JSON.parse(wired[0].httpResponse.body).error.type, 'service_unavailable');

        // one 429
        assert.deepEqual(wired[1].times, { remainingTimes: 1, unlimited: false });
        assert.equal(wired[1].httpResponse.statusCode, 429);

        // unlimited success LLM response
        assert.deepEqual(wired[2].times, { remainingTimes: 0, unlimited: true });
        assert.deepEqual(wired[2].httpLlmResponse, {
            provider: 'OPENAI',
            model: 'gpt-4o',
            completion: { text: 'recovered' }
        });
    });
});
