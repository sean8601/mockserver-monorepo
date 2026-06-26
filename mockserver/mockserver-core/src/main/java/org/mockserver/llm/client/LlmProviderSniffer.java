package org.mockserver.llm.client;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.net.URI;
import java.util.Optional;

/**
 * Maps a forwarded request's target host/URL to an LLM {@link Provider}, enabling
 * GenAI observability on the proxy/forward path. Pure function of its inputs (request
 * host/path, configuration) — no shared mutable state.
 * <p>
 * Detection order:
 * <ol>
 *   <li>Well-known provider hosts (exact or wildcard match), including the OpenAI
 *       Codex backend ({@code chatgpt.com}) used by coding CLIs such as opencode,
 *       which serves the Responses API at {@code /backend-api/codex/responses}</li>
 *   <li>Configured {@code mockserver.llmBaseUrl} Ollama host match</li>
 *   <li>Fallback to configured {@code mockserver.llmProvider} — only when the
 *       request path looks like an LLM endpoint (contains any of
 *       {@code /chat/completions}, {@code /messages}, {@code /completions},
 *       {@code /responses}, {@code /embeddings}, {@code /v1/},
 *       {@code :generatecontent}, {@code /api/generate}, {@code /api/chat})</li>
 * </ol>
 * Returns {@link Optional#empty()} when the request is not LLM traffic.
 */
public final class LlmProviderSniffer {

    private LlmProviderSniffer() {
    }

    /**
     * Path fragments that indicate an LLM endpoint. Used to gate the configured-provider
     * fallback so non-LLM traffic to unknown hosts is never misclassified.
     * Checked case-insensitively.
     */
    private static final String[] LLM_PATH_FRAGMENTS = {
        "/chat/completions",
        "/messages",
        "/completions",
        "/responses",
        "/embeddings",
        "/v1/",
        ":generatecontent",
        "/api/generate",
        "/api/chat",
    };

    // Path-shape patterns for offline analysis detection (sniffByPath), mirroring
    // the dashboard's llmTraffic.ts. Matched against the original (case-sensitive)
    // path because the model name and method are case-sensitive.
    private static final java.util.regex.Pattern GEMINI_PATH_PATTERN = java.util.regex.Pattern.compile(
        "/v1beta/models/[^/]+:(generateContent|streamGenerateContent)"
            + "|/v1/models/gemini-[^/]+:(generateContent|streamGenerateContent)");
    private static final java.util.regex.Pattern OLLAMA_CHAT_PATTERN = java.util.regex.Pattern.compile(
        "(^|/)api/chat(/?$|\\?)");
    // OpenAI Responses API path: the standard hosted path (/v1/responses) and the
    // OpenAI Codex backend used by coding CLIs such as opencode, which serves the
    // same Responses wire format at chatgpt.com/backend-api/codex/responses.
    // Matched case-insensitively (callers lower-case the path before matching).
    private static final java.util.regex.Pattern OPENAI_RESPONSES_PATH_PATTERN = java.util.regex.Pattern.compile(
        "/v1/responses|/codex/responses");

    /**
     * Sniff the LLM provider from a forwarded request's target host.
     *
     * @param forwardedRequest the request that was forwarded to the upstream
     * @return the detected provider, or empty if this is not LLM traffic
     */
    public static Optional<Provider> sniff(HttpRequest forwardedRequest) {
        if (forwardedRequest == null) {
            return Optional.empty();
        }
        String path = forwardedRequest.getPath() != null
            ? forwardedRequest.getPath().getValue() : null;
        return sniffByHostAndPath(extractHost(forwardedRequest), path);
    }

    /**
     * Detect the LLM provider for OFFLINE analysis of already-captured traffic
     * (e.g. the optimisation report). Recognises LLM traffic that {@link #sniff}
     * cannot — most importantly MOCKED traffic served by MockServer itself on
     * localhost, where there is no upstream provider host. Tries the host-based
     * {@link #sniff} first, then falls back to {@link #sniffByPath}, which mirrors
     * the dashboard's client-side path detection so the SAME traffic appears in
     * both the Sessions view and the optimisation report.
     *
     * <p>Intended only for read-only analysis. The live forward/span path must
     * keep using host-gated {@link #sniff} so forwarded non-LLM traffic is never
     * mis-classified.
     */
    public static Optional<Provider> detectForAnalysis(HttpRequest request) {
        return detectForAnalysis(request, null);
    }

    /**
     * Detect the LLM provider for OFFLINE analysis, using the response body as an
     * additional, more resilient signal. Detection order, cheapest/most-specific
     * first:
     * <ol>
     *   <li>well-known host ({@link #sniff})</li>
     *   <li>recognised URL path shape ({@link #sniffByPath})</li>
     *   <li><b>request/response body shape</b> ({@link #sniffByBodyShape}) — the
     *       resilient fallback that recognises LLM traffic from the wire format
     *       itself, so a coding CLI that routes through an unknown host or a
     *       non-standard path (e.g. a future endpoint rename, a private gateway,
     *       a new tool) is still classified without a code change.</li>
     * </ol>
     * The body shape is the slowest-moving signal — it is the provider's API
     * contract — so keying on it (rather than only on host/path, the dimension
     * that varies most between tools and versions) is what keeps capture working
     * as the LLM APIs and CLI harnesses evolve.
     */
    public static Optional<Provider> detectForAnalysis(HttpRequest request, org.mockserver.model.HttpResponse response) {
        Optional<Provider> byHost = sniff(request);
        if (byHost.isPresent()) {
            return byHost;
        }
        Optional<Provider> byPath = sniffByPath(request);
        if (byPath.isPresent()) {
            return byPath;
        }
        return sniffByBodyShape(request, response);
    }

    /**
     * Provider detection from the request/response <em>body shape</em> alone — no
     * host or path required. This is the resilient fallback for {@link
     * #detectForAnalysis}: the wire format is the provider's API contract and moves
     * far more slowly than the host/path a given CLI happens to use, so recognising
     * LLM traffic by its body keeps the Traffic / LLM Traces / LLM Optimise views
     * working when a tool changes endpoints or a new tool appears.
     *
     * <p>Keys on the most stable, provider-distinctive markers and stays
     * conservative (returns empty rather than guess) so non-LLM traffic is not
     * mis-classified. Read-only analysis use only — never the live forward path.
     */
    public static Optional<Provider> sniffByBodyShape(HttpRequest request, org.mockserver.model.HttpResponse response) {
        // Response markers are the most distinctive — check them first.
        String resBody = response != null ? safeBody(response.getBodyAsString()) : null;
        if (resBody != null) {
            // OpenAI Chat Completions: object "chat.completion" / "chat.completion.chunk".
            // Require the JSON value's opening quote so a stray substring can't match.
            if (resBody.contains("\"chat.completion")) {
                return Optional.of(Provider.OPENAI);
            }
            // OpenAI Responses API: response.* streaming events or object "response".
            if (resBody.contains("response.output_text")
                || resBody.contains("response.created")
                || resBody.contains("response.completed")
                || resBody.contains("\"object\":\"response\"")
                || resBody.contains("\"object\": \"response\"")) {
                return Optional.of(Provider.OPENAI_RESPONSES);
            }
            // Anthropic Messages: streaming content_block / message_start, or a
            // non-streamed message envelope with a stop_reason.
            if (resBody.contains("content_block")
                || resBody.contains("message_start")
                || (resBody.contains("\"type\":\"message\"") && resBody.contains("stop_reason"))) {
                return Optional.of(Provider.ANTHROPIC);
            }
            // Gemini: candidates[] plus usage metadata.
            if (resBody.contains("\"candidates\"") && resBody.contains("usageMetadata")) {
                return Optional.of(Provider.GEMINI);
            }
        }

        // Request markers — used when the response is absent or unrecognised.
        if (request == null) {
            return Optional.empty();
        }
        // Anthropic requires the anthropic-version header on every request.
        String anthropicVersion = request.getFirstHeader("anthropic-version");
        if (anthropicVersion != null && !anthropicVersion.isEmpty()) {
            return Optional.of(Provider.ANTHROPIC);
        }
        String reqBody = safeBody(request.getBodyAsString());
        if (reqBody == null) {
            return Optional.empty();
        }
        boolean hasModel = reqBody.contains("\"model\"");
        // OpenAI Responses: the hallmark top-level "input" array (Chat Completions
        // and Anthropic both use "messages" instead).
        if (hasModel && reqBody.contains("\"input\"") && !reqBody.contains("\"messages\"")) {
            return Optional.of(Provider.OPENAI_RESPONSES);
        }
        // Gemini: top-level "contents" array (+ a Gemini-specific companion field).
        if (reqBody.contains("\"contents\"")
            && (reqBody.contains("\"parts\"") || reqBody.contains("generationConfig"))) {
            return Optional.of(Provider.GEMINI);
        }
        // OpenAI Chat Completions: model + messages. (Anthropic is already caught by
        // its required header and by the response-shape check above.)
        if (hasModel && reqBody.contains("\"messages\"")) {
            return Optional.of(Provider.OPENAI);
        }
        return Optional.empty();
    }

    private static String safeBody(String body) {
        return body == null || body.isEmpty() ? null : body;
    }

    /**
     * Path-shape based provider detection, mirroring the dashboard's
     * {@code llmTraffic.ts} ordering (Anthropic, Azure, Bedrock, OpenAI Responses,
     * OpenAI, Gemini, Ollama). Used to analyse captured traffic whose host is not a
     * known provider (e.g. mocked LLM responses on localhost).
     */
    public static Optional<Provider> sniffByPath(HttpRequest request) {
        if (request == null || request.getPath() == null || request.getPath().getValue() == null) {
            return Optional.empty();
        }
        String path = request.getPath().getValue();
        String lower = path.toLowerCase();
        if (lower.contains("/v1/messages")) {
            return Optional.of(Provider.ANTHROPIC);
        }
        if (lower.contains("/openai/deployments/") && lower.contains("/chat/completions")) {
            return Optional.of(Provider.AZURE_OPENAI);
        }
        if (lower.contains("/model/anthropic.") && lower.contains("/invoke")) {
            return Optional.of(Provider.BEDROCK);
        }
        if (OPENAI_RESPONSES_PATH_PATTERN.matcher(lower).find()) {
            return Optional.of(Provider.OPENAI_RESPONSES);
        }
        if (lower.contains("/chat/completions")) {
            return Optional.of(Provider.OPENAI);
        }
        if (GEMINI_PATH_PATTERN.matcher(path).find()) {
            return Optional.of(Provider.GEMINI);
        }
        if (OLLAMA_CHAT_PATTERN.matcher(path).find()) {
            return Optional.of(Provider.OLLAMA);
        }
        return Optional.empty();
    }

    /**
     * Sniff the LLM provider from an explicit host string (for unit testing or
     * callers that already have the host extracted). Equivalent to
     * {@code sniffByHostAndPath(host, null)} — the configured-provider fallback
     * is only applied when the path looks like an LLM endpoint, so with a null
     * path the fallback is skipped.
     */
    public static Optional<Provider> sniffByHost(String host) {
        return sniffByHostAndPath(host, null);
    }

    /**
     * Sniff the LLM provider from an explicit host and path.
     *
     * @param host the target host (may be null)
     * @param path the request path (may be null) — used only for the
     *             configured-provider fallback gate
     */
    public static Optional<Provider> sniffByHostAndPath(String host, String path) {
        if (host == null || host.isEmpty()) {
            return fallbackToConfiguredProvider(path);
        }
        String lowerHost = host.toLowerCase();

        // Well-known provider hosts — no path gate required
        if (lowerHost.equals("api.openai.com")) {
            // Distinguish the OpenAI Responses API (/responses) from the
            // Chat Completions API so LlmClientRegistry uses the correct parser
            if (path != null && path.toLowerCase().contains("/responses")) {
                return Optional.of(Provider.OPENAI_RESPONSES);
            }
            return Optional.of(Provider.OPENAI);
        }
        if (lowerHost.endsWith(".openai.azure.com")) {
            return Optional.of(Provider.AZURE_OPENAI);
        }
        if (lowerHost.equals("api.anthropic.com")) {
            return Optional.of(Provider.ANTHROPIC);
        }
        // OpenAI Codex backend used by coding CLIs (e.g. opencode): chatgpt.com serves
        // the Responses API at /backend-api/codex/responses. Path-gated so non-LLM
        // chatgpt.com traffic (oauth, account, etc.) is never misclassified.
        if (lowerHost.equals("chatgpt.com") || lowerHost.endsWith(".chatgpt.com")) {
            if (path != null && OPENAI_RESPONSES_PATH_PATTERN.matcher(path.toLowerCase()).find()) {
                return Optional.of(Provider.OPENAI_RESPONSES);
            }
        }
        if (lowerHost.equals("generativelanguage.googleapis.com")) {
            return Optional.of(Provider.GEMINI);
        }
        if (isBedrockHost(lowerHost)) {
            return Optional.of(Provider.BEDROCK);
        }

        // Configured Ollama host: match against mockserver.llmBaseUrl
        String configuredBaseUrl = ConfigurationProperties.llmBaseUrl();
        if (configuredBaseUrl != null && !configuredBaseUrl.isEmpty()) {
            String configuredHost = extractHostFromUrl(configuredBaseUrl);
            if (configuredHost != null && lowerHost.equals(configuredHost.toLowerCase())) {
                return Optional.of(Provider.OLLAMA);
            }
        }

        // Fallback: configured mockserver.llmProvider — path-gated
        return fallbackToConfiguredProvider(path);
    }

    /**
     * Check if the host matches the Bedrock pattern: the host starts with "bedrock"
     * or contains ".bedrock" and ends with ".amazonaws.com".
     * Examples: bedrock-runtime.us-east-1.amazonaws.com, bedrock.eu-west-1.amazonaws.com
     */
    private static boolean isBedrockHost(String lowerHost) {
        if (!lowerHost.endsWith(".amazonaws.com")) {
            return false;
        }
        return lowerHost.startsWith("bedrock") || lowerHost.contains(".bedrock");
    }

    /**
     * Fallback to the configured {@code mockserver.llmProvider}, but only when
     * the request path looks like an LLM endpoint. This prevents a forward to
     * e.g. {@code example.com/api/users} from being misclassified as LLM traffic
     * just because a provider is configured.
     */
    private static Optional<Provider> fallbackToConfiguredProvider(String path) {
        if (!looksLikeLlmPath(path)) {
            return Optional.empty();
        }
        String configured = ConfigurationProperties.llmProvider();
        if (configured != null && !configured.isEmpty()) {
            try {
                return Optional.of(Provider.valueOf(configured.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // unrecognised provider name — not LLM traffic
            }
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} when the path (case-insensitive) contains any of the
     * well-known LLM endpoint fragments.
     */
    static boolean looksLikeLlmPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String lowerPath = path.toLowerCase();
        for (String fragment : LLM_PATH_FRAGMENTS) {
            if (lowerPath.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the host from the forwarded request. Prefers the socket address
     * host (set by the forward/proxy path), falling back to the Host header,
     * then the request path if it looks like a URL.
     */
    private static String extractHost(HttpRequest request) {
        // Socket address is the most reliable source on the forward path
        if (request.getSocketAddress() != null
            && request.getSocketAddress().getHost() != null
            && !request.getSocketAddress().getHost().isEmpty()) {
            return stripPort(request.getSocketAddress().getHost());
        }
        // Host header
        String hostHeader = request.getFirstHeader("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            return stripPort(hostHeader);
        }
        return null;
    }

    private static String extractHostFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripPort(String hostMaybeWithPort) {
        // Handle IPv6 addresses in brackets
        if (hostMaybeWithPort.startsWith("[")) {
            int closeBracket = hostMaybeWithPort.indexOf(']');
            if (closeBracket >= 0) {
                return hostMaybeWithPort.substring(1, closeBracket);
            }
        }
        int colon = hostMaybeWithPort.lastIndexOf(':');
        if (colon > 0) {
            return hostMaybeWithPort.substring(0, colon);
        }
        return hostMaybeWithPort;
    }

    /**
     * Extract the model name from the forwarded response body. Providers typically
     * include a {@code "model"} field in their JSON response (OpenAI, Anthropic,
     * Gemini use this pattern). Returns null if extraction fails.
     */
    public static String extractModelFromResponse(org.mockserver.model.HttpResponse response) {
        if (response == null) {
            return null;
        }
        try {
            String body = response.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().readTree(body);
            if (root != null && root.has("model")) {
                String model = root.path("model").asText();
                if (model != null && !model.isEmpty()) {
                    return model;
                }
            }
        } catch (Exception ignored) {
            // fail-soft: not parseable as JSON or no model field
        }
        return null;
    }

    /**
     * Extract the model name from the forwarded request body. Providers typically
     * include a {@code "model"} field in their JSON request body. Returns null if
     * extraction fails.
     */
    public static String extractModelFromRequest(HttpRequest request) {
        if (request == null) {
            return null;
        }
        try {
            String body = request.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().readTree(body);
            if (root != null && root.has("model")) {
                String model = root.path("model").asText();
                if (model != null && !model.isEmpty()) {
                    return model;
                }
            }
        } catch (Exception ignored) {
            // fail-soft: not parseable as JSON or no model field
        }
        return null;
    }
}
