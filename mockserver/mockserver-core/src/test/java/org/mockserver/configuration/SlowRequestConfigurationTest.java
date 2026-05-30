package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class SlowRequestConfigurationTest {

    @Before
    @After
    public void resetProperties() {
        ConfigurationProperties.slowRequestThresholdMillis(0L);
        ConfigurationProperties.metricsRequestDurationRouteLabels(false);
    }

    @Test
    public void shouldReturnDefaultSlowRequestThreshold() {
        assertThat(ConfigurationProperties.slowRequestThresholdMillis(), is(0L));
    }

    @Test
    public void shouldSetAndGetSlowRequestThreshold() {
        // when
        ConfigurationProperties.slowRequestThresholdMillis(500L);

        // then
        assertThat(ConfigurationProperties.slowRequestThresholdMillis(), is(500L));
    }

    @Test
    public void shouldReturnDefaultMetricsRequestDurationRouteLabels() {
        assertThat(ConfigurationProperties.metricsRequestDurationRouteLabels(), is(false));
    }

    @Test
    public void shouldSetAndGetMetricsRequestDurationRouteLabels() {
        // when
        ConfigurationProperties.metricsRequestDurationRouteLabels(true);

        // then
        assertThat(ConfigurationProperties.metricsRequestDurationRouteLabels(), is(true));
    }

    @Test
    public void shouldReturnDefaultSlowRequestThresholdFromConfiguration() {
        Configuration configuration = Configuration.configuration();
        assertThat(configuration.slowRequestThresholdMillis(), is(0L));
    }

    @Test
    public void shouldSetAndGetSlowRequestThresholdFromConfiguration() {
        Configuration configuration = Configuration.configuration().slowRequestThresholdMillis(1000L);
        assertThat(configuration.slowRequestThresholdMillis(), is(1000L));
    }

    @Test
    public void shouldReturnDefaultRouteLabelsFromConfiguration() {
        Configuration configuration = Configuration.configuration();
        assertThat(configuration.metricsRequestDurationRouteLabels(), is(false));
    }

    @Test
    public void shouldSetAndGetRouteLabelsFromConfiguration() {
        Configuration configuration = Configuration.configuration().metricsRequestDurationRouteLabels(true);
        assertThat(configuration.metricsRequestDurationRouteLabels(), is(true));
    }
}
