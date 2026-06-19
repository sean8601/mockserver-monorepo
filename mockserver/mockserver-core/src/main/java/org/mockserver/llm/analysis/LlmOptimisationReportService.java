package org.mockserver.llm.analysis;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Application service that turns captured {@code REQUEST_RESPONSES} log entries
 * into an {@link LlmOptimisationReport} (JSON bundle) or a rendered Markdown
 * brief, applying the optional {@code session}/{@code host}/{@code provider}
 * filters, redaction, and the {@code mockserver.llmOptimisationMaxCalls} bound.
 * <p>
 * Lives in mockserver-core so both the control-plane REST endpoint and the
 * {@code export_optimisation_report} MCP tool (mockserver-netty) share one
 * implementation. Pure of transport: callers pass already-retrieved
 * request/response pairs.
 */
public class LlmOptimisationReportService {

    private final LlmOptimisationReportBuilder builder = new LlmOptimisationReportBuilder();
    private final LlmOptimisationBriefRenderer renderer = new LlmOptimisationBriefRenderer();

    /** Optional filters; null/blank means "no filter". */
    public static final class Filter {
        private final String session;
        private final String host;
        private final String provider;

        public Filter(String session, String host, String provider) {
            this.session = blankToNull(session);
            this.host = blankToNull(host);
            this.provider = blankToNull(provider);
        }

        private static String blankToNull(String s) {
            return s == null || s.trim().isEmpty() ? null : s.trim();
        }
    }

    /** The built report plus the markdown brief, so callers render once. */
    public static final class Result {
        private final LlmOptimisationReport report;
        private final List<CapturedExchange> includedExchanges;

        Result(LlmOptimisationReport report, List<CapturedExchange> includedExchanges) {
            this.report = report;
            this.includedExchanges = includedExchanges;
        }

        public LlmOptimisationReport getReport() {
            return report;
        }

        public List<CapturedExchange> getIncludedExchanges() {
            return includedExchanges;
        }
    }

    /**
     * Build a report from the given recorded pairs and filter.
     */
    public Result build(List<LogEventRequestAndResponse> pairs, Filter filter) {
        Filter f = filter != null ? filter : new Filter(null, null, null);

        List<String> redactedBodyFields = bodyFields();
        List<String> redactedHeaders = new ArrayList<>(FixtureRedactor.defaultSensitiveHeaders());

        // Filter to LLM traffic matching the host/provider filter; collect exchanges + group key.
        List<CapturedExchange> exchanges = new ArrayList<>();
        String groupingKey = null;
        if (pairs != null) {
            for (LogEventRequestAndResponse pair : pairs) {
                if (!(pair.getHttpRequest() instanceof HttpRequest)) {
                    continue;
                }
                HttpRequest request = (HttpRequest) pair.getHttpRequest();
                Optional<Provider> providerOpt = LlmProviderSniffer.sniff(request);
                if (!providerOpt.isPresent()) {
                    continue; // not LLM traffic
                }
                Provider provider = providerOpt.get();
                String host = upstreamHost(request);

                if (f.host != null && (host == null || !host.equalsIgnoreCase(f.host))) {
                    continue;
                }
                if (f.provider != null && !provider.name().equalsIgnoreCase(f.provider)) {
                    continue;
                }
                String key = "host:" + (host != null ? host : "unknown");
                if (f.session != null && !f.session.equalsIgnoreCase(key)) {
                    continue;
                }
                if (groupingKey == null) {
                    groupingKey = key;
                }
                HttpResponse response = pair.getHttpResponse();
                exchanges.add(new CapturedExchange(request, response, null));
            }
        }

        // Bound the report size: keep the most recent N calls.
        int maxCalls = ConfigurationProperties.llmOptimisationMaxCalls();
        if (maxCalls > 0 && exchanges.size() > maxCalls) {
            exchanges = new ArrayList<>(exchanges.subList(exchanges.size() - maxCalls, exchanges.size()));
        }

        if (groupingKey == null) {
            groupingKey = f.session != null ? f.session : "all";
        }

        LlmOptimisationReport report = builder.build(exchanges, groupingKey,
            LlmOptimisationReport.GroupingBasis.PROXY_HOST, redactedHeaders, redactedBodyFields);
        return new Result(report, exchanges);
    }

    /** Render the brief for a previously built result, redacting the appendix. */
    public String renderBrief(Result result) {
        FixtureRedactor redactor = redactor();
        return renderer.render(result.getReport(), result.getIncludedExchanges(), redactor);
    }

    private FixtureRedactor redactor() {
        List<String> bodyFields = bodyFields();
        return bodyFields.isEmpty()
            ? new FixtureRedactor()
            : new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), bodyFields);
    }

    private static List<String> bodyFields() {
        String configured = ConfigurationProperties.fixtureBodyRedactFields();
        List<String> result = new ArrayList<>();
        if (configured != null && !configured.trim().isEmpty()) {
            for (String field : configured.split(",")) {
                String trimmed = field.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static String upstreamHost(HttpRequest request) {
        if (request.getSocketAddress() != null && request.getSocketAddress().getHost() != null
            && !request.getSocketAddress().getHost().isEmpty()) {
            return stripPort(request.getSocketAddress().getHost());
        }
        String hostHeader = request.getFirstHeader("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            return stripPort(hostHeader);
        }
        return null;
    }

    private static String stripPort(String hostMaybeWithPort) {
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

    /** The redacted-header list reported in the bundle, exposed for tests/callers. */
    public List<String> redactedHeaderNames() {
        return new ArrayList<>(FixtureRedactor.defaultSensitiveHeaders());
    }

    /** The configured redacted-body-field list, exposed for tests/callers. */
    public List<String> redactedBodyFieldNames() {
        return new ArrayList<>(bodyFields());
    }
}
