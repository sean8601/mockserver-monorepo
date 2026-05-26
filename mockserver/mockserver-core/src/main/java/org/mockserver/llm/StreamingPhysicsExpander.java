package org.mockserver.llm;

import org.mockserver.model.Delay;
import org.mockserver.model.SseEvent;
import org.mockserver.model.StreamingPhysics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Applies streaming physics (timing delays) to a list of SSE events.
 * <p>
 * Algorithm (per spec section 2.3.2):
 * <ul>
 *   <li>If physics is null or all fields are null, return the events unchanged.</li>
 *   <li>Event 0 delay = timeToFirstToken (default 0 ms).</li>
 *   <li>Events 1..n delay = baseDelay * (1 + uniform(-jitter, +jitter)) where baseDelay = 1000 / tokensPerSecond ms.</li>
 *   <li>Uses java.util.Random seeded with physics.seed (default System.nanoTime()) for reproducible jitter.</li>
 * </ul>
 */
public class StreamingPhysicsExpander {

    private StreamingPhysicsExpander() {
        // utility class
    }

    /**
     * Apply streaming physics delays to SSE events.
     *
     * @param rawEvents the raw events from the codec (not mutated)
     * @param physics   streaming physics parameters (may be null)
     * @return a new list of events with per-event delays set
     */
    public static List<SseEvent> applyPhysics(List<SseEvent> rawEvents, StreamingPhysics physics) {
        if (physics == null || allFieldsNull(physics)) {
            return rawEvents;
        }

        int tokensPerSecond = physics.getTokensPerSecond() != null ? physics.getTokensPerSecond() : 50;
        double jitter = physics.getJitter() != null ? physics.getJitter() : 0.0;
        long seed = physics.getSeed() != null ? physics.getSeed() : System.nanoTime();
        long timeToFirstTokenMs = 0;
        if (physics.getTimeToFirstToken() != null) {
            Delay ttft = physics.getTimeToFirstToken();
            if (ttft.getTimeUnit() != null) {
                timeToFirstTokenMs = ttft.getTimeUnit().toMillis(ttft.getValue());
            }
        }

        long baseDelayMs = 1000L / tokensPerSecond;
        Random random = new Random(seed);

        List<SseEvent> result = new ArrayList<>(rawEvents.size());
        for (int i = 0; i < rawEvents.size(); i++) {
            SseEvent original = rawEvents.get(i);
            long delayMs;

            if (i == 0) {
                delayMs = timeToFirstTokenMs;
            } else {
                double jitterFactor = 1.0 + (random.nextDouble() * 2 * jitter - jitter);
                delayMs = Math.round(baseDelayMs * jitterFactor);
                if (delayMs < 0) {
                    delayMs = 0;
                }
            }

            // Copy event with delay
            SseEvent copy = sseEvent();
            if (original.getEvent() != null) {
                copy = copy.withEvent(original.getEvent());
            }
            if (original.getData() != null) {
                copy = copy.withData(original.getData());
            }
            if (original.getId() != null) {
                copy = copy.withId(original.getId());
            }
            if (original.getRetry() != null) {
                copy = copy.withRetry(original.getRetry());
            }
            copy = copy.withDelay(TimeUnit.MILLISECONDS, delayMs);
            result.add(copy);
        }

        return result;
    }

    private static boolean allFieldsNull(StreamingPhysics physics) {
        return physics.getTimeToFirstToken() == null &&
            physics.getTokensPerSecond() == null &&
            physics.getJitter() == null &&
            physics.getSeed() == null;
    }
}
