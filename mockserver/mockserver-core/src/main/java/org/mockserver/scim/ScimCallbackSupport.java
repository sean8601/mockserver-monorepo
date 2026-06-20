package org.mockserver.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.mockserver.model.HttpResponse.response;

/**
 * Shared helpers for the SCIM class callbacks: store resolution, the bearer-token gate, public base
 * URL derivation, JSON (de)serialization, and SCIM error responses.
 */
final class ScimCallbackSupport {

    static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);
    static final ScimShaper SHAPER = new ScimShaper();

    private ScimCallbackSupport() {
    }

    static ScimResourceStore.Provider provider(HttpRequest request) {
        return ScimResourceStore.getInstance().providerForRequestPath(request.getPath().getValue());
    }

    /**
     * Enforces the bearer-token gate when the provider requires it. Discovery endpoints are exempt
     * and pass {@code null}-checked callers should treat a null return as "allowed".
     *
     * @return a 401 SCIM error response when the token is missing/invalid, otherwise {@code null}
     */
    static HttpResponse bearerGate(HttpRequest request, ScimResourceStore.Provider provider) {
        if (provider == null || !provider.isRequireBearerToken()) {
            return null;
        }
        String authorization = request.getFirstHeader("Authorization");
        String token = null;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        }
        boolean valid;
        if (token == null || token.isEmpty()) {
            valid = false;
        } else if (provider.getExpectedBearerToken() != null && !provider.getExpectedBearerToken().isEmpty()) {
            valid = provider.getExpectedBearerToken().equals(token);
        } else {
            valid = true;
        }
        if (valid) {
            return null;
        }
        return scimError(401, "Unauthorized", null)
            .withHeader("WWW-Authenticate", "Bearer");
    }

    /**
     * Derives the scheme+authority for {@code meta.location} from the request Host header.
     */
    static String publicBaseUrl(HttpRequest request) {
        String host = request.getFirstHeader("Host");
        if (host == null || host.isEmpty()) {
            return "";
        }
        Boolean secure = request.isSecure();
        String scheme = (secure != null && secure) ? "https" : "http";
        return scheme + "://" + host;
    }

    static HttpResponse json(int statusCode, ObjectNode body) {
        return response()
            .withStatusCode(statusCode)
            .withHeader("content-type", ScimShaper.CONTENT_TYPE)
            .withBody(serialize(body));
    }

    static HttpResponse scimError(int statusCode, String detail, String scimType) {
        return json(statusCode, SHAPER.error(statusCode, detail, scimType));
    }

    static String serialize(Object value) {
        try {
            return OBJECT_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize SCIM response to JSON", e);
        }
    }

    static ObjectNode parseObject(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return (ObjectNode) OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the resource id from a request path of the form {@code <basePath>/<Resource>/<id>}.
     *
     * @return the (URL-decoded) id, or {@code null} when the path has no id segment
     */
    static String resourceId(HttpRequest request, ScimResourceStore.Provider provider, ScimShaper.ResourceType type) {
        String path = request.getPath().getValue();
        String prefix = provider.getBasePath() + "/" + type.getPathSegment() + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String remainder = path.substring(prefix.length());
        if (remainder.isEmpty() || remainder.contains("/")) {
            return null;
        }
        return java.net.URLDecoder.decode(remainder, java.nio.charset.StandardCharsets.UTF_8);
    }
}
