package org.mockserver.telemetry;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

public class OtelEndpointsTest {

    @Test
    public void emptyOrNullBaseUsesExporterDefault() {
        assertThat(OtelEndpoints.metrics(null), is(nullValue()));
        assertThat(OtelEndpoints.metrics(""), is(nullValue()));
        assertThat(OtelEndpoints.traces(null), is(nullValue()));
    }

    @Test
    public void appendsSignalPathToBareBase() {
        assertThat(OtelEndpoints.metrics("http://collector:4318"), is("http://collector:4318/v1/metrics"));
        assertThat(OtelEndpoints.traces("http://collector:4318"), is("http://collector:4318/v1/traces"));
    }

    @Test
    public void toleratesTrailingSlash() {
        assertThat(OtelEndpoints.metrics("http://collector:4318/"), is("http://collector:4318/v1/metrics"));
    }

    @Test
    public void toleratesValueAlreadyCarryingASignalPath() {
        assertThat(OtelEndpoints.metrics("http://collector:4318/v1/metrics"), is("http://collector:4318/v1/metrics"));
        // a base carrying the metrics path is still correctly turned into the traces endpoint
        assertThat(OtelEndpoints.traces("http://collector:4318/v1/metrics"), is("http://collector:4318/v1/traces"));
    }

    @Test
    public void toleratesSignalPathWithTrailingSlash() {
        // the previously-buggy case: signal path + trailing slash must not double up
        assertThat(OtelEndpoints.traces("http://collector:4318/v1/metrics/"), is("http://collector:4318/v1/traces"));
        assertThat(OtelEndpoints.metrics("http://collector:4318/v1/traces/"), is("http://collector:4318/v1/metrics"));
    }
}
