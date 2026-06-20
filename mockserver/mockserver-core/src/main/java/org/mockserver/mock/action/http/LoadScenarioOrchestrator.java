package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.load.IterationContext;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.slo.Scope;
import org.mockserver.slo.SloSampleStore;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Orchestrates an in-process API-driven load scenario. A scenario drives an ordered list
 * of templated request {@link LoadStep}s at a target concurrency described by a
 * {@link LoadProfile}, producing latency/error samples for the SLO verdict feature.
 *
 * <p>Copies the {@link ChaosExperimentOrchestrator} shape: a process-wide singleton with a
 * single-thread daemon {@link ScheduledExecutorService} ("load-scenario-scheduler"). The
 * scheduler thread does <b>no I/O</b> — it computes ramp setpoints on a fixed control tick
 * and hands each request to an injected {@code sender} that returns a
 * {@link CompletableFuture} immediately. Step pacing and iteration pacing are scheduled
 * (never {@link Delay#applyDelay() Thread.sleep}-ed), so a slow target never blocks a worker
 * thread.
 *
 * <p><b>Decoupling:</b> core must not depend on the Netty HTTP client, so the actual request
 * sender is injected via {@link #setSender(Function)} (mirrors {@code HttpState.setReplayHandler}).
 * The Netty runtime wires it from {@code HttpActionHandler.getHttpClient()}; unit tests pass a
 * deterministic synchronous fake sender to {@link #start(LoadScenario, Function)}.
 *
 * <p><b>Self-load guard:</b> off by default ({@code loadGenerationEnabled=false} → the PUT
 * endpoint returns 403). Even when enabled, {@link #validate} enforces hard caps on VUs,
 * duration and step count, and dispatch is bounded live by an in-flight {@link Semaphore} and
 * an RPS token bucket, so a forgotten scenario cannot self-DoS the server.
 *
 * <p>Time is read via a pluggable {@link LongSupplier} clock (defaults to
 * {@link TimeService#currentTimeMillis()}) so tests can drive ramp progression deterministically
 * via {@link #tickNow()} / {@link #advanceNow(long)} without wall-clock sleeps.
 */
public class LoadScenarioOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(LoadScenarioOrchestrator.class);

    /** Control tick interval in milliseconds (ramp setpoint recomputation). */
    static final long CONTROL_TICK_MILLIS = 100;

    private static final LoadScenarioOrchestrator INSTANCE = new LoadScenarioOrchestrator(
        TimeService::currentTimeMillis,
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "load-scenario-scheduler");
            t.setDaemon(true);
            return t;
        })
    );

    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<RunningScenario> current = new AtomicReference<>(null);
    private volatile LoadScenarioStatus lastTerminatedStatus;
    /** Sender installed by the runtime; null in unit tests until start() supplies one. */
    private volatile Function<HttpRequest, CompletableFuture<HttpResponse>> installedSender;
    /** Configuration used to read caps and to build the template engines for rendering. */
    private volatile Configuration configuration = Configuration.configuration();

    LoadScenarioOrchestrator(LongSupplier clock, ScheduledExecutorService scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public static LoadScenarioOrchestrator getInstance() {
        return INSTANCE;
    }

    /**
     * Install the request sender that re-issues a {@link HttpRequest} to its target and returns
     * the upstream response. Called by the Netty runtime, wiring the existing HTTP client so the
     * core never depends on it directly (mirrors {@code HttpState.setReplayHandler}).
     */
    public void setSender(Function<HttpRequest, CompletableFuture<HttpResponse>> sender) {
        this.installedSender = sender;
    }

    /** Install the configuration used for caps and template engines. Called by the runtime. */
    public void setConfiguration(Configuration configuration) {
        if (configuration != null) {
            this.configuration = configuration;
        }
    }

    /**
     * Start a scenario. Returns a validation error message if the definition is invalid or the
     * caps are exceeded, or {@code null} on success. Only one scenario may run at a time; starting
     * a new one stops the previous one. When {@code sender} is null the installed runtime sender is
     * used; unit tests pass a deterministic synchronous sender here.
     */
    public String start(LoadScenario scenario, Function<HttpRequest, CompletableFuture<HttpResponse>> sender) {
        String error = validate(scenario);
        if (error != null) {
            return error;
        }
        Function<HttpRequest, CompletableFuture<HttpResponse>> effectiveSender = sender != null ? sender : installedSender;
        if (effectiveSender == null) {
            return "no load sender installed (server runtime not wired)";
        }

        stopInternal("stopped");

        long startedAt = clock.getAsLong();
        RunningScenario running = new RunningScenario(scenario, effectiveSender, startedAt);
        current.set(running);

        // Schedule the periodic control tick on the scheduler thread (no I/O on that thread).
        ScheduledFuture<?> tick = scheduler.scheduleAtFixedRate(
            () -> tick(running), CONTROL_TICK_MILLIS, CONTROL_TICK_MILLIS, TimeUnit.MILLISECONDS);
        running.controlTick = tick;

        LOG.info("load scenario '{}' started with {} step(s), profile={}",
            scenario.getName(), scenario.getSteps().size(), scenario.getProfile().getType());

        // Run an immediate first tick so a VU launches without waiting a full control interval.
        tick(running);
        return null;
    }

    /** Stop the current scenario. Idempotent. */
    public void stop() {
        stopInternal("stopped");
    }

    /** Reset: stop any running scenario and clear terminal status. Called on server reset. */
    public void reset() {
        stopInternal("stopped");
        lastTerminatedStatus = null;
    }

    /**
     * Status of the current scenario, or the last terminated one, or null if none has ever run.
     */
    public LoadScenarioStatus getStatus() {
        RunningScenario run = current.get();
        if (run == null) {
            return lastTerminatedStatus;
        }
        return run.snapshot("running", clock.getAsLong());
    }

    // -- Internal --

    private void stopInternal(String terminalState) {
        RunningScenario run = current.getAndSet(null);
        if (run != null) {
            run.stopped.set(true);
            ScheduledFuture<?> tick = run.controlTick;
            if (tick != null) {
                tick.cancel(false);
            }
            lastTerminatedStatus = run.snapshot(terminalState, clock.getAsLong());
            LOG.info("load scenario '{}' {} ({} requests sent)", run.scenario.getName(), terminalState, run.requestsSent.get());
        }
    }

    /**
     * Control tick: compute the target VU count for the elapsed time and grow/retire VUs to match.
     * Detects completion (duration elapsed or maxRequests reached). Runs on the scheduler thread.
     */
    private void tick(RunningScenario run) {
        if (run.stopped.get() || current.get() != run) {
            return;
        }
        long now = clock.getAsLong();
        long elapsed = now - run.startedAt;
        LoadProfile profile = run.scenario.getProfile();

        // Completion conditions.
        if (elapsed >= profile.getDurationMillis() || run.maxRequestsReached()) {
            completeInternal(run);
            return;
        }

        int target = Math.max(0, profile.targetVusAt(elapsed));
        int active = run.activeVUs.get();
        if (active < target) {
            // Grow: launch VU loops up to the target, bounded by the in-flight semaphore being available.
            int toLaunch = target - active;
            for (int i = 0; i < toLaunch; i++) {
                int vuId = run.activeVUs.getAndIncrement();
                launchIteration(run, vuId, 0);
            }
        } else if (active > target) {
            // Retire surplus: mark them so they stop after their current iteration.
            run.targetVUs.set(target);
        }
        run.targetVUs.set(target);
    }

    private void completeInternal(RunningScenario run) {
        if (current.compareAndSet(run, null)) {
            run.stopped.set(true);
            ScheduledFuture<?> tick = run.controlTick;
            if (tick != null) {
                tick.cancel(false);
            }
            lastTerminatedStatus = run.snapshot("completed", clock.getAsLong());
            LOG.info("load scenario '{}' completed ({} requests sent)", run.scenario.getName(), run.requestsSent.get());
        }
    }

    /**
     * Run one iteration for a virtual user: render and fire each step in order, chaining the
     * CompletableFutures. Step completion (on a sender/worker thread) schedules the next step
     * after the step's think-time. After the last step, schedule the next iteration (respecting
     * iteration pacing) unless the VU is retiring or the scenario is stopped. No dedicated thread
     * per VU; no blocking.
     */
    private void launchIteration(RunningScenario run, int vuId, long vuIteration) {
        if (run.stopped.get() || current.get() != run) {
            run.activeVUs.decrementAndGet();
            return;
        }
        // Retire this VU if it is now above the target concurrency.
        if (vuId >= run.targetVUs.get() && vuIteration > 0) {
            run.activeVUs.decrementAndGet();
            return;
        }
        fireStep(run, vuId, vuIteration, 0);
    }

    private void fireStep(RunningScenario run, int vuId, long vuIteration, int stepIndex) {
        if (run.stopped.get() || current.get() != run) {
            run.activeVUs.decrementAndGet();
            return;
        }
        // Stop dispatching once the request cap is reached; the control tick then transitions the
        // scenario to "completed". Enforcing this here (not only on the tick) bounds the overshoot
        // to roughly the active VU count rather than a full control interval of firing.
        if (run.maxRequestsReached()) {
            completeInternal(run);
            return;
        }
        List<LoadStep> steps = run.scenario.getSteps();
        if (stepIndex >= steps.size()) {
            // Iteration finished: account it and schedule the next iteration (respect pacing).
            long nextIteration = vuIteration + 1;
            Long pacing = run.scenario.getProfile().getIterationPacingMillis();
            long pacingMillis = pacing != null ? Math.max(0, pacing) : 0;
            if (pacingMillis > 0) {
                scheduler.schedule(() -> launchIteration(run, vuId, nextIteration), pacingMillis, TimeUnit.MILLISECONDS);
            } else {
                // Avoid unbounded recursion / starving the single scheduler thread: re-schedule
                // immediately rather than recursing inline.
                scheduler.schedule(() -> launchIteration(run, vuId, nextIteration), 0, TimeUnit.MILLISECONDS);
            }
            return;
        }

        LoadStep step = steps.get(stepIndex);
        long globalIndex = run.iterationIndex.getAndIncrement();
        long count = run.requestsSent.get();
        long elapsed = clock.getAsLong() - run.startedAt;
        IterationContext iteration = new IterationContext(globalIndex, vuId, vuIteration, elapsed, count);

        HttpRequest rendered;
        try {
            rendered = run.render(step.getRequest(), iteration);
        } catch (Exception e) {
            LOG.warn("load scenario '{}' failed to render step {} (vu {} iteration {}): {}",
                run.scenario.getName(), stepIndex, vuId, vuIteration, e.getMessage());
            run.failed.incrementAndGet();
            run.requestsSent.incrementAndGet();
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }

        // Self-load guard at dispatch: acquire an in-flight permit and an RPS token.
        if (!run.inFlight.tryAcquire()) {
            // Too many in flight: skip this dispatch this round and try the next step after pacing.
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }
        if (!run.tryAcquireRpsToken(clock.getAsLong())) {
            run.inFlight.release();
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }

        run.requestsSent.incrementAndGet();
        final long startNanos = TimeService.nanoTime();
        final String host = hostOf(rendered);
        CompletableFuture<HttpResponse> future;
        try {
            future = run.sender.apply(rendered);
        } catch (Exception e) {
            run.inFlight.release();
            recordResult(run, host, null, startNanos, true);
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }
        if (future == null) {
            run.inFlight.release();
            recordResult(run, host, null, startNanos, true);
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }
        future.whenComplete((response, throwable) -> {
            run.inFlight.release();
            boolean error = throwable != null || response == null
                || (response.getStatusCode() != null && response.getStatusCode() >= 500);
            recordResult(run, host, response, startNanos, error);
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
        });
    }

    private void scheduleNextStep(RunningScenario run, int vuId, long vuIteration, int stepIndex, LoadStep step) {
        if (run.stopped.get() || current.get() != run) {
            // The VU loop ends; only decrement once per loop (the loop "owns" one active slot until it ends).
            return;
        }
        long thinkMillis = step.getThinkTime() != null ? Math.max(0, step.getThinkTime().sampleValueMillis()) : 0;
        scheduler.schedule(() -> fireStep(run, vuId, vuIteration, stepIndex + 1), thinkMillis, TimeUnit.MILLISECONDS);
    }

    private void recordResult(RunningScenario run, String host, HttpResponse response, long startNanos, boolean error) {
        long latencyMillis = Math.max(0, (TimeService.nanoTime() - startNanos) / 1_000_000L);
        if (error) {
            run.failed.incrementAndGet();
        } else {
            run.succeeded.incrementAndGet();
        }
        run.recordLatency(latencyMillis);
        Integer statusCode = response != null ? response.getStatusCode() : null;
        // Mirror the forward-path observability so load traffic shows up on the same metrics.
        Metrics.observeForwardRequest(host, statusCode, latencyMillis / 1000.0);
        // Feed the SLO sample store so the SLO verdict feature can read load-driven SLIs.
        SloSampleStore.getInstance().record(TimeService.currentTimeMillis(), latencyMillis, error, Scope.FORWARD, host);
    }

    private static String hostOf(HttpRequest request) {
        if (request == null) {
            return null;
        }
        String hostHeader = request.getFirstHeader("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            int colon = hostHeader.indexOf(':');
            return colon > 0 ? hostHeader.substring(0, colon) : hostHeader;
        }
        return request.getRemoteAddress();
    }

    /**
     * Force one control tick immediately (test hook), bypassing the scheduler interval, so ramp
     * progression can be asserted deterministically. No-op when no scenario is running.
     */
    void tickNow() {
        RunningScenario run = current.get();
        if (run != null) {
            tick(run);
        }
    }

    /**
     * Test hook for the deterministic clock: callers using a mutable {@link LongSupplier} advance
     * the clock externally; this triggers a tick so the orchestrator observes the new elapsed time.
     */
    void advanceNow(long ignoredMillis) {
        tickNow();
    }

    String validate(LoadScenario scenario) {
        if (scenario == null) {
            return "'loadScenario' is required";
        }
        if (scenario.getName() == null || scenario.getName().isBlank()) {
            return "'name' is required";
        }
        List<LoadStep> steps = scenario.getSteps();
        if (steps == null || steps.isEmpty()) {
            return "'steps' must contain at least one step";
        }
        int maxSteps = configuration.loadGenerationMaxSteps();
        if (steps.size() > maxSteps) {
            return "'steps' exceeds the maximum of " + maxSteps;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) == null || steps.get(i).getRequest() == null) {
                return "step[" + i + "] must have a request";
            }
        }
        LoadProfile profile = scenario.getProfile();
        if (profile == null) {
            return "'profile' is required";
        }
        if (profile.getDurationMillis() <= 0) {
            return "'profile.durationMillis' must be > 0";
        }
        long maxDuration = configuration.loadGenerationMaxDurationMillis();
        if (profile.getDurationMillis() > maxDuration) {
            return "'profile.durationMillis' exceeds the maximum of " + maxDuration + " ms";
        }
        int peakVus = profile.peakVus();
        if (peakVus <= 0) {
            return "'profile' must request at least one virtual user";
        }
        int maxVus = configuration.loadGenerationMaxVirtualUsers();
        if (peakVus > maxVus) {
            return "'profile' requests " + peakVus + " virtual users, exceeding the maximum of " + maxVus;
        }
        if (scenario.getTemplateType() == HttpTemplate.TemplateType.JAVASCRIPT) {
            return "templateType JAVASCRIPT is not supported for load steps; use VELOCITY or MUSTACHE";
        }
        return null;
    }

    /** Mutable state for a running scenario. */
    private final class RunningScenario {
        final LoadScenario scenario;
        final Function<HttpRequest, CompletableFuture<HttpResponse>> sender;
        final long startedAt;
        final long startedAtEpoch = TimeService.currentTimeMillis();
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final AtomicInteger activeVUs = new AtomicInteger(0);
        final AtomicInteger targetVUs = new AtomicInteger(0);
        final AtomicLong iterationIndex = new AtomicLong(0);
        final AtomicLong requestsSent = new AtomicLong(0);
        final AtomicLong succeeded = new AtomicLong(0);
        final AtomicLong failed = new AtomicLong(0);
        final Semaphore inFlight;
        final int maxRps;
        volatile ScheduledFuture<?> controlTick;

        // RPS token bucket (1-second window). Synchronized on this monitor.
        private long rpsWindowStart;
        private int rpsTokensUsed;

        // Latency reservoir for percentile snapshots (bounded).
        private static final int LATENCY_CAP = 10_000;
        private final long[] latencies = new long[LATENCY_CAP];
        private int latencyCount;

        // Engines built lazily for rendering (Velocity/Mustache only).
        private volatile TemplateEngine velocity;
        private volatile TemplateEngine mustache;

        RunningScenario(LoadScenario scenario, Function<HttpRequest, CompletableFuture<HttpResponse>> sender, long startedAt) {
            this.scenario = scenario;
            this.sender = sender;
            this.startedAt = startedAt;
            this.inFlight = new Semaphore(Math.max(1, configuration.loadGenerationMaxInFlightRequests()));
            this.maxRps = Math.max(1, configuration.loadGenerationMaxRequestsPerSecond());
            this.rpsWindowStart = startedAt;
        }

        boolean maxRequestsReached() {
            Integer max = scenario.getMaxRequests();
            return max != null && max > 0 && requestsSent.get() >= max;
        }

        synchronized boolean tryAcquireRpsToken(long now) {
            if (now - rpsWindowStart >= 1000) {
                rpsWindowStart = now;
                rpsTokensUsed = 0;
            }
            if (rpsTokensUsed < maxRps) {
                rpsTokensUsed++;
                return true;
            }
            return false;
        }

        synchronized void recordLatency(long latencyMillis) {
            if (latencyCount < LATENCY_CAP) {
                latencies[latencyCount++] = latencyMillis;
            } else {
                // Reservoir wrap: overwrite a deterministic slot so the snapshot stays bounded.
                latencies[(int) (requestsSent.get() % LATENCY_CAP)] = latencyMillis;
            }
        }

        synchronized long percentile(int pct) {
            if (latencyCount == 0) {
                return 0;
            }
            long[] copy = new long[latencyCount];
            System.arraycopy(latencies, 0, copy, 0, latencyCount);
            java.util.Arrays.sort(copy);
            int idx = (int) Math.ceil((pct / 100.0) * latencyCount) - 1;
            return copy[Math.max(0, Math.min(idx, latencyCount - 1))];
        }

        HttpRequest render(HttpRequest request, IterationContext iteration) {
            if (request == null) {
                return null;
            }
            TemplateEngine engine = engineFor(scenario.getTemplateType());
            HttpRequest clone = request.clone();
            String path = request.getPath() != null ? request.getPath().getValue() : null;
            if (path != null && containsTemplate(path)) {
                clone.withPath(engine.renderTemplate(path, request, iteration));
            }
            String body = request.getBodyAsString();
            if (body != null && containsTemplate(body)) {
                clone.withBody(engine.renderTemplate(body, request, iteration));
            }
            return clone;
        }

        private boolean containsTemplate(String value) {
            return value != null && (value.contains("$") || value.contains("{{") || value.contains("#"));
        }

        private TemplateEngine engineFor(HttpTemplate.TemplateType type) {
            if (type == HttpTemplate.TemplateType.MUSTACHE) {
                if (mustache == null) {
                    mustache = new MustacheTemplateEngine(new org.mockserver.logging.MockServerLogger(), configuration);
                }
                return mustache;
            }
            if (velocity == null) {
                velocity = new VelocityTemplateEngine(new org.mockserver.logging.MockServerLogger(), configuration);
            }
            return velocity;
        }

        LoadScenarioStatus snapshot(String state, long now) {
            long elapsed = now - startedAt;
            int currentVus = "running".equals(state) ? targetVUs.get() : 0;
            return new LoadScenarioStatus(
                scenario.getName(),
                state,
                elapsed,
                currentVus,
                requestsSent.get(),
                succeeded.get(),
                failed.get(),
                percentile(50),
                percentile(95),
                percentile(99),
                Integer.toHexString(System.identityHashCode(this)),
                startedAtEpoch,
                "running".equals(state) ? null : now
            );
        }
    }

    /** Immutable snapshot of a load scenario's progress, serialized by the GET endpoint. */
    public static final class LoadScenarioStatus {
        public final String name;
        public final String state;
        public final long elapsedMillis;
        public final int currentVus;
        public final long requestsSent;
        public final long succeeded;
        public final long failed;
        public final long p50Millis;
        public final long p95Millis;
        public final long p99Millis;
        public final String runId;
        public final long startedAtEpochMillis;
        public final Long endedAtEpochMillis;

        public LoadScenarioStatus(String name, String state, long elapsedMillis, int currentVus,
                                  long requestsSent, long succeeded, long failed,
                                  long p50Millis, long p95Millis, long p99Millis,
                                  String runId, long startedAtEpochMillis, Long endedAtEpochMillis) {
            this.name = name;
            this.state = state;
            this.elapsedMillis = elapsedMillis;
            this.currentVus = currentVus;
            this.requestsSent = requestsSent;
            this.succeeded = succeeded;
            this.failed = failed;
            this.p50Millis = p50Millis;
            this.p95Millis = p95Millis;
            this.p99Millis = p99Millis;
            this.runId = runId;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.endedAtEpochMillis = endedAtEpochMillis;
        }
    }
}
