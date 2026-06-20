package org.mockserver.templates.engine.mustache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.helpers.RequestBodyExtractionHelper;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * See: https://github.com/samskivert/jmustache or http://mustache.github.io/mustache.5.html for syntax
 *
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class MustacheTemplateEngine implements TemplateEngine {

    private static ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Mustache.Compiler compiler;
    private HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;

    public MustacheTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        if (objectMapper == null) {
            objectMapper = ObjectMapperFactory.createObjectMapper();
        }
        compiler = Mustache
            .compiler()
            .emptyStringIsFalse(true)
            .zeroIsFalse(true)
            .strictSections(false)
            .defaultValue("")
            .withCollector(new ExtendedCollector());
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, dtoClass);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, response, dtoClass);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        return renderTemplate(template, request, null);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration) {
        try {
            validateTemplate(template);
            Writer writer = new StringWriter();
            Template compiledTemplate = compiler.compile(template);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("request", new HttpRequestTemplateObject(request));
            if (iteration != null) {
                data.put("iteration", iteration);
            }
            data.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
            data.putAll(TemplateFunctions.BUILT_IN_HELPERS);
            data.put("xPath", (Mustache.Lambda) (frag, out) -> evaluatedXPath(frag.execute(), request, out));
            data.put("jsonPath", (Mustache.Lambda) (frag, out) -> evaluateJsonPath(data, frag.execute(), request, out));
            compiledTemplate.execute(data, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    private <T> T executeTemplateInternal(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        T result;
        try {
            validateTemplate(template);
            Writer writer = new StringWriter();
            Template compiledTemplate = compiler.compile(template);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("request", new HttpRequestTemplateObject(request));
            if (response != null) {
                data.put("response", new HttpResponseTemplateObject(response));
            }
            data.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
            data.putAll(TemplateFunctions.BUILT_IN_HELPERS);
            data.put("xPath", (Mustache.Lambda) (frag, out) -> evaluatedXPath(frag.execute(), request, out));
            data.put("jsonPath", (Mustache.Lambda) (frag, out) -> evaluateJsonPath(data, frag.execute(), request, out));
            compiledTemplate.execute(data, writer);
            JsonNode generatedObject = null;
            try {
                generatedObject = objectMapper.readTree(writer.toString());
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                            .setArguments(writer.toString(), request)
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
                        .setArguments(generatedObject != null ? generatedObject : writer.toString(), template, request)
                );
            }
            result = httpTemplateOutputDeserializer.deserializer(request, writer.toString(), dtoClass);
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
        return result;
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.mustacheDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.mustacheDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

    private void evaluateJsonPath(Map<String, Object> data, String jsonPath, HttpRequest request, Writer out) throws IOException {
        // Delegate the actual extraction to the shared helper so the Mustache, Velocity and JavaScript
        // engines all use the same JSONPath/XPath logic and error handling. The Mustache idiom is to
        // store the extracted value under "jsonPathResult" (so it can be iterated in a following section)
        // and emit nothing inline.
        Object jsonPathResult = new RequestBodyExtractionHelper(request, mockServerLogger).jsonPath(jsonPath);
        data.put("jsonPathResult", jsonPathResult);
        out.write("");
    }

    private void evaluatedXPath(String xPath, HttpRequest request, Writer out) throws IOException {
        out.write(new RequestBodyExtractionHelper(request, mockServerLogger).xPath(xPath));
    }
}
