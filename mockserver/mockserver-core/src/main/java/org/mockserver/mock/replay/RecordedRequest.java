package org.mockserver.mock.replay;

import org.mockserver.model.HttpRequest;

/**
 * A recorded proxy request together with the baseline response metadata
 * (status code and latency) captured during the original session.
 */
public class RecordedRequest {

    private final HttpRequest request;
    private final int baselineStatusCode;
    private final long baselineLatencyMs;

    public RecordedRequest(HttpRequest request, int baselineStatusCode, long baselineLatencyMs) {
        this.request = request;
        this.baselineStatusCode = baselineStatusCode;
        this.baselineLatencyMs = baselineLatencyMs;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public int getBaselineStatusCode() {
        return baselineStatusCode;
    }

    public long getBaselineLatencyMs() {
        return baselineLatencyMs;
    }
}
