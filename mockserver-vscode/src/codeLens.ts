import * as vscode from "vscode";

/**
 * Adds "Load into running MockServer" and "Diff against live" actions at the top
 * of every `*.mockserver.json(c)` file, turning a static expectation file into a
 * live control surface.
 */
export class ExpectationCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const topOfFile = new vscode.Range(0, 0, 0, 0);
        return [
            new vscode.CodeLens(topOfFile, {
                title: "$(cloud-upload) Load into running MockServer",
                command: "mockserver.loadExpectations",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(diff) Diff against live",
                command: "mockserver.diffAgainstLive",
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
