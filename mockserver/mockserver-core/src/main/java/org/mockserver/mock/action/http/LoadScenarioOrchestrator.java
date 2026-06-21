package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.load.IterationContext;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.metrics.MetricLabels;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.slo.Scope;
import org.mockserver.slo.SloSampleStore;
import org.mockserver.telemetry.W3CTraceContext;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    /** Most recently started run, retained for test assertions on slot accounting after termination. */
    private volatile RunningScenario lastRun;
    /**
     * The {@code run_id} of the previous (now-completed or replaced) run, whose durable
     * {@code mock_server_load_*} series are retained until the next run starts and then evicted. Bounds
     * the accumulation of per-run series to at most one completed run (see {@link Metrics#evictLoadRun}).
     */
    private volatile String previousRunId;
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
        lastRun = running;
        current.set(running);

        // Bound durable-metric accumulation: evict the PREVIOUS run's mock_server_load_* series now
        // that a new run is starting. The just-completed (or replaced) run's final totals stayed
        // scrapeable until this point; from here only the new run's series accumulate, so at most one
        // completed run's series are ever retained (the prior run's UUID run_id labels would otherwise
        // persist in the Prometheus registry forever). Roll previous forward to the new run.
        if (previousRunId != null && !previousRunId.equals(running.runId)) {
            Metrics.evictLoadRun(previousRunId);
        }
        previousRunId = running.runId;

        // Install live readers for the active-VU and in-flight load gauges (scrape-time, mirrors the
        // chaos GaugeWithCallback). A run that has ended reports nothing because current is cleared.
        Metrics.setLoadGaugeReaders(
            () -> {
                RunningScenario run = current.get();
                if (run == null) {
                    return Collections.emptyMap();
                }
                return Collections.singletonMap(run.gaugeKey, Math.max(0, run.activeVUs.get()));
            },
            () -> {
                RunningScenario run = current.get();
                if (run == null) {
                    return Collections.emptyMap();
                }
                return Collections.singletonMap(run.gaugeKey, Math.max(0, run.inFlightCount.get()));
            });

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
        lastRun = null;
        previousRunId = null;
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
            Metrics.setLoadGaugeReaders(null, null);
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
        // activeVUs is the LIVE population (incremented once per launch below, decremented exactly
        // once when a VU loop ends via endVu). VU ids are allocated from a separate monotonic
        // sequence so id-allocation is never conflated with the live count.
        run.targetVUs.set(target);
        int active = run.activeVUs.get();
        if (active < target) {
            // Grow: launch VU loops up to the target. Increment the live population BEFORE launching
            // so the slot is owned for the whole loop; the loop releases it exactly once via endVu.
            int toLaunch = target - active;
            for (int i = 0; i < toLaunch; i++) {
                int vuId = run.vuIdSequence.getAndIncrement();
                run.activeVUs.incrementAndGet();
                launchIteration(run, vuId, 0);
            }
        }
        // Surplus VUs retire themselves at their next iteration boundary (see launchIteration),
        // driven by the targetVUs setpoint above; no eager decrement here.
    }

    private void completeInternal(RunningScenario run) {
        if (current.compareAndSet(run, null)) {
            run.stopped.set(true);
            ScheduledFuture<?> tick = run.controlTick;
            if (tick != null) {
                tick.cancel(false);
            }
            lastTerminatedStatus = run.snapshot("completed", clock.getAsLong());
            Metrics.setLoadGaugeReaders(null, null);
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
            endVu(run);
            return;
        }
        // Retire surplus VUs at the iteration boundary: if the live population currently exceeds the
        // target concurrency, this VU's loop ends here. The retire decision is based on the live
        // population (not the VU id, since ids come from a monotonic sequence) and is applied as a
        // single atomic conditional decrement so that, when several surplus VUs reach the boundary
        // concurrently, exactly (active - target) of them retire rather than all of them collapsing
        // the population below the target.
        if (vuIteration > 0 && tryRetireSurplus(run)) {
            return;
        }
        fireStep(run, vuId, vuIteration, 0);
    }

    /**
     * Atomically retire this VU iff the live population currently exceeds the target concurrency,
     * decrementing the active-population counter exactly once on success. Returns {@code true} when
     * the VU was retired (its loop must end), {@code false} when it should continue. The
     * compare-and-set loop guarantees concurrent surplus VUs never over-retire below the target.
     */
    private boolean tryRetireSurplus(RunningScenario run) {
        while (true) {
            int active = run.activeVUs.get();
            if (active <= run.targetVUs.get()) {
                return false;
            }
            if (run.activeVUs.compareAndSet(active, active - 1)) {
                return true;
            }
        }
    }

    /**
     * End a virtual user's loop, releasing the single active-population slot it owned. Called from
     * exactly one of the loop's terminal exit points so the live counter decrements exactly once per
     * launched VU (never zero, never twice). VU-id allocation is tracked separately and is never
     * touched here.
     */
    private void endVu(RunningScenario run) {
        run.activeVUs.decrementAndGet();
    }

    private void fireStep(RunningScenario run, int vuId, long vuIteration, int stepIndex) {
        if (run.stopped.get() || current.get() != run) {
            endVu(run);
            return;
        }
        // Stop dispatching once the request cap is reached; the control tick then transitions the
        // scenario to "completed". Enforcing this here (not only on the tick) bounds the overshoot
        // to roughly the active VU count rather than a full control interval of firing. This VU's
        // loop ends here, so release its slot exactly once.
        if (run.maxRequestsReached()) {
            endVu(run);
            completeInternal(run);
            return;
        }
        List<LoadStep> steps = run.scenario.getSteps();
        if (stepIndex >= steps.size()) {
            // Iteration finished: account it and schedule the next iteration (respect pacing).
            Metrics.incrementLoadIteration(run.scenario.getName(), run.runId);
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
        final String stepLabel = run.stepLabel(step, stepIndex);
        final Map<String, String> stepCustomLabels = run.customLabelsFor(step);

        HttpRequest rendered;
        try {
            rendered = run.render(step.getRequest(), iteration);
        } catch (Exception e) {
            LOG.warn("load scenario '{}' failed to render step {} (vu {} iteration {}): {}",
                run.scenario.getName(), stepIndex, vuId, vuIteration, e.getMessage());
            run.failed.incrementAndGet();
            run.requestsSent.incrementAndGet();
            Metrics.incrementLoadError(run.scenario.getName(), run.runId, "render");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }

        // Self-load guard at dispatch: acquire an in-flight permit and an RPS token. Each skip is a
        // distinct throttle reason so an operator can see why a scenario could not reach its setpoint.
        if (!run.inFlight.tryAcquire()) {
            Metrics.incrementLoadThrottled(run.scenario.getName(), run.runId, "inflight_cap");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }
        if (!run.tryAcquireRpsToken(clock.getAsLong())) {
            run.inFlight.release();
            Metrics.incrementLoadThrottled(run.scenario.getName(), run.runId, "rate_limit");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }

        run.requestsSent.incrementAndGet();
        run.inFlightCount.incrementAndGet();
        final long startNanos = TimeService.nanoTime();
        final String host = hostOf(rendered);
        final String route = run.routeLabel(step, rendered);
        final String method = methodOf(rendered);
        final long requestBytes = bodyBytes(rendered != null ? rendered.getBodyAsRawBytes() : null);
        final String traceId = traceIdOf(rendered);
        CompletableFuture<HttpResponse> future;
        try {
            future = run.sender.apply(rendered);
        } catch (Exception e) {
            run.inFlight.release();
            run.inFlightCount.decrementAndGet();
            recordResult(run, host, stepLabel, route, method, requestBytes, traceId, stepCustomLabels, null, startNanos, true, "connection");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }
        if (future == null) {
            run.inFlight.release();
            run.inFlightCount.decrementAndGet();
            recordResult(run, host, stepLabel, route, method, requestBytes, traceId, stepCustomLabels, null, startNanos, true, "null_response");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
            return;
        }
        future.whenComplete((response, throwable) -> {
            run.inFlight.release();
            run.inFlightCount.decrementAndGet();
            boolean error = throwable != null || response == null
                || (response.getStatusCode() != null && response.getStatusCode() >= 500);
            String errorKind = classifyError(throwable, response);
            recordResult(run, host, stepLabel, route, method, requestBytes, traceId, stepCustomLabels, response, startNanos, error, errorKind);
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step);
        });
    }

    /**
     * Classify the error branch of a completed dispatch into one of the kind labels, or null when
     * the request succeeded. A {@code TimeoutException} (or a cause/message naming a timeout) maps to
     * {@code timeout}; any other throwable to {@code connection}; a null response to
     * {@code null_response}; an HTTP 5xx to {@code http_5xx}.
     */
    private static String classifyError(Throwable throwable, HttpResponse response) {
        if (throwable != null) {
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            String name = cause.getClass().getSimpleName().toLowerCase();
            String message = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
            if (name.contains("timeout") || message.contains("timeout") || message.contains("timed out")) {
                return "timeout";
            }
            return "connection";
        }
        if (response == null) {
            return "null_response";
        }
        if (response.getStatusCode() != null && response.getStatusCode() >= 500) {
            return "http_5xx";
        }
        return null;
    }

    private void scheduleNextStep(RunningScenario run, int vuId, long vuIteration, int stepIndex, LoadStep step) {
        if (run.stopped.get() || current.get() != run) {
            // The VU loop ends here (the scenario stopped or was replaced mid-iteration). This is a
            // genuine loop-exit point, so release the slot exactly once — previously this path
            // returned without decrementing, leaking the active-population slot.
            endVu(run);
            return;
        }
        long thinkMillis = step.getThinkTime() != null ? Math.max(0, step.getThinkTime().sampleValueMillis()) : 0;
        scheduler.schedule(() -> fireStep(run, vuId, vuIteration, stepIndex + 1), thinkMillis, TimeUnit.MILLISECONDS);
    }

    private void recordResult(RunningScenario run, String host, String stepLabel, String route, String method,
                              long requestBytes, String traceId, Map<String, String> customLabels,
                              HttpResponse response, long startNanos, boolean error, String errorKind) {
        long latencyMillis = Math.max(0, (TimeService.nanoTime() - startNanos) / 1_000_000L);
        if (error) {
            run.failed.incrementAndGet();
        } else {
            run.succeeded.incrementAndGet();
        }
        Integer statusCode = response != null ? response.getStatusCode() : null;
        double latencySeconds = latencyMillis / 1000.0;
        long responseBytes = bodyBytes(response != null ? response.getBodyAsRawBytes() : null);
        // Mirror the forward-path observability so load traffic still shows up on the same metrics
        // (unchanged behaviour — the load family is purely additive).
        Metrics.observeForwardRequest(host, statusCode, latencySeconds);
        // New first-class load family: scenario/run/step/route/method/status-class dimensioned.
        // Only emit while this run is still the current run: a run replaced by a new PUT has already had
        // its load series evicted, so a late-draining in-flight request must NOT resurrect them (those
        // resurrected series would otherwise be orphaned, since eviction only ever targets the prior run).
        if (current.get() == run) {
            Metrics.observeLoadRequest(run.scenario.getName(), run.runId, stepLabel, route, method, statusCode,
                latencySeconds, requestBytes, responseBytes, traceId, customLabels);
            if (error && errorKind != null) {
                Metrics.incrementLoadError(run.scenario.getName(), run.runId, errorKind);
            }
        }
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

    private static String methodOf(HttpRequest request) {
        if (request == null || request.getMethod() == null) {
            return "unknown";
        }
        String method = request.getMethod().getValue();
        return method != null && !method.isEmpty() ? method : "unknown";
    }

    private static long bodyBytes(byte[] body) {
        return body != null ? body.length : 0L;
    }

    /**
     * Extract the request's trace id (for the histogram exemplar) from a W3C {@code traceparent}
     * header on the rendered request, or null when absent/invalid.
     */
    private static String traceIdOf(HttpRequest request) {
        if (request == null) {
            return null;
        }
        String traceparent = request.getFirstHeader("traceparent");
        if (traceparent == null || traceparent.isEmpty()) {
            return null;
        }
        try {
            W3CTraceContext context = W3CTraceContext.parse(traceparent, null);
            return context != null && context.isValid() ? context.getTraceId() : null;
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Test hook: the live virtual-user population of the current run (the value the active-slot
     * accounting maintains), or {@code 0} when no scenario is running. Used to assert that the
     * active-VU count is decremented exactly once per VU and returns to zero after a scenario stops
     * or completes.
     */
    int activeVuCount() {
        RunningScenario run = current.get();
        if (run != null) {
            return run.activeVUs.get();
        }
        return 0;
    }

    /**
     * Test hook: the live virtual-user population of the most recently started run, even after it has
     * stopped or completed (so a test can verify the active-slot counter drains back to zero once all
     * scheduled VU continuations have observed the terminal state). Returns {@code 0} when no scenario
     * has ever run.
     */
    int lastRunActiveVuCount() {
        RunningScenario run = lastRun;
        return run != null ? run.activeVUs.get() : 0;
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
        /** Stable per-run id (UUID) used as the {@code run_id} metric label and exposed in the status DTO. */
        final String runId = UUID.randomUUID().toString();
        final Metrics.LoadGaugeKey gaugeKey;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        /** Live VU population: incremented once per launch, decremented exactly once when a loop ends. */
        final AtomicInteger activeVUs = new AtomicInteger(0);
        /** Live in-flight (dispatched, not-yet-completed) request count, backing the inflight gauge. */
        final AtomicInteger inFlightCount = new AtomicInteger(0);
        /** Monotonic VU-id allocator, kept separate from the live population counter. */
        final AtomicInteger vuIdSequence = new AtomicInteger(0);
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

        // Engines built lazily for rendering (Velocity/Mustache only).
        private volatile TemplateEngine velocity;
        private volatile TemplateEngine mustache;

        RunningScenario(LoadScenario scenario, Function<HttpRequest, CompletableFuture<HttpResponse>> sender, long startedAt) {
            this.scenario = scenario;
            this.sender = sender;
            this.startedAt = startedAt;
            this.gaugeKey = new Metrics.LoadGaugeKey(scenario.getName(), runId);
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

        /** The {@code step} metric label: the step's explicit name if set, else its 0-based index. */
        String stepLabel(LoadStep step, int stepIndex) {
            if (step != null && step.getName() != null && !step.getName().isBlank()) {
                return step.getName();
            }
            return Integer.toString(stepIndex);
        }

        /**
         * The low-cardinality {@code route} label. When the step has an explicit name, the name is
         * used (operator override); otherwise the rendered request path is templatised via
         * {@link MetricLabels#routeOf(String)} so id-shaped segments collapse to {@code {id}}.
         */
        String routeLabel(LoadStep step, HttpRequest rendered) {
            if (step != null && step.getName() != null && !step.getName().isBlank()) {
                return step.getName();
            }
            String path = rendered != null && rendered.getPath() != null ? rendered.getPath().getValue() : null;
            return MetricLabels.routeOf(path);
        }

        /**
         * Merge scenario-level and step-level custom labels (step keys win on conflict), or null when
         * neither is present. These become OTEL attributes and allowlisted Prometheus labels.
         */
        Map<String, String> customLabelsFor(LoadStep step) {
            Map<String, String> scenarioLabels = scenario.getLabels();
            Map<String, String> stepLabels = step != null ? step.getLabels() : null;
            boolean hasScenario = scenarioLabels != null && !scenarioLabels.isEmpty();
            boolean hasStep = stepLabels != null && !stepLabels.isEmpty();
            if (!hasScenario && !hasStep) {
                return null;
            }
            Map<String, String> merged = new LinkedHashMap<>();
            if (hasScenario) {
                merged.putAll(scenarioLabels);
            }
            if (hasStep) {
                merged.putAll(stepLabels);
            }
            return merged;
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
            // Percentiles are derived from the load histogram's buckets (bounded memory, no
            // reservoir). Any percentile is also directly queryable from the histogram in Prometheus
            // via histogram_quantile(); these DTO fields keep their identical shape.
            return new LoadScenarioStatus(
                scenario.getName(),
                state,
                elapsed,
                currentVus,
                requestsSent.get(),
                succeeded.get(),
                failed.get(),
                Metrics.loadLatencyPercentileMillis(scenario.getName(), runId, 50),
                Metrics.loadLatencyPercentileMillis(scenario.getName(), runId, 95),
                Metrics.loadLatencyPercentileMillis(scenario.getName(), runId, 99),
                runId,
                startedAtEpoch,
                "running".equals(state) ? null : now,
                scenario.getLabels() != null && !scenario.getLabels().isEmpty()
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(scenario.getLabels()))
                    : null
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
        /** Scenario-level custom annotation labels (null when none), echoed for dashboards/clients. */
        public final Map<String, String> labels;

        public LoadScenarioStatus(String name, String state, long elapsedMillis, int currentVus,
                                  long requestsSent, long succeeded, long failed,
                                  long p50Millis, long p95Millis, long p99Millis,
                                  String runId, long startedAtEpochMillis, Long endedAtEpochMillis,
                                  Map<String, String> labels) {
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
            this.labels = labels;
        }
    }
}
