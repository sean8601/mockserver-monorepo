package org.mockserver.templates;

import org.apache.commons.lang3.NotImplementedException;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;

public class ResponseTemplateTester {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(ResponseTemplateTester.class);

    public static HttpResponse testMustacheTemplate(String template, HttpRequest request) {
        return new MustacheTemplateEngine(MOCK_SERVER_LOGGER, new Configuration()).executeTemplate(template, request, HttpResponseDTO.class);
    }

    public static HttpResponse testVelocityTemplate(String template, HttpRequest request) {
        return new VelocityTemplateEngine(MOCK_SERVER_LOGGER, new Configuration()).executeTemplate(template, request, HttpResponseDTO.class);
    }

    public static HttpResponse testJavaScriptTemplate(String template, HttpRequest request) {
        if (JavaScriptTemplateEngine.isPolyglotAvailable()) {
            // A fresh engine is created per call here, so close it afterwards to release the per-thread
            // GraalVM Context it created on this thread. Without this, every call would leak one Context
            // into the calling thread (the process-wide GraalVM Engine is shared and intentionally not closed).
            JavaScriptTemplateEngine engine = new JavaScriptTemplateEngine(MOCK_SERVER_LOGGER, new Configuration());
            try {
                return engine.executeTemplate(template, request, HttpResponseDTO.class);
            } finally {
                engine.close();
            }
        } else {
            throw new NotImplementedException("No JavaScript engine (GraalVM Polyglot) is available on this JVM");
        }
    }

}
