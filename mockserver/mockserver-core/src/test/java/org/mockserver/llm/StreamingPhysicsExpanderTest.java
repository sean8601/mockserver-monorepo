package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.Delay;
import org.mockserver.model.SseEvent;
import org.mockserver.model.StreamingPhysics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.SseEvent.sseEvent;

public class StreamingPhysicsExpanderTest {

    @Test
    public void shouldReturnUnchangedEventsWhenPhysicsIsNull() {
        // given
        List<SseEvent> events = createTestEvents(5);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, null);

        // then
        assertThat(result, is(sameInstance(events)));
    }

    @Test
    public void shouldReturnUnchangedEventsWhenAllFieldsNull() {
        // given
        List<SseEvent> events = createTestEvents(5);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics();

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result, is(sameInstance(events)));
    }

    @Test
    public void shouldUseTimeToFirstTokenForFirstEvent() {
        // given
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTimeToFirstToken(new Delay(TimeUnit.MILLISECONDS, 300))
            .withTokensPerSecond(50);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result.size(), is(3));
        assertThat(result.get(0).getDelay().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(result.get(0).getDelay().getValue(), is(300L));
    }

    @Test
    public void shouldUseTokensPerSecondForSubsequentEvents() {
        // given
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(100) // base delay = 10ms
            .withJitter(0.0);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        // First event delay = 0 (default timeToFirstToken)
        assertThat(result.get(0).getDelay().getValue(), is(0L));
        // Subsequent events: 1000/100 = 10ms with 0 jitter
        assertThat(result.get(1).getDelay().getValue(), is(10L));
        assertThat(result.get(2).getDelay().getValue(), is(10L));
    }

    @Test
    public void shouldProduceDeterministicDelaysWithZeroJitter() {
        // given
        List<SseEvent> events = createTestEvents(5);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50) // base delay = 20ms
            .withJitter(0.0)
            .withSeed(42L);

        // when
        List<SseEvent> result1 = StreamingPhysicsExpander.applyPhysics(events, physics);
        List<SseEvent> result2 = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — all subsequent events should have exactly 20ms delay
        for (int i = 1; i < result1.size(); i++) {
            assertThat(result1.get(i).getDelay().getValue(), is(20L));
            assertThat(result2.get(i).getDelay().getValue(), is(20L));
        }
    }

    @Test
    public void shouldProduceSameDelaysWithFixedSeedAndJitter() {
        // given
        List<SseEvent> events = createTestEvents(10);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50)
            .withJitter(0.2)
            .withSeed(12345L);

        // when
        List<SseEvent> result1 = StreamingPhysicsExpander.applyPhysics(events, physics);
        List<SseEvent> result2 = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — same seed → same delays
        for (int i = 0; i < result1.size(); i++) {
            assertThat(result1.get(i).getDelay().getValue(), is(result2.get(i).getDelay().getValue()));
        }
    }

    @Test
    public void shouldProduceDelaysWithinJitterBounds() {
        // given
        List<SseEvent> events = createTestEvents(100);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50) // base delay = 20ms
            .withJitter(0.2) // +-20% → [16, 24]
            .withSeed(42L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — all subsequent events should have delays within +-20% of 20ms
        for (int i = 1; i < result.size(); i++) {
            long delay = result.get(i).getDelay().getValue();
            assertThat("delay " + delay + " at index " + i + " should be >= 16",
                delay, is(greaterThanOrEqualTo(16L)));
            assertThat("delay " + delay + " at index " + i + " should be <= 24",
                delay, is(lessThanOrEqualTo(24L)));
        }
    }

    @Test
    public void shouldPreserveEventDataWhenApplyingPhysics() {
        // given
        List<SseEvent> events = new ArrayList<>();
        events.add(sseEvent().withEvent("test_event").withData("test_data").withId("id1").withRetry(5000));
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result.get(0).getEvent(), is("test_event"));
        assertThat(result.get(0).getData(), is("test_data"));
        assertThat(result.get(0).getId(), is("id1"));
        assertThat(result.get(0).getRetry(), is(5000));
    }

    @Test
    public void shouldNotMutateInputList() {
        // given
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result, is(not(sameInstance(events))));
        // original events should still have no delay
        for (SseEvent event : events) {
            assertThat(event.getDelay(), is(nullValue()));
        }
    }

    @Test
    public void shouldDefaultTokensPerSecondTo50() {
        // given — only seed is set, so physics is non-null but tokensPerSecond is null
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withSeed(42L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — base delay should be 1000/50 = 20ms
        assertThat(result.get(1).getDelay().getValue(), is(20L));
    }

    private List<SseEvent> createTestEvents(int count) {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(sseEvent().withData("event-" + i));
        }
        return events;
    }
}
