package org.mockserver.mock.breakpoint;

import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.model.RequestDefinition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>Conditional (Nth-hit / skip-count) breakpoints:</b> an optional
 * {@link #skipCount} delays the first pause. The matcher still <em>matches</em>
 * normally on every hit, but only <em>pauses</em> once it has been hit more than
 * {@code skipCount} times. With {@code skipCount = 2} the breakpoint does NOT
 * pause on hits 1 and 2 and DOES pause from hit 3 onward. When {@code skipCount}
 * is {@code null} (the default) the breakpoint pauses on every hit (legacy
 * behaviour). The per-matcher hit counter ({@link #hitCount}) is incremented
 * atomically by {@link #shouldPause()}, so the decision is thread-safe under
 * concurrent matching.
 *
 * <p>Value-equality is on {@link #id} only (UUID assigned at registration).
 */
public class BreakpointMatcher {

    private final String id;
    private final RequestDefinition requestMatcher;
    private final Set<BreakpointPhase> phases;
    private final transient HttpRequestMatcher prebuiltMatcher;
    private final String clientId;
    private final Integer skipCount;
    private final transient AtomicLong hitCount = new AtomicLong(0L);

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher) {
        this(id, requestMatcher, phases, prebuiltMatcher, null, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId) {
        this(id, requestMatcher, phases, prebuiltMatcher, clientId, null);
    }

    public BreakpointMatcher(String id, RequestDefinition requestMatcher,
                             Set<BreakpointPhase> phases, HttpRequestMatcher prebuiltMatcher,
                             String clientId, Integer skipCount) {
        this.id = id;
        this.requestMatcher = requestMatcher;
        this.phases = Collections.unmodifiableSet(EnumSet.copyOf(phases));
        this.prebuiltMatcher = prebuiltMatcher;
        this.clientId = clientId;
        this.skipCount = (skipCount != null && skipCount > 0) ? skipCount : null;
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

    /**
     * The optional skip-count: the number of matching hits to skip before the
     * breakpoint starts pausing. {@code null} (the default) means pause on every
     * hit. A value of {@code n} means do not pause on the first {@code n} hits and
     * pause from hit {@code n + 1} onward.
     */
    public Integer getSkipCount() {
        return skipCount;
    }

    /**
     * The number of times this breakpoint has matched (for diagnostics / tests).
     */
    public long getHitCount() {
        return hitCount.get();
    }

    /**
     * Records a matching hit and decides whether this hit should actually pause.
     *
     * <p>Called exactly once per matching exchange/phase (from
     * {@link BreakpointMatcherRegistry#findMatch}). Increments the per-matcher
     * counter atomically and returns {@code true} if the breakpoint should pause
     * for this hit, {@code false} if the hit falls within the configured
     * skip-count window. When {@link #skipCount} is {@code null} this always
     * returns {@code true} (pause every time).
     *
     * @return {@code true} to pause this hit, {@code false} to skip it
     */
    public boolean shouldPause() {
        long hit = hitCount.incrementAndGet();
        if (skipCount == null) {
            return true;
        }
        return hit > skipCount;
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
            + (skipCount != null ? ", skipCount=" + skipCount : "")
            + ", matcher=" + requestMatcher + "}";
    }
}
