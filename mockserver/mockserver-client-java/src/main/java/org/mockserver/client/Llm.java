package org.mockserver.client;

import org.mockserver.model.*;

import java.util.concurrent.TimeUnit;

/**
 * Convenience re-exports of LLM model-class static factories so that
 * users can write a single import:
 * <pre>
 * import static org.mockserver.client.Llm.*;
 * </pre>
 * and get all LLM-related factories in one import.
 */
public final class Llm {

    private Llm() {
        // utility class
    }

    /**
     * Creates a new Completion builder.
     */
    public static Completion completion() {
        return Completion.completion();
    }

    /**
     * Creates a new ToolUse builder.
     */
    public static ToolUse toolUse(String name) {
        return ToolUse.toolUse(name);
    }

    /**
     * Creates a Usage with the given input tokens.
     */
    public static Usage inputTokens(int inputTokens) {
        return Usage.inputTokens(inputTokens);
    }

    /**
     * Creates a Usage with the given output tokens.
     */
    public static Usage outputTokens(int outputTokens) {
        return Usage.outputTokens(outputTokens);
    }

    /**
     * Creates a new StreamingPhysics builder.
     */
    public static StreamingPhysics streamingPhysics() {
        return StreamingPhysics.streamingPhysics();
    }

    /**
     * Creates a Delay representing the time to first token.
     */
    public static Delay timeToFirstToken(long value, TimeUnit timeUnit) {
        return new Delay(timeUnit, value);
    }

    /**
     * Creates a StreamingPhysics with the given tokens per second.
     */
    public static StreamingPhysics tokensPerSecond(int tokensPerSecond) {
        return StreamingPhysics.streamingPhysics().withTokensPerSecond(tokensPerSecond);
    }

    /**
     * Creates a StreamingPhysics with the given jitter.
     */
    public static StreamingPhysics jitter(double jitter) {
        return StreamingPhysics.streamingPhysics().withJitter(jitter);
    }

    /**
     * Creates a new EmbeddingResponse builder.
     */
    public static EmbeddingResponse embedding() {
        return EmbeddingResponse.embedding();
    }
}
