package org.mockserver.llm;

import org.mockserver.model.Provider;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, deterministic helper that produces the provider-correct rate-limit HTTP
 * headers real LLM providers send. Client SDKs (e.g. the OpenAI Python SDK,
 * Anthropic SDK) read these headers to drive retry/backoff logic, so emitting
 * them faithfully allows MockServer to exercise that logic against a mock.
 *
 * <h3>Provider header reference</h3>
 * <ul>
 *   <li><strong>OPENAI / OPENAI_RESPONSES / AZURE_OPENAI</strong> (source: OpenAI docs
 *       "Rate limits" page) — {@code x-ratelimit-limit-requests},
 *       {@code x-ratelimit-remaining-requests}, {@code x-ratelimit-reset-requests}
 *       (duration, e.g. "6s"), and the {@code -tokens} variants when token info
 *       is available. On a 429 the standard {@code retry-after} header (seconds)
 *       is also emitted.</li>
 *   <li><strong>ANTHROPIC</strong> (source: Anthropic docs "Rate limits" page) —
 *       {@code anthropic-ratelimit-requests-limit},
 *       {@code anthropic-ratelimit-requests-remaining},
 *       {@code anthropic-ratelimit-requests-reset} (RFC 3339 timestamp), and the
 *       {@code -tokens-*} variants when token info is available. On a 429 the
 *       standard {@code retry-after} header (seconds) is also emitted.</li>
 *   <li><strong>GEMINI</strong> (source: Google AI Studio / Vertex AI docs) —
 *       {@code retry-after} on a 429. Google returns a {@code RetryInfo} in the
 *       gRPC status detail; for the HTTP API, only {@code retry-after} is
 *       exposed.</li>
 *   <li><strong>BEDROCK</strong> (source: AWS Bedrock docs) — {@code retry-after}
 *       on a 429. No provider-specific rate-limit headers.</li>
 *   <li><strong>OLLAMA</strong> — none. Ollama is a local inference engine with
 *       no rate-limit concept.</li>
 * </ul>
 *
 * <p>All methods are static, deterministic, and pure (no clocks, no randomness
 * inside — the caller passes {@code resetSeconds} and the current epoch second
 * for RFC 3339 timestamps).
 */
public final class LlmRateLimitHeaders {

    private LlmRateLimitHeaders() {
    }

    /**
     * Produce provider-correct rate-limit headers.
     *
     * @param provider          the LLM provider
     * @param requestLimit      quota limit (requests per window); may be {@code null}
     * @param requestRemaining  requests remaining in the window; may be {@code null}
     * @param resetSeconds      seconds until the window resets; may be {@code null}
     * @param nowEpochSecond    current epoch second (for Anthropic RFC 3339 reset timestamp)
     * @param limited           {@code true} when this is a rate-limit error (429);
     *                          {@code false} for a successful response with quota info
     * @return an insertion-ordered map of header-name to header-value; empty if
     *         the provider has no rate-limit headers (e.g. OLLAMA)
     */
    public static Map<String, String> headersFor(
        Provider provider,
        Integer requestLimit,
        Integer requestRemaining,
        Long resetSeconds,
        long nowEpochSecond,
        boolean limited
    ) {
        if (provider == null) {
            return Collections.emptyMap();
        }
        switch (provider) {
            case OPENAI:
            case OPENAI_RESPONSES:
            case AZURE_OPENAI:
                return openAiHeaders(requestLimit, requestRemaining, resetSeconds, limited);
            case ANTHROPIC:
                return anthropicHeaders(requestLimit, requestRemaining, resetSeconds, nowEpochSecond, limited);
            case GEMINI:
                return geminiHeaders(resetSeconds, limited);
            case BEDROCK:
                return bedrockHeaders(resetSeconds, limited);
            case OLLAMA:
            default:
                return Collections.emptyMap();
        }
    }

    /**
     * OpenAI-family headers (OpenAI, OpenAI Responses, Azure OpenAI).
     * <p>
     * Example real headers from the OpenAI API:
     * <pre>
     * x-ratelimit-limit-requests: 60
     * x-ratelimit-remaining-requests: 59
     * x-ratelimit-reset-requests: 1s
     * x-ratelimit-limit-tokens: 150000
     * x-ratelimit-remaining-tokens: 149984
     * x-ratelimit-reset-tokens: 6ms
     * </pre>
     */
    private static Map<String, String> openAiHeaders(
        Integer requestLimit, Integer requestRemaining, Long resetSeconds, boolean limited
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (requestLimit != null) {
            headers.put("x-ratelimit-limit-requests", String.valueOf(requestLimit));
        }
        if (requestRemaining != null) {
            headers.put("x-ratelimit-remaining-requests", String.valueOf(requestRemaining));
        }
        if (resetSeconds != null) {
            headers.put("x-ratelimit-reset-requests", resetSeconds + "s");
        }
        if (limited && resetSeconds != null) {
            headers.put("retry-after", String.valueOf(resetSeconds));
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Anthropic headers.
     * <p>
     * Example real headers from the Anthropic API:
     * <pre>
     * anthropic-ratelimit-requests-limit: 60
     * anthropic-ratelimit-requests-remaining: 59
     * anthropic-ratelimit-requests-reset: 2025-01-01T00:01:00Z
     * anthropic-ratelimit-tokens-limit: 100000
     * anthropic-ratelimit-tokens-remaining: 99000
     * anthropic-ratelimit-tokens-reset: 2025-01-01T00:01:00Z
     * </pre>
     */
    private static Map<String, String> anthropicHeaders(
        Integer requestLimit, Integer requestRemaining, Long resetSeconds, long nowEpochSecond, boolean limited
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (requestLimit != null) {
            headers.put("anthropic-ratelimit-requests-limit", String.valueOf(requestLimit));
        }
        if (requestRemaining != null) {
            headers.put("anthropic-ratelimit-requests-remaining", String.valueOf(requestRemaining));
        }
        if (resetSeconds != null) {
            String resetTimestamp = Instant.ofEpochSecond(nowEpochSecond + resetSeconds)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
            headers.put("anthropic-ratelimit-requests-reset", resetTimestamp);
        }
        if (limited && resetSeconds != null) {
            headers.put("retry-after", String.valueOf(resetSeconds));
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Google Gemini headers — only {@code retry-after} on a 429.
     */
    private static Map<String, String> geminiHeaders(Long resetSeconds, boolean limited) {
        if (limited && resetSeconds != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("retry-after", String.valueOf(resetSeconds));
            return Collections.unmodifiableMap(headers);
        }
        return Collections.emptyMap();
    }

    /**
     * AWS Bedrock headers — only {@code retry-after} on a 429.
     */
    private static Map<String, String> bedrockHeaders(Long resetSeconds, boolean limited) {
        if (limited && resetSeconds != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("retry-after", String.valueOf(resetSeconds));
            return Collections.unmodifiableMap(headers);
        }
        return Collections.emptyMap();
    }
}
