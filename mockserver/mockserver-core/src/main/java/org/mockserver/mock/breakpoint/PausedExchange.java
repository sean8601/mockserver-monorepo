package org.mockserver.mock.breakpoint;

import org.mockserver.model.HttpRequest;
import org.mockserver.time.TimeService;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a single proxied request that has been paused at a breakpoint,
 * awaiting external resolution (continue / modify / abort) via the control-plane
 * REST API or an automatic timeout.
 */
public class PausedExchange {

    private final String correlationId;
    private final HttpRequest capturedRequest;
    private final CompletableFuture<BreakpointDecision> decisionFuture;
    private final long createdAtMillis;
    private final String matchedExpectationId;

    public PausedExchange(String correlationId, HttpRequest capturedRequest, String matchedExpectationId) {
        this.correlationId = correlationId;
        this.capturedRequest = capturedRequest;
        this.decisionFuture = new CompletableFuture<>();
        this.createdAtMillis = TimeService.currentTimeMillis();
        this.matchedExpectationId = matchedExpectationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public HttpRequest getCapturedRequest() {
        return capturedRequest;
    }

    public CompletableFuture<BreakpointDecision> getDecisionFuture() {
        return decisionFuture;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public String getMatchedExpectationId() {
        return matchedExpectationId;
    }

    /**
     * Age in milliseconds since this exchange was paused.
     */
    public long ageMillis() {
        return TimeService.currentTimeMillis() - createdAtMillis;
    }
}
