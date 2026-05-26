package org.mockserver.model;

import java.util.Objects;

public class Usage extends ObjectWithJsonToString {
    private int hashCode;
    private Integer inputTokens;
    private Integer outputTokens;

    public static Usage usage() {
        return new Usage();
    }

    public static Usage inputTokens(int inputTokens) {
        return new Usage().withInputTokens(inputTokens);
    }

    public static Usage outputTokens(int outputTokens) {
        return new Usage().withOutputTokens(outputTokens);
    }

    public Usage withInputTokens(Integer inputTokens) {
        if (inputTokens != null && inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must be >= 0");
        }
        this.inputTokens = inputTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Usage withOutputTokens(Integer outputTokens) {
        if (outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must be >= 0");
        }
        this.outputTokens = outputTokens;
        this.hashCode = 0;
        return this;
    }

    public Integer getOutputTokens() {
        return outputTokens;
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
        Usage usage = (Usage) o;
        return Objects.equals(inputTokens, usage.inputTokens) &&
            Objects.equals(outputTokens, usage.outputTokens);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(inputTokens, outputTokens);
        }
        return hashCode;
    }
}
