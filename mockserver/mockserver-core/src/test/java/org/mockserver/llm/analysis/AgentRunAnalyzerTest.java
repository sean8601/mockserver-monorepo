package org.mockserver.llm.analysis;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

public class AgentRunAnalyzerTest {

    private final AgentRunAnalyzer analyzer = new AgentRunAnalyzer();

    // Anthropic conversation where the assistant called get_weather and a tool result came back.
    private static HttpRequest toolUseConversation() {
        return request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"What is the weather in Paris?\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "       {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"get_weather\", \"input\": {\"city\": \"Paris\"}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "       {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": \"sunny\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");
    }

    @Test
    public void countsMatchingToolCalls() {
        List<HttpRequest> requests = Collections.singletonList(toolUseConversation());
        AgentRunAnalyzer.ToolCallReport report = analyzer.inspectToolCalls(requests, Provider.ANTHROPIC, "get_weather", null);
        assertThat(report.getCount(), is(1));
        assertThat(report.getMatches().get(0).getName(), is("get_weather"));
    }

    @Test
    public void doesNotCountToolCallsForOtherNames() {
        List<HttpRequest> requests = Collections.singletonList(toolUseConversation());
        AgentRunAnalyzer.ToolCallReport report = analyzer.inspectToolCalls(requests, Provider.ANTHROPIC, "send_email", null);
        assertThat(report.getCount(), is(0));
    }

    @Test
    public void filtersToolCallsByArgumentsRegex() {
        List<HttpRequest> requests = Collections.singletonList(toolUseConversation());
        assertThat(analyzer.inspectToolCalls(requests, Provider.ANTHROPIC, "get_weather", "Paris").getCount(), is(1));
        assertThat(analyzer.inspectToolCalls(requests, Provider.ANTHROPIC, "get_weather", "London").getCount(), is(0));
    }

    @Test
    public void summarisesRunStructure() {
        List<HttpRequest> requests = Collections.singletonList(toolUseConversation());
        Optional<AgentRunAnalyzer.RunSummary> summary = analyzer.summarise(requests, Provider.ANTHROPIC);
        assertThat(summary.isPresent(), is(true));
        assertThat(summary.get().getMessageCount(), is(3));
        assertThat(summary.get().getAssistantTurnCount(), is(1));
        assertThat(summary.get().getToolCallSequence(), contains("get_weather"));
        assertThat(summary.get().getLatestMessageRole(), is("TOOL"));
    }

    @Test
    public void picksRichestConversationAcrossSnapshots() {
        // an early snapshot (1 message) and the full one (3 messages) — analysis uses the richest
        HttpRequest early = request().withBody("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        List<HttpRequest> requests = Arrays.asList(early, toolUseConversation());
        assertThat(analyzer.summarise(requests, Provider.ANTHROPIC).get().getMessageCount(), is(3));
        assertThat(analyzer.inspectToolCalls(requests, Provider.ANTHROPIC, "get_weather", null).getCount(), is(1));
    }

    @Test
    public void emptyWhenNothingDecodes() {
        List<HttpRequest> requests = Collections.singletonList(request().withBody("not json"));
        assertThat(analyzer.summarise(requests, Provider.ANTHROPIC).isPresent(), is(false));
        assertThat(analyzer.inspectToolCalls(requests, Provider.ANTHROPIC, "x", null).getCount(), is(0));
    }

    @Test
    public void toolResultsForReportsCorrelatedToolNames() {
        List<HttpRequest> requests = Collections.singletonList(toolUseConversation());
        // Anthropic correlates tool_result by tool_use_id; the analyzer reports the raw result keys
        Optional<AgentRunAnalyzer.RunSummary> summary = analyzer.summarise(requests, Provider.ANTHROPIC);
        assertThat(summary.get().getToolResultsFor(), hasItem("toolu_1"));
    }
}
