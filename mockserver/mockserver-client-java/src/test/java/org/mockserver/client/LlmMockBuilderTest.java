package org.mockserver.client;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.EmbeddingResponse.embedding;
import static org.mockserver.model.ToolUse.toolUse;

public class LlmMockBuilderTest {

    @Test
    public void shouldBuildAnthropicTextCompletion() {
        // given / when
        Expectation expectation = llmMock("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .respondingWith(
                completion()
                    .withText("The capital of France is Paris.")
                    .withStopReason("end_turn")
                    .withUsage(Usage.usage().withInputTokens(42).withOutputTokens(8))
            )
            .build();

        // then
        assertThat(expectation, is(notNullValue()));
        HttpRequest request = (HttpRequest) expectation.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getPath().getValue(), is("/v1/messages"));

        HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
        assertThat(llmResponse, is(notNullValue()));
        assertThat(llmResponse.getProvider(), is(Provider.ANTHROPIC));
        assertThat(llmResponse.getModel(), is("claude-sonnet-4-20250514"));
        assertThat(llmResponse.getCompletion(), is(notNullValue()));
        assertThat(llmResponse.getCompletion().getText(), is("The capital of France is Paris."));
        assertThat(llmResponse.getCompletion().getStopReason(), is("end_turn"));
        assertThat(llmResponse.getCompletion().getUsage().getInputTokens(), is(42));
        assertThat(llmResponse.getCompletion().getUsage().getOutputTokens(), is(8));
    }

    @Test
    public void shouldBuildOpenAiCompletion() {
        // when
        Expectation expectation = llmMock("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .respondingWith(
                completion().withText("Hello").withStopReason("stop")
            )
            .build();

        // then
        HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
        assertThat(llmResponse.getProvider(), is(Provider.OPENAI));
        assertThat(llmResponse.getModel(), is("gpt-4o"));
    }

    @Test
    public void shouldBuildWithToolCalls() {
        // when
        Expectation expectation = llmMock("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .respondingWith(
                completion()
                    .withText("Let me check.")
                    .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"Paris\"}"))
                    .withStopReason("tool_use")
            )
            .build();

        // then
        HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
        assertThat(llmResponse.getCompletion().getToolCalls(), is(notNullValue()));
        assertThat(llmResponse.getCompletion().getToolCalls().size(), is(1));
        assertThat(llmResponse.getCompletion().getToolCalls().get(0).getName(), is("get_weather"));
    }

    @Test
    public void shouldBuildWithEmbedding() {
        // when
        Expectation expectation = llmMock("/v1/embeddings")
            .withProvider(Provider.OPENAI)
            .respondingWith(
                embedding()
                    .withDimensions(1536)
                    .withDeterministicFromInput(true)
            )
            .build();

        // then
        HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
        assertThat(llmResponse.getEmbedding(), is(notNullValue()));
        assertThat(llmResponse.getEmbedding().getDimensions(), is(1536));
        assertThat(llmResponse.getEmbedding().getDeterministicFromInput(), is(true));
        assertThat(llmResponse.getCompletion(), is(nullValue()));
    }

    @Test
    public void shouldBuildWithStreaming() {
        // when
        Expectation expectation = llmMock("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .respondingWith(
                completion()
                    .withText("Streaming response")
                    .withStreaming(true)
                    .withStreamingPhysics(
                        StreamingPhysics.streamingPhysics()
                            .withTokensPerSecond(100)
                            .withJitter(0.1)
                    )
            )
            .build();

        // then
        HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
        assertThat(llmResponse.getCompletion().getStreaming(), is(true));
        assertThat(llmResponse.getCompletion().getStreamingPhysics(), is(notNullValue()));
        assertThat(llmResponse.getCompletion().getStreamingPhysics().getTokensPerSecond(), is(100));
    }

    @Test
    public void shouldSwitchFromCompletionToEmbeddingOnSecondRespondingWith() {
        // when — calling respondingWith(embedding) after respondingWith(completion)
        Expectation expectation = llmMock("/v1/embeddings")
            .withProvider(Provider.OPENAI)
            .respondingWith(completion().withText("text"))
            .respondingWith(embedding().withDimensions(128))
            .build();

        // then — embedding wins, completion is null
        HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
        assertThat(llmResponse.getEmbedding(), is(notNullValue()));
        assertThat(llmResponse.getCompletion(), is(nullValue()));
    }

    @Test
    public void shouldBuildWithNoModel() {
        // when — model is optional
        Expectation expectation = llmMock("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .respondingWith(completion().withText("test"))
            .build();

        // then
        assertThat(expectation.getHttpLlmResponse().getModel(), is(nullValue()));
    }
}
