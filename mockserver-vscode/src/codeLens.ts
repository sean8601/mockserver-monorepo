import * as vscode from "vscode";

/**
 * Adds "Load into running MockServer", "Verify", "Diff against live", and "Delete"
 * actions at the top of every `*.mockserver.json(c)` file, turning a static
 * expectation file into a live control surface.
 */
export class ExpectationCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const topOfFile = new vscode.Range(0, 0, 0, 0);
        return [
            new vscode.CodeLens(topOfFile, {
                title: "$(cloud-upload) Load into running server",
                command: "mockserver.loadExpectations",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(verified) Verify",
                command: "mockserver.verifyExpectations",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(diff) Diff against live",
                command: "mockserver.diffAgainstLive",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(trash) Delete",
                command: "mockserver.deleteExpectations",
                arguments: [document.uri],
            }),
        ];
    }
}

/** Document selector matching the expectation files this extension understands. */
export const EXPECTATION_FILE_SELECTOR: vscode.DocumentSelector = [
    { scheme: "file", pattern: "**/*.mockserver.json" },
    { scheme: "file", pattern: "**/*.mockserver.jsonc" },
];

/**
 * Adds a "Send to MockServer" action at the top of every
 * `*.mockserver-request.json` scratch-request file, so a developer can fire the
 * request at the running mock and see the response in one click.
 */
export class ScratchRequestCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const topOfFile = new vscode.Range(0, 0, 0, 0);
        return [
            new vscode.CodeLens(topOfFile, {
                title: "$(run) Send to MockServer",
                command: "mockserver.sendRequest",
                arguments: [document.uri],
            }),
        ];
    }
}

/** Document selector matching the scratch-request files this extension understands. */
export const REQUEST_FILE_SELECTOR: vscode.DocumentSelector = [
    { scheme: "file", pattern: "**/*.mockserver-request.json" },
];
