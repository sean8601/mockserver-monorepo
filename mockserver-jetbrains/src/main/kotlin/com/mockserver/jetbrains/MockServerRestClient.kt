package com.mockserver.jetbrains

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal REST client for talking to a running MockServer instance from the IDE.
 *
 * This object is deliberately free of any IntelliJ-platform API so it can be unit
 * tested directly with no IDE and no live server: every network operation is split
 * into a *pure* request-builder (`build*Request`) that can be asserted on, and a
 * thin `send` wrapper. The contracts mirror the VS Code extension's
 * `mockServerClient.ts`.
 *
 * IMPORTANT: callers must never invoke [send] (or the high-level `*` helpers) on
 * the IntelliJ event-dispatch thread — these block on the network. Run them on a
 * pooled/background thread and marshal UI work back onto the EDT.
 */
object MockServerRestClient {

    private val PRETTY: Gson = GsonBuilder().setPrettyPrinting().create()
    private val COMPACT: Gson = Gson()

    /** Base URL for a server reachable on localhost at [port], e.g. `http://localhost:1080`. */
    fun buildBaseUrl(port: Int): String = "http://localhost:$port"

    private fun newClient(): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    /** Result of a high-level call: the HTTP status plus the (possibly formatted) body. */
    data class Result(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    // ---------------------------------------------------------------------
    // Pure request builders (no I/O) — unit-testable.
    // ---------------------------------------------------------------------

    /**
     * `PUT /mockserver/expectation` — load one expectation or an array of
     * expectations. The body is the file text as-is (MockServer accepts either
     * shape); we only set the JSON content type.
     */
    fun buildLoadExpectationsRequest(baseUrl: String, fileText: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/expectation"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(fileText))
            .build()

    /**
     * `PUT /mockserver/retrieve?type=recorded_expectations&format=<format>` —
     * retrieve expectations generated from recorded (proxied/forwarded) traffic.
     */
    fun buildRetrieveRecordedRequest(baseUrl: String, format: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/retrieve?type=recorded_expectations&format=$format"))
            .header("Accept", "application/json")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

    /**
     * `PUT /mockserver/openapi` — generate expectations from an OpenAPI/Swagger
     * spec. A JSON spec is sent as a parsed object so the server treats it
     * unambiguously as an inline payload; anything else (YAML) is sent as a
     * string, which the server also parses. See [buildOpenApiBody].
     */
    fun buildGenerateFromOpenApiRequest(baseUrl: String, specText: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/openapi"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(buildOpenApiBody(specText)))
            .build()

    /**
     * Build the `{"specUrlOrPayload": ...}` body for the OpenAPI endpoint. When
     * [specText] parses as JSON the value is the embedded object/array; otherwise
     * (YAML, or anything unparseable) it is the raw string.
     */
    fun buildOpenApiBody(specText: String): String {
        val parsed: JsonElement? = tryParseJson(specText)
        val payload: JsonElement = parsed ?: COMPACT.toJsonTree(specText)
        val wrapper = com.google.gson.JsonObject()
        wrapper.add("specUrlOrPayload", payload)
        return COMPACT.toJson(wrapper)
    }

    // ---------------------------------------------------------------------
    // Response helpers (no I/O) — unit-testable.
    // ---------------------------------------------------------------------

    /** True when a retrieve/generate body holds no expectations (blank or `[]`). */
    fun isEmptyExpectationsBody(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return true
        val parsed = tryParseJson(trimmed) ?: return false
        return parsed.isJsonArray && parsed.asJsonArray.isEmpty
    }

    /** Pretty-print a JSON body; returns the input unchanged if it is not valid JSON. */
    fun prettyPrintJson(body: String): String {
        val parsed = tryParseJson(body) ?: return body
        return PRETTY.toJson(parsed)
    }

    /** True when [text] parses as a JSON object or array (the shape an expectation file must take). */
    fun isJsonObjectOrArray(text: String): Boolean = tryParseJson(text) != null

    private val OPENAPI_YAML_KEY = Regex("""(?im)^\s*["']?(openapi|swagger)["']?\s*:""")

    /**
     * Heuristic check that [specText] looks like an OpenAPI/Swagger specification —
     * i.e. it has a top-level `openapi` (3.x) or `swagger` (2.0) field, in either
     * JSON or YAML. Used to warn the user clearly when the active editor is not a
     * spec, instead of sending it to the server and surfacing a raw 400.
     */
    fun looksLikeOpenApiSpec(specText: String): Boolean {
        val parsed = tryParseJson(specText)
        if (parsed != null && parsed.isJsonObject) {
            val obj = parsed.asJsonObject
            return obj.has("openapi") || obj.has("swagger")
        }
        // YAML (or any non-JSON-object content): look for a top-level openapi:/swagger: key.
        return OPENAPI_YAML_KEY.containsMatchIn(specText)
    }

    private fun tryParseJson(text: String): JsonElement? =
        try {
            val element = JsonParser.parseString(text)
            // parseString accepts bare primitives ("foo", 12); for our purposes
            // only objects/arrays count as a real JSON spec/payload.
            if (element.isJsonObject || element.isJsonArray) element else null
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------------
    // I/O — must run off the EDT.
    // ---------------------------------------------------------------------

    /** Send a prepared request and capture the status + body as a string. */
    fun send(request: HttpRequest): Result {
        val response: HttpResponse<String> =
            newClient().send(request, HttpResponse.BodyHandlers.ofString())
        return Result(response.statusCode(), response.body() ?: "")
    }
}
