package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Tests the matcher-based breakpoint registry: registration, per-phase matching,
 * empty-registry fast path, remove, clear, entries, and size.
 */
public class BreakpointMatcherRegistryTest {

    private final Configuration configuration = Configuration.configuration();
    private final MockServerLogger logger = new MockServerLogger();

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
    }

    @After
    public void cleanup() {
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    // --- registration and findMatch ---

    @Test
    public void shouldRegisterAndFindMatchForRequestPhase() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(
            request().withPath("/api/test"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );
        assertThat(id, is(notNullValue()));
        assertThat(registry.size(), is(1));

        // matching request and phase
        BreakpointMatcher match = registry.findMatch(
            request().withMethod("GET").withPath("/api/test"),
            BreakpointPhase.REQUEST
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getId(), is(id));

        // correct phase, non-matching request
        BreakpointMatcher noMatch = registry.findMatch(
            request().withPath("/other"),
            BreakpointPhase.REQUEST
        );
        assertThat(noMatch, is(nullValue()));
    }

    @Test
    public void shouldNotMatchWrongPhase() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(
            request().withPath("/api/test"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        // matching request but wrong phase
        BreakpointMatcher match = registry.findMatch(
            request().withPath("/api/test"),
            BreakpointPhase.RESPONSE
        );
        assertThat("should not match wrong phase", match, is(nullValue()));
    }

    @Test
    public void shouldMatchMultiplePhases() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(
            request().withPath("/api/multi"),
            EnumSet.of(BreakpointPhase.REQUEST, BreakpointPhase.RESPONSE),
            configuration, logger
        );

        // should match both phases
        BreakpointMatcher reqMatch = registry.findMatch(
            request().withPath("/api/multi"),
            BreakpointPhase.REQUEST
        );
        assertThat(reqMatch, is(notNullValue()));
        assertThat(reqMatch.getId(), is(id));

        BreakpointMatcher respMatch = registry.findMatch(
            request().withPath("/api/multi"),
            BreakpointPhase.RESPONSE
        );
        assertThat(respMatch, is(notNullValue()));
        assertThat(respMatch.getId(), is(id));

        // should NOT match phases not in the set
        assertThat(registry.findMatch(request().withPath("/api/multi"),
            BreakpointPhase.RESPONSE_STREAM), is(nullValue()));
        assertThat(registry.findMatch(request().withPath("/api/multi"),
            BreakpointPhase.INBOUND_STREAM), is(nullValue()));
    }

    @Test
    public void shouldMatchStreamPhases() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(
            request().withPath("/api/stream"),
            EnumSet.of(BreakpointPhase.RESPONSE_STREAM, BreakpointPhase.INBOUND_STREAM),
            configuration, logger
        );

        assertThat(registry.findMatch(request().withPath("/api/stream"),
            BreakpointPhase.RESPONSE_STREAM), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/api/stream"),
            BreakpointPhase.INBOUND_STREAM), is(notNullValue()));

        // should NOT match non-stream phases
        assertThat(registry.findMatch(request().withPath("/api/stream"),
            BreakpointPhase.REQUEST), is(nullValue()));
    }

    // --- empty-registry fast path ---

    @Test
    public void shouldReturnNullImmediatelyWhenRegistryIsEmpty() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        assertThat(registry.size(), is(0));

        BreakpointMatcher match = registry.findMatch(
            request().withPath("/anything"),
            BreakpointPhase.REQUEST
        );
        assertThat("empty registry should return null immediately", match, is(nullValue()));
    }

    // --- first-match semantics ---

    @Test
    public void shouldReturnFirstMatchInRegistrationOrder() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id1 = registry.register(
            request().withPath("/api/.*"),  // catch-all path pattern
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        String id2 = registry.register(
            request().withPath("/api/specific"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        // both match /api/specific, but the first registered should win
        BreakpointMatcher match = registry.findMatch(
            request().withPath("/api/specific"),
            BreakpointPhase.REQUEST
        );
        assertThat(match, is(notNullValue()));
        assertThat(match.getId(), is(id1));
    }

    // --- remove ---

    @Test
    public void shouldRemoveById() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id1 = registry.register(request().withPath("/a"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        String id2 = registry.register(request().withPath("/b"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);

        assertThat(registry.size(), is(2));

        boolean removed = registry.remove(id1);
        assertThat(removed, is(true));
        assertThat(registry.size(), is(1));

        // should no longer match removed entry
        assertThat(registry.findMatch(request().withPath("/a"), BreakpointPhase.REQUEST), is(nullValue()));
        // remaining entry should still match
        assertThat(registry.findMatch(request().withPath("/b"), BreakpointPhase.REQUEST), is(notNullValue()));
    }

    @Test
    public void shouldReturnFalseWhenRemovingNonExistentId() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        boolean removed = registry.remove("non-existent-id");
        assertThat(removed, is(false));
    }

    // --- clear ---

    @Test
    public void shouldClearAllEntries() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(request().withPath("/a"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        registry.register(request().withPath("/b"),
            EnumSet.of(BreakpointPhase.RESPONSE), configuration, logger);
        registry.register(request().withPath("/c"),
            EnumSet.of(BreakpointPhase.RESPONSE_STREAM), configuration, logger);

        assertThat(registry.size(), is(3));

        registry.clear();
        assertThat(registry.size(), is(0));
        assertThat(registry.entries(), is(empty()));

        // nothing should match
        assertThat(registry.findMatch(request().withPath("/a"), BreakpointPhase.REQUEST), is(nullValue()));
    }

    // --- entries ---

    @Test
    public void shouldReturnSnapshotOfEntries() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id1 = registry.register(request().withPath("/x"),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        String id2 = registry.register(request().withPath("/y"),
            EnumSet.of(BreakpointPhase.RESPONSE, BreakpointPhase.INBOUND_STREAM), configuration, logger);

        List<BreakpointMatcher> entries = registry.entries();
        assertThat(entries, hasSize(2));
        assertThat(entries.get(0).getId(), is(id1));
        assertThat(entries.get(1).getId(), is(id2));

        // verify phases are preserved
        assertThat(entries.get(0).getPhases(), is(EnumSet.of(BreakpointPhase.REQUEST)));
        assertThat(entries.get(1).getPhases(),
            is(EnumSet.of(BreakpointPhase.RESPONSE, BreakpointPhase.INBOUND_STREAM)));
    }

    // --- size ---

    @Test
    public void shouldReportCorrectSize() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        assertThat(registry.size(), is(0));

        String id = registry.register(request(),
            EnumSet.of(BreakpointPhase.REQUEST), configuration, logger);
        assertThat(registry.size(), is(1));

        registry.remove(id);
        assertThat(registry.size(), is(0));
    }

    // --- catch-all matcher ---

    @Test
    public void shouldMatchCatchAllMatcher() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // register a matcher with no path/method constraints (matches everything)
        String id = registry.register(
            request(),  // matches all requests
            EnumSet.of(BreakpointPhase.REQUEST, BreakpointPhase.RESPONSE,
                BreakpointPhase.RESPONSE_STREAM, BreakpointPhase.INBOUND_STREAM),
            configuration, logger
        );

        for (BreakpointPhase phase : BreakpointPhase.values()) {
            BreakpointMatcher match = registry.findMatch(
                request().withMethod("POST").withPath("/any/path"),
                phase
            );
            assertThat("catch-all should match phase " + phase, match, is(notNullValue()));
            assertThat(match.getId(), is(id));
        }
    }

    // --- method matcher ---

    @Test
    public void shouldMatchByMethod() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        registry.register(
            request().withMethod("POST"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        assertThat(registry.findMatch(request().withMethod("POST").withPath("/test"),
            BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withMethod("GET").withPath("/test"),
            BreakpointPhase.REQUEST), is(nullValue()));
    }
}
