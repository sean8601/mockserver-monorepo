import * as vscode from "vscode";
import { execFileSync, execFile } from "child_process";

let outputChannel: vscode.OutputChannel;
let extensionVersion = "latest";

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
    return {
        dockerImage: configuredImage || `mockserver/mockserver:${extensionVersion}`,
        containerName: cfg.get<string>("containerName") || "mockserver-vscode",
        port: cfg.get<number>("port") || 1080,
    };
}

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel("MockServer");
    extensionVersion = (context.extension.packageJSON as { version?: string }).version ?? "latest";

    const startCmd = vscode.commands.registerCommand("mockserver.start", startMockServer);
    const stopCmd = vscode.commands.registerCommand("mockserver.stop", stopMockServer);
    const dashboardCmd = vscode.commands.registerCommand("mockserver.openDashboard", openDashboard);

    context.subscriptions.push(startCmd, stopCmd, dashboardCmd, outputChannel);
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
