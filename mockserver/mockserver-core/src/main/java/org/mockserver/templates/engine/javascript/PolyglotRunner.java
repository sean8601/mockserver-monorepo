package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.helpers.RequestBodyExtractionHelper;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * Holder class for the GraalVM Polyglot API. Loaded lazily by {@link JavaScriptTemplateEngine#executeTemplate}
 * only when {@code POLYGLOT_AVAILABLE} is true. Keeping the {@code org.graalvm.polyglot.*} static
 * references in a separate class ensures the standard MockServer distribution (which does not bundle
 * GraalVM) can still load {@code JavaScriptTemplateEngine} and degrade gracefully.
 *
 * <p>One {@code PolyglotRunner} is created per {@link JavaScriptTemplateEngine} instance and reused across
 * renders. The original path built and tore down a fresh GraalVM {@link Context}, re-parsed the script and
 * re-injected ~26 bindings on EVERY matched request, all under a single engine-wide {@code synchronized}
 * lock that serialised every JavaScript render across the whole server. This reuses three things instead:
 *
 * <ul>
 *   <li><b>A process-wide shared {@link Engine}</b> — thread-safe and able to share parsed/compiled
 *       JavaScript across the {@link Context}s spawned from it (the standard GraalVM scaling pattern).
 *       Built once per JVM (a single lazily-initialised static), NOT per runner: a GraalVM {@code Engine}
 *       is a heavy native resource (it pins Truffle/compiler threads and native memory) and is never
 *       closed, so building one per {@code JavaScriptTemplateEngine} instance accumulated thousands of
 *       native Engines across a long-lived process (e.g. one reused Surefire fork rendering across the
 *       whole test suite), thrashing GC and eventually killing the fork. The {@code Engine} does NOT
 *       depend on the per-instance {@code classFilter} (only each {@link Context}'s
 *       {@code allowHostClassLookup(classFilter)} does), so a single shared {@code Engine} is safe.</li>
 *   <li><b>A cached, parsed {@link Source}</b> — the {@code function handle(...){...} function serialise(...)}
 *       script is parsed into a {@link Source} at most once per distinct script string and reused on every
 *       render (mirroring the Mustache engine's compiled-template cache), keyed by the exact evaluated
 *       script so distinct templates / response-vs-request variants never collide. Bounded LRU.</li>
 *   <li><b>A per-thread {@link Context}</b> — a GraalVM {@code Context} is single-threaded (NOT safe to use
 *       from two threads at once), so each request thread gets its own {@code Context} via a
 *       {@link ThreadLocal}, all built from the shared {@code Engine}. This removes the engine-wide lock so
 *       independent renders run concurrently; the shared {@code Engine} is the only object touched by more
 *       than one thread, and {@code Engine} is documented thread-safe.</li>
 * </ul>
 *
 * <p>Output identity: a reused {@code Context} retains its JavaScript global scope between renders, so every
 * binding (including the per-execution-evaluated {@code BUILT_IN_FUNCTIONS} suppliers and the per-request
 * {@code jsonPath}/{@code xPath}/{@code iteration} values) is (re)written on EVERY render, and {@code iteration}
 * is explicitly removed when absent so a prior load-render cannot leak it into a later normal render.
 * Evaluating the cached {@code Source} each render re-defines {@code handle}/{@code serialise} fresh, so a
 * different template rendered earlier on the same thread cannot bleed through. The rendered output is therefore
 * byte-for-byte identical to the previous fresh-Context-per-render path.
 */
final class PolyglotRunner {

    // ----- parsed-Source cache (parse once, eval many) -----
    // Bound and concurrency approach mirror MustacheTemplateEngine.compiledTemplates: an access-ordered LRU
    // wrapped in Collections.synchronizedMap, keyed by the exact evaluated script string. The same script is
    // rendered on every matching request, so parsing it once and reusing the Source removes the per-request
    // parse cost. Distinct scripts can never collide (key is the content); the bound keeps memory safe if a
    // misbehaving client generates unique scripts per request. 1000 comfortably covers realistic mock configs.
    static final int PARSED_TEMPLATE_CACHE_MAX = 1000;

    // Process-wide GraalVM Engine: ONE per JVM, lazily built on first use. A GraalVM Engine is a heavy
    // native resource (Truffle/compiler threads + native memory) and is never closed for the JVM's life,
    // so it MUST be O(1) in the number of JavaScriptTemplateEngine instances, not O(instances). The Engine
    // is documented thread-safe and is explicitly designed to be shared across Contexts and threads. It does
    // NOT depend on any per-instance classFilter (the security boundary is each Context's
    // allowHostClassLookup(classFilter)), so sharing one Engine across all runners is safe. Built via the
    // initialization-on-demand holder idiom so the native Engine is only created when a JavaScript template
    // is actually rendered, and never when GraalVM is merely on the classpath.
    private static final class SharedEngineHolder {
        static final Engine INSTANCE = Engine.newBuilder()
            // suppress GraalVM's "falling back to interpreter / experimental options" warnings on stderr,
            // matching the previous Context-per-render behaviour (which never opted into those warnings).
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    }

    static Engine sharedEngine() {
        return SharedEngineHolder.INSTANCE;
    }

    private final Predicate<String> classFilter;
    private final Map<String, Source> parsedSources = Collections.synchronizedMap(new LinkedHashMap<String, Source>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) {
            return size() > PARSED_TEMPLATE_CACHE_MAX;
        }
    });

    // A GraalVM Context is single-threaded; give each request thread its own Context, all spawned from the
    // process-wide shared thread-safe Engine so they share parsed/compiled JavaScript. Built lazily on first use per thread
    // with this runner's classFilter. The classFilter predicate reads configuration.javascriptDisallowedClasses()
    // live on every host-class lookup, so a cached Context still honours runtime changes to the disallowed-class
    // list (the predicate, not its result, is what is captured).
    private final ThreadLocal<Context> threadLocalContext;

    PolyglotRunner(Predicate<String> classFilter) {
        this.classFilter = classFilter;
        this.threadLocalContext = ThreadLocal.withInitial(this::newContext);
    }

    private Context newContext() {
        // HostAccess.ALL is equivalent to the previous JSR-223 polyglot.js.allowHostAccess=true.
        // The security boundary is allowHostClassLookup(classFilter), which gates which classes
        // templates can resolve via Java.type(...). HostAccess.EXPLICIT/CONSTRAINED would narrow
        // the attack surface further but require annotating template helper classes. The Context is
        // spawned from the process-wide shared Engine but carries THIS runner's classFilter, so the
        // security boundary remains per-instance: a Context built for one runner's configuration is
        // never reused for a render under a different runner's (potentially stricter) configuration.
        return Context.newBuilder("js")
            .engine(sharedEngine())
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(classFilter)
            .build();
    }

    /**
     * Close this thread's {@link Context} (if any) and drop the {@link ThreadLocal} entry, releasing the
     * per-thread interpreter state held against this runner. The process-wide shared {@link Engine} is
     * deliberately NOT closed — it lives for the JVM and is shared by every runner.
     *
     * <p>The per-instance {@code ThreadLocal<Context>} means a long-lived thread accumulates one Context per
     * runner it ever rendered on. For runners that are short-lived (e.g. {@code ResponseTemplateTester}
     * creating a fresh {@link JavaScriptTemplateEngine} per call, or tests), failing to close would leak a
     * Context per call into every thread that touched the runner. Long-lived action-handler runners can leave
     * their Context open for the handler's life. This only closes the CURRENT thread's Context; runners that
     * outlive a single thread are expected to be few and long-lived.
     */
    void close() {
        Context context = threadLocalContext.get();
        threadLocalContext.remove();
        if (context != null) {
            try {
                context.close(true);
            } catch (RuntimeException ignored) {
                // a Context that is mid-execution or already closed must not surface from a cleanup call;
                // the ThreadLocal entry is already removed so the Context is unreferenced and collectable.
            }
        }
    }

    private Source parsedSource(String fullScript) {
        Source cached = parsedSources.get(fullScript);
        if (cached == null) {
            // Source.create is pure parsing of a string and cannot fail here (syntax is validated at eval
            // time, not create time), so caching it is safe; a benign race where two threads create the same
            // Source concurrently just discards one identical Source.
            cached = Source.create("js", fullScript);
            parsedSources.put(fullScript, cached);
        }
        return cached;
    }

    <T> T run(
        String script,
        boolean includeResponse,
        HttpRequest request,
        HttpResponse response,
        org.mockserver.load.IterationContext iteration,
        ObjectMapper objectMapper,
        MockServerLogger mockServerLogger,
        HttpTemplateOutputDeserializer httpTemplateOutputDeserializer,
        Class<? extends DTO<T>> dtoClass
    ) {
        String serialiseFunction = includeResponse
            ? " function serialise(request, response) { return JSON.stringify(handle(JSON.parse(request), JSON.parse(response)), null, 2); }"
            : " function serialise(request) { return JSON.stringify(handle(JSON.parse(request)), null, 2); }";
        String fullScript = script + serialiseFunction;

        Context context = threadLocalContext.get();

        // In GraalVM Polyglot, context.getBindings("js") returns the JavaScript global scope
        // (not a separate host bindings layer). Both putMember (to inject host values like
        // BUILT_IN_HELPERS) and getMember (to retrieve JS-defined functions after context.eval)
        // operate on the same JS global object. Do not switch this to context.getPolyglotBindings()
        // — that's a different scope and serialise() would be invisible.
        Value jsBindings = context.getBindings("js");
        // BUILT_IN_FUNCTIONS suppliers are evaluated once per template execution. Previously
        // (via JSR-223 ScriptBindings), they were evaluated lazily on each JS variable access,
        // so a template reading $uuid twice would get two different UUIDs. This is a behavioural
        // change documented in the changelog; templates relying on per-access freshness should
        // call the supplier explicitly via a host helper. They are re-evaluated on EVERY render
        // (the Context is reused), so each render still gets fresh now/uuid/rand values.
        TemplateFunctions.BUILT_IN_FUNCTIONS.forEach((key, supplier) ->
            jsBindings.putMember(key, supplier.get()));
        TemplateFunctions.BUILT_IN_HELPERS.forEach(jsBindings::putMember);

        // Expose jsonPath('$.field') and xPath('//field') request-body extraction functions to the
        // script scope, sharing the same extraction logic / error handling as the Mustache and
        // Velocity engines. Registered as java.util.function.Function so GraalJS treats them as
        // callable JS functions. The "jsonPath"/"xPath" names are not used by BUILT_IN_FUNCTIONS or
        // BUILT_IN_HELPERS, so this does not shadow (or get shadowed by) a built-in. Rebound per render
        // so they always target the current request on this thread's reused Context.
        RequestBodyExtractionHelper bodyExtractionHelper = new RequestBodyExtractionHelper(request, mockServerLogger);
        jsBindings.putMember("jsonPath", (Function<String, Object>) bodyExtractionHelper::jsonPath);
        jsBindings.putMember("xPath", (Function<String, Object>) bodyExtractionHelper::xPath);

        // Load-generation only: expose the per-iteration variable as a JS host object so a
        // load-scenario JavaScript step can read iteration.getIndex() etc. Null for every
        // non-load template execution. Because the Context is reused, explicitly REMOVE a stale
        // iteration binding left by a prior load render so the standard response/forward template
        // path is byte-for-byte unchanged (sees no "iteration" global).
        if (iteration != null) {
            jsBindings.putMember("iteration", iteration);
        } else if (jsBindings.hasMember("iteration")) {
            jsBindings.removeMember("iteration");
        }

        // Evaluate the cached, parsed Source. Re-eval each render re-defines handle()/serialise() on this
        // thread's global scope, so a different template rendered earlier on the same thread cannot bleed
        // through; parsing/compilation is shared via the Engine + Source cache, so this no longer re-parses.
        context.eval(parsedSource(fullScript));

        Value serialiseFunc = jsBindings.getMember("serialise");
        Value stringifiedResult;
        if (includeResponse) {
            stringifiedResult = serialiseFunc.execute(
                new HttpRequestTemplateObject(request),
                new HttpResponseTemplateObject(response)
            );
        } else {
            stringifiedResult = serialiseFunc.execute(
                new HttpRequestTemplateObject(request)
            );
        }

        String stringifiedResponse = stringifiedResult.asString();

        JsonNode generatedObject = null;
        try {
            generatedObject = objectMapper.readTree(stringifiedResponse);
        } catch (Throwable throwable) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                        .setArguments(stringifiedResponse, request)
                );
            }
        }
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(TEMPLATE_GENERATED)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                    .setArguments(generatedObject != null ? generatedObject : stringifiedResponse, script, request)
            );
        }
        return httpTemplateOutputDeserializer.deserializer(request, stringifiedResponse, dtoClass);
    }
}
