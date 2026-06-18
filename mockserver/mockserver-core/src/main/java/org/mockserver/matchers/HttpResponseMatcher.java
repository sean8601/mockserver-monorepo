package org.mockserver.matchers;

import org.mockserver.codec.JsonSchemaBodyDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import static org.mockserver.model.NottableString.string;

/**
 * Matches an actual {@link HttpResponse} against a template {@link HttpResponse}.
 * <p>
 * Each template field constrains the match only when it is set (non-null/non-blank).
 * A null or empty template matches any response.
 * <p>
 * Body matching shares the exact dispatch used by request matching via {@link BodyMatching}, so a
 * response body matcher has full parity with a request body matcher: XML/form actual bodies are
 * converted to JSON for the JSON family of matchers, an optional template body matches an absent
 * response body, multipart and binary (including original/compressed) bodies are handled, and an
 * absent actual body against a JSON/XML matcher is a clean non-match rather than a swallowed NPE.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class HttpResponseMatcher {

    private final HttpResponse template;
    private final RegexStringMatcher reasonPhraseMatcher;
    private final MultiValueMapMatcher headerMatcher;
    private final BodyMatcher bodyMatcher;
    private final Boolean bodyOptional;
    private final JsonSchemaBodyDecoder jsonSchemaBodyParser;
    private final MockServerLogger mockServerLogger;

    public HttpResponseMatcher(Configuration configuration, MockServerLogger mockServerLogger, HttpResponse template) {
        this.template = template;
        this.mockServerLogger = mockServerLogger;
        if (template != null) {
            this.reasonPhraseMatcher = template.getReasonPhrase() != null
                ? new RegexStringMatcher(mockServerLogger, string(template.getReasonPhrase()), false)
                : null;
            this.headerMatcher = template.getHeaders() != null && !template.getHeaders().isEmpty()
                ? new MultiValueMapMatcher(mockServerLogger, template.getHeaders(), false)
                : null;
            this.bodyMatcher = template.getBody() != null
                ? BodyMatcherBuilder.buildBodyMatcher(configuration, mockServerLogger, template.getBody(), false)
                : null;
            this.bodyOptional = template.getBody() != null ? template.getBody().getOptional() : null;
            // No expectation / template request is available for a response match — they are used by
            // the parser only for a JSON-conversion failure log, which the response path reports as
            // null (it has no originating request to attribute the failure to).
            this.jsonSchemaBodyParser = this.bodyMatcher != null
                ? new JsonSchemaBodyDecoder(configuration, mockServerLogger, null, null)
                : null;
        } else {
            this.reasonPhraseMatcher = null;
            this.headerMatcher = null;
            this.bodyMatcher = null;
            this.bodyOptional = null;
            this.jsonSchemaBodyParser = null;
        }
    }

    /**
     * Returns {@code true} when the actual response matches the template.
     * A null template matches everything.
     */
    public boolean matches(HttpResponse actual) {
        if (template == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }

        // statusCode: exact integer equality when set on template
        if (template.getStatusCode() != null) {
            if (!template.getStatusCode().equals(actual.getStatusCode())) {
                return false;
            }
        }

        // reasonPhrase: regex match when set on template
        if (reasonPhraseMatcher != null) {
            if (!reasonPhraseMatcher.matches(actual.getReasonPhrase())) {
                return false;
            }
        }

        // headers: reuse existing MultiValueMapMatcher
        if (headerMatcher != null) {
            if (!headerMatcher.matches(null, actual.getHeaders())) {
                return false;
            }
        }

        // body: share the exact dispatch used by request matching so the response body matcher has
        // full parity (JSON conversion of XML/form bodies, optional body, multipart, binary
        // original/compressed, null-safe JSON/XML). A null context is passed because response-side
        // match diagnostics are added in a later change; the dispatch tolerates a null context.
        if (bodyMatcher != null) {
            if (!BodyMatching.bodyMatches(
                bodyMatcher,
                bodyOptional,
                BodyMatching.of(actual),
                null,
                jsonSchemaBodyParser,
                mockServerLogger
            )) {
                return false;
            }
        }

        return true;
    }
}
