package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.*;

public class HttpLlmResponseDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpLlmResponse> {
    private DelayDTO delay;
    private Provider provider;
    private String model;
    private Completion completion;
    private EmbeddingResponse embedding;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean primary;

    public HttpLlmResponseDTO(HttpLlmResponse httpLlmResponse) {
        if (httpLlmResponse != null) {
            if (httpLlmResponse.getDelay() != null) {
                delay = new DelayDTO(httpLlmResponse.getDelay());
            }
            provider = httpLlmResponse.getProvider();
            model = httpLlmResponse.getModel();
            completion = httpLlmResponse.getCompletion();
            embedding = httpLlmResponse.getEmbedding();
            primary = httpLlmResponse.isPrimary();
        }
    }

    public HttpLlmResponseDTO() {
    }

    public HttpLlmResponse buildObject() {
        return new HttpLlmResponse()
            .withDelay(delay != null ? delay.buildObject() : null)
            .withProvider(provider)
            .withModel(model)
            .withCompletion(completion)
            .withEmbedding(embedding)
            .withPrimary(primary);
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public HttpLlmResponseDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    public HttpLlmResponseDTO setProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public String getModel() {
        return model;
    }

    public HttpLlmResponseDTO setModel(String model) {
        this.model = model;
        return this;
    }

    public Completion getCompletion() {
        return completion;
    }

    public HttpLlmResponseDTO setCompletion(Completion completion) {
        this.completion = completion;
        return this;
    }

    public EmbeddingResponse getEmbedding() {
        return embedding;
    }

    public HttpLlmResponseDTO setEmbedding(EmbeddingResponse embedding) {
        this.embedding = embedding;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    public HttpLlmResponseDTO setPrimary(boolean primary) {
        this.primary = primary;
        return this;
    }
}
