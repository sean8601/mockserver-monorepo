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
 * A scratch request spec parsed from a `*.mockserver-request.json` file: a small
 * ad-hoc HTTP request a developer fires at the running mock from the editor.
 * `method` and `path` are required; `headers` and `body` are optional.
 */
export interface RequestSpec {
    method: string;
    path: string;
    headers?: Record<string, string>;
    body?: string;
}

/** A scratch request's response: the HTTP status and the (possibly empty) body. */
export interface ScratchResponse {
    status: number;
    body: string;
}

/**
 * Parse a scratch request file's text into a {@link RequestSpec}. Throws a clear
 * error if the JSON is invalid, the top level is not an object, or `method`/`path`
 * are missing or not strings.
 */
export function parseRequestSpec(text: string): RequestSpec {
    let parsed: unknown;
    try {
        parsed = JSON.parse(text);
    } catch (e) {
        throw new Error(`Not valid JSON: ${(e as Error).message}`);
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("Expected a request object with \"method\" and \"path\"");
    }
    const spec = parsed as Record<string, unknown>;
    if (typeof spec.method !== "string" || spec.method.trim().length === 0) {
        throw new Error('Request spec is missing a "method" (e.g. "GET")');
    }
    if (typeof spec.path !== "string" || spec.path.trim().length === 0) {
        throw new Error('Request spec is missing a "path" (e.g. "/api/x")');
    }
    if (spec.headers !== undefined) {
        if (typeof spec.headers !== "object" || spec.headers === null || Array.isArray(spec.headers)) {
            throw new Error('"headers" must be an object of header name to value');
        }
        for (const [name, value] of Object.entries(spec.headers as Record<string, unknown>)) {
            if (typeof value !== "string") {
                throw new Error(`"headers.${name}" must be a string`);
            }
        }
    }
    if (spec.body !== undefined && typeof spec.body !== "string") {
        throw new Error('"body" must be a string');
    }
    return {
        method: spec.method,
        path: spec.path,
        headers: spec.headers as Record<string, string> | undefined,
        body: spec.body as string | undefined,
    };
}

const OPENAPI_YAML_KEY = /^\s*["']?(openapi|swagger)["']?\s*:/im;

/**
 * Heuristic: does `text` look like an OpenAPI/Swagger specification? True when it
 * has a top-level `openapi` (3.x) or `swagger` (2.0) field, in either JSON or YAML.
 * Used to warn clearly when the wrong kind of file is submitted (e.g. an OpenAPI
 * spec sent to "load expectations", or an expectation file sent to "generate").
 */
export function looksLikeOpenApiSpec(text: string): boolean {
    try {
        const parsed = JSON.parse(text);
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
            return "openapi" in parsed || "swagger" in parsed;
        }
        return false; // valid JSON but an array/primitive — not a spec
    } catch {
        return OPENAPI_YAML_KEY.test(text); // not JSON → check for a YAML spec key
    }
}

/**
 * Fire a scratch request at the running server: send `spec.method spec.path`
 * (relative to `baseUrl`) with the spec's headers and body, and return the
 * response status and body text. Unlike the expectation helpers this does NOT
 * throw on a non-2xx status — the caller wants to see error responses too.
 */
export async function sendScratchRequest(
    baseUrl: string,
    spec: RequestSpec,
    fetchFn: FetchLike
): Promise<ScratchResponse> {
    const res = await fetchFn(`${baseUrl}${spec.path}`, {
        method: spec.method,
        headers: spec.headers,
        body: spec.body,
    });
    return { status: res.status, body: await res.text() };
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

/**
 * Generate MockServer expectations from an OpenAPI / Swagger specification via
 * `PUT /mockserver/openapi`. The spec text is sent inline as `specUrlOrPayload`:
 * a JSON spec is sent as a parsed object, anything else (YAML) as a string —
 * the server parses both. The 201 response IS the generated expectations array,
 * returned pretty-printed and ready to save as a `*.mockserver.json` file.
 */
export async function generateExpectationsFromOpenApi(
    baseUrl: string,
    specText: string,
    fetchFn: FetchLike
): Promise<string> {
    let specUrlOrPayload: unknown;
    try {
        // A JSON spec is sent as an object so the server treats it unambiguously
        // as an inline payload; YAML falls through and is sent as a string.
        specUrlOrPayload = JSON.parse(specText);
    } catch {
        specUrlOrPayload = specText;
    }
    const res = await fetchFn(`${baseUrl}/mockserver/openapi`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ specUrlOrPayload }),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return JSON.stringify(JSON.parse(body), null, 2) + "\n";
    } catch {
        return body;
    }
}

export type RecordedFormat = "json" | "java";

export interface RecordedExpectations {
    /** Formatted content (pretty JSON, or Java DSL as the server emitted it). */
    content: string;
    /** True when the server has not recorded any expectations yet. */
    empty: boolean;
}

/**
 * Retrieve expectations generated from traffic the server has recorded while
 * proxying/forwarding, via `PUT /mockserver/retrieve?type=recorded_expectations`,
 * as either JSON (pretty-printed) or Java DSL — the "record real traffic into
 * code" flow. Returns the content plus whether it is empty (no recorded traffic).
 */
export async function retrieveRecordedExpectations(
    baseUrl: string,
    format: RecordedFormat,
    fetchFn: FetchLike
): Promise<RecordedExpectations> {
    const res = await fetchFn(
        `${baseUrl}/mockserver/retrieve?type=recorded_expectations&format=${format}`,
        { method: "PUT" }
    );
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    if (format === "java") {
        return { content: body, empty: body.trim().length === 0 };
    }
    // JSON: pretty-print and detect the empty-array case.
    try {
        const parsed = JSON.parse(body);
        const empty = Array.isArray(parsed) && parsed.length === 0;
        return { content: JSON.stringify(parsed, null, 2) + "\n", empty };
    } catch {
        return { content: body, empty: body.trim().length === 0 };
    }
}
