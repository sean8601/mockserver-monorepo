// Minimal REST client for talking to a running MockServer instance from the
// editor. Kept free of the `vscode` API so it can be unit-tested directly, and
// it takes an injectable `fetch` so tests can run without a live server.

import { parse as parseJsonc, ParseError, printParseErrorCode } from "jsonc-parser";

export type FetchLike = (
    input: string,
    init?: {
        method?: string;
        headers?: Record<string, string>;
        body?: string | Uint8Array;
    }
) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>;

export function buildBaseUrl(port: number): string {
    return `http://localhost:${port}`;
}

/**
 * Build the HTML document for the in-editor dashboard webview: a full-size
 * `<iframe>` pointing at the running server's dashboard. VS Code webviews block
 * framing by default, so the document carries a Content-Security-Policy that
 * explicitly allows framing localhost (`frame-src http://localhost:* http://127.0.0.1:*`)
 * and inline styles for the full-bleed iframe. Kept free of the `vscode` API so
 * it can be unit-tested directly.
 */
export function buildDashboardWebviewHtml(dashboardUrl: string): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; frame-src http://localhost:* http://127.0.0.1:*;">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MockServer Dashboard</title>
    <style>
        html, body { margin: 0; padding: 0; height: 100%; }
        iframe { width: 100%; height: 100vh; border: none; }
    </style>
</head>
<body>
    <iframe src="${dashboardUrl}" title="MockServer Dashboard"></iframe>
</body>
</html>`;
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

export interface RequestLog {
    /** The received request log, pretty-printed as JSON. */
    content: string;
    /** True when the server has not recorded any requests yet (`[]` or blank). */
    empty: boolean;
}

/**
 * Retrieve the log of requests the server has received, as pretty JSON, via
 * `PUT /mockserver/retrieve?type=requests&format=json`. Returns the content plus
 * whether it is empty (no requests recorded yet — `[]` or a blank body).
 */
export async function retrieveRequestLog(
    baseUrl: string,
    fetchFn: FetchLike
): Promise<RequestLog> {
    const res = await fetchFn(
        `${baseUrl}/mockserver/retrieve?type=requests&format=json`,
        { method: "PUT", headers: { Accept: "application/json" } }
    );
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        const parsed = JSON.parse(body);
        const empty = Array.isArray(parsed) && parsed.length === 0;
        return { content: JSON.stringify(parsed, null, 2) + "\n", empty };
    } catch {
        return { content: body, empty: body.trim().length === 0 };
    }
}

/**
 * Reset the running server via `PUT /mockserver/reset`, clearing all expectations
 * and the recorded request log. Throws (with status + body) on a non-ok response.
 */
export async function resetServer(baseUrl: string, fetchFn: FetchLike): Promise<void> {
    const res = await fetchFn(`${baseUrl}/mockserver/reset`, { method: "PUT" });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
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

export interface DriftReport {
    /** Number of drift records the server reported. */
    count: number;
    /** A readable, plain-text summary of the drift records. */
    report: string;
    /** True when the server reported no drift (count === 0). */
    empty: boolean;
}

/**
 * Format a single drift value for the report. The server may omit
 * `expectedValue`/`actualValue`, send a primitive, or send a nested object —
 * render objects/arrays compactly as JSON and show "—" when absent.
 */
function formatDriftValue(value: unknown): string {
    if (value === undefined || value === null) {
        return "—";
    }
    if (typeof value === "object") {
        try {
            return JSON.stringify(value);
        } catch {
            return String(value);
        }
    }
    return String(value);
}

/**
 * A single mock-drift record as reported by `GET /mockserver/drift`. The server
 * may omit `expectedValue`/`actualValue` (e.g. a field added or removed). All
 * other fields are always present.
 */
export interface DriftRecord {
    /** The id of the expectation whose stub has drifted from the real upstream. */
    expectationId: string;
    /** The kind of drift, e.g. `HEADER_CHANGED`, `STATUS`, `SCHEMA_FIELD_ADDED`. */
    driftType: string;
    /** The drifted field (a JSON path or response location), e.g. `$.status`. */
    field: string;
    /** The value the stub expectation declares (absent for some drift types). */
    expectedValue?: unknown;
    /** The value the real upstream returned (absent for some drift types). */
    actualValue?: unknown;
    /** The detector's confidence in the drift, 0..1 (1.0 = certain). */
    confidence: number;
    /** When the drift was observed, in epoch milliseconds. */
    epochTimeMs: number;
}

/**
 * Fetch the drift endpoint and return the parsed `{ count, drifts }` body, or
 * `undefined` when the body is not parseable JSON (so callers can fall back to
 * the raw text). Shared by {@link retrieveDrift} and {@link retrieveDriftRecords}.
 * Throws (with status + body) on a non-ok response.
 */
async function fetchDrift(
    baseUrl: string,
    fetchFn: FetchLike,
    limit?: number
): Promise<{ parsed?: { count?: number; drifts?: unknown[] }; body: string }> {
    const query = typeof limit === "number" ? `?limit=${limit}` : "";
    const res = await fetchFn(`${baseUrl}/mockserver/drift${query}`, {
        method: "GET",
        headers: { Accept: "application/json" },
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return { parsed: JSON.parse(body), body };
    } catch {
        return { parsed: undefined, body };
    }
}

/**
 * Retrieve the latest mock-drift records via `GET /mockserver/drift`. Drift is
 * recorded when the server proxies traffic to a real upstream and a matching
 * stub expectation differs structurally from the real response. Returns the
 * record count, a readable text summary, and whether it is empty (no drift).
 * `limit` (when provided) caps how many records the server returns.
 */
export async function retrieveDrift(
    baseUrl: string,
    fetchFn: FetchLike,
    limit?: number
): Promise<DriftReport> {
    const { parsed, body } = await fetchDrift(baseUrl, fetchFn, limit);
    if (parsed === undefined) {
        // Non-JSON or unexpected body — surface the raw text so nothing is lost.
        return { count: 0, report: body, empty: body.trim().length === 0 };
    }
    const drifts = Array.isArray(parsed.drifts) ? parsed.drifts : [];
    const count = typeof parsed.count === "number" ? parsed.count : drifts.length;
    const header = `MockServer drift report — ${count} record(s)`;
    if (count === 0 && drifts.length === 0) {
        return { count: 0, report: header, empty: true };
    }
    const lines = drifts.map((entry) => {
        const d = (entry ?? {}) as Record<string, unknown>;
        const expected = formatDriftValue(d.expectedValue);
        const actual = formatDriftValue(d.actualValue);
        return (
            `[${formatDriftValue(d.driftType)}] ${formatDriftValue(d.field)}: ` +
            `expected ${expected} / actual ${actual} ` +
            `(confidence ${formatDriftValue(d.confidence)}, expectation ${formatDriftValue(d.expectationId)})`
        );
    });
    return { count, report: [header, ...lines].join("\n") + "\n", empty: count === 0 };
}

/**
 * Retrieve the latest mock-drift records via `GET /mockserver/drift` as a
 * structured array of {@link DriftRecord}, for programmatic use (e.g. mapping to
 * editor diagnostics). Throws (with status + body) on a non-ok response; returns
 * `[]` when the server reports no drift or sends a non-JSON / unexpected body.
 * `limit` (when provided) caps how many records the server returns.
 */
export async function retrieveDriftRecords(
    baseUrl: string,
    fetchFn: FetchLike,
    limit?: number
): Promise<DriftRecord[]> {
    const { parsed } = await fetchDrift(baseUrl, fetchFn, limit);
    if (parsed === undefined || !Array.isArray(parsed.drifts)) {
        return [];
    }
    return parsed.drifts as DriftRecord[];
}

/** A drift mapped to a position in an open expectation file, ready to surface as a diagnostic. */
export interface DriftDiagnostic {
    /** Zero-based line in the document the diagnostic attaches to (0 when no match found). */
    line: number;
    /** Human-readable message describing the drift. */
    message: string;
    /** Severity bucket, mapped from the drift type / confidence. */
    severity: "error" | "warning" | "info";
}

/**
 * Map drift severity from a record. The mapping (a heuristic — drift is advisory):
 * - `error`   — a status-code drift (`driftType` contains `STATUS`), a removed
 *               schema field (`SCHEMA_FIELD_REMOVED`), or a certain drift
 *               (`confidence >= 1.0`): the stub is very likely wrong.
 * - `warning` — a newly added schema field (`SCHEMA_FIELD_ADDED`): the upstream
 *               grew a field the stub doesn't model.
 * - `info`    — everything else (value changes, low-confidence drift).
 */
function driftSeverity(record: DriftRecord): "error" | "warning" | "info" {
    const type = record.driftType ?? "";
    if (type.includes("STATUS") || type.startsWith("SCHEMA_FIELD_REMOVED") || record.confidence >= 1.0) {
        return "error";
    }
    if (type === "SCHEMA_FIELD_ADDED") {
        return "warning";
    }
    return "info";
}

/**
 * Find the zero-based line in `docText` of the expectation whose `"id"` equals
 * `expectationId`. Heuristic: scan for a line that contains both an `"id"` key
 * and the id string as a quoted value. Returns -1 when no such line is found.
 */
function findExpectationLine(docText: string, expectationId: string): number {
    if (!expectationId) {
        return -1;
    }
    const quotedId = `"${expectationId}"`;
    const lines = docText.split("\n");
    for (let i = 0; i < lines.length; i++) {
        if (lines[i].includes('"id"') && lines[i].includes(quotedId)) {
            return i;
        }
    }
    return -1;
}

/**
 * Build the URL for uploading a WASM custom-rule module to the running server:
 * `PUT /mockserver/wasm/modules?name=<moduleName>`. The module name is URL-encoded
 * so names with spaces or reserved characters are sent safely.
 */
export function buildWasmUploadUrl(baseUrl: string, name: string): string {
    return `${baseUrl}/mockserver/wasm/modules?name=${encodeURIComponent(name)}`;
}

/**
 * Upload a compiled `.wasm` custom-rule module to the running server via
 * `PUT /mockserver/wasm/modules?name=<name>`, sending the raw module bytes with a
 * `Content-Type: application/octet-stream` header. Once uploaded the module can be
 * referenced by name in an expectation body matcher (`{ "type": "WASM", "wasm": "<name>" }`).
 * Throws (with status + body) on a non-ok response — so the server's
 * "WASM support is disabled" 403 message surfaces verbatim.
 */
export async function uploadWasmModule(
    baseUrl: string,
    name: string,
    bytes: Uint8Array,
    fetchFn: FetchLike
): Promise<void> {
    const res = await fetchFn(buildWasmUploadUrl(baseUrl, name), {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream" },
        body: bytes,
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

/**
 * Retrieve the names of the WASM custom-rule modules currently registered on the
 * server, as pretty JSON, via `GET /mockserver/wasm/modules`. Throws (with status +
 * body) on a non-ok response; returns the raw body if it is not parseable JSON.
 */
export async function retrieveWasmModules(baseUrl: string, fetchFn: FetchLike): Promise<string> {
    const res = await fetchFn(`${baseUrl}/mockserver/wasm/modules`, {
        method: "GET",
        headers: { Accept: "application/json" },
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

/**
 * Map drift records to {@link DriftDiagnostic}s anchored to the matching
 * expectation lines in `docText`. For each record, the line is found by scanning
 * for a JSON `"id"` line whose value equals `record.expectationId`; when no line
 * matches, the diagnostic attaches to line 0 so it is still surfaced. The message
 * reads `Drift [<driftType>] <field>: expected <e> / actual <a> (confidence <c>)`,
 * with absent values shown as `—`. Pure and `vscode`-free so it can be unit-tested.
 */
export function mapDriftToDiagnostics(records: DriftRecord[], docText: string): DriftDiagnostic[] {
    return records.map((record) => {
        const found = findExpectationLine(docText, record.expectationId);
        const expected = formatDriftValue(record.expectedValue);
        const actual = formatDriftValue(record.actualValue);
        const message =
            `Drift [${record.driftType}] ${record.field}: ` +
            `expected ${expected} / actual ${actual} ` +
            `(confidence ${formatDriftValue(record.confidence)})`;
        return {
            line: found >= 0 ? found : 0,
            message,
            severity: driftSeverity(record),
        };
    });
}
