package org.mockserver.metrics;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.util.Collection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;

public class OtelMetricsExporterTest {

    @Test
    public void exportsExplicitMockServerMetricsAsObservableGauges() {
        // given — a known value in an explicitly-defined MockServer metric
        Metrics.clear(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        Metrics enabled = new Metrics(configuration().metricsEnabled(true));
        enabled.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        int expected = Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT);

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OtelMetricsExporter exporter = OtelMetricsExporter.startWithReader(reader);
        try {
            // when — OTel collects (triggers the observable-gauge callbacks)
            Collection<MetricData> collected = reader.collectAllMetrics();

            // then — there is a gauge per Metrics.Name, and the requests gauge reads
            // the same value the Prometheus metric holds
            assertThat(collected.size(), greaterThanOrEqualTo(Metrics.Name.values().length));
            MetricData requests = collected.stream()
                .filter(m -> m.getName().equals("requests_received_count"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("requests_received_count gauge not exported"));
            long value = requests.getLongGaugeData().getPoints().iterator().next().getValue();
            assertThat((int) value, is(expected));
        } finally {
            exporter.stop();
        }
    }

    @Test
    public void disabledByDefaultReturnsNull() {
        // off unless configured — startIfEnabled reads mockserver.otelMetricsEnabled (default false)
        assertThat(OtelMetricsExporter.startIfEnabled() == null, is(true));
    }
}
