package org.mockserver.client;

import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

/**
 * Fluent builder for creating LLM mock expectations.
 * <p>
 * Usage:
 * <pre>
 * llmMock("/v1/messages")
 *     .withProvider(Provider.ANTHROPIC)
 *     .withModel("claude-sonnet-4")
 *     .respondingWith(
 *         completion()
 *             .withText("The capital of France is Paris.")
 *             .withStopReason("end_turn")
 *             .withUsage(usage().withInputTokens(42).withOutputTokens(8))
 *     )
 *     .applyTo(mockServerClient);
 * </pre>
 */
public class LlmMockBuilder {

    private final String path;
    private Provider provider;
    private String model;
    private Completion completion;
    private EmbeddingResponse embedding;

    private LlmMockBuilder(String path) {
        this.path = path;
    }

    /**
     * Entry point for building an LLM mock expectation.
     *
     * @param path the HTTP path to match (e.g. "/v1/messages", "/v1/chat/completions")
     * @return a new LlmMockBuilder
     */
    public static LlmMockBuilder llmMock(String path) {
        return new LlmMockBuilder(path);
    }

    public LlmMockBuilder withProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public LlmMockBuilder withModel(String model) {
        this.model = model;
        return this;
    }

    public LlmMockBuilder respondingWith(Completion completion) {
        this.completion = completion;
        this.embedding = null;
        return this;
    }

    public LlmMockBuilder respondingWith(EmbeddingResponse embedding) {
        this.embedding = embedding;
        this.completion = null;
        return this;
    }

    /**
     * Build the expectation and register it with the MockServerClient.
     *
     * @param client the MockServerClient to register with
     * @return the created expectations
     */
    public Expectation[] applyTo(MockServerClient client) {
        return client.upsert(build());
    }

    /**
     * Build the expectation without registering it.
     *
     * @return the built expectation
     */
    public Expectation build() {
        HttpLlmResponse action = llmResponse()
            .withProvider(provider)
            .withModel(model);

        if (completion != null) {
            action = action.withCompletion(completion);
        }
        if (embedding != null) {
            action = action.withEmbedding(embedding);
        }

        return Expectation.when(
            request().withMethod("POST").withPath(path)
        ).thenRespondWithLlm(action);
    }
}
