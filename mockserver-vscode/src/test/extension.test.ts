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
    },
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

    await test("activate registers the load + diff commands", () => {
        assert.ok(registeredCommands.has("mockserver.loadExpectations"), "load command not registered");
        assert.ok(registeredCommands.has("mockserver.diffAgainstLive"), "diff command not registered");
    });

    // --- mockServerClient (pure REST helpers, exercised with a fake fetch) ---
    const client = require("../mockServerClient");

    await test("buildBaseUrl builds a localhost URL", () => {
        assert.strictEqual(client.buildBaseUrl(1080), "http://localhost:1080");
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

    // --- CodeLens provider ---
    const { ExpectationCodeLensProvider } = require("../codeLens");

    await test("CodeLens provider offers load + diff lenses", () => {
        const provider = new ExpectationCodeLensProvider();
        const lenses = provider.provideCodeLenses({ uri: { toString: () => "file:///x.mockserver.json" } });
        assert.strictEqual(lenses.length, 2);
        assert.strictEqual(lenses[0].command.command, "mockserver.loadExpectations");
        assert.strictEqual(lenses[1].command.command, "mockserver.diffAgainstLive");
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
