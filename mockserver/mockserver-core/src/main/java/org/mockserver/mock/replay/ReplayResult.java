package org.mockserver.mock.replay;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * The comparison result for a single replayed request — captures the baseline
 * response metadata alongside the actual replay outcome.
 */
public class ReplayResult extends ObjectWithReflectiveEqualsHashCodeToString {

    private String path;
    private String method;
    private int baselineStatusCode;
    private int replayStatusCode;
    private long baselineLatencyMs;
    private long replayLatencyMs;
    private long latencyDeltaMs;
    private boolean statusMatch;

    // --- fluent setters + getters ---

    public String getPath() {
        return path;
    }

    public ReplayResult setPath(String path) {
        this.path = path;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public ReplayResult setMethod(String method) {
        this.method = method;
        return this;
    }

    public int getBaselineStatusCode() {
        return baselineStatusCode;
    }

    public ReplayResult setBaselineStatusCode(int baselineStatusCode) {
        this.baselineStatusCode = baselineStatusCode;
        return this;
    }

    public int getReplayStatusCode() {
        return replayStatusCode;
    }

    public ReplayResult setReplayStatusCode(int replayStatusCode) {
        this.replayStatusCode = replayStatusCode;
        return this;
    }

    public long getBaselineLatencyMs() {
        return baselineLatencyMs;
    }

    public ReplayResult setBaselineLatencyMs(long baselineLatencyMs) {
        this.baselineLatencyMs = baselineLatencyMs;
        return this;
    }

    public long getReplayLatencyMs() {
        return replayLatencyMs;
    }

    public ReplayResult setReplayLatencyMs(long replayLatencyMs) {
        this.replayLatencyMs = replayLatencyMs;
        return this;
    }

    public long getLatencyDeltaMs() {
        return latencyDeltaMs;
    }

    public ReplayResult setLatencyDeltaMs(long latencyDeltaMs) {
        this.latencyDeltaMs = latencyDeltaMs;
        return this;
    }

    public boolean isStatusMatch() {
        return statusMatch;
    }

    public ReplayResult setStatusMatch(boolean statusMatch) {
        this.statusMatch = statusMatch;
        return this;
    }
}
