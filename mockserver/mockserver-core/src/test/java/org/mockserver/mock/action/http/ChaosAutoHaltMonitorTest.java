package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpChaosProfile;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class ChaosAutoHaltMonitorTest {

    private boolean originalEnabled;
    private long originalThreshold;
    private long originalWindow;

    @Before
    public void saveOriginals() {
        originalEnabled = ConfigurationProperties.chaosAutoHaltEnabled();
        originalThreshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        originalWindow = ConfigurationProperties.chaosAutoHaltWindowMillis();
        // Reset shared state
        Metrics.resetAdditionalMetricsForTesting();
        ChaosAutoHaltMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
    }

    @After
    public void restoreOriginals() {
        ConfigurationProperties.chaosAutoHaltEnabled(originalEnabled);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(originalThreshold);
        ConfigurationProperties.chaosAutoHaltWindowMillis(originalWindow);
        Metrics.resetAdditionalMetricsForTesting();
        ChaosAutoHaltMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceededByErrorFaults() {
        // given - a local monitor with a controllable clock
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // and - active chaos in the registry
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));

        // when - record destructive errors below threshold
        monitor.recordError("error");
        monitor.recordError("error");
        assertThat("chaos still active below threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));

        // when - record error that crosses the threshold
        monitor.recordError("error");

        // then - chaos is halted
        assertThat("chaos halted after threshold exceeded",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceededByDropFaults() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - drop faults cross threshold
        monitor.recordError("drop");
        monitor.recordError("drop");

        // then - chaos is halted
        assertThat("chaos halted by drop faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldHaltChaosWhenThresholdExceededByQuotaFaults() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - quota faults cross threshold
        monitor.recordError("quota");
        monitor.recordError("quota");

        // then - chaos is halted
        assertThat("chaos halted by quota faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldHaltChaosWhenMixedDestructiveFaultsCrossThreshold() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - a mix of destructive fault types crosses the threshold
        monitor.recordError("error");
        monitor.recordError("drop");
        monitor.recordError("quota");

        // then - chaos is halted
        assertThat("chaos halted by mixed destructive faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldNotHaltWhenOnlyLatencyFaultsAreInjected() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - many latency faults (benign, non-destructive)
        for (int i = 0; i < 100; i++) {
            monitor.recordError("latency");
        }

        // then - chaos is NOT halted because latency is not destructive
        assertThat("chaos NOT halted by latency-only faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void shouldNotHaltWhenOnlyBenignFaultsAreInjected() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - benign fault types that should NOT trigger auto-halt
        for (int i = 0; i < 20; i++) {
            monitor.recordError("latency");
            monitor.recordError("slow");
            monitor.recordError("truncate");
            monitor.recordError("malformed");
            monitor.recordError("graphql");
        }

        // then - chaos is NOT halted
        assertThat("chaos NOT halted by benign faults",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void shouldIgnoreNullFaultType() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(1);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - null fault type
        monitor.recordError(null);

        // then - not counted
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void shouldNotCountBenignFaultsTowardThreshold() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // record 2 destructive errors (below threshold of 3)
        monitor.recordError("error");
        monitor.recordError("drop");

        // record many benign faults — these should NOT push us over
        for (int i = 0; i < 50; i++) {
            monitor.recordError("latency");
            monitor.recordError("slow");
        }

        // then - still below threshold, chaos still active
        assertThat("benign faults do not count toward threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(2));

        // one more destructive fault pushes us over
        monitor.recordError("quota");
        assertThat("destructive fault pushes over threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void shouldNotHaltWhenFeatureDisabled() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(1);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // when - record many destructive errors (feature is disabled)
        for (int i = 0; i < 100; i++) {
            monitor.recordError("error");
        }

        // then - chaos is NOT halted
        assertThat("chaos not halted when feature disabled",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldEvictOldErrorsOutsideWindow() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(5);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // record 4 destructive errors at time=1000
        for (int i = 0; i < 4; i++) {
            monitor.recordError("error");
        }

        // advance clock past the window so old errors expire
        now.set(12_000L);

        // record 1 more error — only 1 error in window, well below threshold of 5
        monitor.recordError("error");

        assertThat("old errors evicted, below threshold",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.currentWindowSize(), is(1));
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldNotHaltWhenNoChaosIsActive() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        // no chaos registered
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));

        // when - record destructive errors exceeding threshold with no chaos active
        monitor.recordError("error");
        monitor.recordError("error");

        // then - halt count stays at 0 (nothing to halt)
        assertThat(monitor.getHaltCount(), is(0L));
    }

    @Test
    public void shouldClearWindowAfterHalt() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // trigger halt
        monitor.recordError("error");
        monitor.recordError("drop");
        assertThat(monitor.getHaltCount(), is(1L));

        // re-register chaos
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // one more error should NOT immediately re-trigger (window was cleared)
        monitor.recordError("error");
        assertThat("single error after halt does not re-trigger",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(1L));
    }

    @Test
    public void resetClearsMonitorState() {
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(2);
        ConfigurationProperties.chaosAutoHaltWindowMillis(10_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        monitor.recordError("error");
        monitor.recordError("error");
        assertThat(monitor.getHaltCount(), is(1L));

        monitor.reset();
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(0));
    }

    @Test
    public void resetPreventsStaleErrorsFromHaltingFreshChaos() {
        // Verifies that HttpState.reset() clearing the monitor prevents stale
        // errors accumulated before reset from halting newly-registered chaos
        AtomicLong now = new AtomicLong(1000L);
        ChaosAutoHaltMonitor monitor = new ChaosAutoHaltMonitor(now::get);

        ConfigurationProperties.chaosAutoHaltEnabled(true);
        ConfigurationProperties.chaosAutoHaltErrorThreshold(3);
        ConfigurationProperties.chaosAutoHaltWindowMillis(60_000L);

        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // accumulate 2 errors (just below threshold)
        monitor.recordError("error");
        monitor.recordError("drop");
        assertThat(monitor.currentWindowSize(), is(2));

        // simulate server reset
        monitor.reset();
        ServiceChaosRegistry.getInstance().reset();

        // register fresh chaos
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        // one error should NOT trigger halt (stale errors were cleared by reset)
        monitor.recordError("error");
        assertThat("stale errors cleared by reset do not count",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
        assertThat(monitor.getHaltCount(), is(0L));
        assertThat(monitor.currentWindowSize(), is(1));
    }

    @Test
    public void singletonInstanceRecordErrorIsNoOpWhenDisabled() {
        // Verify the default instance does nothing when feature is off
        ConfigurationProperties.chaosAutoHaltEnabled(false);
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));

        ChaosAutoHaltMonitor.getInstance().recordError("error");

        assertThat("singleton no-op when disabled",
            ServiceChaosRegistry.getInstance().entries().isEmpty(), is(false));
    }
}
