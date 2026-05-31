package org.mockserver.mock.replay;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class ReplayOrchestratorTest {

    @Test
    public void shouldReturnReplayIdOnStart() {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        List<RecordedRequest> requests = List.of(
            new RecordedRequest(request().withPath("/api/test"), 200, 50L)
        );

        // when
        String replayId = orchestrator.startReplay(requests, 0, (req, callback) ->
            callback.onResult(response().withStatusCode(200), null)
        );

        // then
        assertThat(replayId, is(notNullValue()));
        assertThat(replayId.length(), is(greaterThan(0)));
    }

    @Test
    public void shouldTrackRunningStatus() {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        CountDownLatch senderBlock = new CountDownLatch(1);
        List<RecordedRequest> requests = List.of(
            new RecordedRequest(request().withPath("/api/test"), 200, 50L)
        );

        // when
        String replayId = orchestrator.startReplay(requests, 0, (req, callback) -> {
            try {
                senderBlock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            callback.onResult(response().withStatusCode(200), null);
        });

        // then -- report should exist and show RUNNING
        ReplayReport report = orchestrator.getReport(replayId);
        assertThat(report, is(notNullValue()));
        assertThat(report.getStatus(), is("RUNNING"));
        assertThat(report.getTotalRequests(), is(1));

        // unblock the sender
        senderBlock.countDown();
    }

    @Test
    public void shouldCompleteAfterAllRequestsFinish() throws Exception {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        List<RecordedRequest> requests = List.of(
            new RecordedRequest(request().withPath("/api/one").withMethod("GET"), 200, 10L),
            new RecordedRequest(request().withPath("/api/two").withMethod("POST"), 201, 20L)
        );

        CountDownLatch completionLatch = new CountDownLatch(1);

        // when
        String replayId = orchestrator.startReplay(requests, 0, (req, callback) -> {
            // simulate matching status for /one, mismatching for /two
            String path = req.getPath() != null ? req.getPath().getValue() : "";
            if ("/api/one".equals(path)) {
                callback.onResult(response().withStatusCode(200), null);
            } else {
                callback.onResult(response().withStatusCode(500), null);
            }
        });

        // wait for completion
        for (int i = 0; i < 50; i++) {
            ReplayReport report = orchestrator.getReport(replayId);
            if (report != null && "COMPLETED".equals(report.getStatus())) {
                completionLatch.countDown();
                break;
            }
            Thread.sleep(50);
        }
        assertThat("replay should complete", completionLatch.await(1, TimeUnit.SECONDS), is(true));

        // then
        ReplayReport report = orchestrator.getReport(replayId);
        assertThat(report.getStatus(), is("COMPLETED"));
        assertThat(report.getTotalRequests(), is(2));
        assertThat(report.getCompletedRequests(), is(2));
        assertThat(report.getSuccessCount(), is(1));
        assertThat(report.getFailureCount(), is(1));
        assertThat(report.getResults(), hasSize(2));

        // verify result details for the matching request
        ReplayResult matchResult = report.getResults().stream()
            .filter(r -> "/api/one".equals(r.getPath()))
            .findFirst()
            .orElse(null);
        assertThat(matchResult, is(notNullValue()));
        assertThat(matchResult.getMethod(), is("GET"));
        assertThat(matchResult.getBaselineStatusCode(), is(200));
        assertThat(matchResult.getReplayStatusCode(), is(200));
        assertThat(matchResult.isStatusMatch(), is(true));

        // verify result details for the mismatching request
        ReplayResult mismatchResult = report.getResults().stream()
            .filter(r -> "/api/two".equals(r.getPath()))
            .findFirst()
            .orElse(null);
        assertThat(mismatchResult, is(notNullValue()));
        assertThat(mismatchResult.getMethod(), is("POST"));
        assertThat(mismatchResult.getBaselineStatusCode(), is(201));
        assertThat(mismatchResult.getReplayStatusCode(), is(500));
        assertThat(mismatchResult.isStatusMatch(), is(false));
    }

    @Test
    public void shouldHandleEmptyRequestList() throws Exception {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        List<RecordedRequest> requests = Collections.emptyList();

        // when
        String replayId = orchestrator.startReplay(requests, 0, (req, callback) ->
            callback.onResult(response().withStatusCode(200), null)
        );

        // wait for completion
        for (int i = 0; i < 50; i++) {
            ReplayReport report = orchestrator.getReport(replayId);
            if (report != null && "COMPLETED".equals(report.getStatus())) {
                break;
            }
            Thread.sleep(50);
        }

        // then
        ReplayReport report = orchestrator.getReport(replayId);
        assertThat(report, is(notNullValue()));
        assertThat(report.getStatus(), is("COMPLETED"));
        assertThat(report.getTotalRequests(), is(0));
        assertThat(report.getCompletedRequests(), is(0));
    }

    @Test
    public void shouldHandleSenderError() throws Exception {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        List<RecordedRequest> requests = List.of(
            new RecordedRequest(request().withPath("/api/error"), 200, 10L)
        );

        // when -- sender reports an error
        String replayId = orchestrator.startReplay(requests, 0, (req, callback) ->
            callback.onResult(null, new RuntimeException("connection refused"))
        );

        // wait for completion
        for (int i = 0; i < 50; i++) {
            ReplayReport report = orchestrator.getReport(replayId);
            if (report != null && "COMPLETED".equals(report.getStatus())) {
                break;
            }
            Thread.sleep(50);
        }

        // then
        ReplayReport report = orchestrator.getReport(replayId);
        assertThat(report.getStatus(), is("COMPLETED"));
        assertThat(report.getFailureCount(), is(1));
        assertThat(report.getSuccessCount(), is(0));

        ReplayResult result = report.getResults().get(0);
        assertThat(result.getReplayStatusCode(), is(0)); // error case
        assertThat(result.isStatusMatch(), is(false));
    }

    @Test
    public void shouldResetClearAllReplays() throws Exception {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        String replayId = orchestrator.startReplay(
            List.of(new RecordedRequest(request().withPath("/api/test"), 200, 10L)),
            0,
            (req, callback) -> callback.onResult(response().withStatusCode(200), null)
        );

        // wait for completion
        for (int i = 0; i < 50; i++) {
            ReplayReport report = orchestrator.getReport(replayId);
            if (report != null && "COMPLETED".equals(report.getStatus())) {
                break;
            }
            Thread.sleep(50);
        }

        // when
        orchestrator.reset();

        // then
        assertThat(orchestrator.getReport(replayId), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForUnknownReplayId() {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();

        // then
        assertThat(orchestrator.getReport("nonexistent-id"), is(nullValue()));
    }

    @Test
    public void shouldRecordBaselineLatencyInResults() throws Exception {
        // given
        ReplayOrchestrator orchestrator = new ReplayOrchestrator();
        long baselineLatency = 150L;
        List<RecordedRequest> requests = List.of(
            new RecordedRequest(request().withPath("/api/latency"), 200, baselineLatency)
        );

        // when
        String replayId = orchestrator.startReplay(requests, 0, (req, callback) ->
            callback.onResult(response().withStatusCode(200), null)
        );

        // wait for completion
        for (int i = 0; i < 50; i++) {
            ReplayReport report = orchestrator.getReport(replayId);
            if (report != null && "COMPLETED".equals(report.getStatus())) {
                break;
            }
            Thread.sleep(50);
        }

        // then
        ReplayReport report = orchestrator.getReport(replayId);
        assertThat(report.getResults(), hasSize(1));
        ReplayResult result = report.getResults().get(0);
        assertThat(result.getBaselineLatencyMs(), is(baselineLatency));
        assertThat(result.getReplayLatencyMs(), is(greaterThanOrEqualTo(0L)));
        // latencyDeltaMs = replayLatencyMs - baselineLatencyMs
        assertThat(result.getLatencyDeltaMs(), is(result.getReplayLatencyMs() - baselineLatency));
    }
}
