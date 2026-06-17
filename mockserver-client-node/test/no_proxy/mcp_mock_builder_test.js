'use strict';

/*
 * Pure unit tests for the MCP (Model Context Protocol) mock builder.
 *
 * These assert the exact expectation JSON produced by mcpMock(...).build(),
 * proving the Node client emits the same wire format as the Java client's
 * McpMockBuilder (JSON_RPC / JSON_PATH request matchers + a VELOCITY
 * httpResponseTemplate that renders a JSON-RPC 2.0 response echoing the
 * inbound id via $!{request.jsonRpcRawId}).
 *
 * No live server is required — the builder is a pure function.
 */

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');

// Require the builder both directly and via the index/client surface so the
// public wiring is covered too.
var mcpMock = require('../../mcpMockBuilder').mcpMock;
var indexMcpMock = require('../../index').mcpMock;
var mockServerClient = require('../../index').mockServerClient;

// ---------- helpers ----------

/** Find the single expectation whose request body matches the given JSON_RPC method. */
function findByJsonRpc(expectations, method) {
    return expectations.filter(function (e) {
        return e.httpRequest && e.httpRequest.body &&
            e.httpRequest.body.type === 'JSON_RPC' && e.httpRequest.body.method === method;
    });
}

/** Find the single expectation whose request body is a JSON_PATH containing the substring. */
function findByJsonPath(expectations, substring) {
    return expectations.filter(function (e) {
        return e.httpRequest && e.httpRequest.body &&
            e.httpRequest.body.type === 'JSON_PATH' &&
            e.httpRequest.body.jsonPath.indexOf(substring) !== -1;
    });
}

// =========================================================================
// public wiring
// =========================================================================

describe('mcpMock wiring', function () {
    it('is exported from the package index', function () {
        assert.equal(typeof mcpMock, 'function');
        assert.equal(typeof indexMcpMock, 'function');
    });

    it('is exposed as a method on a client instance', function () {
        var client = mockServerClient('localhost', 1080);
        assert.equal(typeof client.mcpMock, 'function');
        var builder = client.mcpMock('/mcp');
        assert.equal(typeof builder.withTool, 'function');
        assert.equal(typeof builder.build, 'function');
        assert.equal(typeof builder.applyTo, 'function');
    });

    it('defaults the path to /mcp', function () {
        var expectations = mcpMock().build();
        expectations.forEach(function (e) {
            assert.equal(e.httpRequest.path, '/mcp');
        });
    });
});

// =========================================================================
// base protocol expectations (initialize / ping / notifications)
// =========================================================================

describe('mcpMock base protocol expectations', function () {
    it('always emits initialize, ping and notifications/initialized', function () {
        var expectations = mcpMock('/mcp').build();
        assert.equal(expectations.length, 3);
        assert.equal(findByJsonRpc(expectations, 'initialize').length, 1);
        assert.equal(findByJsonRpc(expectations, 'ping').length, 1);
        assert.equal(findByJsonRpc(expectations, 'notifications/initialized').length, 1);
    });

    it('renders the initialize result with serverInfo and an empty capabilities object', function () {
        var init = findByJsonRpc(mcpMock('/mcp').build(), 'initialize')[0];
        assert.deepEqual(init.httpRequest, {
            method: 'POST',
            path: '/mcp',
            body: { type: 'JSON_RPC', method: 'initialize' }
        });
        assert.equal(init.httpResponseTemplate.templateType, 'VELOCITY');
        assert.equal(
            init.httpResponseTemplate.template,
            '{"statusCode": 200, ' +
            '"headers": [{"name": "Content-Type", "values": ["application/json"]}], ' +
            '"body": {"jsonrpc": "2.0", "result": ' +
            '{"protocolVersion": "2025-03-26", "capabilities": {}, ' +
            '"serverInfo": {"name": "MockMCPServer", "version": "1.0.0"}}' +
            ', "id": $!{request.jsonRpcRawId}}}'
        );
    });

    it('renders notifications/initialized as a plain 200 (not a template)', function () {
        var note = findByJsonRpc(mcpMock('/mcp').build(), 'notifications/initialized')[0];
        assert.equal(note.httpResponseTemplate, undefined);
        assert.deepEqual(note.httpResponse, {
            statusCode: 200,
            headers: [{ name: 'Content-Type', values: ['application/json'] }],
            body: '{}'
        });
    });

    it('honours withServerName / withServerVersion / withProtocolVersion', function () {
        var init = findByJsonRpc(
            mcpMock('/mcp').withServerName('S').withServerVersion('9').withProtocolVersion('2024-01-01').build(),
            'initialize'
        )[0];
        assert.ok(init.httpResponseTemplate.template.indexOf('"protocolVersion": "2024-01-01"') !== -1);
        assert.ok(init.httpResponseTemplate.template.indexOf('"name": "S", "version": "9"') !== -1);
    });
});

// =========================================================================
// tools
// =========================================================================

describe('mcpMock tool registration', function () {
    it('produces the exact tools/list and tools/call expectation JSON', function () {
        var expectations = mcpMock('/mcp')
            .withTool('get_weather')
            .withDescription('Get the weather for a city')
            .withInputSchema('{"type":"object","properties":{"city":{"type":"string"}}}')
            .respondingWith('sunny')
            .and()
            .build();

        // initialize, ping, notifications, tools/list, tools/call => 5
        assert.equal(expectations.length, 5);

        // ----- tools/list -----
        var list = findByJsonRpc(expectations, 'tools/list')[0];
        assert.deepEqual(list.httpRequest, {
            method: 'POST',
            path: '/mcp',
            body: { type: 'JSON_RPC', method: 'tools/list' }
        });
        assert.equal(list.httpResponseTemplate.templateType, 'VELOCITY');
        assert.equal(
            list.httpResponseTemplate.template,
            '{"statusCode": 200, ' +
            '"headers": [{"name": "Content-Type", "values": ["application/json"]}], ' +
            '"body": {"jsonrpc": "2.0", "result": ' +
            '{"tools": [{"name": "get_weather", "description": "Get the weather for a city", ' +
            '"inputSchema": {"type":"object","properties":{"city":{"type":"string"}}}}]}' +
            ', "id": $!{request.jsonRpcRawId}}}'
        );

        // ----- tools/call -----
        var call = findByJsonPath(expectations, "@.method == 'tools/call'")[0];
        assert.deepEqual(call.httpRequest, {
            method: 'POST',
            path: '/mcp',
            body: {
                type: 'JSON_PATH',
                jsonPath: "$[?(@.method == 'tools/call' && @.params.name == 'get_weather')]"
            }
        });
        assert.equal(call.httpResponseTemplate.templateType, 'VELOCITY');
        assert.equal(
            call.httpResponseTemplate.template,
            '{"statusCode": 200, ' +
            '"headers": [{"name": "Content-Type", "values": ["application/json"]}], ' +
            '"body": {"jsonrpc": "2.0", "result": ' +
            '{"content": [{"type": "text", "text": "sunny"}], "isError": false}' +
            ', "id": $!{request.jsonRpcRawId}}}'
        );
    });

    it('flags an error response when respondingWith(text, true) is used', function () {
        var call = findByJsonPath(
            mcpMock('/mcp').withTool('boom').respondingWith('kaboom', true).and().build(),
            "@.method == 'tools/call'"
        )[0];
        assert.ok(call.httpResponseTemplate.template.indexOf('"isError": true') !== -1);
    });

    it('emits tools/list with no tools when only withToolsCapability() is set', function () {
        var expectations = mcpMock('/mcp').withToolsCapability().build();
        var list = findByJsonRpc(expectations, 'tools/list')[0];
        assert.ok(list.httpResponseTemplate.template.indexOf('"tools": []') !== -1);
        // initialize capabilities advertises tools
        var init = findByJsonRpc(expectations, 'initialize')[0];
        assert.ok(init.httpResponseTemplate.template.indexOf('"tools": {"listChanged": false}') !== -1);
    });
});

// =========================================================================
// resources
// =========================================================================

describe('mcpMock resource registration', function () {
    it('produces the exact resources/list and resources/read expectation JSON', function () {
        var expectations = mcpMock('/mcp')
            .withResource('file:///config.json')
            .withName('config')
            .withDescription('App config')
            .withMimeType('application/json')
            .withContent('{"debug":true}')
            .and()
            .build();

        // initialize, ping, notifications, resources/list, resources/read => 5
        assert.equal(expectations.length, 5);

        // ----- resources/list -----
        var list = findByJsonRpc(expectations, 'resources/list')[0];
        assert.deepEqual(list.httpRequest, {
            method: 'POST',
            path: '/mcp',
            body: { type: 'JSON_RPC', method: 'resources/list' }
        });
        assert.equal(
            list.httpResponseTemplate.template,
            '{"statusCode": 200, ' +
            '"headers": [{"name": "Content-Type", "values": ["application/json"]}], ' +
            '"body": {"jsonrpc": "2.0", "result": ' +
            '{"resources": [{"uri": "file:///config.json", "name": "config", ' +
            '"description": "App config", "mimeType": "application/json"}]}' +
            ', "id": $!{request.jsonRpcRawId}}}'
        );

        // ----- resources/read -----
        var read = findByJsonPath(expectations, "@.method == 'resources/read'")[0];
        assert.deepEqual(read.httpRequest, {
            method: 'POST',
            path: '/mcp',
            body: {
                type: 'JSON_PATH',
                jsonPath: "$[?(@.method == 'resources/read' && @.params.uri == 'file:///config.json')]"
            }
        });
        assert.equal(
            read.httpResponseTemplate.template,
            '{"statusCode": 200, ' +
            '"headers": [{"name": "Content-Type", "values": ["application/json"]}], ' +
            '"body": {"jsonrpc": "2.0", "result": ' +
            '{"contents": [{"uri": "file:///config.json", "mimeType": "application/json", ' +
            '"text": "{\\"debug\\":true}"}]}' +
            ', "id": $!{request.jsonRpcRawId}}}'
        );
    });

    it('defaults resource mimeType to application/json', function () {
        var read = findByJsonPath(
            mcpMock('/mcp').withResource('mem://x').withContent('hi').and().build(),
            "@.method == 'resources/read'"
        )[0];
        assert.ok(read.httpResponseTemplate.template.indexOf('"mimeType": "application/json"') !== -1);
    });
});

// =========================================================================
// escaping
// =========================================================================

describe('mcpMock escaping', function () {
    it('JSON-escapes and Velocity-escapes tool response content', function () {
        var call = findByJsonPath(
            mcpMock('/mcp').withTool('t').respondingWith('a$b#c"d').and().build(),
            "@.method == 'tools/call'"
        )[0];
        // " is JSON-escaped to \" ; $ -> ${esc.d} ; # -> ${esc.h}
        assert.ok(call.httpResponseTemplate.template.indexOf('a${esc.d}b${esc.h}c\\"d') !== -1);
    });

    it('escapes single quotes inside the JSONPath matcher', function () {
        var call = mcpMock('/mcp').withTool("it's").respondingWith('x').and().build()
            .filter(function (e) { return e.httpRequest.body && e.httpRequest.body.type === 'JSON_PATH'; })[0];
        assert.ok(call.httpRequest.body.jsonPath.indexOf("it\\'s") !== -1);
    });

    it('rejects an invalid inputSchema JSON string', function () {
        assert.throws(function () {
            mcpMock('/mcp').withTool('t').withInputSchema('{not json').respondingWith('x').and().build();
        }, /Invalid JSON for inputSchema/);
    });
});
