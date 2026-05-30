package org.mockserver.benchmark;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.JsonBody;
import org.mockserver.scheduler.Scheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Hot-path micro-benchmark for {@link RequestMatchers#firstMatchingExpectation}.
 *
 * <p>The benchmarked request matches <em>no</em> expectation, which forces the
 * full N-matcher scan — the worst case for the per-request allocation churn the
 * Part-A optimizations target (a {@code MatchDifference} allocated per matcher,
 * the rebuilt sorted matcher list, the {@code becauseBuilder} strings). Run with
 * the GC profiler to capture bytes-allocated-per-op:
 *
 * <pre>./run.sh -prof gc MatchingBenchmark</pre>
 *
 * <p>Uses the default {@link Configuration} (metrics off, {@code
 * detailedMatchFailures} off, INFO logging off) — the common case Part A
 * optimizes. Capture {@code -prof gc} numbers here before and after each A1/A2
 * change; a reduction in {@code gc.alloc.rate.norm} is the proof an allocation
 * win is real (k6's end-to-end signal cannot isolate it).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingBenchmark {

    /** Number of expectations registered before matching (scan length). */
    @Param({"1", "10", "100", "1000"})
    public int expectationCount;

    /** Shape of the registered matchers (exercises different matcher code). */
    @Param({"EXACT", "REGEX", "JSON_BODY"})
    public String matcherType;

    /**
     * Server log level. INFO is the default; WARN models a performance-tuned
     * deployment (logging reduced below INFO). The per-field "because" string
     * building on the match path is gated only by !controlPlaneMatcher today, so
     * it runs — and is discarded — at WARN too; this param exposes that.
     */
    @Param({"INFO", "WARN"})
    public String logLevel;

    private RequestMatchers requestMatchers;
    private HttpRequest noMatchRequest;

    @Setup(Level.Trial)
    public void setup() {
        // model a performance-tuned deployment: detailed match reports off (the
        // default) and a configurable log level
        ConfigurationProperties.logLevel(logLevel);
        ConfigurationProperties.detailedMatchFailures(false);
        Configuration configuration = Configuration.configuration();
        requestMatchers = new RequestMatchers(
            configuration,
            new MockServerLogger(),
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        );
        for (int i = 0; i < expectationCount; i++) {
            requestMatchers.add(buildExpectation(i), API);
        }
        // matches none of the registered expectations -> full scan of all N
        noMatchRequest = request()
            .withMethod("GET")
            .withPath("/no-such-path-zzzzzz")
            .withBody("{\"unmatched\":true}");
    }

    private Expectation buildExpectation(int i) {
        switch (matcherType) {
            case "REGEX":
                return new Expectation(request().withPath("/regex/path-[a-z]+-" + i))
                    .thenRespond(response().withBody("r" + i));
            case "JSON_BODY":
                return new Expectation(request().withBody(new JsonBody("{\"id\": " + i + "}")))
                    .thenRespond(response().withBody("j" + i));
            case "EXACT":
            default:
                return new Expectation(request().withMethod("GET").withPath("/exact/path-" + i))
                    .thenRespond(response().withBody("e" + i));
        }
    }

    @Benchmark
    public Expectation firstMatchingExpectation_noMatch() {
        // returns null (no match); returning it keeps JMH from eliminating the call
        return requestMatchers.firstMatchingExpectation(noMatchRequest);
    }
}
