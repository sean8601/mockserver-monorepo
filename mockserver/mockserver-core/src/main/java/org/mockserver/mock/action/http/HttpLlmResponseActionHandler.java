package org.mockserver.mock.action.http;

import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.slf4j.event.Level;

import java.util.Optional;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.model.HttpResponse.response;

public class HttpLlmResponseActionHandler {

    private final MockServerLogger mockServerLogger;

    public HttpLlmResponseActionHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public HttpResponse handle(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        Provider provider = httpLlmResponse.getProvider();
        if (provider == null) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("no provider specified for LLM response action for request:{}")
                        .setArguments(request)
                );
            }
            return response()
                .withStatusCode(501)
                .withBody("{\"error\":\"LLM codec not implemented for provider: null\"}");
        }

        Optional<ProviderCodec> codec = ProviderCodecRegistry.getInstance().lookup(provider);
        if (!codec.isPresent()) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("no codec registered for provider {} for LLM response action for request:{}")
                        .setArguments(provider, request)
                );
            }
            return response()
                .withStatusCode(501)
                .withBody("{\"error\":\"LLM codec not implemented for provider: " + provider + "\"}");
        }

        // At M0 this path is unreachable since no codecs are registered.
        // Future milestones will implement codec-based encoding here.
        return response()
            .withStatusCode(501)
            .withBody("{\"error\":\"LLM codec not implemented for provider: " + provider + "\"}");
    }
}
