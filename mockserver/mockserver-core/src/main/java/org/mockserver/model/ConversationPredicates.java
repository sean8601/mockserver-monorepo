package org.mockserver.model;

import org.mockserver.llm.ParsedMessage;

import java.util.Objects;

/**
 * Serialisable predicate descriptors for LLM conversation matching.
 * Unlike {@link org.mockserver.matchers.LlmConversationMatcher} (which is an
 * evaluation-time object), this class carries only the data needed to describe
 * predicates and survives JSON round-tripping through the MockServer HTTP API.
 * <p>
 * All fields are optional; only non-null fields are emitted in JSON.
 */
public class ConversationPredicates extends ObjectWithJsonToString {
    private int hashCode;
    private Integer turnIndex;
    private String latestMessageContains;
    private String latestMessageMatches;   // regex source string, not Pattern
    private ParsedMessage.Role latestMessageRole;
    private String containsToolResultFor;
    private String semanticMatchAgainst;          // opt-in fuzzy LLM-judged match (off by default)
    private NormalizationOptions normalization;   // optional modifier applied before contains/matches

    public static ConversationPredicates conversationPredicates() {
        return new ConversationPredicates();
    }

    public ConversationPredicates withTurnIndex(Integer turnIndex) {
        this.turnIndex = turnIndex;
        this.hashCode = 0;
        return this;
    }

    public Integer getTurnIndex() {
        return turnIndex;
    }

    public ConversationPredicates withLatestMessageContains(String latestMessageContains) {
        this.latestMessageContains = latestMessageContains;
        this.hashCode = 0;
        return this;
    }

    public String getLatestMessageContains() {
        return latestMessageContains;
    }

    public ConversationPredicates withLatestMessageMatches(String latestMessageMatches) {
        this.latestMessageMatches = latestMessageMatches;
        this.hashCode = 0;
        return this;
    }

    public String getLatestMessageMatches() {
        return latestMessageMatches;
    }

    public ConversationPredicates withLatestMessageRole(ParsedMessage.Role latestMessageRole) {
        this.latestMessageRole = latestMessageRole;
        this.hashCode = 0;
        return this;
    }

    public ParsedMessage.Role getLatestMessageRole() {
        return latestMessageRole;
    }

    public ConversationPredicates withContainsToolResultFor(String containsToolResultFor) {
        this.containsToolResultFor = containsToolResultFor;
        this.hashCode = 0;
        return this;
    }

    public String getContainsToolResultFor() {
        return containsToolResultFor;
    }

    /**
     * Opt-in fuzzy semantic match: the expected meaning the latest message
     * should express, judged by a runtime LLM. Off by default — ignored unless
     * {@code mockserver.llmSemanticMatchingEnabled} is set and a backend
     * resolves. Non-deterministic; exploratory only, never for assertions.
     */
    public ConversationPredicates withSemanticMatchAgainst(String semanticMatchAgainst) {
        this.semanticMatchAgainst = semanticMatchAgainst;
        this.hashCode = 0;
        return this;
    }

    public String getSemanticMatchAgainst() {
        return semanticMatchAgainst;
    }

    /**
     * Optional normalisation applied to the prompt text (and, for substring
     * matching, the expected value) before {@code latestMessageContains} /
     * {@code latestMessageMatches} are evaluated. A modifier, not a predicate:
     * normalisation alone does not make the matcher active — see
     * {@link #hasAnyPredicate()}.
     */
    public ConversationPredicates withNormalization(NormalizationOptions normalization) {
        this.normalization = normalization;
        this.hashCode = 0;
        return this;
    }

    public NormalizationOptions getNormalization() {
        return normalization;
    }

    /**
     * Returns true if at least one predicate field is set. {@code normalization}
     * is intentionally excluded — it only modifies how the text predicates match.
     */
    public boolean hasAnyPredicate() {
        return turnIndex != null
            || latestMessageContains != null
            || latestMessageMatches != null
            || latestMessageRole != null
            || containsToolResultFor != null
            || semanticMatchAgainst != null;
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
        ConversationPredicates that = (ConversationPredicates) o;
        return Objects.equals(turnIndex, that.turnIndex) &&
            Objects.equals(latestMessageContains, that.latestMessageContains) &&
            Objects.equals(latestMessageMatches, that.latestMessageMatches) &&
            Objects.equals(latestMessageRole, that.latestMessageRole) &&
            Objects.equals(containsToolResultFor, that.containsToolResultFor) &&
            Objects.equals(semanticMatchAgainst, that.semanticMatchAgainst) &&
            Objects.equals(normalization, that.normalization);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(turnIndex, latestMessageContains, latestMessageMatches, latestMessageRole, containsToolResultFor, semanticMatchAgainst, normalization);
        }
        return hashCode;
    }
}
