package org.mockserver.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.http.ChaosAutoHaltMonitor;
import org.mockserver.mock.action.http.ForwardCircuitBreaker;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.model.Action;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXCEPTION;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "FieldMayBeFinal"})
public class Metrics {

    private static final AtomicReference<Boolean> additionalMetricsRegistered = new AtomicReference<>(false);
    // Guards the registration block in the constructor and resetAdditionalMetricsForTesting()
    // so they cannot interleave. Without this, concurrent test classes that reset-then-register
    // race on PrometheusRegistry.defaultRegistry — one thread's clear() can land in the middle
    // of another thread's registration, causing "duplicate metric name" errors.
    private static final Object registrationLock = new Object();
    private static final Map<Name, Gauge> metrics = new ConcurrentHashMap<>();
    // Request-latency histogram. Null until metrics are enabled, so
    // observeRequestDurationSeconds() is a no-op when metrics are off (the
    // caller on the request hot path pays nothing — see the Part A/C tension).
    private static volatile Histogram requestDurationSeconds;
    // Per-route (method-labeled) histogram, registered only when route labels are enabled.
    private static volatile Histogram requestDurationByMethodSeconds;
    // Counter for slow forwarded requests. Null until metrics are enabled.
    private static volatile Counter slowRequestTotal;
    // Counter for log events dropped because the event-log disruptor ring buffer was full.
    // Null until metrics are enabled. Makes the (previously silent for INFO/DEBUG) ring-buffer
    // saturation cliff observable under load.
    private static volatile Counter droppedLogEventsTotal;
    // Per-upstream forwarded-request observability. Histogram of forward/proxy
    // latency labeled by upstream host, plus a count labeled by host + status
    // class. Both null until metrics are enabled. Cardinality is bounded by the
    // number of distinct upstream hosts forwarded to (NOT the full URL/path).
    private static volatile Histogram forwardRequestDurationSeconds;
    private static volatile Counter forwardRequestsTotal;
    // Counter for HTTP chaos faults injected (error or latency). Null until metrics are enabled.
    private static volatile Counter httpChaosInjectedTotal;
    // Counter for chaos auto-halt events. Null until metrics are enabled.
    private static volatile Counter chaosAutoHaltTotal;
    // Counter for MCP tool calls, labeled by tool name. Null until metrics are enabled.
    private static volatile Counter mcpToolCallsTotal;
    // Counters for async/broker messages published to and consumed from a broker,
    // labeled by channel. Null until metrics are enabled. Incremented by the
    // mockserver-async module (publish path + subscriber record path).
    private static volatile Counter asyncMessagesPublishedTotal;
    private static volatile Counter asyncMessagesConsumedTotal;
    // LLM token and cost counters, labeled by provider and model.
    // Null until metrics are enabled AND llmMetricsEnabled is true.
    private static volatile Counter llmInputTokensTotal;
    private static volatile Counter llmOutputTokensTotal;
    private static volatile Counter llmCostUsdTotal;
    // Counter for LLM cost-budget circuit-breaker trips. Null until metrics are enabled.
    private static volatile Counter llmCostBudgetTrippedTotal;
    // Opt-in per-expectation match counter, labeled by the stable expectation id.
    // Null unless metrics are enabled AND configuration.perExpectationMetricsEnabled()
    // is true. OFF by default because per-expectation labels can explode
    // Prometheus cardinality: one time series per distinct expectation id. Using the
    // stable expectation id (not the request path) bounds cardinality to the number of
    // expectations, but operators with very large or churning expectation sets should
    // leave this off. See docs/code/metrics.md.
    private static volatile Counter expectationMatchedTotal;
    // Supplier of active expectations, set by HttpState at startup so the
    // expectations-by-type GaugeWithCallback can read live state at scrape time
    // without a core->netty dependency.
    private static final AtomicReference<Supplier<List<Expectation>>> activeExpectationsSupplier = new AtomicReference<>();
    // Supplier of the current cluster member count, set by HttpState at startup
    // so the cluster_members GaugeWithCallback can read live membership at scrape
    // time from the StateBackend without Metrics depending on the state package.
    // Defaults to 1 (single local node) until a supplier is registered.
    private static final AtomicReference<Supplier<Integer>> clusterMemberCountSupplier = new AtomicReference<>();
    // OTel histogram for OTLP export. Set by OtelMetricsExporter when enabled; null otherwise.
    private static volatile io.opentelemetry.api.metrics.DoubleHistogram otelRequestDurationHistogram;

    private final Boolean metricsEnabled;

    public Metrics(Configuration configuration) {
        metricsEnabled = configuration.metricsEnabled();
        if (metricsEnabled) {
            synchronized (registrationLock) {
                if (additionalMetricsRegistered.compareAndSet(false, true)) {
                    PrometheusRegistry.defaultRegistry.register(new BuildInfoCollector());
                    PrometheusRegistry.defaultRegistry.register(new JvmMetricsCollector());
                    Arrays.stream(Name.values()).forEach(Metrics::getOrCreate);
                    requestDurationSeconds = Histogram.builder()
                        .name("mock_server_request_duration_seconds")
                        .help("MockServer request handling duration in seconds")
                        .classicOnly()
                        .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                        .register();
                    slowRequestTotal = Counter.builder()
                        .name("mock_server_slow_requests")
                        .help("Total number of forwarded requests that exceeded the slow request threshold")
                        .register();
                    droppedLogEventsTotal = Counter.builder()
                        .name("mock_server_dropped_log_events")
                        .help("Total number of log events dropped because the event-log ring buffer was full")
                        .register();
                    forwardRequestDurationSeconds = Histogram.builder()
                        .name("mock_server_forward_request_duration_seconds")
                        .help("Latency of forwarded/proxied requests in seconds, labeled by upstream host")
                        .labelNames("upstream_host")
                        .classicOnly()
                        .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                        .register();
                    forwardRequestsTotal = Counter.builder()
                        .name("mock_server_forward_requests")
                        .help("Total forwarded/proxied requests by upstream host and response status class")
                        .labelNames("upstream_host", "status_class")
                        .register();
                    httpChaosInjectedTotal = Counter.builder()
                        .name("mock_server_http_chaos_injected")
                        .help("Total HTTP chaos faults injected by type")
                        .labelNames("fault_type")
                        .register();
                    chaosAutoHaltTotal = Counter.builder()
                        .name("mock_server_chaos_auto_halt")
                        .help("Total number of times the chaos auto-halt circuit-breaker triggered")
                        .register();
                    mcpToolCallsTotal = Counter.builder()
                        .name("mock_server_mcp_tool_calls")
                        .help("Total MCP tool calls by tool name")
                        .labelNames("tool")
                        .register();
                    asyncMessagesPublishedTotal = Counter.builder()
                        .name("mock_server_async_messages_published")
                        .help("Total async/broker messages published by channel")
                        .labelNames("channel")
                        .register();
                    asyncMessagesConsumedTotal = Counter.builder()
                        .name("mock_server_async_messages_consumed")
                        .help("Total async/broker messages consumed/recorded by channel")
                        .labelNames("channel")
                        .register();
                    if (Boolean.TRUE.equals(configuration.llmMetricsEnabled())) {
                        llmInputTokensTotal = Counter.builder()
                            .name("mock_server_llm_input_tokens")
                            .help("Total LLM input tokens by provider and model")
                            .labelNames("provider", "model")
                            .register();
                        llmOutputTokensTotal = Counter.builder()
                            .name("mock_server_llm_output_tokens")
                            .help("Total LLM output tokens by provider and model")
                            .labelNames("provider", "model")
                            .register();
                        llmCostUsdTotal = Counter.builder()
                            .name("mock_server_llm_cost_usd")
                            .help("Cumulative estimated LLM cost in USD by provider and model")
                            .labelNames("provider", "model")
                            .register();
                    }
                    llmCostBudgetTrippedTotal = Counter.builder()
                        .name("mock_server_llm_cost_budget_tripped")
                        .help("Total number of times the LLM cost-budget circuit-breaker tripped")
                        .register();
                    if (Boolean.TRUE.equals(configuration.perExpectationMetricsEnabled())) {
                        // Opt-in: registered only when perExpectationMetricsEnabled is true so
                        // the default scrape is byte-for-byte identical to today (no new series).
                        expectationMatchedTotal = Counter.builder()
                            .name("mock_server_expectation_matched")
                            .help("Total expectation matches (matched and responded) labeled by stable expectation id")
                            .labelNames("expectation_id")
                            .register();
                    }
                    // Callback gauge, labeled by action_type: read active expectations at
                    // scrape time and group by action type, so no imperative tracking is needed.
                    GaugeWithCallback.builder()
                        .name("mock_server_expectations_by_type")
                        .help("Number of active expectations grouped by action type")
                        .labelNames("action_type")
                        .callback(callback ->
                            getActiveExpectationCountByType().forEach((actionType, count) ->
                                callback.call(count, actionType)))
                        .register();
                    // Callback gauge, labeled by fault_type: read the live registry at scrape
                    // time rather than tracking it imperatively, so TTL auto-revert (which
                    // removes a profile without a put/remove call) is reflected without any
                    // extra plumbing. One series per fault type so it can be charted by type.
                    GaugeWithCallback.builder()
                        .name("mock_server_active_service_chaos")
                        .help("Number of active service-scoped chaos profiles configured with each fault type")
                        .labelNames("fault_type")
                        .callback(callback ->
                            getActiveServiceChaosCountByFaultType().forEach((faultType, count) ->
                                callback.call(count, faultType)))
                        .register();
                    // Callback gauge: report the live cluster member count at scrape
                    // time. For a single-node / in-memory deployment this is 1; for a
                    // clustered backend it reflects the current fleet size read from
                    // the StateBackend via the registered supplier.
                    GaugeWithCallback.builder()
                        .name("mock_server_cluster_members")
                        .help("Number of members in the MockServer cluster (1 for a single-node deployment)")
                        .callback(callback -> callback.call(getClusterMemberCount()))
                        .register();
                    // Callback gauge: number of upstreams whose forward/proxy circuit breaker is
                    // currently open. Read live at scrape time so half-open recovery is reflected
                    // without imperative plumbing. Always 0 when the breaker is disabled.
                    GaugeWithCallback.builder()
                        .name("mock_server_upstream_circuit_open")
                        .help("Number of upstreams whose forward/proxy circuit breaker is currently open")
                        .callback(callback -> callback.call(getOpenUpstreamCircuitCount()))
                        .register();
                    if (Boolean.TRUE.equals(configuration.metricsRequestDurationRouteLabels())) {
                        requestDurationByMethodSeconds = Histogram.builder()
                            .name("mock_server_request_duration_by_method_seconds")
                            .help("MockServer request handling duration in seconds, labeled by HTTP method")
                            .labelNames("method")
                            .classicOnly()
                            .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                            .register();
                    }
                }
            }
        }
    }

    private static Gauge getOrCreate(Name name) {
        synchronized (name) {
            Gauge gauge = metrics.get(name);
            if (gauge == null) {
                try {
                    gauge = Gauge.builder()
                        .name(name.name().toLowerCase())
                        .help(name.description)
                        .register();
                    metrics.put(name, gauge);
                } catch (Throwable throwable) {
                    new MockServerLogger().logEvent(
                        new LogEntry()
                            .setType(EXCEPTION)
                            .setMessageFormat("exception:{} creating metric:{}")
                            .setArguments(throwable.getMessage(), name.name())
                            .setThrowable(throwable)
                    );
                }
            }
            return gauge;
        }
    }

    /**
     * Reset the one-shot registration guard and null all lazily-registered
     * metrics so that a subsequent {@code new Metrics(configuration)} call
     * re-registers them.  Also clears the default Prometheus registry.
     * <p>
     * Public for cross-package test access (e.g. chaos injection tests);
     * intended for test use only to guarantee deterministic test ordering.
     */
    public static void resetAdditionalMetricsForTesting() {
        synchronized (registrationLock) {
            additionalMetricsRegistered.set(false);
            requestDurationSeconds = null;
            requestDurationByMethodSeconds = null;
            slowRequestTotal = null;
            droppedLogEventsTotal = null;
            forwardRequestDurationSeconds = null;
            forwardRequestsTotal = null;
            httpChaosInjectedTotal = null;
            chaosAutoHaltTotal = null;
            mcpToolCallsTotal = null;
            asyncMessagesPublishedTotal = null;
            asyncMessagesConsumedTotal = null;
            llmInputTokensTotal = null;
            llmOutputTokensTotal = null;
            llmCostUsdTotal = null;
            llmCostBudgetTrippedTotal = null;
            expectationMatchedTotal = null;
            otelRequestDurationHistogram = null;
            activeExpectationsSupplier.set(null);
            clusterMemberCountSupplier.set(null);
            metrics.clear();
            PrometheusRegistry.defaultRegistry.clear();
        }
    }

    public static void clear() {
        metrics.forEach((name, gauge) -> gauge.set(0));
    }

    public static void clear(Name name) {
        getOrCreate(name).set(0);
    }

    public void set(Name name, Integer value) {
        if (metricsEnabled) {
            getOrCreate(name).set(value);
        }
    }

    public static Integer get(Name name) {
        return (int) getOrCreate(name).get();
    }

    /**
     * Register an OTel histogram for request duration. Called by
     * {@link OtelMetricsExporter} when OTLP export is enabled.
     */
    public static void registerOtelRequestDurationHistogram(io.opentelemetry.api.metrics.DoubleHistogram histogram) {
        otelRequestDurationHistogram = histogram;
    }

    /**
     * Record a request-handling duration (seconds) in the latency histogram.
     * No-op unless metrics are enabled (the histogram is null until then), so a
     * caller on the request hot path pays nothing when metrics are off.
     */
    public static void observeRequestDurationSeconds(double seconds) {
        Histogram histogram = requestDurationSeconds;
        if (histogram != null) {
            histogram.observe(seconds);
        }
        io.opentelemetry.api.metrics.DoubleHistogram otelHistogram = otelRequestDurationHistogram;
        if (otelHistogram != null) {
            otelHistogram.record(seconds);
        }
    }

    /**
     * Record a request-handling duration (seconds) in the per-method labeled histogram.
     * No-op unless route labels are enabled.
     *
     * @param seconds  duration in seconds
     * @param method   the HTTP method (e.g. "GET", "POST")
     */
    public static void observeRequestDurationByMethodSeconds(double seconds, String method) {
        Histogram histogram = requestDurationByMethodSeconds;
        if (histogram != null && method != null) {
            histogram.labelValues(method.toUpperCase()).observe(seconds);
        }
    }

    /**
     * Return the current slow-request count, or 0 if metrics are disabled.
     * Used by {@link OtelMetricsExporter} to mirror the Prometheus counter via OTLP.
     */
    public static long getSlowRequestCount() {
        Counter counter = slowRequestTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the slow request counter. No-op unless metrics are enabled.
     */
    public static void incrementSlowRequestTotal() {
        Counter counter = slowRequestTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Increment the dropped-log-events counter (event-log ring buffer full).
     * No-op unless metrics are enabled (the counter is null otherwise). The
     * authoritative, always-available count is maintained on
     * {@link org.mockserver.log.MockServerEventLog#getDroppedLogEventCount()};
     * this mirrors it to Prometheus when metrics are on.
     */
    public static void incrementDroppedLogEvents() {
        Counter counter = droppedLogEventsTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current dropped-log-events count, or 0 if metrics are disabled.
     */
    public static long getDroppedLogEventCount() {
        Counter counter = droppedLogEventsTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Record observability for a single forwarded/proxied request: its latency
     * (seconds) in the per-upstream histogram and a count in the per-upstream,
     * per-status-class counter. No-op unless metrics are enabled (both metrics
     * are null until then), so the forward path pays nothing when metrics are off.
     * <p>
     * Cardinality is bounded by the number of distinct upstream hosts (the
     * {@code upstream_host} label is the host only — never the full URL/path) and
     * the five status classes ({@code 1xx}..{@code 5xx}). A null/blank host is
     * recorded as {@code "unknown"}.
     *
     * @param upstreamHost the resolved upstream host (no port, no path)
     * @param statusCode   the upstream response status code, or null if unknown
     * @param latencySeconds the forward latency in seconds (negative values are clamped to 0)
     */
    public static void observeForwardRequest(String upstreamHost, Integer statusCode, double latencySeconds) {
        String hostLabel = upstreamHost != null && !upstreamHost.isEmpty() ? upstreamHost : "unknown";
        Histogram histogram = forwardRequestDurationSeconds;
        if (histogram != null) {
            histogram.labelValues(hostLabel).observe(latencySeconds > 0 ? latencySeconds : 0);
        }
        Counter counter = forwardRequestsTotal;
        if (counter != null) {
            counter.labelValues(hostLabel, statusClass(statusCode)).inc();
        }
    }

    /**
     * Map an HTTP status code to its status class label ({@code "1xx"}..{@code "5xx"}).
     * Unknown/out-of-range codes are recorded as {@code "unknown"} so the
     * {@code status_class} label has a small, bounded value set.
     */
    private static String statusClass(Integer statusCode) {
        if (statusCode == null || statusCode < 100 || statusCode >= 600) {
            return "unknown";
        }
        return (statusCode / 100) + "xx";
    }

    /**
     * Return the current forward-request count for the given upstream host and
     * status class, or 0 if metrics are disabled.
     */
    public static long getForwardRequestCount(String upstreamHost, String statusClass) {
        Counter counter = forwardRequestsTotal;
        if (counter == null || upstreamHost == null || statusClass == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(upstreamHost, statusClass).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return true if the per-upstream forward metrics are registered (i.e. metrics are enabled).
     */
    public static boolean isForwardMetricsActive() {
        return forwardRequestsTotal != null;
    }

    /**
     * Return the current chaos-injected count for the given fault type, or 0 if
     * metrics are disabled. Used by {@link OtelMetricsExporter} to mirror the
     * Prometheus counter via OTLP.
     */
    public static long getHttpChaosInjectedCount(String faultType) {
        Counter counter = httpChaosInjectedTotal;
        if (counter == null || faultType == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(faultType).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Increment the HTTP chaos injected counter for the given fault type.
     * No-op when metrics are disabled (counter not registered) or faultType is null.
     *
     * @param faultType one of "drop", "error", "latency", "truncate", "malformed", "slow", "quota", "graphql", or "rateLimit"
     */
    public static void incrementHttpChaosInjected(String faultType) {
        Counter counter = httpChaosInjectedTotal;
        if (counter != null && faultType != null) {
            counter.labelValues(faultType).inc();
        }
        // Evaluate the chaos auto-halt circuit-breaker on every chaos fault injection.
        // ChaosAutoHaltMonitor.recordError() is a no-op when the feature is disabled
        // or when the fault type is non-destructive (e.g. latency, slow).
        ChaosAutoHaltMonitor.getInstance().recordError(faultType);
    }

    /**
     * Increment the chaos auto-halt counter. Called by
     * {@link ChaosAutoHaltMonitor} when the circuit-breaker triggers.
     * No-op when metrics are disabled (counter not registered).
     */
    public static void incrementChaosAutoHalt() {
        Counter counter = chaosAutoHaltTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current chaos auto-halt count, or 0 if metrics are disabled.
     */
    public static long getChaosAutoHaltCount() {
        Counter counter = chaosAutoHaltTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the MCP tool call counter for the given tool name.
     * No-op when metrics are disabled (counter not registered) or toolName is null.
     * Counts each completed tool invocation (called after the tool handler returns).
     *
     * @param toolName the name of the MCP tool invoked (from the bounded tool registry)
     */
    public static void incrementMcpToolCall(String toolName) {
        Counter counter = mcpToolCallsTotal;
        if (counter != null && toolName != null) {
            counter.labelValues(toolName).inc();
        }
    }

    /**
     * Return the current MCP tool call count for the given tool name, or 0 if
     * metrics are disabled.
     */
    public static long getMcpToolCallCount(String toolName) {
        Counter counter = mcpToolCallsTotal;
        if (counter == null || toolName == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(toolName).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Increment the async-messages-published counter for the given channel.
     * No-op when metrics are disabled (counter not registered) or channel is null.
     * Called by the mockserver-async publish path (one increment per message published to a broker).
     *
     * @param channel the broker channel/topic the message was published to
     */
    public static void incrementAsyncMessagePublished(String channel) {
        Counter counter = asyncMessagesPublishedTotal;
        if (counter != null && channel != null) {
            counter.labelValues(channel).inc();
        }
    }

    /**
     * Increment the async-messages-consumed counter for the given channel.
     * No-op when metrics are disabled (counter not registered) or channel is null.
     * Called by the mockserver-async subscriber record path (one increment per message recorded from a broker).
     *
     * @param channel the broker channel/topic the message was consumed from
     */
    public static void incrementAsyncMessageConsumed(String channel) {
        Counter counter = asyncMessagesConsumedTotal;
        if (counter != null && channel != null) {
            counter.labelValues(channel).inc();
        }
    }

    /**
     * Return the current async-messages-published count for the given channel, or 0 if
     * metrics are disabled.
     */
    public static long getAsyncMessagePublishedCount(String channel) {
        Counter counter = asyncMessagesPublishedTotal;
        if (counter == null || channel == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(channel).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return the current async-messages-consumed count for the given channel, or 0 if
     * metrics are disabled.
     */
    public static long getAsyncMessageConsumedCount(String channel) {
        Counter counter = asyncMessagesConsumedTotal;
        if (counter == null || channel == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(channel).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Increment the LLM token and cost counters for a served or forwarded completion.
     * No-op when LLM metrics are not registered (llmMetricsEnabled is false or metrics are disabled).
     * Fail-soft: a null or unresolvable provider/model is recorded as "unknown".
     *
     * @param provider     the LLM provider name (e.g. "ANTHROPIC", "OPENAI")
     * @param model        the model name (e.g. "claude-opus-4")
     * @param inputTokens  number of input tokens (may be 0)
     * @param outputTokens number of output tokens (may be 0)
     * @param costUsd      estimated cost in USD (may be 0.0; null means unknown and is skipped)
     */
    public static void incrementLlmTokens(String provider, String model, long inputTokens, long outputTokens, Double costUsd) {
        String providerLabel = provider != null && !provider.isEmpty() ? provider.toLowerCase() : "unknown";
        String modelLabel = model != null && !model.isEmpty() ? model : "unknown";
        Counter inputCounter = llmInputTokensTotal;
        if (inputCounter != null && inputTokens > 0) {
            inputCounter.labelValues(providerLabel, modelLabel).inc(inputTokens);
        }
        Counter outputCounter = llmOutputTokensTotal;
        if (outputCounter != null && outputTokens > 0) {
            outputCounter.labelValues(providerLabel, modelLabel).inc(outputTokens);
        }
        Counter costCounter = llmCostUsdTotal;
        if (costCounter != null && costUsd != null && costUsd > 0.0) {
            costCounter.labelValues(providerLabel, modelLabel).inc(costUsd);
        }
    }

    /**
     * Return the current LLM input token count for the given provider and model, or 0 if
     * LLM metrics are disabled.
     */
    public static long getLlmInputTokens(String provider, String model) {
        Counter counter = llmInputTokensTotal;
        if (counter == null || provider == null || model == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(provider.toLowerCase(), model).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return the current LLM output token count for the given provider and model, or 0 if
     * LLM metrics are disabled.
     */
    public static long getLlmOutputTokens(String provider, String model) {
        Counter counter = llmOutputTokensTotal;
        if (counter == null || provider == null || model == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(provider.toLowerCase(), model).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return the current cumulative LLM cost in USD for the given provider and model, or 0.0 if
     * LLM metrics are disabled.
     */
    public static double getLlmCostUsd(String provider, String model) {
        Counter counter = llmCostUsdTotal;
        if (counter == null || provider == null || model == null) {
            return 0.0;
        }
        try {
            return counter.labelValues(provider.toLowerCase(), model).get();
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Return the aggregate cumulative LLM cost in USD across all providers and models.
     * Used by the cost-budget circuit-breaker. Returns 0.0 if LLM metrics are disabled.
     */
    public static double getLlmCostUsdTotal() {
        Counter counter = llmCostUsdTotal;
        if (counter == null) {
            return 0.0;
        }
        try {
            // Sum across all label combinations by iterating the data points
            final double[] total = {0.0};
            counter.collect().getDataPoints().forEach(dp -> total[0] += dp.getValue());
            return total[0];
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Return true if LLM token/cost counters are registered (i.e. both metricsEnabled
     * and llmMetricsEnabled are true).
     */
    public static boolean isLlmMetricsActive() {
        return llmInputTokensTotal != null;
    }

    /**
     * Increment the LLM cost-budget circuit-breaker tripped counter.
     * No-op when metrics are disabled (counter not registered).
     */
    public static void incrementLlmCostBudgetTripped() {
        Counter counter = llmCostBudgetTrippedTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current LLM cost-budget tripped count, or 0 if metrics are disabled.
     */
    public static long getLlmCostBudgetTrippedCount() {
        Counter counter = llmCostBudgetTrippedTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the per-expectation match counter for the given stable expectation id.
     * <p>
     * No-op unless per-expectation metrics are enabled
     * ({@link Configuration#perExpectationMetricsEnabled()}) AND metrics are enabled
     * (the counter is null otherwise), or when
     * {@code expectationId} is null. Labeled by the stable expectation id (not the
     * request path) so cardinality is bounded by the number of distinct expectations.
     *
     * @param expectationId the stable id of the matched expectation
     */
    public static void incrementExpectationMatched(String expectationId) {
        Counter counter = expectationMatchedTotal;
        if (counter != null && expectationId != null) {
            counter.labelValues(expectationId).inc();
        }
    }

    /**
     * Return the current per-expectation match count for the given expectation id,
     * or 0 if the per-expectation counter is not registered.
     */
    public static long getExpectationMatchedCount(String expectationId) {
        Counter counter = expectationMatchedTotal;
        if (counter == null || expectationId == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(expectationId).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return true if the per-expectation match counter is registered (i.e. both
     * metricsEnabled and perExpectationMetricsEnabled are on).
     */
    public static boolean isPerExpectationMetricsActive() {
        return expectationMatchedTotal != null;
    }

    /**
     * Set the supplier of active expectations. Called by HttpState at startup
     * so the expectations-by-type GaugeWithCallback can read live state at
     * scrape time without a core-to-netty dependency.
     *
     * @param supplier returns the list of currently-active expectations
     */
    public static void setActiveExpectationsSupplier(Supplier<List<Expectation>> supplier) {
        activeExpectationsSupplier.set(supplier);
    }

    /**
     * Per-action-type count of currently-active expectations.
     * Backs the {@code mock_server_expectations_by_type} Prometheus gauge;
     * reads the live expectation list at scrape time via the registered supplier.
     */
    public static Map<String, Integer> getActiveExpectationCountByType() {
        Map<String, Integer> counts = new HashMap<>();
        Supplier<List<Expectation>> supplier = activeExpectationsSupplier.get();
        if (supplier != null) {
            try {
                List<Expectation> expectations = supplier.get();
                if (expectations != null) {
                    for (Expectation expectation : expectations) {
                        Action<?> action = expectation.getAction();
                        if (action != null) {
                            String typeName = action.getType().name();
                            counts.merge(typeName, 1, Integer::sum);
                        }
                    }
                }
            } catch (Exception ignored) {
                // fail-soft: return whatever has been accumulated
            }
        }
        return counts;
    }

    /**
     * Per-fault-type count of currently-active service-scoped chaos profiles.
     * Backs the {@code mock_server_active_service_chaos} Prometheus gauge and its
     * OTLP mirror; reads the registry directly (not gated on {@code metricsEnabled},
     * the gauge is only registered when metrics are on).
     */
    public static Map<String, Integer> getActiveServiceChaosCountByFaultType() {
        return ServiceChaosRegistry.getInstance().activeCountByFaultType();
    }

    /**
     * Set the supplier of the current cluster member count. Called by HttpState
     * at startup so the {@code mock_server_cluster_members} GaugeWithCallback can
     * read live membership from the StateBackend at scrape time without Metrics
     * depending on the state package.
     *
     * @param supplier returns the current number of cluster members
     */
    public static void setClusterMemberCountSupplier(Supplier<Integer> supplier) {
        clusterMemberCountSupplier.set(supplier);
    }

    /**
     * Current cluster member count, backing the {@code mock_server_cluster_members}
     * gauge. Returns 1 (single local node) when no supplier is registered or the
     * supplier fails/returns a non-positive value.
     */
    public static int getClusterMemberCount() {
        Supplier<Integer> supplier = clusterMemberCountSupplier.get();
        if (supplier != null) {
            try {
                Integer count = supplier.get();
                if (count != null && count > 0) {
                    return count;
                }
            } catch (Exception ignored) {
                // fail-soft: fall through to the single-node default
            }
        }
        return 1;
    }

    /**
     * Number of upstreams whose forward/proxy circuit breaker is currently open, backing the
     * {@code mock_server_upstream_circuit_open} gauge. Reads the live {@link ForwardCircuitBreaker}
     * registry at scrape time; returns 0 when the breaker is disabled or no upstream is open.
     */
    public static int getOpenUpstreamCircuitCount() {
        try {
            return ForwardCircuitBreaker.getInstance().openCircuitCount();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public void increment(Name name) {
        if (metricsEnabled) {
            getOrCreate(name).inc();
        }
    }

    public void increment(Action.Type type) {
        if (metricsEnabled) {
            increment(Name.valueOf(type.name() + "_ACTIONS_COUNT"));
        }
    }

    public void decrement(Name name) {
        if (metricsEnabled) {
            getOrCreate(name).dec();
        }
    }

    public void decrement(Action.Type type) {
        if (metricsEnabled) {
            decrement(Name.valueOf(type.name() + "_ACTIONS_COUNT"));
        }
    }

    public static void clearRequestAndExpectationMetrics() {
        clear(Name.REQUESTS_RECEIVED_COUNT);
        clear(Name.EXPECTATIONS_NOT_MATCHED_COUNT);
        clear(Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT);
    }

    public static void clearActionMetrics() {
        clear(Name.FORWARD_ACTIONS_COUNT);
        clear(Name.FORWARD_TEMPLATE_ACTIONS_COUNT);
        clear(Name.FORWARD_CLASS_CALLBACK_ACTIONS_COUNT);
        clear(Name.FORWARD_OBJECT_CALLBACK_ACTIONS_COUNT);
        clear(Name.FORWARD_REPLACE_ACTIONS_COUNT);
        clear(Name.RESPONSE_ACTIONS_COUNT);
        clear(Name.RESPONSE_TEMPLATE_ACTIONS_COUNT);
        clear(Name.RESPONSE_CLASS_CALLBACK_ACTIONS_COUNT);
        clear(Name.RESPONSE_OBJECT_CALLBACK_ACTIONS_COUNT);
        clear(Name.SSE_RESPONSE_ACTIONS_COUNT);
        clear(Name.LLM_RESPONSE_ACTIONS_COUNT);
        clear(Name.LLM_CHAOS_INJECTED_COUNT);
        clear(Name.WEBSOCKET_RESPONSE_ACTIONS_COUNT);
        clear(Name.GRPC_STREAM_RESPONSE_ACTIONS_COUNT);
        clear(Name.BINARY_RESPONSE_ACTIONS_COUNT);
        clear(Name.DNS_RESPONSE_ACTIONS_COUNT);
        clear(Name.ERROR_ACTIONS_COUNT);
    }

    public static void clearWebSocketMetrics() {
        clear(Name.WEBSOCKET_CALLBACK_CLIENTS_COUNT);
        clear(Name.WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT);
        clear(Name.WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT);
    }

    public enum Name {
        REQUESTS_RECEIVED_COUNT("Expectation not matched count"),
        EXPECTATIONS_NOT_MATCHED_COUNT("Expectation not matched count"),
        RESPONSE_EXPECTATIONS_MATCHED_COUNT("Response expectation matched count"),
        FORWARD_EXPECTATIONS_MATCHED_COUNT("Forward expectation matched count"),
        FORWARD_ACTIONS_COUNT("Action forward count"),
        FORWARD_TEMPLATE_ACTIONS_COUNT("Action forward template count"),
        FORWARD_CLASS_CALLBACK_ACTIONS_COUNT("Action forward class callback count"),
        FORWARD_OBJECT_CALLBACK_ACTIONS_COUNT("Action forward object callback count"),
        FORWARD_REPLACE_ACTIONS_COUNT("Action forward replace count"),
        RESPONSE_ACTIONS_COUNT("Action response count"),
        RESPONSE_TEMPLATE_ACTIONS_COUNT("Action response template count"),
        RESPONSE_CLASS_CALLBACK_ACTIONS_COUNT("Action response class callback count"),
        RESPONSE_OBJECT_CALLBACK_ACTIONS_COUNT("Action response object callback count"),
        SSE_RESPONSE_ACTIONS_COUNT("Action SSE response count"),
        LLM_RESPONSE_ACTIONS_COUNT("Action LLM response count"),
        LLM_CHAOS_INJECTED_COUNT("Action LLM chaos injected count"),
        WEBSOCKET_RESPONSE_ACTIONS_COUNT("Action WebSocket response count"),
        GRPC_STREAM_RESPONSE_ACTIONS_COUNT("Action gRPC stream response count"),
        BINARY_RESPONSE_ACTIONS_COUNT("Action binary response count"),
        DNS_RESPONSE_ACTIONS_COUNT("Action DNS response count"),
        ERROR_ACTIONS_COUNT("Action error count"),
        WEBSOCKET_CALLBACK_CLIENTS_COUNT("Websocket callback client count"),
        WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT("Websocket callback response handler count"),
        WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT("Websocket callback forward handler count");

        public final String description;

        Name(String description) {
            this.description = description;
        }
    }
}
