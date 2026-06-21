package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.slo.SloSampleStore;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for {@link LoadScenarioOrchestrator}. Uses a deterministic mutable clock and a
 * synchronous fake sender (every request returns an already-completed future), so assertions
 * about request volume and templated variation are deterministic without wall-clock sleeps.
 * Where a count must be reached, a bounded poll on the sender's recorded queue is used.
 */
public class LoadScenarioOrchestratorTest {

    private AtomicLong clock;
    private ScheduledExecutorService scheduler;
    private LoadScenarioOrchestrator orchestrator;

    private boolean originalEnabled;
    private int originalMaxVus;
    private long originalMaxDuration;
    private int originalMaxSteps;
    private boolean originalSloTracking;

    @Before
    public void setUp() {
        clock = new AtomicLong(10_000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-load-scenario-scheduler");
            t.setDaemon(true);
            return t;
        });
        orchestrator = new LoadScenarioOrchestrator(clock::get, scheduler);
        Metrics.resetAdditionalMetricsForTesting();
        SloSampleStore.getInstance().reset();

        originalEnabled = ConfigurationProperties.loadGenerationEnabled();
        originalMaxVus = ConfigurationProperties.loadGenerationMaxVirtualUsers();
        originalMaxDuration = ConfigurationProperties.loadGenerationMaxDurationMillis();
        originalMaxSteps = ConfigurationProperties.loadGenerationMaxSteps();
        originalSloTracking = ConfigurationProperties.sloTrackingEnabled();
    }

    @After
    public void tearDown() {
        orchestrator.reset();
        scheduler.shutdownNow();
        Metrics.resetAdditionalMetricsForTesting();
        SloSampleStore.getInstance().reset();
        ConfigurationProperties.loadGenerationEnabled(originalEnabled);
        ConfigurationProperties.loadGenerationMaxVirtualUsers(originalMaxVus);
        ConfigurationProperties.loadGenerationMaxDurationMillis(originalMaxDuration);
        ConfigurationProperties.loadGenerationMaxSteps(originalMaxSteps);
        ConfigurationProperties.sloTrackingEnabled(originalSloTracking);
    }

    /** A synchronous fake sender that records every request and returns a 200 immediately. */
    private static final class RecordingSender implements Function<HttpRequest, CompletableFuture<HttpResponse>> {
        final ConcurrentLinkedQueue<HttpRequest> sent = new ConcurrentLinkedQueue<>();

        @Override
        public CompletableFuture<HttpResponse> apply(HttpRequest httpRequest) {
            sent.add(httpRequest);
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        }
    }

    private boolean awaitAtLeast(RecordingSender sender, int n, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (sender.sent.size() >= n) {
                return true;
            }
            Thread.sleep(5);
        }
        return sender.sent.size() >= n;
    }

    /** Poll until the most recent run's live active-VU count reaches the expected value (or timeout). */
    private boolean awaitLastRunActiveVus(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (orchestrator.lastRunActiveVuCount() == expected) {
                return true;
            }
            Thread.sleep(5);
        }
        return orchestrator.lastRunActiveVuCount() == expected;
    }

    @Test
    public void constantProfileDrivesRequestsUntilMaxRequests() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("constant")
            .withMaxRequests(25)
            .withProfile(LoadProfile.constant(3, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        String error = orchestrator.start(scenario, sender);
        assertThat(error, is(nullValue()));

        assertThat("scenario should reach maxRequests", awaitAtLeast(sender, 25, 5_000L), is(true));
        // The cap is enforced at dispatch time, so the count overshoots only by roughly the number
        // of in-flight VUs (3), not by a full control interval of firing — it must not run forever.
        long deadline = System.currentTimeMillis() + 500L;
        while (orchestrator.getStatus() != null && "running".equals(orchestrator.getStatus().state)
            && System.currentTimeMillis() < deadline) {
            orchestrator.tickNow();
            Thread.sleep(5);
        }
        assertThat(sender.sent.size(), greaterThanOrEqualTo(25));
        assertThat(sender.sent.size(), lessThan(60));
    }

    @Test
    public void linearProfileRampsTargetVusOverTime() {
        // Pure ramp-shape assertion via the profile (deterministic, no traffic needed).
        LoadProfile linear = LoadProfile.linear(0, 10, 1000L);
        assertThat(linear.targetVusAt(0), is(0));
        assertThat(linear.targetVusAt(500), is(5));
        assertThat(linear.targetVusAt(1000), is(10));
        assertThat(linear.targetVusAt(2000), is(10)); // clamped at end

        // And via the orchestrator status currentVus driven by tickNow + the mutable clock.
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("linear")
            .withProfile(LoadProfile.linear(0, 8, 1000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));
        assertThat(orchestrator.start(scenario, sender), is(nullValue()));

        clock.set(10_000L + 500L);
        orchestrator.tickNow();
        assertThat(orchestrator.getStatus().currentVus, is(4));

        // Just before the duration boundary the ramp is essentially at endVus; at exactly the
        // duration the scenario transitions to completed, which is covered separately.
        clock.set(10_000L + 999L);
        orchestrator.tickNow();
        assertThat(orchestrator.getStatus().currentVus, is(8));
    }

    @Test
    public void perIterationTemplatingProducesDistinctPaths() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("templated")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(
                request().withPath("/item/$iteration.index").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));

        Set<String> distinctPaths = sender.sent.stream()
            .map(r -> r.getPath().getValue())
            .collect(java.util.stream.Collectors.toSet());
        // $iteration.index varies per global iteration, so paths must differ.
        assertThat(distinctPaths.size(), greaterThan(1));
        assertThat(distinctPaths, hasItem("/item/0"));
    }

    @Test
    public void recordsSamplesIntoSloStoreWhenTrackingEnabled() throws Exception {
        ConfigurationProperties.sloTrackingEnabled(true);
        SloSampleStore.getInstance().reset();
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("slo-feed")
            .withMaxRequests(10)
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 10, 5_000L), is(true));
        // give the whenComplete callbacks a moment to flush into the store
        long deadline = System.currentTimeMillis() + 2_000L;
        while (SloSampleStore.getInstance().size() < 10 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(SloSampleStore.getInstance().size(), greaterThanOrEqualTo(10));
    }

    @Test
    public void rejectsScenarioExceedingVuCap() {
        ConfigurationProperties.loadGenerationMaxVirtualUsers(5);
        orchestrator.setConfiguration(org.mockserver.configuration.Configuration.configuration());
        LoadScenario scenario = new LoadScenario()
            .withName("too-many-vus")
            .withProfile(LoadProfile.constant(50, 1000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("exceeding the maximum of 5"));
    }

    @Test
    public void rejectsScenarioExceedingStepCap() {
        ConfigurationProperties.loadGenerationMaxSteps(2);
        orchestrator.setConfiguration(org.mockserver.configuration.Configuration.configuration());
        LoadScenario scenario = new LoadScenario()
            .withName("too-many-steps")
            .withProfile(LoadProfile.constant(1, 1000L))
            .withSteps(
                new LoadStep().withRequest(request().withPath("/a")),
                new LoadStep().withRequest(request().withPath("/b")),
                new LoadStep().withRequest(request().withPath("/c")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("exceeds the maximum of 2"));
    }

    @Test
    public void rejectsMissingProfileAndSteps() {
        assertThat(orchestrator.start(new LoadScenario().withName("x").withProfile(LoadProfile.constant(1, 1000L)),
            new RecordingSender()), containsString("steps"));
        assertThat(orchestrator.start(new LoadScenario().withName("x")
                .withSteps(new LoadStep().withRequest(request().withPath("/a"))),
            new RecordingSender()), containsString("profile"));
    }

    @Test
    public void activeVuCountReturnsToZeroAfterStop() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("drain-on-stop")
            .withProfile(LoadProfile.constant(4, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // Let the VUs spin up and issue some traffic so loops are genuinely in flight.
        assertThat("scenario should produce traffic", awaitAtLeast(sender, 20, 5_000L), is(true));
        assertThat("active VUs should have ramped up", orchestrator.activeVuCount(), is(greaterThan(0)));

        orchestrator.stop();

        // Every launched VU loop must release its slot exactly once as it observes the stopped state,
        // so the live active-VU count drains back to zero (no leak, no double-count to a negative).
        assertThat("active VU count should drain to zero after stop",
            awaitLastRunActiveVus(0, 5_000L), is(true));
        assertThat(orchestrator.lastRunActiveVuCount(), is(0));
    }

    @Test
    public void activeVuCountReturnsToZeroAfterCompletion() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("drain-on-complete")
            .withMaxRequests(30)
            .withProfile(LoadProfile.constant(4, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat("scenario should reach maxRequests", awaitAtLeast(sender, 30, 5_000L), is(true));

        // Drive ticks so completion is observed, then the in-flight VU loops drain their slots.
        long deadline = System.currentTimeMillis() + 2_000L;
        while (orchestrator.getStatus() != null && "running".equals(orchestrator.getStatus().state)
            && System.currentTimeMillis() < deadline) {
            orchestrator.tickNow();
            Thread.sleep(5);
        }
        assertThat(orchestrator.getStatus().state, is("completed"));
        assertThat("active VU count should drain to zero after completion",
            awaitLastRunActiveVus(0, 5_000L), is(true));
        assertThat(orchestrator.lastRunActiveVuCount(), is(0));
    }

    @Test
    public void activeVuCountRampsDownToTargetWithoutUnderflow() throws Exception {
        // Ramp up to a high concurrency, then drop the target and assert the live population settles
        // exactly at the new target (surplus VUs retire exactly once each; concurrent retirement
        // never collapses below the target).
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("ramp-down")
            .withProfile(LoadProfile.linear(6, 2, 1_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // At t=0 the linear profile starts at 6 VUs.
        assertThat("active VUs should ramp up to the start target",
            awaitLastRunActiveVus(6, 5_000L), is(true));

        // Advance the clock so the linear ramp's target falls to 2, then tick to apply the setpoint
        // and let surplus VUs retire at their iteration boundaries.
        clock.set(10_000L + 1_000L - 1L);
        orchestrator.tickNow();
        assertThat("surplus VUs should retire down to exactly the target",
            awaitLastRunActiveVus(2, 5_000L), is(true));
        assertThat("retirement must not underflow below the target",
            orchestrator.lastRunActiveVuCount(), is(2));
    }

    @Test
    public void getStatusReportsTerminalStateAfterStop() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("stoppable")
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));
        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        orchestrator.stop();
        assertThat(orchestrator.getStatus(), is(notNullValue()));
        assertThat(orchestrator.getStatus().state, is("stopped"));
    }
}
