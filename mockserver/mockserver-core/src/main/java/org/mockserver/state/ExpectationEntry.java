package org.mockserver.state;

import org.mockserver.mock.Expectation;

/**
 * Serializable entry stored in the expectation {@link KeyValueStore}.
 * Contains the {@link Expectation} definition plus the three sort fields
 * (priority, created, id) that determine matching order. The live
 * {@code HttpRequestMatcher} is NOT part of this entry — each node builds
 * it lazily and caches it node-locally.
 */
public final class ExpectationEntry {

    private final Expectation expectation;
    private final int priority;
    private final long created;
    private final String id;

    public ExpectationEntry(Expectation expectation) {
        this.expectation = expectation;
        this.id = expectation.getId();
        this.priority = expectation.getPriority();
        this.created = expectation.getCreated();
    }

    public Expectation getExpectation() {
        return expectation;
    }

    public int getPriority() {
        return priority;
    }

    public long getCreated() {
        return created;
    }

    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return "ExpectationEntry{id='" + id + "', priority=" + priority + ", created=" + created + '}';
    }
}
