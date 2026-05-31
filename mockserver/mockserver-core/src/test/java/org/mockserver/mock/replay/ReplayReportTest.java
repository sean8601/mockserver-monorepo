package org.mockserver.mock.replay;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ReplayReportTest {

    @Test
    public void shouldCreateReportWithFactoryMethod() {
        // when
        ReplayReport report = ReplayReport.create("test-id", 5);

        // then
        assertThat(report.getReplayId(), is("test-id"));
        assertThat(report.getStatus(), is("RUNNING"));
        assertThat(report.getTotalRequests(), is(5));
        assertThat(report.getCompletedRequests(), is(0));
        assertThat(report.getSuccessCount(), is(0));
        assertThat(report.getFailureCount(), is(0));
        assertThat(report.getResults(), is(nullValue()));
    }

    @Test
    public void shouldSupportFluentSetters() {
        // when
        ReplayResult result = new ReplayResult()
            .setPath("/api/test")
            .setMethod("POST")
            .setBaselineStatusCode(200)
            .setReplayStatusCode(503)
            .setBaselineLatencyMs(50L)
            .setReplayLatencyMs(120L)
            .setLatencyDeltaMs(70L)
            .setStatusMatch(false);

        // then
        assertThat(result.getPath(), is("/api/test"));
        assertThat(result.getMethod(), is("POST"));
        assertThat(result.getBaselineStatusCode(), is(200));
        assertThat(result.getReplayStatusCode(), is(503));
        assertThat(result.getBaselineLatencyMs(), is(50L));
        assertThat(result.getReplayLatencyMs(), is(120L));
        assertThat(result.getLatencyDeltaMs(), is(70L));
        assertThat(result.isStatusMatch(), is(false));
    }

    @Test
    public void shouldSupportEquality() {
        // given
        ReplayResult a = new ReplayResult().setPath("/a").setMethod("GET").setBaselineStatusCode(200).setReplayStatusCode(200).setStatusMatch(true);
        ReplayResult b = new ReplayResult().setPath("/a").setMethod("GET").setBaselineStatusCode(200).setReplayStatusCode(200).setStatusMatch(true);

        // then
        assertThat(a, is(equalTo(b)));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    @Test
    public void shouldSetResultsOnReport() {
        // given
        ReplayResult result = new ReplayResult().setPath("/test").setStatusMatch(true);
        ReplayReport report = ReplayReport.create("id-1", 1)
            .setStatus("COMPLETED")
            .setCompletedRequests(1)
            .setSuccessCount(1)
            .setFailureCount(0)
            .setResults(List.of(result));

        // then
        assertThat(report.getResults(), hasSize(1));
        assertThat(report.getResults().get(0).getPath(), is("/test"));
    }
}
