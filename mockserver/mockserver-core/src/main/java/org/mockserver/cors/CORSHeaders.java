package org.mockserver.cors;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static io.netty.handler.codec.http.HttpMethod.OPTIONS;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * @author jamesdbloom
 */
public class CORSHeaders {

    private static final String ANY_ORIGIN = "*";
    private static final String NULL_ORIGIN = "null";
    // Sensible defaults used when corsAllowMethods / corsAllowHeaders are left blank,
    // so the control-plane API (and dashboard) is usable cross-origin out of the box
    // rather than returning an empty Access-Control-Allow-Methods/Headers header.
    public static final String DEFAULT_ALLOW_METHODS = "CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE";
    public static final String DEFAULT_ALLOW_HEADERS = "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization";

    private final String corsAllowOrigin;
    private final String corsAllowHeaders;
    private final String corsAllowMethods;
    private final boolean corsAllowCredentials;
    private final String corsMaxAge;

    public CORSHeaders(String corsAllowOrigin, String corsAllowHeaders, String corsAllowMethods, boolean corsAllowCredentials, int corsMaxAge) {
        this(
            configuration()
                .corsAllowOrigin(corsAllowOrigin)
                .corsAllowHeaders(corsAllowHeaders)
                .corsAllowMethods(corsAllowMethods)
                .corsAllowCredentials(corsAllowCredentials)
                .corsMaxAgeInSeconds(corsMaxAge)
        );
    }

    public CORSHeaders(Configuration configuration) {
        this.corsAllowOrigin = configuration.corsAllowOrigin();
        this.corsAllowHeaders = configuration.corsAllowHeaders();
        this.corsAllowMethods = configuration.corsAllowMethods();
        this.corsAllowCredentials = configuration.corsAllowCredentials();
        this.corsMaxAge = "" + configuration.corsMaxAgeInSeconds();
    }

    public static boolean isPreflightRequest(Configuration configuration, HttpRequest request) {
        final Headers headers = request.getHeaders();
        boolean isPreflightRequest = request.getMethod().getValue().equals(OPTIONS.name()) &&
            headers.containsEntry(HttpHeaderNames.ORIGIN.toString()) &&
            headers.containsEntry(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD.toString());
        if (isPreflightRequest) {
            configuration.enableCORSForAPI(true);
        }
        return isPreflightRequest;
    }

    public void addCORSHeaders(HttpRequest request, HttpResponse response) {
        String origin = request.getFirstHeader(HttpHeaderNames.ORIGIN.toString());
        // An explicit corsAllowOrigin (e.g. an allow-list) is always honoured; when it
        // is left blank (the default) the requesting Origin is reflected so the API /
        // dashboard works from any origin, instead of emitting an empty (invalid)
        // Access-Control-Allow-Origin header.
        boolean explicitAllowOrigin = corsAllowOrigin != null && !corsAllowOrigin.isEmpty();
        if (NULL_ORIGIN.equals(origin)) {
            setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(), NULL_ORIGIN);
        } else if (!origin.isEmpty() && corsAllowCredentials) {
            // With credentials the actual Origin must be reflected ("*" is invalid).
            setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(), origin);
            setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS.toString(), "true");
        } else if (!origin.isEmpty()) {
            setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(), explicitAllowOrigin ? corsAllowOrigin : origin);
            setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS.toString(), "" + corsAllowCredentials);
        } else {
            String originValue = explicitAllowOrigin ? corsAllowOrigin : ANY_ORIGIN;
            setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(), originValue);
            // "*" with credentials=true is forbidden by the CORS spec; omit the
            // credentials header in that (degenerate, no-Origin) case.
            if (!(ANY_ORIGIN.equals(originValue) && corsAllowCredentials)) {
                setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS.toString(), "" + corsAllowCredentials);
            }
        }
        String allowMethods = (corsAllowMethods != null && !corsAllowMethods.isEmpty()) ? corsAllowMethods : DEFAULT_ALLOW_METHODS;
        setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS.toString(), allowMethods);
        String requestedHeaders = request.getFirstHeader(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS.toString());
        String allowHeaders;
        if (corsAllowHeaders != null && !corsAllowHeaders.isEmpty()) {
            allowHeaders = requestedHeaders.isEmpty() ? corsAllowHeaders : corsAllowHeaders + ", " + requestedHeaders;
        } else if (!requestedHeaders.isEmpty()) {
            // Reflect the headers the browser asked to send in the preflight.
            allowHeaders = requestedHeaders;
        } else {
            allowHeaders = DEFAULT_ALLOW_HEADERS;
        }
        setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS.toString(), allowHeaders);
        setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS.toString(), allowHeaders);
        setHeaderIfNotAlreadyExists(response, HttpHeaderNames.ACCESS_CONTROL_MAX_AGE.toString(), corsMaxAge);
    }

    private void setHeaderIfNotAlreadyExists(HttpResponse response, String name, String value) {
        if (response.getFirstHeader(name).isEmpty()) {
            response.withHeader(name, value);
        }
    }

}
