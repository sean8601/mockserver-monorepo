package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

public class MetricsTest {

    @Before
    public void resetStaticState() {
        // Reset the process-static one-shot guard and clear the default
        // registry so each test starts with a clean slate — prevents
        // order-dependent failures caused by the CAS guard in Metrics.
        Metrics.resetAdditionalMetricsForTesting();
    }

    @Test
    public void registersAndRecordsRequestDurationHistogram() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeRequestDurationSeconds(0.05);

        assertThat(scrapeContains("mock_server_request_duration_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationDoesNotThrow() {
        // safe to call regardless of registration state (no-op when absent)
        Metrics.observeRequestDurationSeconds(0.01);
    }

    @Test
    public void registersSlowRequestCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementSlowRequestTotal();

        assertThat(scrapeContains("mock_server_slow_requests"), is(true));
    }

    @Test
    public void incrementSlowRequestTotalDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementSlowRequestTotal();
    }

    @Test
    public void registersMethodLabeledHistogramWhenEnabled() {
        new Metrics(configuration().metricsEnabled(true).metricsRequestDurationRouteLabels(true));
        Metrics.observeRequestDurationByMethodSeconds(0.05, "GET");

        assertThat(scrapeContains("mock_server_request_duration_by_method_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationByMethodDoesNotThrowWhenDisabled() {
        // safe to call when labeled histogram not registered (no-op)
        Metrics.observeRequestDurationByMethodSeconds(0.01, "POST");
    }

    private static boolean scrapeContains(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
