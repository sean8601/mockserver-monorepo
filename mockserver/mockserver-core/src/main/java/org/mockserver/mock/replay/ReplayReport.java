package org.mockserver.mock.replay;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.List;

/**
 * Tracks the progress and outcome of a single replay session. Updated
 * incrementally as each request completes and queryable via the
 * {@code GET /mockserver/replay/{replayId}} endpoint.
 */
public class ReplayReport extends ObjectWithReflectiveEqualsHashCodeToString {

    private String replayId;
    private String status; // RUNNING, COMPLETED, FAILED
    private int totalRequests;
    private int completedRequests;
    private int successCount;
    private int failureCount;
    private List<ReplayResult> results;

    public static ReplayReport create(String replayId, int total) {
        return new ReplayReport()
            .setReplayId(replayId)
            .setStatus("RUNNING")
            .setTotalRequests(total);
    }

    // --- fluent setters + getters ---

    public String getReplayId() {
        return replayId;
    }

    public ReplayReport setReplayId(String replayId) {
        this.replayId = replayId;
        return this;
    }

    public String getStatus() {
        return status;
    }

    public ReplayReport setStatus(String status) {
        this.status = status;
        return this;
    }

    public int getTotalRequests() {
        return totalRequests;
    }

    public ReplayReport setTotalRequests(int totalRequests) {
        this.totalRequests = totalRequests;
        return this;
    }

    public int getCompletedRequests() {
        return completedRequests;
    }

    public ReplayReport setCompletedRequests(int completedRequests) {
        this.completedRequests = completedRequests;
        return this;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public ReplayReport setSuccessCount(int successCount) {
        this.successCount = successCount;
        return this;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public ReplayReport setFailureCount(int failureCount) {
        this.failureCount = failureCount;
        return this;
    }

    public List<ReplayResult> getResults() {
        return results;
    }

    public ReplayReport setResults(List<ReplayResult> results) {
        this.results = results;
        return this;
    }
}
