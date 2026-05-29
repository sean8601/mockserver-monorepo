package org.mockserver.llm.semantic;

/**
 * Process-wide opt-in gate for semantic prompt matching. A {@link SemanticPromptMatcher}
 * is installed only when the operator sets {@code mockserver.llmSemanticMatchingEnabled}
 * AND a runtime LLM backend resolves (done at server start). Until then
 * {@link #isEnabled()} is false and the {@code semanticMatch} conversation
 * predicate is ignored — deterministic matching is never affected by default.
 * <p>
 * This is deliberately a static holder (like the telemetry and metrics holders)
 * so the matcher on the request path can consult it without constructor wiring.
 */
public final class SemanticMatching {

    private static volatile SemanticPromptMatcher matcher;

    private SemanticMatching() {
    }

    public static void install(SemanticPromptMatcher semanticPromptMatcher) {
        matcher = semanticPromptMatcher;
    }

    public static void clear() {
        matcher = null;
    }

    /** True only when semantic matching has been explicitly enabled and wired. */
    public static boolean isEnabled() {
        return matcher != null;
    }

    /**
     * Evaluate the semantic match. Returns false (fail-closed) when not enabled
     * or on any error — callers should guard with {@link #isEnabled()} to
     * distinguish "disabled, ignore the predicate" from "enabled, did not match".
     */
    public static boolean matches(String subject, String expectedMeaning) {
        SemanticPromptMatcher current = matcher;
        if (current == null) {
            return false;
        }
        try {
            return current.matchesSemantically(subject, expectedMeaning);
        } catch (Exception e) {
            return false;
        }
    }
}
