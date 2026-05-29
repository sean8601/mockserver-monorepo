package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative fault/chaos injection for an {@link HttpLlmResponse}, for testing
 * agent resilience: probabilistic provider errors (e.g. 429/529 with a
 * {@code Retry-After} header), mid-stream truncation, and malformed SSE chunks.
 * <p>
 * Determinism: with {@code errorProbability} of {@code 1.0} (always) or
 * {@code 0.0}/null (never) the error decision is fully deterministic. A
 * fractional probability draws once per response; set {@code seed} to make that
 * single draw reproducible (note: a fixed seed yields the same decision every
 * time). Truncation and malformed-SSE are deterministic. Follows the model
 * field/{@code withX}/getter convention so it round-trips without a bespoke
 * (de)serializer.
 */
public class LlmChaosProfile extends ObjectWithJsonToString {

    public enum TruncateMode {
        NONE,
        MID_STREAM
    }

    private int hashCode;
    private Integer errorStatus;       // e.g. 429, 529, 500
    private String retryAfter;         // value for the Retry-After header on an error
    private Double errorProbability;   // 0.0–1.0; null/0 = never inject an error
    private TruncateMode truncateMode; // streaming: truncate the SSE event stream
    private Double truncateAtFraction; // 0.0–1.0 fraction of events to keep before truncating
    private Boolean malformedSse;      // streaming: emit a malformed (broken-JSON) SSE chunk
    private Long seed;                 // optional, makes a fractional errorProbability reproducible

    public static LlmChaosProfile llmChaosProfile() {
        return new LlmChaosProfile();
    }

    public LlmChaosProfile withErrorStatus(Integer errorStatus) {
        this.errorStatus = errorStatus;
        this.hashCode = 0;
        return this;
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public LlmChaosProfile withRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        this.hashCode = 0;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public LlmChaosProfile withErrorProbability(Double errorProbability) {
        this.errorProbability = errorProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public LlmChaosProfile withTruncateMode(TruncateMode truncateMode) {
        this.truncateMode = truncateMode;
        this.hashCode = 0;
        return this;
    }

    public TruncateMode getTruncateMode() {
        return truncateMode;
    }

    public LlmChaosProfile withTruncateAtFraction(Double truncateAtFraction) {
        this.truncateAtFraction = truncateAtFraction;
        this.hashCode = 0;
        return this;
    }

    public Double getTruncateAtFraction() {
        return truncateAtFraction;
    }

    public LlmChaosProfile withMalformedSse(Boolean malformedSse) {
        this.malformedSse = malformedSse;
        this.hashCode = 0;
        return this;
    }

    public Boolean getMalformedSse() {
        return malformedSse;
    }

    public LlmChaosProfile withSeed(Long seed) {
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
        LlmChaosProfile that = (LlmChaosProfile) o;
        return Objects.equals(errorStatus, that.errorStatus) &&
            Objects.equals(retryAfter, that.retryAfter) &&
            Objects.equals(errorProbability, that.errorProbability) &&
            truncateMode == that.truncateMode &&
            Objects.equals(truncateAtFraction, that.truncateAtFraction) &&
            Objects.equals(malformedSse, that.malformedSse) &&
            Objects.equals(seed, that.seed);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(errorStatus, retryAfter, errorProbability, truncateMode, truncateAtFraction, malformedSse, seed);
        }
        return hashCode;
    }
}
