package org.mockserver.mock.breakpoint;

import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.model.RequestDefinition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A registered breakpoint: a request matcher + set of phases at which matching
 * forwarded exchanges should be paused for interactive inspection/modification.
 *
 * <p>The prebuilt {@link HttpRequestMatcher} is created once at registration time
 * (via {@link org.mockserver.matchers.MatcherBuilder#transformsToMatcher(RequestDefinition)})
 * and reused for every {@code findMatch} call — no allocation on the hot path.
 *
 * <p>The {@link #clientId} identifies the owning callback WebSocket client.
 * It is required -- matched exchanges are always dispatched over the callback
 * WebSocket to the owning client for interactive resolution.
 *
 * <p>Value-equality is on {@link #id} only (UUID assigned at registration).
 */
public class BreakpointMatcher {

    private final String id;
    private final RequestDefinition requestMatcher;
    private final Set<BreakpointPhase> phases;
    private final transient HttpRequestMatcher prebuiltMatcher;
    private final String clientId;

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher) {
        this(id, requestMatcher, phases, prebuiltMatcher, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId) {
        this.id = id;
        this.requestMatcher = requestMatcher;
        this.phases = Collections.unmodifiableSet(EnumSet.copyOf(phases));
        this.prebuiltMatcher = prebuiltMatcher;
        this.clientId = clientId;
    }

    public String getId() {
        return id;
    }

    public RequestDefinition getRequestMatcher() {
        return requestMatcher;
    }

    public Set<BreakpointPhase> getPhases() {
        return phases;
    }

    public HttpRequestMatcher getPrebuiltMatcher() {
        return prebuiltMatcher;
    }

    /**
     * The callback WebSocket client id that owns this breakpoint (required).
     */
    public String getClientId() {
        return clientId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BreakpointMatcher that = (BreakpointMatcher) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "BreakpointMatcher{id='" + id + "'"
            + ", phases=" + phases
            + (clientId != null ? ", clientId='" + clientId + "'" : "")
            + ", matcher=" + requestMatcher + "}";
    }
}
