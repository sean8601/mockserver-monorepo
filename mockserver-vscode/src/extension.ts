import * as vscode from "vscode";
import * as path from "path";
import { execFileSync, execFile } from "child_process";
import { ExpectationCodeLensProvider, EXPECTATION_FILE_SELECTOR } from "./codeLens";
import * as client from "./mockServerClient";

let outputChannel: vscode.OutputChannel;
let extensionVersion = "latest";

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

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel("MockServer");
    extensionVersion = (context.extension.packageJSON as { version?: string }).version ?? "latest";

    const startCmd = vscode.commands.registerCommand("mockserver.start", startMockServer);
    const stopCmd = vscode.commands.registerCommand("mockserver.stop", stopMockServer);
    const dashboardCmd = vscode.commands.registerCommand("mockserver.openDashboard", openDashboard);
    const loadCmd = vscode.commands.registerCommand("mockserver.loadExpectations", loadExpectations);
    const diffCmd = vscode.commands.registerCommand("mockserver.diffAgainstLive", diffAgainstLive);
    const recordCmd = vscode.commands.registerCommand("mockserver.saveRecorded", saveRecordedExpectations);

    const codeLensProvider = vscode.languages.registerCodeLensProvider(
        EXPECTATION_FILE_SELECTOR,
        new ExpectationCodeLensProvider()
    );
    const contentProvider = vscode.workspace.registerTextDocumentContentProvider(
        LIVE_SCHEME,
        liveContentProvider
    );

    context.subscriptions.push(
        startCmd,
        stopCmd,
        dashboardCmd,
        loadCmd,
        diffCmd,
        recordCmd,
        codeLensProvider,
        contentProvider,
        liveContentChanged,
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
        const count = await client.loadExpectations(
            client.buildBaseUrl(port),
            doc.getText(),
            httpFetch
        );
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
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve recorded expectations — ${(e as Error).message}`);
    }
}
