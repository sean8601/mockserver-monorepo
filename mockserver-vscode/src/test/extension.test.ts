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
    },
    commands: {
        registerCommand(id: string, handler: Function): Disposable {
            registeredCommands.set(id, handler);
            return { dispose() { registeredCommands.delete(id); } };
        },
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

    console.log(`\nResults: ${passed} passed, ${failed} failed, ${passed + failed} total`);
    if (failed > 0) {
        process.exit(1);
    }
}

runTests().catch((e) => {
    console.error(e);
    process.exit(1);
});
