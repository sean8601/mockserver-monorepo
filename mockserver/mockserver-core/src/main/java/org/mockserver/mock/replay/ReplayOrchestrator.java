package org.mockserver.mock.replay;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.uuid.UUIDService;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Manages replay sessions -- retrieves recorded traffic, sends it at a configured
 * rate, and collects comparison results. The singleton instance is used by the
 * control-plane endpoints; tests may create dedicated instances via the
 * package-private constructor.
 */
public class ReplayOrchestrator {

    private static final ReplayOrchestrator INSTANCE = new ReplayOrchestrator();

    private final ConcurrentHashMap<String, ReplayReport> activeReplays = new ConcurrentHashMap<>();

    /** Package-private so tests in the same package can create isolated instances. */
    ReplayOrchestrator() {
    }

    public static ReplayOrchestrator getInstance() {
        return INSTANCE;
    }

    /**
     * Start a replay. Returns the replayId immediately; replay runs asynchronously.
     *
     * @param requests   the recorded requests to replay (with baseline status/latency metadata)
     * @param ratePerSec max requests per second (0 = unlimited)
     * @param sender     callback to actually send each request (provided by the netty layer)
     * @return the replayId that can be used to poll for progress
     */
    public String startReplay(List<RecordedRequest> requests, int ratePerSec,
                              BiConsumer<HttpRequest, ReplayResultCallback> sender) {
        String replayId = UUIDService.getUUID();
        ReplayReport report = ReplayReport.create(replayId, requests.size());
        activeReplays.put(replayId, report);

        Thread replayThread = new Thread(() -> {
            List<ReplayResult> results = new CopyOnWriteArrayList<>();
            long intervalMs = ratePerSec > 0 ? 1000L / ratePerSec : 0;

            for (RecordedRequest recorded : requests) {
                if (!activeReplays.containsKey(replayId)) {
                    break; // cancelled
                }

                long start = System.currentTimeMillis();
                CountDownLatch latch = new CountDownLatch(1);
                ReplayResult[] resultHolder = new ReplayResult[1];

                sender.accept(recorded.getRequest(), (response, error) -> {
                    long elapsed = System.currentTimeMillis() - start;
                    ReplayResult r = new ReplayResult()
                        .setPath(recorded.getRequest().getPath() != null ? recorded.getRequest().getPath().getValue() : "/")
                        .setMethod(recorded.getRequest().getMethod() != null ? recorded.getRequest().getMethod().getValue() : "GET")
                        .setBaselineStatusCode(recorded.getBaselineStatusCode())
                        .setBaselineLatencyMs(recorded.getBaselineLatencyMs())
                        .setReplayLatencyMs(elapsed)
                        .setLatencyDeltaMs(elapsed - recorded.getBaselineLatencyMs());
                    if (response != null && response.getStatusCode() != null) {
                        r.setReplayStatusCode(response.getStatusCode())
                            .setStatusMatch(response.getStatusCode() == recorded.getBaselineStatusCode());
                    } else {
                        r.setReplayStatusCode(error != null ? 0 : 200)
                            .setStatusMatch(false);
                    }
                    resultHolder[0] = r;
                    latch.countDown();
                });

                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (resultHolder[0] != null) {
                    results.add(resultHolder[0]);
                    boolean success = resultHolder[0].isStatusMatch();
                    synchronized (report) {
                        report.setCompletedRequests(results.size())
                            .setSuccessCount(report.getSuccessCount() + (success ? 1 : 0))
                            .setFailureCount(report.getFailureCount() + (success ? 0 : 1));
                    }
                }

                if (intervalMs > 0) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            report.setStatus("COMPLETED").setResults(results);
        }, "mockserver-replay-" + replayId);
        replayThread.setDaemon(true);
        replayThread.start();

        return replayId;
    }

    /**
     * Returns the report for the given replay, or {@code null} if unknown.
     */
    public ReplayReport getReport(String replayId) {
        return activeReplays.get(replayId);
    }

    /**
     * Clear all replay state. Called on server reset.
     */
    public void reset() {
        activeReplays.clear();
    }
}
