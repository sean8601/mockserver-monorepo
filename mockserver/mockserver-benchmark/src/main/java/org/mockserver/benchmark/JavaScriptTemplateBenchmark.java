package org.mockserver.benchmark;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockserver.model.HttpRequest.request;

/**
 * Hot-path micro-benchmark for {@link JavaScriptTemplateEngine#executeTemplate} — the GraalVM/GraalJS
 * template render path.
 *
 * <p>Targets two wins from sharing a GraalVM {@code Engine} + caching the parsed {@code Source} +
 * per-thread {@code Context} (replacing the previous fresh-Context-per-render under a global lock):
 *
 * <ul>
 *   <li><b>Allocation</b> — no per-request {@code Context} build/teardown and no per-request script
 *       parse. Run single-threaded with the GC profiler to capture {@code gc.alloc.rate.norm}
 *       (bytes/op):<pre>./run.sh -prof gc JavaScriptTemplateBenchmark.renderSingleThread</pre></li>
 *   <li><b>Throughput under contention</b> — the old {@code synchronized executeTemplate} serialised
 *       every render server-wide; each thread now renders on its own {@code Context}. The
 *       {@code renderMultiThread} method runs at {@link Threads}(8) so concurrent throughput
 *       (ops/time) is visible:<pre>./run.sh JavaScriptTemplateBenchmark.renderMultiThread</pre></li>
 * </ul>
 *
 * <p>The benchmarked template is representative: it reads per-request data (method, path, body, a
 * header) and a dynamic built-in ({@code uuid}), then returns a small response object — the shape a
 * typical {@code httpResponseTemplate} JavaScript template has. The same engine instance is shared
 * across iterations/threads (as in the server), so the Source cache is warm after the first render.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JavaScriptTemplateBenchmark {

    private static final String TEMPLATE =
        "return {" +
        "    'statusCode': 200," +
        "    'headers': { 'X-Echo-Path': [ request.path ], 'X-Request-Id': [ uuid ] }," +
        "    'body': '{\"method\":\"' + request.method + '\",\"path\":\"' + request.path +" +
        "             '\",\"host\":\"' + request.headers.host[0] + '\",\"body\":\"' + request.body + '\"}'" +
        "};";

    private JavaScriptTemplateEngine engine;

    @Setup(Level.Trial)
    public void setup() {
        // default Configuration; INFO logging is off on a MockServerLogger() with no enabled levels, so
        // the per-render TEMPLATE_GENERATED log event is skipped — the common performance-tuned case.
        engine = new JavaScriptTemplateEngine(new MockServerLogger(), Configuration.configuration());
    }

    /** Per-thread request so concurrent renders carry distinct per-request data (no shared mutable input). */
    @State(Scope.Thread)
    public static class RequestState {
        private static final AtomicInteger SEQ = new AtomicInteger();
        HttpRequest request;

        @Setup(Level.Trial)
        public void init() {
            int n = SEQ.getAndIncrement();
            // lowercase "host" matches how JavaScriptTemplateEngineTest reads request.headers.host[0]
            // (header names are exposed lowercased to the template scope)
            request = request()
                .withMethod("POST")
                .withPath("/order/" + n)
                .withHeader("host", "mock-server.com")
                .withBody("payload-" + n);
        }
    }

    /** Single-thread render — run with {@code -prof gc} to measure bytes/op (the allocation win). */
    @Benchmark
    @Threads(1)
    public Object renderSingleThread(RequestState state) {
        return engine.executeTemplate(TEMPLATE, state.request, HttpResponseDTO.class);
    }

    /** 8-thread render on ONE shared engine — measures concurrent throughput (the dropped-lock win). */
    @Benchmark
    @Threads(8)
    public Object renderMultiThread(RequestState state) {
        return engine.executeTemplate(TEMPLATE, state.request, HttpResponseDTO.class);
    }
}
