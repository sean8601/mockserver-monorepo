package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import static org.mockserver.model.NottableString.string;

/**
 * Matches an actual {@link HttpResponse} against a template {@link HttpResponse}.
 * <p>
 * Each template field constrains the match only when it is set (non-null/non-blank).
 * A null or empty template matches any response.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class HttpResponseMatcher {

    private final HttpResponse template;
    private final RegexStringMatcher reasonPhraseMatcher;
    private final MultiValueMapMatcher headerMatcher;
    private final BodyMatcher bodyMatcher;

    public HttpResponseMatcher(Configuration configuration, MockServerLogger mockServerLogger, HttpResponse template) {
        this.template = template;
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
        } else {
            this.reasonPhraseMatcher = null;
            this.headerMatcher = null;
            this.bodyMatcher = null;
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

        // body: reuse the same body matcher building logic as request matching
        if (bodyMatcher != null) {
            if (!bodyMatcherMatches(actual)) {
                return false;
            }
        }

        return true;
    }

    private boolean bodyMatcherMatches(HttpResponse actual) {
        if (bodyMatcher instanceof BinaryMatcher) {
            return bodyMatcher.matches(null, actual.getBodyAsRawBytes());
        } else if (bodyMatcher instanceof ExactStringMatcher ||
            bodyMatcher instanceof SubStringMatcher ||
            bodyMatcher instanceof RegexStringMatcher) {
            return bodyMatcher.matches(null, string(actual.getBodyAsString()));
        } else if (bodyMatcher instanceof XmlStringMatcher ||
            bodyMatcher instanceof XmlSchemaMatcher ||
            bodyMatcher instanceof JsonRpcMatcher ||
            bodyMatcher instanceof GraphQLMatcher) {
            return bodyMatcher.matches(null, actual.getBodyAsString());
        } else if (bodyMatcher instanceof JsonStringMatcher ||
            bodyMatcher instanceof JsonSchemaMatcher ||
            bodyMatcher instanceof JsonPathMatcher) {
            return bodyMatcher.matches(null, actual.getBodyAsString());
        } else {
            return bodyMatcher.matches(null, actual.getBodyAsString());
        }
    }
}
