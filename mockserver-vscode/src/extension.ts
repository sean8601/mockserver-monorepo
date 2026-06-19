import * as vscode from "vscode";
import * as path from "path";
import { execFileSync, execFile } from "child_process";
import {
    ExpectationCodeLensProvider,
    EXPECTATION_FILE_SELECTOR,
    ScratchRequestCodeLensProvider,
    REQUEST_FILE_SELECTOR,
} from "./codeLens";
import * as client from "./mockServerClient";
import { MockServerActionsProvider } from "./actionsView";
import { MockServerDashboardViewProvider } from "./dashboardView";

let outputChannel: vscode.OutputChannel;
let extensionVersion = "latest";

// Collection backing the inline drift diagnostics shown on expectation files.
// Created in activate() so it shares the extension lifecycle.
let driftDiagnostics: vscode.DiagnosticCollection;

// Status-bar entry point. Shows the configured port and, on click, a quick-pick
// of the most useful actions. No live health polling — the label just reflects
// the configured port (read fresh when refreshed), not a probed server state.
let statusBarItem: vscode.StatusBarItem;

// Adapter from the global fetch to the small FetchLike used by mockServerClient.
const httpFetch: client.FetchLike = async (url, init) => {
    const res = await fetch(url, init);
    return { ok: res.ok, status: res.status, text: () => res.text() };
};

// Backing store for the read-only "live expectations" documents shown in the
// diff view, exposed under the `mockserver-live` URI scheme.
const LIVE_SCHEME = "mockserver-live";
const liveContent = new Map<string, string>();
const liveContentChanged = new vscode.EventEmitter<vscode.Uri>();
const liveContentProvider: vscode.TextDocumentContentProvider = {
    onDidChange: liveContentChanged.event,
    provideTextDocumentContent(uri: vscode.Uri): string {
        return liveContent.get(uri.toString()) ?? "";
    },
};

interface MockServerConfig {
    dockerImage: string;
    containerName: string;
    port: number;
}

// Reads settings fresh on each use so changes apply without reloading the window.
// The Docker image tag defaults to the extension's OWN version, so it stays in
// lockstep with the release and never drifts from a hardcoded constant.
function getConfig(): MockServerConfig {
    const cfg = vscode.workspace.getConfiguration("mockserver");
    const configuredImage = (cfg.get<string>("dockerImage") ?? "").trim();
    const configuredPort = cfg.get<number>("port");
    const validPort =
        typeof configuredPort === "number" && configuredPort >= 1 && configuredPort <= 65535;
    return {
        dockerImage: configuredImage || `mockserver/mockserver:${extensionVersion}`,
        containerName: cfg.get<string>("containerName") || "mockserver-vscode",
        port: validPort ? configuredPort : 1080,
    };
}

// Build the status-bar item: a server glyph + the configured port, clickable to
// open the quick-pick of common actions. Kept deliberately simple (no polling).
function createStatusBarItem(): vscode.StatusBarItem {
    const item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    item.text = `$(server) MockServer :${getConfig().port}`;
    item.tooltip = "MockServer — click for actions";
    item.command = "mockserver.statusBarMenu";
    return item;
}

// Quick-pick of the most useful actions, invoked from the status-bar click.
// Each pick runs an existing command id — no new behaviour, just discoverability.
async function showStatusBarMenu(): Promise<void> {
    const { port } = getConfig();
    const actions: Array<{ label: string; command: string }> = [
        { label: "$(dashboard) Open Dashboard", command: "mockserver.openDashboardInEditor" },
        { label: "$(globe) Open Dashboard in Browser", command: "mockserver.openDashboard" },
        { label: "$(play) Start (Docker)", command: "mockserver.start" },
        { label: "$(debug-stop) Stop", command: "mockserver.stop" },
        { label: "$(list-unordered) View Request Log", command: "mockserver.viewRequestLog" },
    ];
    const pick = await vscode.window.showQuickPick(actions, {
        placeHolder: `MockServer on port ${port}`,
    });
    if (pick) {
        await vscode.commands.executeCommand(pick.command);
    }
}

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel("MockServer");
    extensionVersion = (context.extension.packageJSON as { version?: string }).version ?? "latest";

    const startCmd = vscode.commands.registerCommand("mockserver.start", startMockServer);
    const stopCmd = vscode.commands.registerCommand("mockserver.stop", stopMockServer);
    const dashboardCmd = vscode.commands.registerCommand("mockserver.openDashboard", openDashboard);
    const dashboardInEditorCmd = vscode.commands.registerCommand(
        "mockserver.openDashboardInEditor",
        openDashboardInEditor
    );
    const loadCmd = vscode.commands.registerCommand("mockserver.loadExpectations", loadExpectations);
    const diffCmd = vscode.commands.registerCommand("mockserver.diffAgainstLive", diffAgainstLive);
    const verifyCmd = vscode.commands.registerCommand("mockserver.verifyExpectations", verifyExpectations);
    const deleteCmd = vscode.commands.registerCommand("mockserver.deleteExpectations", deleteExpectations);
    const recordCmd = vscode.commands.registerCommand("mockserver.saveRecorded", saveRecordedExpectations);
    const openApiCmd = vscode.commands.registerCommand("mockserver.generateFromOpenApi", generateFromOpenApi);
    const sendRequestCmd = vscode.commands.registerCommand("mockserver.sendRequest", sendRequest);
    const showDriftCmd = vscode.commands.registerCommand("mockserver.showDrift", showDrift);
    const showDriftDiagnosticsCmd = vscode.commands.registerCommand(
        "mockserver.showDriftDiagnostics",
        showDriftDiagnostics
    );
    const viewRequestLogCmd = vscode.commands.registerCommand("mockserver.viewRequestLog", viewRequestLog);
    const findByTraceCmd = vscode.commands.registerCommand("mockserver.findByTrace", findByTrace);
    const resetCmd = vscode.commands.registerCommand("mockserver.reset", resetServer);
    const uploadWasmCmd = vscode.commands.registerCommand("mockserver.uploadWasm", uploadWasm);
    const listWasmCmd = vscode.commands.registerCommand("mockserver.listWasm", listWasm);
    const statusBarMenuCmd = vscode.commands.registerCommand(
        "mockserver.statusBarMenu",
        showStatusBarMenu
    );

    // Activity Bar "Actions" tree (the grouped buttons). The provider reads the
    // configured port fresh for its status item; refreshView re-fires its change
    // event so the status item re-reads the port after a settings change.
    const actionsProvider = new MockServerActionsProvider(() => getConfig().port);
    const actionsView = vscode.window.registerTreeDataProvider(
        "mockserver.actions",
        actionsProvider
    );
    const refreshViewCmd = vscode.commands.registerCommand("mockserver.refreshView", () =>
        actionsProvider.refresh()
    );

    // Docked dashboard in the bottom Panel (NOT an editor tab). retainContextWhenHidden
    // keeps the framed dashboard alive when the panel is hidden.
    const dashboardProvider = new MockServerDashboardViewProvider(() => getConfig().port);
    const dashboardView = vscode.window.registerWebviewViewProvider(
        MockServerDashboardViewProvider.viewType,
        dashboardProvider,
        { webviewOptions: { retainContextWhenHidden: true } }
    );

    statusBarItem = createStatusBarItem();
    statusBarItem.show();

    driftDiagnostics = vscode.languages.createDiagnosticCollection("mockserver-drift");

    const codeLensProvider = vscode.languages.registerCodeLensProvider(
        EXPECTATION_FILE_SELECTOR,
        new ExpectationCodeLensProvider()
    );
    const requestCodeLensProvider = vscode.languages.registerCodeLensProvider(
        REQUEST_FILE_SELECTOR,
        new ScratchRequestCodeLensProvider()
    );
    const contentProvider = vscode.workspace.registerTextDocumentContentProvider(
        LIVE_SCHEME,
        liveContentProvider
    );

    context.subscriptions.push(
        startCmd,
        stopCmd,
        dashboardCmd,
        dashboardInEditorCmd,
        loadCmd,
        diffCmd,
        verifyCmd,
        deleteCmd,
        recordCmd,
        openApiCmd,
        sendRequestCmd,
        showDriftCmd,
        showDriftDiagnosticsCmd,
        viewRequestLogCmd,
        findByTraceCmd,
        resetCmd,
        uploadWasmCmd,
        listWasmCmd,
        statusBarMenuCmd,
        refreshViewCmd,
        actionsView,
        dashboardView,
        statusBarItem,
        codeLensProvider,
        requestCodeLensProvider,
        contentProvider,
        liveContentChanged,
        driftDiagnostics,
        outputChannel
    );
}

export function deactivate(): void {
    // Nothing to clean up
}

async function startMockServer(): Promise<void> {
    const { dockerImage, containerName, port } = getConfig();
    // Pass arguments as an array (no shell) so a container name or image from
    // settings cannot be interpreted as a shell command.
    const runArgs = ["run", "-d", "--rm", "--name", containerName, "-p", `${port}:1080`, dockerImage];

    outputChannel.appendLine(`Starting MockServer (${dockerImage}) on port ${port}...`);
    outputChannel.show(true);

    try {
        // Check if Docker is available
        execFileSync("docker", ["info"], { stdio: "pipe" });
    } catch {
        vscode.window.showErrorMessage(
            "Docker is not running. Please start Docker Desktop and try again."
        );
        return;
    }

    // Check if container is already running
    try {
        const running = execFileSync(
            "docker",
            ["ps", "--filter", `name=${containerName}`, "--format", "{{.Names}}"],
            { encoding: "utf-8" }
        ).trim();
        if (running === containerName) {
            vscode.window.showInformationMessage(
                `MockServer is already running on port ${port}.`
            );
            return;
        }
    } catch {
        // Ignore — proceed with start
    }

    execFile("docker", runArgs, (error, stdout, stderr) => {
        if (error) {
            const msg = stderr || error.message;
            outputChannel.appendLine(`Error: ${msg}`);
            vscode.window.showErrorMessage(`Failed to start MockServer: ${msg}`);
            return;
        }
        const containerId = stdout.trim().substring(0, 12);
        outputChannel.appendLine(`MockServer started (container: ${containerId}).`);
        vscode.window.showInformationMessage(
            `MockServer started on http://localhost:${port}`
        );
    });
}

async function stopMockServer(): Promise<void> {
    const { containerName } = getConfig();
    outputChannel.appendLine("Stopping MockServer...");
    outputChannel.show(true);

    execFile("docker", ["stop", containerName], (error, _stdout, stderr) => {
        if (error) {
            if (stderr.includes("No such container") || stderr.includes("not found")) {
                vscode.window.showWarningMessage("MockServer container is not running.");
            } else {
                vscode.window.showErrorMessage(`Failed to stop MockServer: ${stderr || error.message}`);
            }
            outputChannel.appendLine(`Stop result: ${stderr || error.message}`);
            return;
        }
        outputChannel.appendLine("MockServer stopped.");
        vscode.window.showInformationMessage("MockServer stopped.");
    });
}

async function openDashboard(): Promise<void> {
    const { port } = getConfig();
    const url = `http://localhost:${port}/mockserver/dashboard`;
    const opened = await vscode.env.openExternal(vscode.Uri.parse(url));
    if (!opened) {
        vscode.window.showErrorMessage(`Failed to open dashboard at ${url}`);
    }
}

// Reveal the docked dashboard: focus the `mockserver.dashboard` WebviewView in
// the bottom Panel (NOT an editor tab). VS Code auto-generates a `<viewId>.focus`
// command for every contributed view; executing it shows and focuses the panel
// view, resolving its WebviewViewProvider on first reveal. Keeps the
// external-browser command (`mockserver.openDashboard`) available separately.
async function openDashboardInEditor(): Promise<void> {
    await vscode.commands.executeCommand("mockserver.dashboard.focus");
}

// Resolve the expectation file a CodeLens/command should act on: the explicit
// URI passed by the CodeLens, else the active editor's document.
function resolveTargetUri(uri?: vscode.Uri): vscode.Uri | undefined {
    return uri ?? vscode.window.activeTextEditor?.document.uri;
}

async function loadExpectations(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const text = doc.getText();
        if (client.looksLikeOpenApiSpec(text)) {
            vscode.window.showWarningMessage(
                "MockServer: this looks like an OpenAPI/Swagger spec, not an expectation. " +
                "Use \"Generate Expectations From OpenAPI Spec\" instead."
            );
            return;
        }
        const count = await client.loadExpectations(client.buildBaseUrl(port), text, httpFetch);
        vscode.window.showInformationMessage(
            `Loaded ${count} expectation(s) into MockServer on port ${port}.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to load expectations — ${(e as Error).message}`);
    }
}

async function diffAgainstLive(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const live = await client.retrieveActiveExpectations(client.buildBaseUrl(port), httpFetch);
        const liveUri = vscode.Uri.parse(`${LIVE_SCHEME}:/active-expectations.json`);
        liveContent.set(liveUri.toString(), live);
        liveContentChanged.fire(liveUri);
        await vscode.commands.executeCommand(
            "vscode.diff",
            liveUri,
            target,
            `MockServer live ↔ ${path.basename(target.fsPath)}`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to diff against live — ${(e as Error).message}`);
    }
}

// Verify that every request declared in the expectation file was received by the
// running server (each at least once), via PUT /mockserver/verify. Reports the
// first unmet verification's reason, or success when all are satisfied. Useful
// after a test run to confirm the mocked endpoints were actually exercised.
async function verifyExpectations(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const definitions = client.extractRequestDefinitions(doc.getText());
        if (definitions.length === 0) {
            vscode.window.showInformationMessage(
                "No request definitions found in this file to verify."
            );
            return;
        }
        const baseUrl = client.buildBaseUrl(port);
        for (let i = 0; i < definitions.length; i++) {
            const { verified, reason } = await client.verifyRequestReceived(
                baseUrl,
                definitions[i],
                httpFetch
            );
            if (!verified) {
                vscode.window.showWarningMessage(
                    `MockServer: declared request ${i + 1} of ${definitions.length} was not received — ` +
                        `${reason.split("\n")[0]}`
                );
                return;
            }
        }
        vscode.window.showInformationMessage(
            `Verified: all ${definitions.length} declared request(s) were received.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to verify — ${(e as Error).message}`);
    }
}

// Remove from the running server the expectations declared in this file (matched by
// each declared request), via PUT /mockserver/clear?type=expectations. Confirms
// first so a stray CodeLens click cannot silently delete live state.
async function deleteExpectations(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const choice = await vscode.window.showWarningMessage(
        "Delete these expectations from the running MockServer?",
        { modal: true },
        "Delete"
    );
    if (choice !== "Delete") {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const count = await client.clearExpectations(client.buildBaseUrl(port), doc.getText(), httpFetch);
        if (count === 0) {
            vscode.window.showInformationMessage(
                "No request definitions found in this file to delete."
            );
            return;
        }
        vscode.window.showInformationMessage(
            `Cleared ${count} expectation matcher(s) from MockServer on port ${port}.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to delete expectations — ${(e as Error).message}`);
    }
}

// Offer to write generated/recorded content to a new workspace file (so the user
// can keep the artifact in the repo), defaulting the name and extension to the
// suggested values. Returns true when a file was written. Falls back to nothing
// (the caller already opened an editor tab) when there is no workspace folder or
// the user cancels.
async function offerToSaveToWorkspace(
    suggestedName: string,
    content: string
): Promise<boolean> {
    const folders = vscode.workspace.workspaceFolders;
    const defaultUri =
        folders && folders.length > 0
            ? vscode.Uri.joinPath(folders[0].uri, suggestedName)
            : undefined;
    const dest = await vscode.window.showSaveDialog({
        defaultUri,
        saveLabel: "Save to Workspace",
    });
    if (!dest) {
        return false; // user cancelled — the editor tab is still open
    }
    await vscode.workspace.fs.writeFile(dest, Buffer.from(content, "utf8"));
    const saved = await vscode.workspace.openTextDocument(dest);
    await vscode.window.showTextDocument(saved);
    return true;
}

async function saveRecordedExpectations(): Promise<void> {
    const pick = await vscode.window.showQuickPick(
        [
            { label: "JSON", description: "Expectation JSON (loadable as *.mockserver.json)", format: "json" as const },
            { label: "Java", description: "MockServerClient Java DSL", format: "java" as const },
        ],
        { placeHolder: "Format for recorded expectations" }
    );
    if (!pick) {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const recorded = await client.retrieveRecordedExpectations(
            client.buildBaseUrl(port),
            pick.format,
            httpFetch
        );
        if (recorded.empty) {
            vscode.window.showInformationMessage(
                "MockServer has not recorded any expectations yet. Recorded expectations are generated " +
                "from traffic the server proxies or forwards to a real upstream."
            );
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: recorded.content,
            language: pick.format === "java" ? "java" : "json",
        });
        await vscode.window.showTextDocument(doc);
        // Record-to-code: offer to drop the artifact straight into the repo as a
        // *.mockserver.json (JSON) or *.java (Java DSL) workspace file.
        const suggestedName =
            pick.format === "java" ? "RecordedExpectations.java" : "recorded.mockserver.json";
        await offerToSaveToWorkspace(suggestedName, recorded.content);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve recorded expectations — ${(e as Error).message}`);
    }
}

async function generateFromOpenApi(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("Open an OpenAPI/Swagger spec file first.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const text = doc.getText();
        if (!client.looksLikeOpenApiSpec(text)) {
            vscode.window.showWarningMessage(
                "MockServer: the active editor doesn't look like an OpenAPI/Swagger spec (no top-level " +
                "\"openapi\" or \"swagger\" field). Open your spec file and run this again."
            );
            return;
        }
        const generated = await client.generateExpectationsFromOpenApi(
            client.buildBaseUrl(port),
            text,
            httpFetch
        );
        if (generated.trim() === "[]") {
            vscode.window.showInformationMessage(
                "The OpenAPI spec produced no expectations (no operations matched)."
            );
            return;
        }
        const out = await vscode.workspace.openTextDocument({ content: generated, language: "json" });
        await vscode.window.showTextDocument(out);
        // Contract→stub: offer to write the generated expectations into a new
        // *.mockserver.json workspace file (schema-validated, loadable, in-repo).
        const baseName = path.basename(target.fsPath).replace(/\.(json|ya?ml)$/i, "");
        const saved = await offerToSaveToWorkspace(`${baseName || "openapi"}.mockserver.json`, generated);
        if (!saved) {
            vscode.window.showInformationMessage(
                "Generated MockServer expectations from the OpenAPI spec. Save as a *.mockserver.json file to keep them."
            );
        }
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to generate from OpenAPI — ${(e as Error).message}`);
    }
}

async function sendRequest(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("Open a *.mockserver-request.json file first.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const spec = client.parseRequestSpec(doc.getText());
        const baseUrl = client.buildBaseUrl(port);
        const response = await client.sendScratchRequest(baseUrl, spec, httpFetch);
        // Also ask the server whether the request matched a registered expectation,
        // and — on a miss — the nearest-miss reason. Best-effort: a server without the
        // debugMismatch endpoint must not break the (already-useful) response view.
        let analysis: client.MatchAnalysis | undefined;
        try {
            analysis = await client.analyseMatch(baseUrl, spec, httpFetch);
        } catch {
            analysis = undefined;
        }
        const out = await vscode.workspace.openTextDocument({
            content: formatScratchResponse(response, analysis),
            language: "text",
        });
        await vscode.window.showTextDocument(out);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to send request — ${(e as Error).message}`);
    }
}

async function showDrift(): Promise<void> {
    const { port } = getConfig();
    try {
        const drift = await client.retrieveDrift(client.buildBaseUrl(port), httpFetch);
        if (drift.empty) {
            vscode.window.showInformationMessage(
                "No drift detected. Drift is recorded when MockServer proxies traffic to a real " +
                "upstream and a matching stub expectation differs from the real response."
            );
            return;
        }
        const out = await vscode.workspace.openTextDocument({
            content: drift.report,
            language: "text",
        });
        await vscode.window.showTextDocument(out);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve drift report — ${(e as Error).message}`);
    }
}

// Map the pure DriftDiagnostic severity strings to the vscode enum.
function toDiagnosticSeverity(
    severity: "error" | "warning" | "info"
): vscode.DiagnosticSeverity {
    switch (severity) {
        case "error":
            return vscode.DiagnosticSeverity.Error;
        case "warning":
            return vscode.DiagnosticSeverity.Warning;
        default:
            return vscode.DiagnosticSeverity.Information;
    }
}

// Surface the server's drift records as inline diagnostics on the open
// expectation file: each drift attaches to the line of its matching expectation
// (by id), so a developer sees "the real upstream differs from this stub" right
// in the *.mockserver.json file. Re-running refreshes; no drift clears them.
async function showDriftDiagnostics(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const records = await client.retrieveDriftRecords(client.buildBaseUrl(port), httpFetch);
        if (records.length === 0) {
            driftDiagnostics.delete(target);
            vscode.window.showInformationMessage(
                "No drift detected. Drift is recorded when MockServer proxies traffic to a real " +
                "upstream and a matching stub expectation differs from the real response."
            );
            return;
        }
        const mapped = client.mapDriftToDiagnostics(records, doc.getText());
        const diagnostics = mapped.map((d) => {
            const lineLength = doc.lineAt(d.line).text.length;
            const range = new vscode.Range(d.line, 0, d.line, lineLength);
            return new vscode.Diagnostic(range, d.message, toDiagnosticSeverity(d.severity));
        });
        driftDiagnostics.set(target, diagnostics);
        vscode.window.showInformationMessage(
            `${diagnostics.length} drift record(s) shown as diagnostics.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve drift — ${(e as Error).message}`);
    }
}

// Open the server's received-request log in a new JSON editor tab. When the
// server has not recorded any requests yet, say so rather than opening an empty
// tab.
async function viewRequestLog(): Promise<void> {
    const { port } = getConfig();
    try {
        const log = await client.retrieveRequestLog(client.buildBaseUrl(port), httpFetch);
        if (log.empty) {
            vscode.window.showInformationMessage("No requests recorded yet.");
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: log.content,
            language: "json",
        });
        await vscode.window.showTextDocument(doc);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve request log — ${(e as Error).message}`);
    }
}

// Distributed-trace correlation: prompt for a W3C trace id (or a full traceparent),
// fetch the server's received-request log, and open the subset of requests that
// belong to that trace — every hop one trace produced — in a new JSON editor tab.
async function findByTrace(): Promise<void> {
    const input = await vscode.window.showInputBox({
        prompt: "Trace id (32 hex) or full traceparent",
        placeHolder: "4bf92f3577b34da6a3ce929d0e0e4736",
        validateInput: (value) => {
            if (value.trim().length === 0) {
                return "Enter a trace id or traceparent.";
            }
            return client.extractTraceId(value) === null
                ? "Enter a 32-hex trace id or a full W3C traceparent."
                : undefined;
        },
    });
    if (input === undefined) {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const log = await client.retrieveRequestLog(client.buildBaseUrl(port), httpFetch);
        const { traceId, matches } = client.filterRequestsByTrace(log.content, input);
        if (traceId === null) {
            vscode.window.showWarningMessage(
                "Enter a 32-hex trace id or a full W3C traceparent."
            );
            return;
        }
        if (matches.length === 0) {
            vscode.window.showInformationMessage(`No requests found for trace ${traceId}.`);
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: JSON.stringify(matches, null, 2) + "\n",
            language: "json",
        });
        await vscode.window.showTextDocument(doc);
        vscode.window.showInformationMessage(
            `${matches.length} request(s) found for trace ${traceId}.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to find requests by trace — ${(e as Error).message}`);
    }
}

// Reset the running server (clear all expectations and the request log) after a
// modal confirmation, so a stray Command Palette pick can't wipe state silently.
async function resetServer(): Promise<void> {
    const choice = await vscode.window.showWarningMessage(
        "Reset MockServer? This clears all expectations and logs.",
        { modal: true },
        "Reset"
    );
    if (choice !== "Reset") {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        await client.resetServer(client.buildBaseUrl(port), httpFetch);
        vscode.window.showInformationMessage("MockServer reset.");
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to reset — ${(e as Error).message}`);
    }
}

// Upload a compiled .wasm custom-rule module to the running server so it can be
// referenced by name in an expectation body matcher. Prompts for the file and a
// module name (defaulting to the file's basename), then PUTs the raw bytes. The
// server's "WASM support is disabled" 403 message surfaces verbatim on error.
async function uploadWasm(): Promise<void> {
    const picked = await vscode.window.showOpenDialog({
        canSelectMany: false,
        filters: { WebAssembly: ["wasm"] },
        openLabel: "Upload WASM Module",
    });
    if (!picked || picked.length === 0) {
        return; // user cancelled
    }
    const fileUri = picked[0];
    const defaultName = path.basename(fileUri.fsPath, ".wasm");
    const name = await vscode.window.showInputBox({
        prompt: "Name to register the WASM module under",
        value: defaultName,
        validateInput: (value) =>
            value.trim().length === 0 ? "Enter a non-empty module name." : undefined,
    });
    if (name === undefined || name.trim().length === 0) {
        return; // user cancelled or gave an empty name
    }
    const moduleName = name.trim();
    const { port } = getConfig();
    try {
        const bytes = await vscode.workspace.fs.readFile(fileUri);
        await client.uploadWasmModule(client.buildBaseUrl(port), moduleName, bytes, httpFetch);
        vscode.window.showInformationMessage(
            `Uploaded WASM module "${moduleName}". Reference it in an expectation body matcher as ` +
            `{ "type": "WASM", "moduleName": "${moduleName}" }.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to upload WASM module — ${(e as Error).message}`);
    }
}

// Open the names of the WASM custom-rule modules registered on the server in a new
// JSON editor tab (or say so when none are registered).
async function listWasm(): Promise<void> {
    const { port } = getConfig();
    try {
        const modules = await client.retrieveWasmModules(client.buildBaseUrl(port), httpFetch);
        if (modules.trim() === "" || modules.trim() === "[]") {
            vscode.window.showInformationMessage("No WASM modules are registered on the server.");
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: modules,
            language: "json",
        });
        await vscode.window.showTextDocument(doc);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to list WASM modules — ${(e as Error).message}`);
    }
}

// Render a scratch response as a small text summary: the match analysis (whether
// the request matched a registered expectation, and if not the nearest miss) when
// available, then `HTTP <status>` and the body, pretty-printed when it is JSON.
function formatScratchResponse(
    response: client.ScratchResponse,
    analysis?: client.MatchAnalysis
): string {
    let body = response.body;
    try {
        body = JSON.stringify(JSON.parse(body), null, 2);
    } catch {
        // not JSON — show the body verbatim
    }
    const header = analysis ? client.formatMatchAnalysis(analysis) + "\n\n" : "";
    return `${header}HTTP ${response.status}\n\n${body}`;
}
