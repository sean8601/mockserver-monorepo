package org.mockserver.model;

import java.util.Objects;

public class EmbeddingResponse extends ObjectWithJsonToString {
    private int hashCode;
    private Integer dimensions;
    private Boolean deterministicFromInput;
    private Long seed;

    public static EmbeddingResponse embedding() {
        return new EmbeddingResponse();
    }

    public EmbeddingResponse withDimensions(Integer dimensions) {
        this.dimensions = dimensions;
        this.hashCode = 0;
        return this;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public EmbeddingResponse withDeterministicFromInput(Boolean deterministicFromInput) {
        this.deterministicFromInput = deterministicFromInput;
        this.hashCode = 0;
        return this;
    }

    public Boolean getDeterministicFromInput() {
        return deterministicFromInput;
    }

    public EmbeddingResponse withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
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
        EmbeddingResponse that = (EmbeddingResponse) o;
        return Objects.equals(dimensions, that.dimensions) &&
            Objects.equals(deterministicFromInput, that.deterministicFromInput) &&
            Objects.equals(seed, that.seed);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(dimensions, deterministicFromInput, seed);
        }
        return hashCode;
    }
}
