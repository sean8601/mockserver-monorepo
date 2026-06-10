package org.mockserver.matchers;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.slf4j.event.Level.TRACE;

public class MatchDifference {

    public enum Field {
        METHOD("method"),
        PATH("path"),
        PATH_PARAMETERS("pathParameters"),
        QUERY_PARAMETERS("queryParameters"),
        COOKIES("cookies"),
        HEADERS("headers"),
        BODY("body"),
        SECURE("secure"),
        PROTOCOL("protocol"),
        KEEP_ALIVE("keep-alive"),
        OPERATION("operation"),
        OPENAPI("openapi"),
        DNS_NAME("dnsName"),
        DNS_TYPE("dnsType"),
        DNS_CLASS("dnsClass"),
        BINARY_BODY("binaryBody");

        private final String name;

        Field(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private final boolean detailedMatchFailures;
    private final RequestDefinition httpRequest;
    private final Map<Field, List<String>> differences = new ConcurrentHashMap<>();
    private Field fieldName;
    // When true, a matcher computing differences against this context must NOT emit
    // EXPECTATION_MATCHED / EXPECTATION_NOT_MATCHED events to the event log. Read-only
    // diagnostics (explainUnmatched, debugMismatch) re-run real matchers purely to
    // collect field differences and must not pollute the very log they inspect — those
    // entries also lack a request correlationId, so they cannot be grouped in the dashboard
    // and would saturate its bounded log window. This flag is request-scoped (a fresh
    // MatchDifference per evaluation), so it is thread-safe unlike toggling the matcher.
    private boolean suppressMatchResultLogging;

    public MatchDifference(boolean detailedMatchFailures, RequestDefinition httpRequest) {
        this.detailedMatchFailures = detailedMatchFailures;
        this.httpRequest = httpRequest;
    }

    /**
     * Mark this difference context as diagnostic-only: matchers must compute differences without
     * logging EXPECTATION_MATCHED / EXPECTATION_NOT_MATCHED events. Used by read-only endpoints
     * (explainUnmatched, debugMismatch) so they do not write side-effect entries into the event log.
     */
    public MatchDifference suppressMatchResultLogging() {
        this.suppressMatchResultLogging = true;
        return this;
    }

    public boolean isSuppressMatchResultLogging() {
        return suppressMatchResultLogging;
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, Throwable throwable, String messageFormat, Object... arguments) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setHttpRequest(httpRequest)
                    .setCorrelationId(httpRequest.getLogCorrelationId())
                    .setMessageFormat(messageFormat)
                    .setArguments(arguments)
                    .setThrowable(throwable)
            );
        }
        return addDifference(messageFormat, arguments);
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(MockServerLogger mockServerLogger, String messageFormat, Object... arguments) {
        return addDifference(mockServerLogger, null, messageFormat, arguments);
    }

    public MatchDifference addDifference(Field fieldName, String messageFormat, Object... arguments) {
        if (detailedMatchFailures) {
            if (isNotBlank(messageFormat) && arguments != null && fieldName != null) {
                this.differences
                    .computeIfAbsent(fieldName, key -> new ArrayList<>())
                    .add(formatLogMessage(1, messageFormat, arguments));
            }
        }
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifference addDifference(String messageFormat, Object... arguments) {
        return addDifference(fieldName, messageFormat, arguments);
    }

    public RequestDefinition getHttpRequest() {
        return httpRequest;
    }

    public String getLogCorrelationId() {
        return httpRequest.getLogCorrelationId();
    }

    @SuppressWarnings("UnusedReturnValue")
    protected MatchDifference currentField(Field fieldName) {
        this.fieldName = fieldName;
        return this;
    }

    public List<String> getDifferences(Field fieldName) {
        return this.differences.get(fieldName);
    }

    public Map<Field, List<String>> getAllDifferences() {
        return this.differences;
    }

    public void addDifferences(Map<Field, List<String>> differences) {
        for (Field field : differences.keySet()) {
            this.differences
                .computeIfAbsent(field, key -> new ArrayList<>())
                .addAll(differences.get(field));
        }
    }
}
