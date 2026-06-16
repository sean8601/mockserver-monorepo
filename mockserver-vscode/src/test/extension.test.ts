/**
 * Unit test for MockServer VS Code extension.
 * Tests command registration without requiring a running VS Code instance.
 * Uses a minimal stub of the vscode API.
 */

import * as assert from "assert";

// Stub the vscode module before importing the extension
interface Disposable {
    dispose(): void;
}

interface Subscription {
    push(...items: Disposable[]): void;
}

interface FakeContext {
    subscriptions: Disposable[];
    extension: { packageJSON: { version: string } };
}

const registeredCommands: Map<string, Function> = new Map();
const outputLines: string[] = [];
const configValues: Record<string, unknown> = {};

// Build a minimal vscode stub
const vscodeStub = {
    workspace: {
        getConfiguration(_section?: string) {
            return {
                get<T>(key: string): T | undefined {
                    return configValues[key] as T | undefined;
                },
            };
        },
        registerTextDocumentContentProvider(_scheme: string, _provider: any): Disposable {
            return { dispose() {} };
        },
        openTextDocument(_uri: any) {
            return Promise.resolve({ getText: () => "{}" });
        },
    },
    commands: {
        registerCommand(id: string, handler: Function): Disposable {
            registeredCommands.set(id, handler);
            return { dispose() { registeredCommands.delete(id); } };
        },
        executeCommand(_id: string, ..._args: any[]) { return Promise.resolve(undefined); },
    },
    languages: {
        registerCodeLensProvider(_selector: any, _provider: any): Disposable {
            return { dispose() {} };
        },
        createDiagnosticCollection(_name?: string) {
            return {
                set(_uri: any, _diags?: any) {},
                delete(_uri: any) {},
                clear() {},
                dispose() {},
            };
        },
    },
    DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2, Hint: 3 },
    Diagnostic: class {
        constructor(public range: any, public message: string, public severity?: number) {}
    },
    EventEmitter: class {
        event = (_listener?: any): Disposable => ({ dispose() {} });
        fire(_e?: any): void {}
        dispose(): void {}
    },
    Range: class {
        constructor(
            public startLine: number,
            public startCharacter: number,
            public endLine: number,
            public endCharacter: number
        ) {}
    },
    CodeLens: class {
        constructor(public range: any, public command?: any) {}
    },
    window: {
        createOutputChannel(_name: string) {
            return {
                appendLine(msg: string) { outputLines.push(msg); },
                show(_preserveFocus?: boolean) {},
                dispose() {},
            };
        },
        showInformationMessage(_msg: string) {},
        showErrorMessage(_msg: string) {},
        showWarningMessage(_msg: string) {},
        createWebviewPanel(_viewType: string, _title: string, _column: any, _options: any) {
            return { webview: { html: "" }, dispose() {} };
        },
    },
    ViewColumn: { Active: -1 },
    env: {
        openExternal(_uri: any) { return Promise.resolve(true); },
    },
    Uri: {
        parse(value: string) { return { toString: () => value }; },
    },
};

// Patch require to intercept 'vscode' imports
const Module = require("module");
const originalRequire = Module.prototype.require;
Module.prototype.require = function (id: string) {
    if (id === "vscode") {
        return vscodeStub;
    }
    return originalRequire.apply(this, arguments);
};

// Now import the extension (it will get our stub)
// Clear the module cache first so it picks up the stub
delete require.cache[require.resolve("../extension")];
const extension = require("../extension");

async function runTests(): Promise<void> {
    console.log("Running MockServer VS Code extension tests...\n");

    let passed = 0;
    let failed = 0;

    async function test(name: string, fn: () => void | Promise<void>): Promise<void> {
        try {
            await fn();
            console.log(`  PASS: ${name}`);
            passed++;
        } catch (e: any) {
            console.log(`  FAIL: ${name}`);
            console.log(`        ${e.message}`);
            failed++;
        }
    }

    // Setup: activate the extension
    registeredCommands.clear();
    const fakeContext: FakeContext = {
        subscriptions: [],
        extension: { packageJSON: { version: "9.9.9" } },
    };
    extension.activate(fakeContext);

    await test("activate registers mockserver.start command", () => {
        assert.ok(
            registeredCommands.has("mockserver.start"),
            "mockserver.start command not registered"
        );
    });

    await test("activate registers mockserver.stop command", () => {
        assert.ok(
            registeredCommands.has("mockserver.stop"),
            "mockserver.stop command not registered"
        );
    });

    await test("activate registers mockserver.openDashboard command", () => {
        assert.ok(
            registeredCommands.has("mockserver.openDashboard"),
            "mockserver.openDashboard command not registered"
        );
    });

    await test("activate adds disposables to subscriptions", () => {
        // 3 commands + 1 output channel = 4
        assert.ok(
            fakeContext.subscriptions.length >= 3,
            `Expected at least 3 subscriptions, got ${fakeContext.subscriptions.length}`
        );
    });

    await test("deactivate does not throw", () => {
        assert.doesNotThrow(() => extension.deactivate());
    });

    await test("registered command handlers are functions", () => {
        for (const [id, handler] of registeredCommands) {
            assert.strictEqual(typeof handler, "function", `Handler for ${id} is not a function`);
        }
    });

    await test("openDashboard uses the configured port (default 1080)", async () => {
        let openedUrl = "";
        vscodeStub.env.openExternal = (uri: any) => {
            openedUrl = uri.toString();
            return Promise.resolve(true);
        };
        await registeredCommands.get("mockserver.openDashboard")!();
        assert.ok(
            openedUrl.includes("http://localhost:1080/mockserver/dashboard"),
            `unexpected dashboard URL: ${openedUrl}`
        );
    });

    await test("openDashboard honours a configured custom port", async () => {
        configValues["port"] = 2080;
        let openedUrl = "";
        vscodeStub.env.openExternal = (uri: any) => {
            openedUrl = uri.toString();
            return Promise.resolve(true);
        };
        await registeredCommands.get("mockserver.openDashboard")!();
        delete configValues["port"];
        assert.ok(openedUrl.includes(":2080/"), `port not honoured: ${openedUrl}`);
    });

    await test("bundled expectation schema is present and well-formed", () => {
        const fs = require("fs");
        const path = require("path");
        const schemaPath = path.resolve(__dirname, "../../schemas/mockserver-expectation.schema.json");
        assert.ok(fs.existsSync(schemaPath), `schema not found at ${schemaPath}`);
        const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
        assert.ok(schema.definitions && schema.definitions.expectation, "missing expectation definition");
        assert.ok(Array.isArray(schema.oneOf), "schema root should accept one expectation or an array");
    });

    await test("activate registers mockserver.openDashboardInEditor command", () => {
        assert.ok(
            registeredCommands.has("mockserver.openDashboardInEditor"),
            "mockserver.openDashboardInEditor command not registered"
        );
    });

    await test("activate registers the load + diff + record + openapi commands", () => {
        assert.ok(registeredCommands.has("mockserver.loadExpectations"), "load command not registered");
        assert.ok(registeredCommands.has("mockserver.diffAgainstLive"), "diff command not registered");
        assert.ok(registeredCommands.has("mockserver.saveRecorded"), "record command not registered");
        assert.ok(registeredCommands.has("mockserver.generateFromOpenApi"), "openapi command not registered");
    });

    await test("activate registers the mockserver.sendRequest command", () => {
        assert.ok(registeredCommands.has("mockserver.sendRequest"), "sendRequest command not registered");
    });

    await test("activate registers the mockserver.showDrift command", () => {
        assert.ok(registeredCommands.has("mockserver.showDrift"), "showDrift command not registered");
    });

    await test("activate registers the mockserver.showDriftDiagnostics command", () => {
        assert.ok(
            registeredCommands.has("mockserver.showDriftDiagnostics"),
            "showDriftDiagnostics command not registered"
        );
    });

    // --- mockServerClient (pure REST helpers, exercised with a fake fetch) ---
    const client = require("../mockServerClient");

    await test("buildBaseUrl builds a localhost URL", () => {
        assert.strictEqual(client.buildBaseUrl(1080), "http://localhost:1080");
    });

    await test("buildDashboardWebviewHtml embeds the URL in an iframe and allows framing localhost", () => {
        const url = "http://localhost:1080/mockserver/dashboard";
        const html = client.buildDashboardWebviewHtml(url) as string;
        assert.ok(html.includes(`<iframe src="${url}"`), "iframe src should be the dashboard URL");
        assert.ok(
            /Content-Security-Policy[^>]*frame-src[^>]*http:\/\/localhost:\*/.test(html),
            "CSP should allow frame-src http://localhost:*"
        );
    });

    await test("parseExpectations accepts a single object and an array", () => {
        assert.deepStrictEqual(client.parseExpectations('{"httpRequest":{}}'), [{ httpRequest: {} }]);
        assert.strictEqual(client.parseExpectations('[{"a":1},{"b":2}]').length, 2);
    });

    await test("parseExpectations rejects empty array, invalid JSON, and non-objects", () => {
        assert.throws(() => client.parseExpectations("[]"), /empty array/);
        assert.throws(() => client.parseExpectations("{not json"), /Not valid JSON/);
        assert.throws(() => client.parseExpectations("42"), /Expected an expectation/);
    });

    await test("parseExpectations accepts JSONC (comments + trailing comma) without corrupting URLs", () => {
        const jsonc = `{
            // a forward to an http URL — the // inside the string must survive
            "httpRequest": { "path": "/x" },
            "httpForward": { "host": "http://example.com", "port": 80 },
        }`;
        const result = client.parseExpectations(jsonc) as any[];
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].httpForward.host, "http://example.com");
    });

    await test("loadExpectations PUTs an array and returns the count", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve("") });
        };
        const count = await client.loadExpectations("http://localhost:1080", '[{"a":1},{"b":2}]', fakeFetch);
        assert.strictEqual(count, 2);
        assert.strictEqual(captured.url, "http://localhost:1080/mockserver/expectation");
        assert.strictEqual(captured.init.method, "PUT");
        assert.ok(Array.isArray(JSON.parse(captured.init.body)), "body should be a JSON array");
    });

    await test("loadExpectations throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve("bad matcher") });
        await assert.rejects(
            () => client.loadExpectations("http://localhost:1080", '{"httpResponse":{}}', fakeFetch),
            /400: bad matcher/
        );
    });

    await test("retrieveActiveExpectations pretty-prints the server JSON", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('[{"id":"x"}]') });
        const out = await client.retrieveActiveExpectations("http://localhost:1080", fakeFetch);
        assert.ok(out.includes('"id": "x"'), "expected pretty-printed JSON");
    });

    await test("retrieveRecordedExpectations (json) flags empty and pretty-prints", async () => {
        const emptyFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("[]") });
        const empty = await client.retrieveRecordedExpectations("http://localhost:1080", "json", emptyFetch);
        assert.strictEqual(empty.empty, true);

        const dataFetch = (url: string) => {
            assert.ok(url.includes("type=recorded_expectations&format=json"), `url=${url}`);
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('[{"id":"r"}]') });
        };
        const data = await client.retrieveRecordedExpectations("http://localhost:1080", "json", dataFetch);
        assert.strictEqual(data.empty, false);
        assert.ok(data.content.includes('"id": "r"'), "expected pretty-printed JSON");
    });

    await test("retrieveRecordedExpectations (java) returns the DSL verbatim and flags empty", async () => {
        const dsl = "new MockServerClient(...).when(request()...)";
        const javaFetch = (url: string) => {
            assert.ok(url.includes("format=java"), `url=${url}`);
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(dsl) });
        };
        const out = await client.retrieveRecordedExpectations("http://localhost:1080", "java", javaFetch);
        assert.strictEqual(out.content, dsl);
        assert.strictEqual(out.empty, false);

        const emptyJava = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("   ") });
        const empty = await client.retrieveRecordedExpectations("http://localhost:1080", "java", emptyJava);
        assert.strictEqual(empty.empty, true);
    });

    await test("generateExpectationsFromOpenApi sends a JSON spec as an object", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve('[{"id":"op"}]') });
        };
        const out = await client.generateExpectationsFromOpenApi(
            "http://localhost:1080",
            '{"openapi":"3.0.0","paths":{}}',
            fakeFetch
        );
        assert.ok(captured.url.endsWith("/mockserver/openapi"), `url=${captured.url}`);
        const sent = JSON.parse(captured.init.body);
        assert.strictEqual(typeof sent.specUrlOrPayload, "object", "JSON spec should be sent as an object");
        assert.ok(out.includes('"id": "op"'), "expected pretty-printed expectations");
    });

    await test("generateExpectationsFromOpenApi sends a YAML spec as a string", async () => {
        let body: any = {};
        const fakeFetch = (_url: string, init: any) => {
            body = JSON.parse(init.body);
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve("[]") });
        };
        await client.generateExpectationsFromOpenApi("http://localhost:1080", "openapi: 3.0.0\npaths: {}", fakeFetch);
        assert.strictEqual(typeof body.specUrlOrPayload, "string", "YAML spec should be sent as a string");
    });

    await test("generateExpectationsFromOpenApi throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve("invalid spec") });
        await assert.rejects(
            () => client.generateExpectationsFromOpenApi("http://localhost:1080", "{}", fakeFetch),
            /400: invalid spec/
        );
    });

    await test("parseRequestSpec accepts a valid spec with headers and body", () => {
        const spec = client.parseRequestSpec(
            '{"method":"POST","path":"/api/x","headers":{"Accept":"application/json"},"body":"hi"}'
        );
        assert.strictEqual(spec.method, "POST");
        assert.strictEqual(spec.path, "/api/x");
        assert.deepStrictEqual(spec.headers, { Accept: "application/json" });
        assert.strictEqual(spec.body, "hi");
    });

    await test("parseRequestSpec rejects a missing method", () => {
        assert.throws(() => client.parseRequestSpec('{"path":"/api/x"}'), /missing a "method"/);
    });

    await test("parseRequestSpec rejects a missing path", () => {
        assert.throws(() => client.parseRequestSpec('{"method":"GET"}'), /missing a "path"/);
    });

    await test("parseRequestSpec rejects invalid JSON", () => {
        assert.throws(() => client.parseRequestSpec("{not json"), /Not valid JSON/);
    });

    await test("sendScratchRequest passes url, method, headers, body to fetch and returns status+body", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"ok":true}') });
        };
        const spec = {
            method: "POST",
            path: "/api/x",
            headers: { Accept: "application/json" },
            body: "payload",
        };
        const response = await client.sendScratchRequest("http://localhost:1080", spec, fakeFetch);
        assert.strictEqual(captured.url, "http://localhost:1080/api/x");
        assert.strictEqual(captured.init.method, "POST");
        assert.deepStrictEqual(captured.init.headers, { Accept: "application/json" });
        assert.strictEqual(captured.init.body, "payload");
        assert.strictEqual(response.status, 200);
        assert.strictEqual(response.body, '{"ok":true}');
    });

    await test("retrieveDrift flags the empty state (count 0, no drifts)", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"count":0,"drifts":[]}') });
        const out = await client.retrieveDrift("http://localhost:1080", fakeFetch);
        assert.strictEqual(out.empty, true);
        assert.strictEqual(out.count, 0);
    });

    await test("retrieveDrift summarises records and GETs /mockserver/drift", async () => {
        let captured: any = {};
        const payload = {
            count: 2,
            drifts: [
                {
                    expectationId: "exp-1",
                    driftType: "HEADER_CHANGED",
                    field: "$.status",
                    expectedValue: "active",
                    actualValue: "archived",
                    confidence: 0.9,
                    epochTimeMs: 1,
                },
                {
                    expectationId: "exp-2",
                    driftType: "FIELD_ADDED",
                    field: "$.newField",
                    confidence: 0.5,
                    epochTimeMs: 2,
                },
            ],
        };
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) });
        };
        const out = await client.retrieveDrift("http://localhost:1080", fakeFetch);
        assert.strictEqual(out.empty, false);
        assert.strictEqual(out.count, 2);
        assert.ok(captured.url.includes("/mockserver/drift"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "GET");
        assert.ok(out.report.includes("HEADER_CHANGED"), "report should include the driftType");
        assert.ok(out.report.includes("$.status"), "report should include the field");
        assert.ok(out.report.includes("FIELD_ADDED"), "report should include the second driftType");
        assert.ok(out.report.includes("— "), "missing value should render as an em dash");
    });

    await test("retrieveDrift passes the limit as a query param when provided", async () => {
        let url = "";
        const fakeFetch = (u: string) => {
            url = u;
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"count":0,"drifts":[]}') });
        };
        await client.retrieveDrift("http://localhost:1080", fakeFetch, 10);
        assert.ok(url.includes("/mockserver/drift?limit=10"), `url=${url}`);
    });

    await test("retrieveDrift throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("drift not enabled") });
        await assert.rejects(
            () => client.retrieveDrift("http://localhost:1080", fakeFetch),
            /404: drift not enabled/
        );
    });

    await test("retrieveDriftRecords returns the parsed drifts array and GETs /mockserver/drift", async () => {
        let captured: any = {};
        const payload = {
            count: 2,
            drifts: [
                { expectationId: "exp-1", driftType: "STATUS", field: "$.status", confidence: 1.0, epochTimeMs: 1 },
                { expectationId: "exp-2", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", confidence: 0.5, epochTimeMs: 2 },
            ],
        };
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) });
        };
        const records = await client.retrieveDriftRecords("http://localhost:1080", fakeFetch);
        assert.strictEqual(records.length, 2);
        assert.strictEqual(records[0].expectationId, "exp-1");
        assert.ok(captured.url.includes("/mockserver/drift"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "GET");
    });

    await test("retrieveDriftRecords returns [] when the server reports no drift", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"count":0,"drifts":[]}') });
        const records = await client.retrieveDriftRecords("http://localhost:1080", fakeFetch);
        assert.deepStrictEqual(records, []);
    });

    await test("retrieveDriftRecords returns [] on a non-JSON body", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("not json") });
        const records = await client.retrieveDriftRecords("http://localhost:1080", fakeFetch);
        assert.deepStrictEqual(records, []);
    });

    await test("retrieveDriftRecords throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("drift not enabled") });
        await assert.rejects(
            () => client.retrieveDriftRecords("http://localhost:1080", fakeFetch),
            /404: drift not enabled/
        );
    });

    await test("mapDriftToDiagnostics anchors a record to the line of its matching expectation id", () => {
        const docText = [
            "[",
            "  {",
            '    "id": "exp-1",',
            '    "httpRequest": { "path": "/a" }',
            "  }",
            "]",
        ].join("\n");
        const records = [
            { expectationId: "exp-1", driftType: "HEADER_CHANGED", field: "$.x", confidence: 0.8, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, docText);
        assert.strictEqual(diags.length, 1);
        assert.strictEqual(diags[0].line, 2, "should anchor to the line containing the matching id");
    });

    await test("mapDriftToDiagnostics falls back to line 0 when no expectation id matches", () => {
        const docText = '[{"id":"other"}]';
        const records = [
            { expectationId: "missing", driftType: "HEADER_CHANGED", field: "$.x", confidence: 0.8, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, docText);
        assert.strictEqual(diags[0].line, 0, "no match should fall back to line 0");
    });

    await test("mapDriftToDiagnostics maps STATUS drift to error and SCHEMA_FIELD_ADDED to warning", () => {
        const records = [
            { expectationId: "a", driftType: "STATUS", field: "$.status", confidence: 0.2, epochTimeMs: 1 },
            { expectationId: "b", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", confidence: 0.2, epochTimeMs: 2 },
            { expectationId: "c", driftType: "HEADER_CHANGED", field: "$.x", confidence: 0.2, epochTimeMs: 3 },
        ];
        const diags = client.mapDriftToDiagnostics(records, "{}");
        assert.strictEqual(diags[0].severity, "error", "STATUS drift should be error");
        assert.strictEqual(diags[1].severity, "warning", "SCHEMA_FIELD_ADDED should be warning");
        assert.strictEqual(diags[2].severity, "info", "value change should be info");
    });

    await test("mapDriftToDiagnostics message includes the driftType and field, and — for absent values", () => {
        const records = [
            { expectationId: "a", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", confidence: 0.5, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, "{}");
        assert.ok(diags[0].message.includes("SCHEMA_FIELD_ADDED"), "message should include driftType");
        assert.ok(diags[0].message.includes("$.newField"), "message should include field");
        assert.ok(diags[0].message.includes("—"), "absent values should render as an em dash");
        assert.ok(diags[0].message.includes("confidence 0.5"), "message should include confidence");
    });

    await test("looksLikeOpenApiSpec detects specs and rejects expectations/junk", () => {
        assert.strictEqual(client.looksLikeOpenApiSpec('{"openapi":"3.0.0","paths":{}}'), true);
        assert.strictEqual(client.looksLikeOpenApiSpec('{"swagger":"2.0","paths":{}}'), true);
        assert.strictEqual(client.looksLikeOpenApiSpec("openapi: 3.0.0\npaths: {}"), true);
        assert.strictEqual(client.looksLikeOpenApiSpec('{"httpRequest":{},"httpResponse":{}}'), false);
        assert.strictEqual(client.looksLikeOpenApiSpec('[{"httpResponse":{}}]'), false);
        assert.strictEqual(client.looksLikeOpenApiSpec('{"note":"openapi is great"}'), false);
        assert.strictEqual(client.looksLikeOpenApiSpec("just text"), false);
    });

    // --- CodeLens providers ---
    const { ExpectationCodeLensProvider, ScratchRequestCodeLensProvider } = require("../codeLens");

    await test("CodeLens provider offers load + diff lenses", () => {
        const provider = new ExpectationCodeLensProvider();
        const lenses = provider.provideCodeLenses({ uri: { toString: () => "file:///x.mockserver.json" } });
        assert.strictEqual(lenses.length, 2);
        assert.strictEqual(lenses[0].command.command, "mockserver.loadExpectations");
        assert.strictEqual(lenses[1].command.command, "mockserver.diffAgainstLive");
    });

    await test("scratch-request CodeLens provider offers a send lens", () => {
        const provider = new ScratchRequestCodeLensProvider();
        const lenses = provider.provideCodeLenses({ uri: { toString: () => "file:///x.mockserver-request.json" } });
        assert.strictEqual(lenses.length, 1);
        assert.strictEqual(lenses[0].command.command, "mockserver.sendRequest");
    });

    console.log(`\nResults: ${passed} passed, ${failed} failed, ${passed + failed} total`);
    if (failed > 0) {
        process.exit(1);
    }
}

runTests().catch((e) => {
    console.error(e);
    process.exit(1);
});
