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
     * Returns true if at least one predicate field is set.
     */
    public boolean hasAnyPredicate() {
        return turnIndex != null
            || latestMessageContains != null
            || latestMessageMatches != null
            || latestMessageRole != null
            || containsToolResultFor != null;
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
            Objects.equals(containsToolResultFor, that.containsToolResultFor);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(turnIndex, latestMessageContains, latestMessageMatches, latestMessageRole, containsToolResultFor);
        }
        return hashCode;
    }
}
