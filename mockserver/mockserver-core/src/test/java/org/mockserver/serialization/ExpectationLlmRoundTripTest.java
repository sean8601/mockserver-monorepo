package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.EmbeddingResponse.embedding;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.model.Usage.usage;

public class ExpectationLlmRoundTripTest {

    private final ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

    @Test
    public void shouldRoundTripCompletionExpectation() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withText("Hello, world!")
                            .withStopReason("end_turn")
                            .withUsage(usage().withInputTokens(10).withOutputTokens(25))
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.ANTHROPIC));
        assertThat(result.getHttpLlmResponse().getModel(), is("claude-sonnet-4-20250514"));
        assertThat(result.getHttpLlmResponse().getCompletion().getText(), is("Hello, world!"));
        assertThat(result.getHttpLlmResponse().getCompletion().getStopReason(), is("end_turn"));
        assertThat(result.getHttpLlmResponse().getCompletion().getUsage().getInputTokens(), is(10));
        assertThat(result.getHttpLlmResponse().getCompletion().getUsage().getOutputTokens(), is(25));
    }

    @Test
    public void shouldRoundTripEmbeddingExpectation() {
        // given
        Expectation original = when(request().withPath("/v1/embeddings"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("text-embedding-3-small")
                    .withEmbedding(
                        embedding()
                            .withDimensions(1536)
                            .withDeterministicFromInput(true)
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.OPENAI));
        assertThat(result.getHttpLlmResponse().getModel(), is("text-embedding-3-small"));
        assertThat(result.getHttpLlmResponse().getEmbedding().getDimensions(), is(1536));
        assertThat(result.getHttpLlmResponse().getEmbedding().getDeterministicFromInput(), is(true));
    }

    @Test
    public void shouldRoundTripWithToolCalls() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\"}"))
                            .withStopReason("tool_use")
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse().getCompletion().getToolCalls().size(), is(1));
        assertThat(result.getHttpLlmResponse().getCompletion().getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(result.getHttpLlmResponse().getCompletion().getToolCalls().get(0).getArguments(), is("{\"city\":\"London\"}"));
    }

    @Test
    public void shouldDeserializeFromJsonString() {
        // given
        String json = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/v1/messages\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpLlmResponse\" : {" + NEW_LINE +
            "    \"provider\" : \"ANTHROPIC\"," + NEW_LINE +
            "    \"model\" : \"claude-sonnet-4-20250514\"," + NEW_LINE +
            "    \"completion\" : {" + NEW_LINE +
            "      \"text\" : \"Hello\"," + NEW_LINE +
            "      \"stopReason\" : \"end_turn\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.ANTHROPIC));
        assertThat(result.getHttpLlmResponse().getModel(), is("claude-sonnet-4-20250514"));
        assertThat(result.getHttpLlmResponse().getCompletion().getText(), is("Hello"));
        assertThat(result.getHttpLlmResponse().getCompletion().getStopReason(), is("end_turn"));
    }

    @Test
    public void shouldPreserveExpectationEqualityAfterRoundTrip() {
        // given
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.GEMINI)
            .withModel("gemini-pro")
            .withCompletion(
                completion()
                    .withText("Gemini response")
                    .withStopReason("STOP")
                    .withUsage(usage().withInputTokens(5).withOutputTokens(15))
            );
        Expectation original = when(request().withPath("/v1/generateContent"))
            .thenRespondWithLlm(llmResponse);

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then - the LLM response fields are preserved
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getHttpLlmResponse().getProvider(), is(original.getHttpLlmResponse().getProvider()));
        assertThat(deserialized[0].getHttpLlmResponse().getModel(), is(original.getHttpLlmResponse().getModel()));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion(), is(original.getHttpLlmResponse().getCompletion()));
    }
}
