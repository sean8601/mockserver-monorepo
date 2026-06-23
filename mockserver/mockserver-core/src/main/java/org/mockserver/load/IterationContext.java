package org.mockserver.load;

import java.util.Collections;
import java.util.Map;

/**
 * Per-iteration template variable exposed to a {@link LoadScenario} step under the
 * key {@code "iteration"}, sibling of {@code request} / {@code response}. It lets a
 * step's templated request fields vary per iteration without any cross-step state
 * (e.g. {@code $iteration.index} for a unique path segment).
 *
 * <p>Plain JavaBean getters so all three engine syntaxes resolve identically:
 * {@code $iteration.index} (Velocity), {@code {{iteration.index}}} (Mustache) and
 * {@code iteration.getIndex()} (JavaScript).
 *
 * <ul>
 *   <li>{@link #getIndex()} — global iteration index across all virtual users (0-based)</li>
 *   <li>{@link #getVuId()} — the launching virtual user's id (0-based)</li>
 *   <li>{@link #getVuIteration()} — the iteration count within that virtual user (0-based)</li>
 *   <li>{@link #getElapsedMillis()} — millis since the scenario started</li>
 *   <li>{@link #getCount()} — total requests dispatched so far (the global request counter)</li>
 *   <li>{@link #getCaptured()} — cross-step captured variables for this iteration (see {@link LoadCapture})</li>
 * </ul>
 *
 * <p>{@link #getCaptured()} exposes the iteration's mutable captured-variable map so a step's
 * templated request fields can reference values extracted from an earlier step's response, e.g.
 * {@code $iteration.captured.token} (Velocity) / {@code {{iteration.captured.token}}} (Mustache).
 * Both engines resolve a member of a {@code Map<String,String>} getter by key. Scope is per-iteration
 * (one virtual user's single pass through the steps); the map is never shared across users/iterations.
 */
public class IterationContext {

    private final long index;
    private final int vuId;
    private final long vuIteration;
    private final long elapsedMillis;
    private final long count;
    private final Map<String, String> captured;

    public IterationContext(long index, int vuId, long vuIteration, long elapsedMillis, long count) {
        this(index, vuId, vuIteration, elapsedMillis, count, Collections.emptyMap());
    }

    public IterationContext(long index, int vuId, long vuIteration, long elapsedMillis, long count, Map<String, String> captured) {
        this.index = index;
        this.vuId = vuId;
        this.vuIteration = vuIteration;
        this.elapsedMillis = elapsedMillis;
        this.count = count;
        this.captured = captured != null ? captured : Collections.emptyMap();
    }

    public long getIndex() {
        return index;
    }

    public int getVuId() {
        return vuId;
    }

    public long getVuIteration() {
        return vuIteration;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public long getCount() {
        return count;
    }

    /**
     * The iteration's cross-step captured variables (name to extracted value), referenced from a
     * step's templated fields as {@code $iteration.captured.<name>} / {@code {{iteration.captured.<name>}}}.
     * Never null (empty when nothing has been captured yet).
     */
    public Map<String, String> getCaptured() {
        return captured;
    }
}
