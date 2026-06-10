package org.mockserver.mock.breakpoint;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.model.RequestDefinition;
import org.mockserver.uuid.UUIDService;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide registry of breakpoint matchers. Each entry describes a request
 * matcher and a set of phases; when a forwarded exchange matches, it is paused
 * at the corresponding phase for interactive inspection/modification.
 *
 * <p>Thread-safe via a single {@link CopyOnWriteArrayList} as the source of truth.
 * This is ideal for the access pattern here: reads ({@link #findMatch}) are frequent
 * and on the data plane (including the Netty event loop), while writes
 * ({@link #register}, {@link #remove}, {@link #clear}) are rare control-plane
 * operations. A single structure means register/remove/clear are atomic with respect
 * to a concurrent {@link #findMatch} — there is no window in which two backing
 * collections can disagree.
 *
 * <p><b>Event-loop safety:</b> {@link #findMatch} is designed to be called on the
 * Netty event loop for stream-frame phases. When the registry is empty, it returns
 * {@code null} immediately via a single {@link CopyOnWriteArrayList#isEmpty()} check
 * (zero allocation). When non-empty, it iterates a stable snapshot of the prebuilt
 * matchers without any blocking or allocation beyond the matcher's own match logic.
 */
public class BreakpointMatcherRegistry {

    private static final BreakpointMatcherRegistry INSTANCE = new BreakpointMatcherRegistry();

    private final CopyOnWriteArrayList<BreakpointMatcher> entries = new CopyOnWriteArrayList<>();

    public static BreakpointMatcherRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a new breakpoint matcher (REST-park resolution, no owner client).
     *
     * @param matcher       the request definition to match against
     * @param phases        the set of phases at which matching exchanges should break
     * @param configuration the active server configuration (passed to MatcherBuilder)
     * @param logger        the server logger (passed to MatcherBuilder)
     * @return the assigned UUID id for the registered breakpoint
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           Configuration configuration, MockServerLogger logger) {
        return register(matcher, phases, null, configuration, logger);
    }

    /**
     * Registers a new breakpoint matcher with an optional owner clientId.
     *
     * <p>When {@code clientId} is non-null, matched exchanges are dispatched over
     * the callback WebSocket to that client for interactive resolution. When null,
     * the existing REST-park behaviour is used.
     *
     * @param matcher       the request definition to match against
     * @param phases        the set of phases at which matching exchanges should break
     * @param clientId      the callback WS client that owns this breakpoint, or null
     * @param configuration the active server configuration (passed to MatcherBuilder)
     * @param logger        the server logger (passed to MatcherBuilder)
     * @return the assigned UUID id for the registered breakpoint
     */
    public String register(RequestDefinition matcher, Set<BreakpointPhase> phases,
                           String clientId,
                           Configuration configuration, MockServerLogger logger) {
        String id = UUIDService.getUUID();
        HttpRequestMatcher prebuilt = new MatcherBuilder(configuration, logger).transformsToMatcher(matcher);
        BreakpointMatcher entry = new BreakpointMatcher(id, matcher, phases, prebuilt, clientId);
        entries.add(entry);
        return id;
    }

    /**
     * Finds the first registered breakpoint whose phases contain the given phase
     * AND whose prebuilt matcher matches the given request.
     *
     * <p>Returns {@code null} if the registry is empty or no matcher matches.
     * This method is allocation-light and safe to call on the Netty event loop.
     *
     * @param request the inbound request to match against
     * @param phase   the phase to check
     * @return the first matching {@link BreakpointMatcher}, or {@code null}
     */
    public BreakpointMatcher findMatch(RequestDefinition request, BreakpointPhase phase) {
        if (entries.isEmpty()) {
            return null;
        }
        for (BreakpointMatcher entry : entries) {
            if (entry.getPhases().contains(phase) && entry.getPrebuiltMatcher().matches(request)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Removes a breakpoint by id.
     *
     * @return true if the breakpoint was found and removed
     */
    public boolean remove(String id) {
        return entries.removeIf(entry -> entry.getId().equals(id));
    }

    /**
     * Removes all breakpoints owned by the given callback client.
     * Called when a WebSocket client disconnects so its breakpoints are cleaned up.
     *
     * @param clientId the client id whose breakpoints should be removed
     * @return the number of breakpoints removed
     */
    public int removeByClientId(String clientId) {
        if (clientId == null) {
            return 0;
        }
        int removed = 0;
        // CopyOnWriteArrayList.removeIf atomically removes all matching entries
        // but does not return a count — iterate explicitly for the count.
        java.util.Iterator<BreakpointMatcher> it = entries.iterator();
        while (it.hasNext()) {
            BreakpointMatcher entry = it.next();
            if (clientId.equals(entry.getClientId())) {
                entries.remove(entry);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Clears all registered breakpoints.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Returns a snapshot of all registered breakpoints in registration order.
     */
    public List<BreakpointMatcher> entries() {
        return new ArrayList<>(entries);
    }

    /**
     * Number of currently registered breakpoints.
     */
    public int size() {
        return entries.size();
    }
}
