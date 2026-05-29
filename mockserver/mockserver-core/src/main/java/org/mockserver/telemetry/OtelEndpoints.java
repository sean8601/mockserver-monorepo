package org.mockserver.telemetry;

/**
 * Resolves per-signal OTLP HTTP endpoints from the single configured base URL
 * (see {@code mockserver.otelEndpoint}). Shared by the metrics and trace
 * exporters so both derive their endpoints consistently.
 */
public final class OtelEndpoints {

    public static final String METRICS_PATH = "/v1/metrics";
    public static final String TRACES_PATH = "/v1/traces";

    private OtelEndpoints() {
    }

    /**
     * Resolve the metrics endpoint, or null to use the exporter default.
     */
    public static String metrics(String base) {
        return signal(base, METRICS_PATH);
    }

    /**
     * Resolve the traces endpoint, or null to use the exporter default.
     */
    public static String traces(String base) {
        return signal(base, TRACES_PATH);
    }

    private static String signal(String base, String signalPath) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String b = base.trim();
        // tolerate a value already carrying a signal path and/or a trailing slash:
        // strip trailing slashes first, then a signal-path suffix, then slashes again
        b = stripTrailingSlashes(b);
        if (b.endsWith(METRICS_PATH)) {
            b = b.substring(0, b.length() - METRICS_PATH.length());
        } else if (b.endsWith(TRACES_PATH)) {
            b = b.substring(0, b.length() - TRACES_PATH.length());
        }
        b = stripTrailingSlashes(b);
        return b + signalPath;
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
