package org.mockserver.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Optional exporter that publishes MockServer's <em>explicitly-defined</em>
 * metrics (the same {@link Metrics.Name} gauges exposed for Prometheus) via
 * OpenTelemetry OTLP. Off unless {@code mockserver.otelMetricsEnabled} is set.
 * <p>
 * Deliberately metrics-only: it registers one OTel observable gauge per
 * {@link Metrics.Name} whose callback reads the current {@link Metrics#get}
 * value at each collection. No spans, no auto-instrumentation. The OTel SDK is
 * self-configured here (MockServer usually runs standalone), using the OTLP
 * HTTP/protobuf exporter with the JDK HttpClient sender.
 */
public class OtelMetricsExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelMetricsExporter.class);

    private final SdkMeterProvider meterProvider;

    private OtelMetricsExporter(SdkMeterProvider meterProvider) {
        this.meterProvider = meterProvider;
    }

    /**
     * Start the exporter if enabled in configuration, returning the running
     * instance, or {@code null} if disabled or startup failed (fail-soft —
     * telemetry must never prevent the server from running).
     */
    public static OtelMetricsExporter startIfEnabled() {
        if (!ConfigurationProperties.otelMetricsEnabled()) {
            return null;
        }
        try {
            String endpoint = org.mockserver.telemetry.OtelEndpoints.metrics(ConfigurationProperties.otelEndpoint());
            OtlpHttpMetricExporter otlpExporter = endpoint != null
                ? OtlpHttpMetricExporter.builder().setEndpoint(endpoint).build()
                : OtlpHttpMetricExporter.builder().build();
            MetricReader reader = PeriodicMetricReader.builder(otlpExporter)
                .setInterval(Duration.ofSeconds(ConfigurationProperties.otelMetricsExportIntervalSeconds()))
                .build();
            OtelMetricsExporter exporter = startWithReader(reader);
            LOGGER.info("OpenTelemetry metrics export enabled (endpoint {}, interval {}s)",
                endpoint == null || endpoint.isEmpty() ? "default" : endpoint,
                ConfigurationProperties.otelMetricsExportIntervalSeconds());
            return exporter;
        } catch (Exception e) {
            LOGGER.warn("failed to start OpenTelemetry metrics export ({}); continuing without it", e.getMessage());
            return null;
        }
    }

    /**
     * Build a meter provider with the given reader and register an observable
     * gauge per {@link Metrics.Name}. Visible for testing (a test can pass an
     * in-memory reader instead of the OTLP periodic reader).
     */
    public static OtelMetricsExporter startWithReader(MetricReader reader) {
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .setResource(Resource.getDefault().merge(
                Resource.create(Attributes.of(stringKey("service.name"), "mockserver"))))
            .registerMetricReader(reader)
            .build();
        Meter meter = provider.get("org.mockserver");
        for (Metrics.Name name : Metrics.Name.values()) {
            meter.gaugeBuilder(name.name().toLowerCase())
                .setDescription(name.description)
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(Metrics.get(name)));
        }
        return new OtelMetricsExporter(provider);
    }

    /**
     * Stop exporting and release resources. Safe to call once.
     */
    public void stop() {
        try {
            meterProvider.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.debug("error shutting down OpenTelemetry metrics export: {}", e.getMessage());
        }
    }
}
