package org.mockserver.mock.breakpoint;

import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.mockserver.mock.breakpoint.PausedExchange.Phase;

import java.util.*;
import java.util.concurrent.*;

/**
 * Process-wide registry of request breakpoints. Holds paused (breakpointed)
 * proxy-forward exchanges until they are resolved via the control-plane REST
 * API or auto-continued by the timeout rail.
 *
 * <p>Thread-safe: uses a {@link ConcurrentHashMap} internally and is designed
 * to be called from Netty worker threads (never the event loop).
 *
 * <p><b>DoS rail:</b> the registry enforces a hard cap on concurrently held
 * exchanges ({@link Configuration#breakpointMaxHeld()}, default 50). When the
 * cap is reached, new breakpoint intercepts are skipped and the request is
 * forwarded normally.
 *
 * <p><b>Timeout rail:</b> each paused exchange auto-continues if not resolved
 * within {@link Configuration#breakpointTimeoutMillis()} (default 30 000 ms).
 */
public class BreakpointRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BreakpointRegistry.class);

    private static final BreakpointRegistry INSTANCE = new BreakpointRegistry();

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MockServer-breakpoint-timeout");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, PausedExchange> held = new ConcurrentHashMap<>();

    public static BreakpointRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Attempts to pause a forwarded request at a breakpoint.
     *
     * <p>If the held-exchange cap is already reached, returns {@code null}
     * (the caller should proceed with forwarding normally).
     *
     * <p>Otherwise, registers a {@link PausedExchange} and schedules a
     * timeout auto-continue. Returns the registered exchange whose
     * {@link PausedExchange#getDecisionFuture()} the caller should block on
     * (on the scheduler worker thread, NOT the event loop).
     *
     * @param correlationId the request's log correlation id (unique per request)
     * @param request       the captured {@link HttpRequest} (already deserialized, no raw ByteBuf)
     * @param expectationId the matched expectation id, or null for unmatched proxy
     * @param configuration the active server configuration (for maxHeld and timeout)
     * @return the registered {@link PausedExchange}, or {@code null} if the cap is reached
     */
    public PausedExchange pause(String correlationId, HttpRequest request, String expectationId, Configuration configuration) {
        int maxHeld = configuration.breakpointMaxHeld();
        if (held.size() >= maxHeld) {
            LOG.info("breakpoint cap reached ({}/{}), skipping breakpoint for correlation={}", held.size(), maxHeld, correlationId);
            return null;
        }

        PausedExchange exchange = new PausedExchange(correlationId, request, expectationId);
        held.put(correlationId, exchange);

        // schedule timeout auto-continue: complete with CONTINUE if not yet resolved
        long timeoutMillis = configuration.breakpointTimeoutMillis();
        ScheduledFuture<?> timeoutHandle = TIMEOUT_SCHEDULER.schedule(() -> {
            if (exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal())) {
                LOG.info("breakpoint auto-continued (timeout {}ms) for correlation={}", timeoutMillis, correlationId);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        // when the decision is resolved (by API call or timeout), clean up.
        // Use conditional remove so a stale callback from a reset()-completed
        // exchange cannot remove a NEW exchange with the same correlationId.
        exchange.getDecisionFuture().whenComplete((decision, throwable) -> {
            timeoutHandle.cancel(false);
            held.remove(correlationId, exchange);
        });

        return exchange;
    }

    /**
     * Attempts to pause a forwarded response at a breakpoint (RESPONSE phase).
     *
     * <p>If the held-exchange cap is already reached, returns {@code null}
     * (the caller should write the response normally).
     *
     * <p>Otherwise, registers a RESPONSE-phase {@link PausedExchange} and schedules a
     * timeout auto-continue. Returns the registered exchange whose
     * {@link PausedExchange#getDecisionFuture()} the caller chains the client write onto.
     *
     * @param correlationId the request's log correlation id (unique per request)
     * @param request       the original inbound request (for context/logging)
     * @param response      the upstream {@link HttpResponse} to hold
     * @param expectationId the matched expectation id, or null for unmatched proxy
     * @param configuration the active server configuration (for maxHeld and timeout)
     * @return the registered {@link PausedExchange}, or {@code null} if the cap is reached
     */
    public PausedExchange pauseResponse(String correlationId, HttpRequest request, HttpResponse response, String expectationId, Configuration configuration) {
        int maxHeld = configuration.breakpointMaxHeld();
        if (held.size() >= maxHeld) {
            LOG.info("breakpoint cap reached ({}/{}), skipping response breakpoint for correlation={}", held.size(), maxHeld, correlationId);
            return null;
        }

        // Use a response-phase correlation ID suffix so request + response breakpoints
        // on the same logical request don't collide in the registry
        String responseCorrelationId = correlationId + "-response";
        PausedExchange exchange = new PausedExchange(responseCorrelationId, request, response, expectationId);
        held.put(responseCorrelationId, exchange);

        long timeoutMillis = configuration.breakpointTimeoutMillis();
        ScheduledFuture<?> timeoutHandle = TIMEOUT_SCHEDULER.schedule(() -> {
            if (exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal())) {
                LOG.info("response breakpoint auto-continued (timeout {}ms) for correlation={}", timeoutMillis, responseCorrelationId);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        exchange.getDecisionFuture().whenComplete((decision, throwable) -> {
            timeoutHandle.cancel(false);
            held.remove(responseCorrelationId, exchange);
        });

        return exchange;
    }

    /**
     * Resolves a paused exchange as CONTINUE (forward original request or write original response).
     *
     * @return true if the exchange was found and resolved
     */
    public boolean resolveContinue(String correlationId) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal());
    }

    /**
     * Resolves a REQUEST-phase paused exchange as MODIFY (forward a replacement request).
     *
     * <p>If the exchange is in RESPONSE phase, this is a no-op (returns false) to
     * prevent type-confusion — a request-modify payload against a response-phase id
     * would complete the future with a null {@code modifiedRequest}, causing a
     * downstream NPE.
     *
     * @return true if the exchange was found, was in REQUEST phase, and was resolved
     */
    public boolean resolveModify(String correlationId, HttpRequest modifiedRequest) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        if (exchange.getPhase() != Phase.REQUEST) {
            LOG.info("rejecting resolveModify (request) against RESPONSE-phase exchange, correlation={}", correlationId);
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.modify(modifiedRequest));
    }

    /**
     * Resolves a RESPONSE-phase paused exchange as MODIFY (write a replacement response).
     *
     * <p>If the exchange is in REQUEST phase, this is a no-op (returns false) to
     * prevent type-confusion — a response-modify payload against a request-phase id
     * would complete the future with a null {@code modifiedResponse}, causing the
     * switch to fall through to the original response (masking the caller's intent).
     *
     * @return true if the exchange was found, was in RESPONSE phase, and was resolved
     */
    public boolean resolveModifyResponse(String correlationId, HttpResponse modifiedResponse) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        if (exchange.getPhase() != Phase.RESPONSE) {
            LOG.info("rejecting resolveModifyResponse against REQUEST-phase exchange, correlation={}", correlationId);
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.modifyResponse(modifiedResponse));
    }

    /**
     * Resolves a paused exchange as ABORT (do not forward; return the given response).
     *
     * @return true if the exchange was found and resolved
     */
    public boolean resolveAbort(String correlationId, HttpResponse abortResponse) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.abort(abortResponse));
    }

    /**
     * Returns a snapshot of all currently held (paused) exchanges.
     */
    public Map<String, PausedExchange> entries() {
        return new LinkedHashMap<>(held);
    }

    /**
     * Number of currently held exchanges.
     */
    public int size() {
        return held.size();
    }

    /**
     * Auto-continues all held exchanges so their async continuations fire.
     * Called on server reset.
     *
     * <p>Takes a snapshot and clears the map BEFORE completing futures, so that
     * asynchronous {@code whenComplete} callbacks (which call {@code held.remove()})
     * cannot race with a subsequent {@code pause()} call that re-populates the same
     * correlation id — the callbacks find the map already empty and skip harmlessly.
     */
    public void reset() {
        java.util.List<PausedExchange> snapshot = new java.util.ArrayList<>(held.values());
        held.clear();
        for (PausedExchange exchange : snapshot) {
            exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal());
        }
    }
}
