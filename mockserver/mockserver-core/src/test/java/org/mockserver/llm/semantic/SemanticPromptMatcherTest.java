package org.mockserver.llm.semantic;

import org.junit.Test;
import org.mockserver.llm.client.LlmBackend;
import org.mockserver.llm.client.LlmCompletionService;
import org.mockserver.llm.client.LlmTransport;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpResponse.response;

public class SemanticPromptMatcherTest {

    private static SemanticPromptMatcher matcherReturning(String judgeContent, int status) {
        // Ollama-shaped judge response routed through a stub transport
        LlmTransport transport = (req, timeout) -> response().withStatusCode(status)
            .withBody("{\"message\":{\"content\":\"" + judgeContent + "\"}}");
        return new SemanticPromptMatcher(new LlmCompletionService(transport), LlmBackend.of(Provider.OLLAMA, null));
    }

    @Test
    public void matchesWhenJudgeSaysYes() {
        assertThat(matcherReturning("yes", 200).matchesSemantically("what's the weather?", "asking about weather"), is(true));
    }

    @Test
    public void doesNotMatchWhenJudgeSaysNo() {
        assertThat(matcherReturning("no", 200).matchesSemantically("book a flight", "asking about weather"), is(false));
    }

    @Test
    public void toleratesAffirmativeWithPunctuation() {
        assertThat(matcherReturning("Yes.", 200).matchesSemantically("hi", "greeting"), is(true));
    }

    @Test
    public void failsClosedOnServiceError() {
        // non-2xx → LlmCompletionService returns empty → matcher returns false
        assertThat(matcherReturning("yes", 500).matchesSemantically("hi", "greeting"), is(false));
    }

    @Test
    public void failsClosedOnNullInputs() {
        SemanticPromptMatcher matcher = matcherReturning("yes", 200);
        assertThat(matcher.matchesSemantically(null, "x"), is(false));
        assertThat(matcher.matchesSemantically("x", null), is(false));
    }
}
