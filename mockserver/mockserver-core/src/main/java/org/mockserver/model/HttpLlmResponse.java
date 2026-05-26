package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

public class HttpLlmResponse extends Action<HttpLlmResponse> {
    private int hashCode;
    private Provider provider;
    private String model;
    private Completion completion;
    private EmbeddingResponse embedding;

    public static HttpLlmResponse llmResponse() {
        return new HttpLlmResponse();
    }

    public HttpLlmResponse withProvider(Provider provider) {
        this.provider = provider;
        this.hashCode = 0;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    public HttpLlmResponse withModel(String model) {
        this.model = model;
        this.hashCode = 0;
        return this;
    }

    public String getModel() {
        return model;
    }

    public HttpLlmResponse withCompletion(Completion completion) {
        this.completion = completion;
        this.hashCode = 0;
        return this;
    }

    public Completion getCompletion() {
        return completion;
    }

    public HttpLlmResponse withEmbedding(EmbeddingResponse embedding) {
        this.embedding = embedding;
        this.hashCode = 0;
        return this;
    }

    public EmbeddingResponse getEmbedding() {
        return embedding;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.LLM_RESPONSE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        HttpLlmResponse that = (HttpLlmResponse) o;
        return Objects.equals(provider, that.provider) &&
            Objects.equals(model, that.model) &&
            Objects.equals(completion, that.completion) &&
            Objects.equals(embedding, that.embedding);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), provider, model, completion, embedding);
        }
        return hashCode;
    }
}
