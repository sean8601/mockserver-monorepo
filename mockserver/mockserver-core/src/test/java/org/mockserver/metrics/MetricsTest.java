package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

public class MetricsTest {

    @Test
    public void registersAndRecordsRequestDurationHistogram() {
        // constructing an enabled Metrics registers the histogram (once per JVM)
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeRequestDurationSeconds(0.05);

        assertThat(scrapeContains("mock_server_request_duration_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationDoesNotThrow() {
        // safe to call regardless of registration state (no-op when absent)
        Metrics.observeRequestDurationSeconds(0.01);
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
