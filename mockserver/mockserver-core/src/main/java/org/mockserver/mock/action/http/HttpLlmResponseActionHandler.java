package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.llm.LlmErrorBodies;
import org.mockserver.llm.LlmErrorBody;
import org.mockserver.llm.LlmQuotaRegistry;
import org.mockserver.llm.LlmRateLimitHeaders;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                HttpResponse encoded = codecInstance.encode(completion, model);
                validateStructuredOutput(completion, encoded, provider, request);
                applyRateLimitHeaders(encoded, provider, false, httpLlmResponse.getChaos());
                org.mockserver.telemetry.GenAiSpans.recordCompletion(provider, model, completion);
                HttpActionHandler.recordLlmUsageMetrics(provider, model, completion);
                return encoded;
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

    static final String STRUCTURED_OUTPUT_INVALID_HEADER = "x-mockserver-structured-output-invalid";

    /**
     * Validate the completion's configured text against its declared
     * {@link Completion#getOutputSchema() output schema}, if any. Fail-soft: a
     * mismatch never alters the response body — it adds the
     * {@code x-mockserver-structured-output-invalid} diagnostic header (when an
     * {@code encoded} response is supplied) and logs a warning. A blank schema,
     * absent text, or a malformed schema are all treated as "nothing to check"
     * and never throw, so structured-output validation can never break an LLM
     * response. {@code encoded} may be {@code null} (e.g. streaming) — then only
     * the warning is logged.
     */
    void validateStructuredOutput(Completion completion, HttpResponse encoded, Provider provider, HttpRequest request) {
        if (completion == null) {
            return;
        }
        String schema = completion.getOutputSchema();
        String text = completion.getText();
        if (schema == null || schema.trim().isEmpty() || text == null) {
            return;
        }
        try {
            String error = new JsonSchemaValidator(mockServerLogger, schema).isValid(text, false);
            if (error != null && !error.isEmpty()) {
                if (encoded != null) {
                    encoded.withHeader(STRUCTURED_OUTPUT_INVALID_HEADER, compactHeaderValue(error));
                }
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("configured LLM completion text for provider {} does not conform to its declared outputSchema:{}")
                            .setArguments(provider, error)
                    );
                }
            }
        } catch (Exception e) {
            // a malformed schema must never break the response — surface it as a warning only
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("could not validate LLM completion text against outputSchema for provider {} — treating schema as a no-op:{}")
                        .setArguments(provider, e.getMessage())
                );
            }
        }
    }

    /**
     * Flatten a (possibly multi-line) validation message into a single header-safe
     * line, collapsing CR/LF — HTTP header values must not contain line breaks.
     */
    private static String compactHeaderValue(String message) {
        return message.replaceAll("[\\r\\n]+", "; ").trim();
    }

    /**
     * Returns the streaming wire format for the given provider. Used by the
     * action handler dispatch to choose between SSE and NDJSON framing.
     *
     * @param provider the LLM provider
     * @return the streaming format (defaults to {@link StreamingFormat#SSE})
     */
    public StreamingFormat streamingFormatFor(Provider provider) {
        return ProviderCodecRegistry.getInstance().lookup(provider)
            .map(ProviderCodec::streamingFormat)
            .orElse(StreamingFormat.SSE);
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

        List<SseEvent> events = codecInstance.encodeStreaming(completion, model, physics);
        validateStructuredOutput(completion, null, provider, request);
        org.mockserver.telemetry.GenAiSpans.recordCompletion(provider, model, completion);
        HttpActionHandler.recordLlmUsageMetrics(provider, model, completion);
        // Rate-limit headers are intentionally not applied on the streaming path: it
        // returns SSE events rather than an HttpResponse, and a quota breach is
        // surfaced as a non-streaming error before streaming begins.
        return applyStreamingChaos(events, httpLlmResponse.getChaos());
    }

    /**
     * Returns an error {@link HttpResponse} (status + optional {@code Retry-After})
     * when the chaos profile triggers a probabilistic error, or {@code null}
     * otherwise. An {@code errorStatus} with no {@code errorProbability} always
     * fires; a fractional probability draws once (reproducible via {@code seed}).
     * Applies to both streaming and non-streaming responses — a provider error is
     * a normal HTTP response, not an SSE stream.
     */
    public HttpResponse chaosErrorResponseOrNull(HttpLlmResponse httpLlmResponse) {
        LlmChaosProfile chaos = httpLlmResponse.getChaos();
        if (chaos == null) {
            return null;
        }
        // Compute token count for this response (used by the token-based quota).
        long requestTokens = estimateTokenCount(httpLlmResponse);

        // Stateful quota (a hard, deterministic rate limit) is checked first and
        // consumes one slot per call. An exceeded quota short-circuits to its own
        // error before any probabilistic-error decision.
        HttpResponse quotaError = quotaErrorResponseOrNull(chaos, requestTokens, httpLlmResponse.getProvider());
        if (quotaError != null) {
            return quotaError;
        }
        // The probabilistic error fires when an errorStatus is set, OR when only an
        // errorKind is declared (the provider's natural status then supplies the code).
        LlmErrorBody.Kind kind = parseErrorKind(chaos.getErrorKind());
        if (chaos.getErrorStatus() == null && kind == null) {
            return null;
        }
        if (!ChaosProbability.shouldInject(chaos.getErrorProbability(), chaos.getSeed())) {
            return null;
        }
        Provider provider = httpLlmResponse.getProvider();
        ProviderError providerError = resolveProviderError(provider, kind, chaos.getErrorStatus());
        HttpResponse errorResponse = response()
            .withStatusCode(providerError.statusCode)
            .withHeader("content-type", "application/json")
            .withBody(providerError.jsonBody);
        // applyRateLimitHeaders is the single owner of the Retry-After header (and the
        // provider-specific rate-limit headers) so it is never emitted twice.
        applyRateLimitHeaders(errorResponse, provider, true, chaos);
        return errorResponse;
    }

    /** Parse a case-insensitive errorKind string to an {@link LlmErrorBody.Kind}, or null when unset/unrecognised. */
    private static LlmErrorBody.Kind parseErrorKind(String errorKind) {
        if (errorKind == null || errorKind.trim().isEmpty()) {
            return null;
        }
        try {
            return LlmErrorBody.Kind.valueOf(errorKind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolved (status, body) for an injected error: provider-specific when available, else the generic chaos body. */
    private static final class ProviderError {
        final int statusCode;
        final String jsonBody;

        ProviderError(int statusCode, String jsonBody) {
            this.statusCode = statusCode;
            this.jsonBody = jsonBody;
        }
    }

    private static final String GENERIC_CHAOS_BODY =
        "{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected chaos error\"}}";

    /**
     * Resolve the body + status for an injected probabilistic error.
     *
     * <p>Two layered behaviours, both emitting a provider-correct error body so client
     * SDK retry/backoff logic can be tested faithfully:
     * <ul>
     *   <li><strong>Explicit {@code errorKind}</strong> ({@code kind != null}) — the
     *       profile declares intent (OVERLOAD/RATE_LIMIT/SERVER_ERROR). The status is the
     *       explicit {@code explicitStatus} when set, otherwise the provider's
     *       <em>natural</em> status for that kind (e.g. Anthropic OVERLOAD → 529). This is
     *       the only path that works with no {@code errorStatus} at all.</li>
     *   <li><strong>Status-derived</strong> ({@code kind == null}, {@code explicitStatus}
     *       non-null) — the kind is derived from the status (429 → rate-limit, 529 →
     *       overloaded, other → server error) and the provider's body emitted at that
     *       exact status. Preserves the automatic provider-shape behaviour for callers
     *       that only set {@code errorStatus}.</li>
     * </ul>
     * For an unknown/null provider (no provider-specific shape) both paths fall back to
     * the generic chaos body; a missing status then defaults to 500.
     */
    private static ProviderError resolveProviderError(Provider provider, LlmErrorBody.Kind kind, Integer explicitStatus) {
        if (kind != null) {
            // Explicit errorKind: the branch helper supplies the provider's natural status for the kind.
            LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(provider, kind);
            if (shape != null) {
                int status = explicitStatus != null ? explicitStatus : shape.getStatusCode();
                return new ProviderError(status, shape.getJsonBody());
            }
        } else if (explicitStatus != null) {
            // No errorKind, but an explicit status: derive the kind from the status and
            // emit the provider-correct body at that status (automatic provider shaping).
            LlmErrorBodies.Kind statusKind = LlmErrorBodies.kindForStatus(explicitStatus);
            String body = LlmErrorBodies.bodyFor(provider, statusKind, explicitStatus, "injected chaos error");
            if (body != null) {
                return new ProviderError(explicitStatus, body);
            }
        }
        int status = explicitStatus != null ? explicitStatus : 500;
        return new ProviderError(status, GENERIC_CHAOS_BODY);
    }

    /**
     * Estimate the token count for the given LLM response. If the completion has
     * a {@link Usage} with inputTokens/outputTokens, returns their sum. Otherwise
     * falls back to {@code ceil(text.length() / 4)} as a rough approximation.
     * Returns 0 for embeddings or missing completions.
     */
    static long estimateTokenCount(HttpLlmResponse httpLlmResponse) {
        Completion completion = httpLlmResponse.getCompletion();
        if (completion == null) {
            return 0L;
        }
        Usage usage = completion.getUsage();
        if (usage != null) {
            int input = usage.getInputTokens() != null ? usage.getInputTokens() : 0;
            int output = usage.getOutputTokens() != null ? usage.getOutputTokens() : 0;
            if (input > 0 || output > 0) {
                return (long) input + output;
            }
        }
        // Fall back to a rough character-based estimate
        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            return (long) Math.ceil(text.length() / 4.0);
        }
        return 0L;
    }

    /**
     * If the profile declares a stateful request-count quota and/or a token-based
     * quota and this request exceeds either within its current window, return the
     * quota error response (status {@code quotaErrorStatus}, default 429, plus the
     * {@code Retry-After} header when set); otherwise {@code null}.
     * <p>
     * The request-count quota is checked first. If it passes, the token-based quota
     * is checked (charging {@code requestTokens} against the token window). Both
     * quotas use independent counters within the registry, namespaced by suffix
     * ({@code quotaName} for request-count, {@code quotaName + ":tokens"} for
     * token-based).
     * <p>
     * Called once per matched LLM request (via {@link #chaosErrorResponseOrNull})
     * regardless of whether the response path ultimately delivers an LLM payload,
     * so the count reflects requests received, not payloads returned.
     *
     * @param chaos         the chaos profile (never null)
     * @param requestTokens estimated token count for this response
     */
    HttpResponse quotaErrorResponseOrNull(LlmChaosProfile chaos, long requestTokens, Provider provider) {
        // --- request-count quota ---
        if (chaos.getQuotaName() != null && chaos.getQuotaLimit() != null && chaos.getQuotaWindowMillis() != null) {
            boolean allowed = LlmQuotaRegistry.getInstance()
                .tryAcquire(chaos.getQuotaName(), chaos.getQuotaLimit(), chaos.getQuotaWindowMillis());
            if (!allowed) {
                return buildQuotaErrorResponse(chaos, "quota_exceeded", "LLM request quota exceeded", provider);
            }
        }
        // --- token-based quota ---
        // Note: when the request-count quota fires above, this block is skipped and
        // the token window is intentionally not charged — a blocked request produces
        // no response and therefore consumes no tokens.
        if (chaos.getQuotaName() != null && chaos.getTokenQuotaLimit() != null && chaos.getTokenQuotaWindowMillis() != null) {
            boolean allowed = LlmQuotaRegistry.getInstance()
                .tryAcquire(chaos.getQuotaName() + ":tokens", chaos.getTokenQuotaLimit(), chaos.getTokenQuotaWindowMillis(), requestTokens);
            if (!allowed) {
                return buildQuotaErrorResponse(chaos, "token_quota_exceeded", "LLM token quota exceeded", provider);
            }
        }
        return null;
    }

    private HttpResponse buildQuotaErrorResponse(LlmChaosProfile chaos, String errorType, String message, Provider provider) {
        // When the profile opts in to an explicit errorKind (e.g. forcing OVERLOAD for a
        // quota breach instead of the default rate-limit shape), emit the active provider's
        // distinct error body at that kind's natural status, so client SDK backoff logic
        // can parse it. An explicit quotaErrorStatus still overrides the status.
        LlmErrorBody.Kind explicitKind = parseErrorKind(chaos.getErrorKind());
        if (explicitKind != null) {
            LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(provider, explicitKind);
            if (shape != null) {
                int providerStatus = chaos.getQuotaErrorStatus() != null ? chaos.getQuotaErrorStatus() : shape.getStatusCode();
                HttpResponse providerResponse = response()
                    .withStatusCode(providerStatus)
                    .withHeader("content-type", "application/json")
                    .withBody(shape.getJsonBody());
                applyRateLimitHeaders(providerResponse, provider, true, chaos);
                return providerResponse;
            }
        }
        int status = chaos.getQuotaErrorStatus() != null ? chaos.getQuotaErrorStatus() : 429;
        // No explicit errorKind: a quota breach is a rate-limit error, so emit the
        // provider-correct error body derived from the status (so client SDK retry/backoff
        // logic reads the right error.type/code), falling back to the generic body — which
        // preserves the distinct quota_exceeded / token_quota_exceeded error types — for an
        // unknown/null provider.
        LlmErrorBodies.Kind statusKind = LlmErrorBodies.kindForStatus(status);
        String body = LlmErrorBodies.bodyFor(provider, statusKind, status, message);
        if (body == null) {
            body = "{\"error\":{\"type\":\"" + errorType + "\",\"message\":\"" + message + "\"}}";
        }
        HttpResponse errorResponse = response()
            .withStatusCode(status)
            .withHeader("content-type", "application/json")
            .withBody(body);
        // applyRateLimitHeaders is the single owner of the Retry-After header (and the
        // provider-specific rate-limit headers) so it is never emitted twice.
        applyRateLimitHeaders(errorResponse, provider, true, chaos);
        return errorResponse;
    }

    /**
     * Apply streaming chaos to the SSE event list: mid-stream truncation (keep a
     * leading fraction of events) and/or a malformed (broken-JSON) trailing
     * chunk. Deterministic. Returns the input unchanged when no streaming chaos
     * is configured.
     */
    List<SseEvent> applyStreamingChaos(List<SseEvent> events, LlmChaosProfile chaos) {
        if (chaos == null || events == null || events.isEmpty()) {
            return events;
        }
        List<SseEvent> result = events;
        if (chaos.getTruncateMode() == LlmChaosProfile.TruncateMode.MID_STREAM) {
            double fraction = chaos.getTruncateAtFraction() != null ? chaos.getTruncateAtFraction() : 0.5;
            if (fraction < 0.0) {
                fraction = 0.0;
            }
            if (fraction > 1.0) {
                fraction = 1.0;
            }
            int keep = (int) Math.floor(events.size() * fraction);
            result = new ArrayList<>(events.subList(0, keep));
        }
        if (Boolean.TRUE.equals(chaos.getMalformedSse())) {
            // append a deliberately broken-JSON chunk so the client must handle a
            // corrupt mid-stream event (missing closing brace)
            result = new ArrayList<>(result);
            result.add(SseEvent.sseEvent().withData("{\"malformed\":true"));
        }
        return result;
    }

    /**
     * Apply provider-correct rate-limit headers to the response. When a quota is
     * configured, derives {@code requestLimit} and {@code resetSeconds} from the
     * chaos profile; {@code requestRemaining} is {@code null} (we cannot cheaply
     * compute it without re-reading the registry window, and omitting it is safe —
     * real providers sometimes omit it too). Delegates to the pure helper
     * {@link LlmRateLimitHeaders#headersFor}.
     */
    private void applyRateLimitHeaders(HttpResponse response, Provider provider, boolean limited, LlmChaosProfile chaos) {
        if (chaos == null || provider == null) {
            return;
        }
        Integer requestLimit = chaos.getQuotaLimit();
        // Reset window comes from whichever quota is configured (request-count first,
        // then token-based), falling back to a numeric Retry-After value.
        Long resetSeconds = null;
        if (chaos.getQuotaWindowMillis() != null) {
            resetSeconds = Math.max(1, chaos.getQuotaWindowMillis() / 1000);
        } else if (chaos.getTokenQuotaWindowMillis() != null) {
            resetSeconds = Math.max(1, chaos.getTokenQuotaWindowMillis() / 1000);
        } else if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            try {
                resetSeconds = Long.parseLong(chaos.getRetryAfter());
            } catch (NumberFormatException ignored) {
                // retryAfter may be an HTTP-date string — handled via the literal Retry-After below
            }
        }
        Integer requestRemaining = limited ? 0 : null;
        long nowEpochSecond = System.currentTimeMillis() / 1000;
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            provider, requestLimit, requestRemaining, resetSeconds, nowEpochSecond, limited);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            response.withHeader(entry.getKey(), entry.getValue());
        }
        // Retry-After is a standard HTTP header (not provider-specific), so it is set
        // here for every provider — including Gemini, Bedrock, and Ollama, which have
        // no provider-specific rate-limit headers. Prefer the literal configured
        // Retry-After (which may be an HTTP-date), else the computed reset seconds.
        // Single source → no duplicate header.
        if (limited) {
            String retryAfter = chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()
                ? chaos.getRetryAfter()
                : (resetSeconds != null ? String.valueOf(resetSeconds) : null);
            if (retryAfter != null) {
                response.withHeader("Retry-After", retryAfter);
            }
        }
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
