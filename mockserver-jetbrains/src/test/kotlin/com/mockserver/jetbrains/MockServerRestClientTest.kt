package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure (no-IDE, no-network) parts of [MockServerRestClient]:
 * URL building, request construction, the OpenAPI JSON-vs-YAML body branch, and
 * response handling. No running IDE or live server is required.
 */
class MockServerRestClientTest {

    private fun bodyOf(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
        val sb = StringBuilder()
        val subscriber = object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(item: java.nio.ByteBuffer) { sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item)) }
            override fun onError(t: Throwable) {}
            override fun onComplete() {}
        }
        publisher.subscribe(subscriber)
        return sb.toString()
    }

    // --- buildBaseUrl ---------------------------------------------------

    @Test
    fun `base url uses localhost and the given port`() {
        assertEquals("http://localhost:1080", MockServerRestClient.buildBaseUrl(1080))
        assertEquals("http://localhost:2080", MockServerRestClient.buildBaseUrl(2080))
    }

    // --- load expectations ----------------------------------------------

    @Test
    fun `load expectations PUTs to expectation endpoint with json content type and body verbatim`() {
        val text = """{ "httpRequest": { "path": "/a" } }"""
        val req = MockServerRestClient.buildLoadExpectationsRequest("http://localhost:1080", text)
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/expectation", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""))
        assertEquals(text, bodyOf(req))
    }

    @Test
    fun `load expectations sends an array body unchanged`() {
        val text = """[ { "httpRequest": { "path": "/a" } }, { "httpRequest": { "path": "/b" } } ]"""
        val req = MockServerRestClient.buildLoadExpectationsRequest("http://localhost:1080", text)
        assertEquals(text, bodyOf(req))
    }

    // --- retrieve recorded ----------------------------------------------

    @Test
    fun `retrieve recorded PUTs with type and json format query params`() {
        val req = MockServerRestClient.buildRetrieveRecordedRequest("http://localhost:1080", "json")
        assertEquals("PUT", req.method())
        assertEquals(
            "http://localhost:1080/mockserver/retrieve?type=recorded_expectations&format=json",
            req.uri().toString()
        )
    }

    @Test
    fun `retrieve recorded honours the requested format`() {
        val req = MockServerRestClient.buildRetrieveRecordedRequest("http://localhost:1080", "java")
        assertTrue(req.uri().toString().endsWith("format=java"))
    }

    // --- OpenAPI body branch --------------------------------------------

    @Test
    fun `openapi body embeds a JSON spec as an object payload`() {
        val spec = """{ "openapi": "3.0.0", "info": { "title": "t", "version": "1" } }"""
        val body = MockServerRestClient.buildOpenApiBody(spec)
        val root = JsonParser.parseString(body).asJsonObject
        val payload = root.get("specUrlOrPayload")
        assertTrue(payload.isJsonObject, "JSON spec should be embedded as an object, was: $payload")
        assertEquals("3.0.0", payload.asJsonObject.get("openapi").asString)
    }

    @Test
    fun `openapi body sends a YAML spec as a string payload`() {
        val spec = "openapi: 3.0.0\ninfo:\n  title: t\n  version: '1'\n"
        val body = MockServerRestClient.buildOpenApiBody(spec)
        val payload = JsonParser.parseString(body).asJsonObject.get("specUrlOrPayload")
        assertTrue(payload.isJsonPrimitive && payload.asJsonPrimitive.isString)
        assertEquals(spec, payload.asString)
    }

    @Test
    fun `openapi body treats a bare JSON string as a string payload (not embedded)`() {
        // JsonParser would happily parse "\"hello\"" as a primitive; ensure only
        // objects/arrays are embedded so an accidental bare value is sent raw.
        val spec = "\"just a string\""
        val payload = JsonParser.parseString(MockServerRestClient.buildOpenApiBody(spec))
            .asJsonObject.get("specUrlOrPayload")
        assertTrue(payload.isJsonPrimitive && payload.asJsonPrimitive.isString)
        assertEquals(spec, payload.asString)
    }

    @Test
    fun `generate from openapi PUTs to openapi endpoint with json content type`() {
        val req = MockServerRestClient.buildGenerateFromOpenApiRequest("http://localhost:1080", "{}")
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/openapi", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""))
    }

    // --- response helpers -----------------------------------------------

    @Test
    fun `empty body is detected as empty`() {
        assertTrue(MockServerRestClient.isEmptyExpectationsBody(""))
        assertTrue(MockServerRestClient.isEmptyExpectationsBody("   \n  "))
    }

    @Test
    fun `empty json array is detected as empty`() {
        assertTrue(MockServerRestClient.isEmptyExpectationsBody("[]"))
        assertTrue(MockServerRestClient.isEmptyExpectationsBody("  [ ]  "))
    }

    @Test
    fun `non-empty array is not empty`() {
        assertFalse(MockServerRestClient.isEmptyExpectationsBody("""[ { "httpRequest": {} } ]"""))
    }

    @Test
    fun `non-json non-blank body is not treated as empty`() {
        assertFalse(MockServerRestClient.isEmptyExpectationsBody("some Java DSL text"))
    }

    @Test
    fun `pretty print formats valid json`() {
        val pretty = MockServerRestClient.prettyPrintJson("""[{"a":1}]""")
        assertTrue(pretty.contains("\n"), "expected multi-line pretty output, was: $pretty")
        // Round-trips to the same structure.
        assertEquals(
            JsonParser.parseString("""[{"a":1}]"""),
            JsonParser.parseString(pretty)
        )
    }

    @Test
    fun `pretty print returns non-json input unchanged`() {
        val raw = "not json at all"
        assertEquals(raw, MockServerRestClient.prettyPrintJson(raw))
    }

    @Test
    fun `result ok reflects 2xx status`() {
        assertTrue(MockServerRestClient.Result(200, "").ok)
        assertTrue(MockServerRestClient.Result(201, "").ok)
        assertFalse(MockServerRestClient.Result(404, "").ok)
        assertFalse(MockServerRestClient.Result(500, "").ok)
    }

    @Test
    fun `looksLikeOpenApiSpec detects json and yaml specs`() {
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("""{ "openapi": "3.0.0", "paths": {} }"""))
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("""{ "swagger": "2.0", "paths": {} }"""))
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("openapi: 3.0.0\npaths: {}"))
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("swagger: \"2.0\"\npaths: {}"))
    }

    @Test
    fun `looksLikeOpenApiSpec rejects expectations and junk`() {
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("""{ "httpRequest": {}, "httpResponse": {} }"""))
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("""[ { "httpResponse": {} } ]"""))
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec(""))
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("just some text"))
        // a value merely containing the substring is not a top-level key
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("""{ "note": "openapi is great" }"""))
    }

    // --- parseRequestSpec -----------------------------------------------

    @Test
    fun `parseRequestSpec reads method path headers and body`() {
        val spec = MockServerRestClient.parseRequestSpec(
            """{ "method": "POST", "path": "/api/x", "headers": { "X-A": "1", "X-B": "2" }, "body": "hi" }"""
        )
        assertEquals("POST", spec.method)
        assertEquals("/api/x", spec.path)
        assertEquals(mapOf("X-A" to "1", "X-B" to "2"), spec.headers)
        assertEquals("hi", spec.body)
    }

    @Test
    fun `parseRequestSpec defaults headers and body when omitted`() {
        val spec = MockServerRestClient.parseRequestSpec("""{ "method": "GET", "path": "/" }""")
        assertEquals("GET", spec.method)
        assertEquals("/", spec.path)
        assertTrue(spec.headers.isEmpty())
        assertNull(spec.body)
    }

    @Test
    fun `parseRequestSpec rejects a missing method`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "path": "/api/x" }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a blank method`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "method": "  ", "path": "/api/x" }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a missing path`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "method": "GET" }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a non-string header value`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "method": "GET", "path": "/", "headers": { "X-A": 1 } }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a non-object input`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""[ { "method": "GET", "path": "/" } ]""")
        }
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("not json")
        }
    }

    // --- buildScratchRequest --------------------------------------------

    @Test
    fun `buildScratchRequest targets base plus path with the given method`() {
        val spec = MockServerRestClient.RequestSpec("DELETE", "/api/x")
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals("DELETE", req.method())
        assertEquals("http://localhost:1080/api/x", req.uri().toString())
    }

    @Test
    fun `buildScratchRequest sets each header`() {
        val spec = MockServerRestClient.RequestSpec("GET", "/", mapOf("X-A" to "1", "Accept" to "application/json"))
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals("1", req.headers().firstValue("X-A").orElse(""))
        assertEquals("application/json", req.headers().firstValue("Accept").orElse(""))
    }

    @Test
    fun `buildScratchRequest sends the body when present`() {
        val spec = MockServerRestClient.RequestSpec("POST", "/api/x", body = """{"a":1}""")
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals("POST", req.method())
        assertEquals("""{"a":1}""", bodyOf(req))
    }

    @Test
    fun `buildScratchRequest uses no body for a body-less spec`() {
        val spec = MockServerRestClient.RequestSpec("GET", "/")
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals(0L, req.bodyPublisher().orElseThrow().contentLength())
    }

    @Test
    fun `isJsonObjectOrArray distinguishes shapes`() {
        assertTrue(MockServerRestClient.isJsonObjectOrArray("""{ "a": 1 }"""))
        assertTrue(MockServerRestClient.isJsonObjectOrArray("""[ 1, 2 ]"""))
        assertFalse(MockServerRestClient.isJsonObjectOrArray("not json"))
        assertFalse(MockServerRestClient.isJsonObjectOrArray("42"))
        assertFalse(MockServerRestClient.isJsonObjectOrArray("openapi: 3.0.0"))
    }
}
