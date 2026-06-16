// Minimal REST client for talking to a running MockServer instance from the
// editor. Kept free of the `vscode` API so it can be unit-tested directly, and
// it takes an injectable `fetch` so tests can run without a live server.

import { parse as parseJsonc, ParseError, printParseErrorCode } from "jsonc-parser";

export type FetchLike = (
    input: string,
    init?: {
        method?: string;
        headers?: Record<string, string>;
        body?: string;
    }
) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>;

export function buildBaseUrl(port: number): string {
    return `http://localhost:${port}`;
}

/**
 * Parse an expectation file's text into a list of expectations. A file may hold
 * a single expectation object or an array of them (initialization JSON).
 * Throws a clear error if the JSON is invalid or the shape is unexpected.
 */
export function parseExpectations(text: string): unknown[] {
    // Use a JSONC-tolerant parser so `*.mockserver.jsonc` files (comments and
    // trailing commas) load correctly. A naive comment-strip would corrupt `//`
    // inside string values such as URLs, so a real tokenizer is required.
    const errors: ParseError[] = [];
    const parsed: unknown = parseJsonc(text, errors, { allowTrailingComma: true });
    if (errors.length > 0) {
        const first = errors[0];
        throw new Error(
            `Not valid JSON: ${printParseErrorCode(first.error)} at offset ${first.offset}`
        );
    }
    if (Array.isArray(parsed)) {
        if (parsed.length === 0) {
            throw new Error("Expectation file is an empty array");
        }
        return parsed;
    }
    if (parsed && typeof parsed === "object") {
        return [parsed];
    }
    throw new Error("Expected an expectation object or an array of expectations");
}

/**
 * Load the file's expectation(s) into the running server via
 * `PUT /mockserver/expectation` (which accepts a single expectation or an array).
 * Returns the number of expectations sent.
 */
export async function loadExpectations(
    baseUrl: string,
    text: string,
    fetchFn: FetchLike
): Promise<number> {
    const expectations = parseExpectations(text);
    const res = await fetchFn(`${baseUrl}/mockserver/expectation`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(expectations),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    return expectations.length;
}

/**
 * Retrieve the expectations currently registered on the server, as pretty JSON,
 * via `PUT /mockserver/retrieve?type=active_expectations&format=json`.
 */
export async function retrieveActiveExpectations(
    baseUrl: string,
    fetchFn: FetchLike
): Promise<string> {
    const res = await fetchFn(
        `${baseUrl}/mockserver/retrieve?type=active_expectations&format=json`,
        { method: "PUT", headers: { Accept: "application/json" } }
    );
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return JSON.stringify(JSON.parse(body), null, 2) + "\n";
    } catch {
        return body; // return as-is if the server sent something unparseable
    }
}
