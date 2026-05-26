package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.slf4j.event.Level;

import java.util.List;
import java.util.Optional;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.model.HttpResponse.response;

public class HttpLlmResponseActionHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
                .withStatusCode(400)
                .withBody("{\"error\":\"unsupported LLM provider: null\",\"supported\":" + supportedProvidersJson() + "}");
        }

        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        Optional<ProviderCodec> codec = registry.lookup(provider);
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
                .withStatusCode(400)
                .withBody("{\"error\":\"unsupported LLM provider: " + provider + "\",\"supported\":" + supportedProvidersJson() + "}");
        }

        String model = httpLlmResponse.getModel();
        ProviderCodec codecInstance = codec.get();

        try {
            // Embedding path
            if (httpLlmResponse.getEmbedding() != null) {
                String inputText = extractInputFromRequest(request);
                return codecInstance.encodeEmbedding(httpLlmResponse.getEmbedding(), inputText);
            }

            // Non-streaming completion path
            Completion completion = httpLlmResponse.getCompletion();
            if (completion != null && !Boolean.TRUE.equals(completion.getStreaming())) {
                return codecInstance.encode(completion, model);
            }

            // Streaming completion path: this method only handles non-streaming.
            // Streaming is dispatched via handleStreaming() from HttpActionHandler,
            // so reaching here means the caller bypassed the normal dispatch.
            if (completion != null && Boolean.TRUE.equals(completion.getStreaming())) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("streaming LLM response reached the non-streaming handle() path for provider {} — dispatcher bug; returning 501")
                            .setArguments(provider)
                    );
                }
                return response()
                    .withStatusCode(501)
                    .withBody("{\"error\":\"streaming LLM responses must be dispatched through the SSE handler\"}");
            }

            // No completion or embedding configured
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"httpLlmResponse must have either a completion or embedding configured\"}");
        } catch (UnsupportedOperationException e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("llm codec encode failed for provider {} for request:{}")
                        .setArguments(provider, request)
                        .setThrowable(e)
                );
            }
            return response()
                .withStatusCode(502)
                .withBody("{\"error\":\"llm codec encode failed\",\"provider\":\"" + provider.name() + "\"}");
        }
    }

    /**
     * Handle streaming LLM response by producing a list of SSE events.
     * Called by HttpActionHandler when streaming is detected.
     *
     * @param httpLlmResponse the LLM response action
     * @param request         the original HTTP request
     * @return list of SSE events to be sent through the SSE handler
     */
    public List<SseEvent> handleStreaming(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        Provider provider = httpLlmResponse.getProvider();
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        ProviderCodec codecInstance = registry.lookup(provider)
            .orElseThrow(() -> new IllegalStateException("no codec registered for provider " + provider));

        Completion completion = httpLlmResponse.getCompletion();
        String model = httpLlmResponse.getModel();
        StreamingPhysics physics = completion.getStreamingPhysics();

        return codecInstance.encodeStreaming(completion, model, physics);
    }

    private String extractInputFromRequest(HttpRequest request) {
        if (request.getBody() != null) {
            String bodyString = request.getBody().toString();
            try {
                JsonNode bodyNode = OBJECT_MAPPER.readTree(bodyString);
                JsonNode inputNode = bodyNode.get("input");
                if (inputNode != null) {
                    if (inputNode.isTextual()) {
                        return inputNode.asText();
                    }
                    return inputNode.toString();
                }
            } catch (Exception e) {
                // not parseable, return empty
            }
        }
        return "";
    }

    private String supportedProvidersJson() {
        List<String> names = ProviderCodecRegistry.getInstance().supportedProviderNames();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(names.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
