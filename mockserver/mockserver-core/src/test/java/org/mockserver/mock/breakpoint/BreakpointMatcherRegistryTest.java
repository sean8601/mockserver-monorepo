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

    // --- conditional (Nth-hit / skip-count) breakpoints ---

    @Test
    public void shouldNotPauseWithinSkipWindowAndPauseAfter() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // skipCount = 2 => do NOT pause on hits 1 and 2, DO pause from hit 3
        registry.register(
            request().withPath("/api/skip"),
            EnumSet.of(BreakpointPhase.REQUEST),
            "client-1", 2,
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/skip");

        // hit 1 — matches but skipped
        assertThat("hit 1 should not pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        // hit 2 — matches but skipped
        assertThat("hit 2 should not pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(nullValue()));
        // hit 3 — pauses
        assertThat("hit 3 should pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        // hit 4 — still pauses
        assertThat("hit 4 should pause",
            registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
    }

    @Test
    public void shouldPauseEveryTimeWhenSkipCountAbsent() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // no skipCount => legacy behaviour: pause on every hit
        registry.register(
            request().withPath("/api/always"),
            EnumSet.of(BreakpointPhase.REQUEST),
            configuration, logger
        );

        HttpRequest matching = request().withPath("/api/always");
        for (int i = 1; i <= 5; i++) {
            assertThat("hit " + i + " should pause",
                registry.findMatch(matching, BreakpointPhase.REQUEST), is(notNullValue()));
        }
    }

    @Test
    public void shouldTreatNullAndZeroAndNegativeSkipCountAsPauseEveryTime() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // skipCount = 0 and a negative value both mean "pause every time"
        registry.register(request().withPath("/zero"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 0, configuration, logger);
        registry.register(request().withPath("/neg"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", -3, configuration, logger);

        assertThat(registry.findMatch(request().withPath("/zero"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/zero"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/neg"), BreakpointPhase.REQUEST), is(notNullValue()));
        assertThat(registry.findMatch(request().withPath("/neg"), BreakpointPhase.REQUEST), is(notNullValue()));

        // skipCount normalised to null on the entry
        for (BreakpointMatcher entry : registry.entries()) {
            assertThat(entry.getSkipCount(), is(nullValue()));
        }
    }

    @Test
    public void shouldKeepSkipCounterPerBreakpoint() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        // two independent breakpoints with their own counters
        registry.register(request().withPath("/a"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 1, configuration, logger);
        registry.register(request().withPath("/b"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 1, configuration, logger);

        HttpRequest a = request().withPath("/a");
        HttpRequest b = request().withPath("/b");

        // hit /a once (skipped) — should not advance /b's counter
        assertThat(registry.findMatch(a, BreakpointPhase.REQUEST), is(nullValue()));
        // /b's first hit is still skipped (its own counter is independent)
        assertThat(registry.findMatch(b, BreakpointPhase.REQUEST), is(nullValue()));
        // /a second hit pauses
        assertThat(registry.findMatch(a, BreakpointPhase.REQUEST), is(notNullValue()));
        // /b second hit pauses
        assertThat(registry.findMatch(b, BreakpointPhase.REQUEST), is(notNullValue()));
    }

    @Test
    public void shouldExposeSkipCountAndHitCountOnEntry() {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        String id = registry.register(request().withPath("/hits"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", 2, configuration, logger);

        BreakpointMatcher entry = registry.entries().get(0);
        assertThat(entry.getId(), is(id));
        assertThat(entry.getSkipCount(), is(2));
        assertThat(entry.getHitCount(), is(0L));

        registry.findMatch(request().withPath("/hits"), BreakpointPhase.REQUEST);
        registry.findMatch(request().withPath("/hits"), BreakpointPhase.REQUEST);
        assertThat(entry.getHitCount(), is(2L));
    }

    @Test
    public void shouldIncrementSkipCounterAtomicallyUnderConcurrency() throws Exception {
        BreakpointMatcherRegistry registry = BreakpointMatcherRegistry.getInstance();

        final int skip = 100;
        final int threads = 8;
        final int hitsPerThread = 50; // 400 total hits, skip 100 => 300 pauses
        registry.register(request().withPath("/conc"),
            EnumSet.of(BreakpointPhase.REQUEST), "c", skip, configuration, logger);

        final java.util.concurrent.atomic.AtomicInteger pauses = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < hitsPerThread; i++) {
                    if (registry.findMatch(request().withPath("/conc"), BreakpointPhase.REQUEST) != null) {
                        pauses.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        int totalHits = threads * hitsPerThread;
        // exactly `skip` hits are skipped, the rest pause — no lost/double counts
        assertThat(pauses.get(), is(totalHits - skip));
        assertThat(registry.entries().get(0).getHitCount(), is((long) totalHits));
    }
}
