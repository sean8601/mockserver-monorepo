package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.formatting.StringFormatter.indentAndToString;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"RedundantSuppression", "FieldMayBeFinal"})
public class JavaScriptTemplateEngine implements TemplateEngine {

    private static final boolean POLYGLOT_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("org.graalvm.polyglot.Context");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        POLYGLOT_AVAILABLE = available;
    }

    private final ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    private final Configuration configuration;
    private final Predicate<String> classFilter;
    // Held as Object (not PolyglotRunner) and created only when POLYGLOT_AVAILABLE so the JVM does not
    // resolve PolyglotRunner's org.graalvm.polyglot.* field types when GraalVM is absent — preserving the
    // graceful-degradation guarantee on the standard distribution. Cast to PolyglotRunner only inside the
    // POLYGLOT_AVAILABLE branch in executeTemplateInternal. The runner owns the shared Engine, the parsed-
    // Source cache and the per-thread Context, so it is created once per engine instance and reused.
    private final Object polyglotRunner;

    public JavaScriptTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this.configuration = (configuration == null) ? configuration() : configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.classFilter = className -> isClassAllowed(className, this.configuration);
        this.polyglotRunner = POLYGLOT_AVAILABLE ? new PolyglotRunner(this.classFilter) : null;
        if (mockServerLogger != null
            && mockServerLogger.isEnabledForInstance(Level.WARN)
            && !isNotBlank(this.configuration.javascriptDisallowedClasses())) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("JavaScript template engine has no class restrictions (mockserver.javascriptDisallowedClasses is empty). Templates can use Java.type(\"...\") to instantiate arbitrary Java classes including Runtime — only use JavaScript templates from trusted sources, or populate mockserver.javascriptDisallowedClasses with at least java.lang.Runtime,java.lang.ProcessBuilder,java.lang.System.")
            );
        }
    }

    public static boolean isPolyglotAvailable() {
        return POLYGLOT_AVAILABLE;
    }

    /**
     * Release the per-thread GraalVM {@link org.graalvm.polyglot.Context} this engine's runner holds against
     * the CURRENT thread (if any). The process-wide shared GraalVM Engine is NOT closed — it lives for the
     * JVM. Call this when a {@code JavaScriptTemplateEngine} is short-lived (created per call / per test) so a
     * Context is not leaked into the calling thread for every such instance; long-lived action-handler engines
     * may simply leave their Context open for their life. No-op when GraalVM is not on the classpath.
     */
    public void close() {
        if (POLYGLOT_AVAILABLE && polyglotRunner != null) {
            ((PolyglotRunner) polyglotRunner).close();
        }
    }

    private static boolean isClassAllowed(String className, Configuration configuration) {
        if (isNotBlank(configuration.javascriptDisallowedClasses())) {
            Iterable<String> restrictedClasses = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedClasses());
            return StreamSupport.stream(restrictedClasses.spliterator(), false)
                .noneMatch(restrictedClass -> restrictedClass.equalsIgnoreCase(className));
        }
        return true;
    }

    // NOTE: these were previously synchronized, which serialised every JavaScript render across the whole
    // server on one lock. The lock is gone: each request thread now renders on its own GraalVM Context
    // (a ThreadLocal owned by the shared PolyglotRunner), so independent renders run concurrently. The only
    // cross-thread shared object is the thread-safe GraalVM Engine; the Source cache is a synchronized LRU.
    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, null, dtoClass, false);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, response, null, dtoClass, true);
    }

    /**
     * Load-generation only: execute a JavaScript template with a per-iteration variable
     * ({@code iteration}) bound in the script scope. Used by the load executor so a JavaScript
     * load-scenario step can vary its output per iteration. Identical to
     * {@link #executeTemplate(String, HttpRequest, Class)} when {@code iteration} is null.
     */
    public <T> T executeTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, iteration, dtoClass, false);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        // JavaScript templates are designed to construct and return a full response object, not a text
        // fragment, so they are not supported for FileBody templating. Use httpResponseTemplate (or
        // httpResponseTemplate with templateFile) with a JavaScript template instead.
        throw new UnsupportedOperationException("JavaScript templates are not supported for file body templating; use a Velocity or Mustache templateType, or an httpResponseTemplate for JavaScript");
    }

    private <T> T executeTemplateInternal(String template, HttpRequest request, HttpResponse response, org.mockserver.load.IterationContext iteration, Class<? extends DTO<T>> dtoClass, boolean includeResponse) {
        String script = includeResponse ? wrapTemplateWithResponse(template) : wrapTemplate(template);
        try {
            validateTemplate(template);
            if (POLYGLOT_AVAILABLE) {
                // Delegate to the shared PolyglotRunner (nested holder class). The JVM only resolves the
                // org.graalvm.polyglot.* references inside PolyglotRunner when this branch is taken (and
                // when the runner was created in the constructor under the same POLYGLOT_AVAILABLE guard),
                // so the standard distribution (no GraalVM on classpath) loads this class and degrades
                // gracefully via the else branch instead of failing with NoClassDefFoundError.
                return ((PolyglotRunner) polyglotRunner).run(
                    script,
                    includeResponse,
                    request,
                    response,
                    iteration,
                    objectMapper,
                    mockServerLogger,
                    httpTemplateOutputDeserializer,
                    dtoClass
                );
            } else {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat(
                            "JavaScript based templating requires GraalVM Polyglot on the classpath, " +
                                "please add org.graalvm.polyglot:polyglot and org.graalvm.polyglot:js to the classpath, " +
                                "or use the MockServer 'graaljs' Docker image variant"
                        )
                        .setArguments(new RuntimeException("GraalVM Polyglot API not on classpath"))
                );
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    static String wrapTemplate(String template) {
        return "function handle(request) {" + indentAndToString(template)[0] + "}";
    }

    static String wrapTemplateWithResponse(String template) {
        return "function handle(request, response) {" + indentAndToString(template)[0] + "}";
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.javascriptDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

}
