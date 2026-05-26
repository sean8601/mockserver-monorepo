package org.mockserver.client;

import org.junit.Test;
import org.mockserver.llm.IsolationSource;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.matchers.LlmConversationMatcher;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Completion;
import org.mockserver.model.ConversationPredicates;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;

import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.client.LlmConversationBuilder.conversation;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;

public class LlmConversationBuilderTest {

    @Test
    public void shouldBuildTwoTurnConversation() {
        // given/when
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withToolCall(toolUse("search").withArguments("{\"q\":\"test\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("search")
                .respondingWith(completion()
                    .withText("The answer is 42.")
                    .withStopReason("end_turn"))
            .andThen()
            .build();

        // then
        assertThat(expectations.length, is(2));

        // First turn
        Expectation turn0 = expectations[0];
        assertThat(turn0.getScenarioName(), is(notNullValue()));
        assertThat(turn0.getScenarioState(), is("Started"));
        assertThat(turn0.getNewScenarioState(), is("turn_1"));
        assertThat(turn0.getHttpLlmResponse(), is(notNullValue()));
        assertThat(turn0.getHttpLlmResponse().getProvider(), is(Provider.ANTHROPIC));
        assertThat(turn0.getHttpLlmResponse().getModel(), is("claude-sonnet-4-20250514"));
        assertThat(turn0.getHttpLlmResponse().getCompletion().getStopReason(), is("tool_use"));

        // Verify predicates are serialisable on the response
        ConversationPredicates preds0 = turn0.getHttpLlmResponse().getConversationPredicates();
        assertThat(preds0, is(notNullValue()));
        assertThat(preds0.getTurnIndex(), is(0));

        // Verify lazy-init matcher also works
        LlmConversationMatcher matcher0 = turn0.getHttpLlmResponse().getConversationMatcher();
        assertThat(matcher0, is(notNullValue()));
        assertThat(matcher0.getTurnIndex(), is(0));
        assertThat(matcher0.getProvider(), is(Provider.ANTHROPIC));

        // Second turn
        Expectation turn1 = expectations[1];
        assertThat(turn1.getScenarioName(), is(turn0.getScenarioName())); // Same scenario
        assertThat(turn1.getScenarioState(), is("turn_1"));
        assertThat(turn1.getNewScenarioState(), is("__done"));
        assertThat(turn1.getHttpLlmResponse().getCompletion().getText(), is("The answer is 42."));

        ConversationPredicates preds1 = turn1.getHttpLlmResponse().getConversationPredicates();
        assertThat(preds1, is(notNullValue()));
        assertThat(preds1.getContainsToolResultFor(), is("search"));

        LlmConversationMatcher matcher1 = turn1.getHttpLlmResponse().getConversationMatcher();
        assertThat(matcher1, is(notNullValue()));
        assertThat(matcher1.getContainsToolResultFor(), is("search"));
    }

    @Test
    public void shouldBuildWithIsolation() {
        // given/when
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .isolateBy(IsolationSource.header("x-session-id"))
            .turn()
                .respondingWith(completion().withText("Hello"))
            .andThen()
            .build();

        // then
        assertThat(expectations.length, is(1));
        String scenarioName = expectations[0].getScenarioName();
        assertThat(scenarioName, containsString("__iso=header:x-session-id"));

        // Verify decode
        IsolationSource decoded = LlmConversationBuilder.decodeIsolationSource(scenarioName);
        assertThat(decoded, is(IsolationSource.header("x-session-id")));
    }

    @Test
    public void shouldBuildWithQueryParameterIsolation() {
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.OPENAI)
            .isolateBy(IsolationSource.queryParameter("agent"))
            .turn()
                .respondingWith(completion().withText("Hi"))
            .andThen()
            .build();

        String scenarioName = expectations[0].getScenarioName();
        IsolationSource decoded = LlmConversationBuilder.decodeIsolationSource(scenarioName);
        assertThat(decoded, is(IsolationSource.queryParameter("agent")));
    }

    @Test
    public void shouldBuildWithCookieIsolation() {
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.OPENAI)
            .isolateBy(IsolationSource.cookie("sid"))
            .turn()
                .respondingWith(completion().withText("Hi"))
            .andThen()
            .build();

        String scenarioName = expectations[0].getScenarioName();
        IsolationSource decoded = LlmConversationBuilder.decodeIsolationSource(scenarioName);
        assertThat(decoded, is(IsolationSource.cookie("sid")));
    }

    @Test
    public void shouldBuildWithoutIsolation() {
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .turn()
                .respondingWith(completion().withText("Hello"))
            .andThen()
            .build();

        String scenarioName = expectations[0].getScenarioName();
        assertThat(scenarioName, not(containsString("__iso=")));

        IsolationSource decoded = LlmConversationBuilder.decodeIsolationSource(scenarioName);
        assertThat(decoded, is(nullValue()));
    }

    @Test
    public void shouldBuildThreeTurnConversation() {
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .turn()
                .respondingWith(completion().withText("Turn 0"))
            .andThen()
            .turn()
                .respondingWith(completion().withText("Turn 1"))
            .andThen()
            .turn()
                .respondingWith(completion().withText("Turn 2"))
            .andThen()
            .build();

        assertThat(expectations.length, is(3));
        assertThat(expectations[0].getScenarioState(), is("Started"));
        assertThat(expectations[0].getNewScenarioState(), is("turn_1"));
        assertThat(expectations[1].getScenarioState(), is("turn_1"));
        assertThat(expectations[1].getNewScenarioState(), is("turn_2"));
        assertThat(expectations[2].getScenarioState(), is("turn_2"));
        assertThat(expectations[2].getNewScenarioState(), is("__done"));
    }

    @Test
    public void shouldWireAllPredicatesToMatcher() {
        Pattern regex = Pattern.compile("test.*pattern");

        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.OPENAI)
            .turn()
                .whenTurnIndex(1)
                .whenLatestMessageContains("hello")
                .whenLatestMessageContains(regex)
                .whenLatestMessageRole(ParsedMessage.Role.TOOL)
                .whenContainsToolResultFor("search")
                .respondingWith(completion().withText("Matched"))
            .andThen()
            .build();

        // Verify serialisable predicates
        ConversationPredicates preds = expectations[0].getHttpLlmResponse().getConversationPredicates();
        assertThat(preds, is(notNullValue()));
        assertThat(preds.getTurnIndex(), is(1));
        assertThat(preds.getLatestMessageContains(), is("hello"));
        assertThat(preds.getLatestMessageMatches(), is("test.*pattern"));
        assertThat(preds.getLatestMessageRole(), is(ParsedMessage.Role.TOOL));
        assertThat(preds.getContainsToolResultFor(), is("search"));

        // Verify lazy-reconstructed matcher
        LlmConversationMatcher matcher = expectations[0].getHttpLlmResponse().getConversationMatcher();
        assertThat(matcher.getTurnIndex(), is(1));
        assertThat(matcher.getLatestMessageContains(), is("hello"));
        assertThat(matcher.getLatestMessageMatches().pattern(), is("test.*pattern"));
        assertThat(matcher.getLatestMessageRole(), is(ParsedMessage.Role.TOOL));
        assertThat(matcher.getContainsToolResultFor(), is("search"));
        assertThat(matcher.getProvider(), is(Provider.OPENAI));
    }

    @Test
    public void shouldSetPostMethodOnRequest() {
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .turn()
                .respondingWith(completion().withText("Hello"))
            .andThen()
            .build();

        assertThat(expectations[0].getHttpRequest().toString(), containsString("POST"));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenNoTurns() {
        conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .build();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenNoPath() {
        conversation()
            .withProvider(Provider.ANTHROPIC)
            .turn()
                .respondingWith(completion().withText("Hello"))
            .andThen()
            .build();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenNoProvider() {
        conversation()
            .withPath("/v1/messages")
            .turn()
                .respondingWith(completion().withText("Hello"))
            .andThen()
            .build();
    }

    @Test
    public void shouldAllTurnsShareSameScenarioName() {
        Expectation[] expectations = conversation()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .turn()
                .respondingWith(completion().withText("A"))
            .andThen()
            .turn()
                .respondingWith(completion().withText("B"))
            .andThen()
            .build();

        assertThat(expectations[0].getScenarioName(), is(expectations[1].getScenarioName()));
    }

    @Test
    public void shouldBaseScenarioNameStripIsolation() {
        String full = "__llm_conv_abc__iso=header:x-id";
        assertThat(LlmConversationBuilder.baseScenarioName(full), is("__llm_conv_abc"));
    }

    @Test
    public void shouldBaseScenarioNameReturnFullWhenNoIsolation() {
        String name = "__llm_conv_abc";
        assertThat(LlmConversationBuilder.baseScenarioName(name), is(name));
    }
}
