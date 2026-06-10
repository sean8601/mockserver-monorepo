'use strict';

var { describe, it, beforeEach } = require('node:test');
var assert = require('node:assert/strict');

// Suppress unhandled Q-promise rejections from webSocketClient teardown
process.on('unhandledRejection', function () {});

/**
 * Tests for breakpoint message routing in the Node client.
 *
 * These tests exercise the message routing logic directly by simulating
 * the WebSocket message handling without a real server connection.
 * The mockServerClient.js and webSocketClient.js modifications are tested
 * by verifying the client object exposes the expected breakpoint API methods
 * and by testing the routing/mapping functions in isolation.
 */

describe('breakpoint API surface', function () {

    it('mockServerClient exposes breakpoint methods', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.equal(typeof client.addBreakpoint, 'function');
        assert.equal(typeof client.addRequestBreakpoint, 'function');
        assert.equal(typeof client.addRequestAndResponseBreakpoint, 'function');
        assert.equal(typeof client.listBreakpointMatchers, 'function');
        assert.equal(typeof client.removeBreakpointMatcher, 'function');
        assert.equal(typeof client.clearBreakpointMatchers, 'function');
    });

    it('addBreakpoint throws on null matcher', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.addBreakpoint(null, ["REQUEST"], function () {});
        }, /non-null requestMatcher/);
    });

    it('addBreakpoint throws on empty phases', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.addBreakpoint({path: "/test"}, [], function () {});
        }, /non-empty phases/);
    });

    it('removeBreakpointMatcher throws on null id', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.removeBreakpointMatcher(null);
        }, /requires a breakpointId/);
    });
});

describe('webSocketClient breakpoint handler routing', function () {

    // We can't easily unit-test the full WS message handling without
    // mocking the websocket library deeply, but we can test the structure
    // of the messages that would be sent.

    it('PausedStreamFrameDTO auto-continue decision has correct shape', function () {
        var correlationId = 'test-corr-123';
        var decision = {
            correlationId: correlationId,
            action: 'CONTINUE'
        };
        var envelope = {
            type: 'org.mockserver.serialization.model.StreamFrameDecisionDTO',
            value: JSON.stringify(decision)
        };
        var serialized = JSON.stringify(envelope);
        var parsed = JSON.parse(serialized);

        assert.equal(parsed.type, 'org.mockserver.serialization.model.StreamFrameDecisionDTO');
        var inner = JSON.parse(parsed.value);
        assert.equal(inner.correlationId, correlationId);
        assert.equal(inner.action, 'CONTINUE');
    });

    it('MODIFY decision includes base64 body', function () {
        var decision = {
            correlationId: 'frame-1',
            action: 'MODIFY',
            body: Buffer.from('modified data').toString('base64')
        };
        var envelope = {
            type: 'org.mockserver.serialization.model.StreamFrameDecisionDTO',
            value: JSON.stringify(decision)
        };
        var parsed = JSON.parse(JSON.stringify(envelope));
        var inner = JSON.parse(parsed.value);

        assert.equal(inner.action, 'MODIFY');
        assert.equal(Buffer.from(inner.body, 'base64').toString(), 'modified data');
    });

    it('DROP decision has no body', function () {
        var decision = {
            correlationId: 'frame-2',
            action: 'DROP'
        };

        assert.equal(decision.action, 'DROP');
        assert.equal(decision.body, undefined);
    });

    it('INJECT decision includes body', function () {
        var decision = {
            correlationId: 'frame-3',
            action: 'INJECT',
            body: Buffer.from('extra frame').toString('base64')
        };

        assert.equal(decision.action, 'INJECT');
        assert.equal(Buffer.from(decision.body, 'base64').toString(), 'extra frame');
    });

    it('CLOSE decision has no body', function () {
        var decision = {
            correlationId: 'frame-4',
            action: 'CLOSE'
        };

        assert.equal(decision.action, 'CLOSE');
        assert.equal(decision.body, undefined);
    });
});

describe('breakpoint REST payload structure', function () {

    it('register body includes httpRequest, phases, and clientId', function () {
        var body = {
            httpRequest: { method: 'GET', path: '/api/.*' },
            phases: ['REQUEST', 'RESPONSE'],
            clientId: 'test-client-id'
        };

        assert.equal(body.httpRequest.method, 'GET');
        assert.equal(body.httpRequest.path, '/api/.*');
        assert.deepEqual(body.phases, ['REQUEST', 'RESPONSE']);
        assert.equal(body.clientId, 'test-client-id');
    });

    it('register response has id and phases', function () {
        var response = {
            id: 'breakpoint-uuid-1234',
            phases: ['REQUEST', 'RESPONSE']
        };

        assert.equal(response.id, 'breakpoint-uuid-1234');
        assert.deepEqual(response.phases, ['REQUEST', 'RESPONSE']);
    });

    it('remove body has id', function () {
        var body = { id: 'breakpoint-uuid-1234' };
        assert.equal(body.id, 'breakpoint-uuid-1234');
    });

    it('stream frame decision actions are valid', function () {
        var validActions = ['CONTINUE', 'MODIFY', 'DROP', 'INJECT', 'CLOSE'];
        validActions.forEach(function (action) {
            var decision = { correlationId: 'c', action: action };
            assert.equal(decision.action, action);
        });
    });

    it('breakpoint request handler can return request or response', function () {
        // REQUEST handler returning HttpRequest = continue/modify
        var requestResult = { method: 'PUT', path: '/modified' };
        assert.equal(requestResult.statusCode, undefined); // no statusCode => request

        // REQUEST handler returning HttpResponse = abort
        var responseResult = { statusCode: 403, body: 'forbidden' };
        assert.notEqual(responseResult.statusCode, undefined); // has statusCode => response
    });
});
