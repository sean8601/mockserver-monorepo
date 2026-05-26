package org.mockserver.client;

/**
 * Builder for multi-turn LLM conversation mocks.
 * <p>
 * <b>This is a pre-release stub.</b> Only the {@link #conversation()} entry
 * point is callable; every method beyond construction throws
 * {@link UnsupportedOperationException}. The full implementation
 * — including {@code turn()}, conversation-aware matchers
 * ({@code whenLatestMessageContains}, {@code whenContainsToolResultFor}, …)
 * and per-session isolation via {@code isolateBy(...)} — lands in the
 * conversation-matcher milestone. Do not depend on this class in production
 * code until then.
 */
public class LlmConversationBuilder {

    private LlmConversationBuilder() {
    }

    /**
     * Entry point for building a multi-turn LLM conversation mock.
     *
     * @return a new LlmConversationBuilder
     */
    public static LlmConversationBuilder conversation() {
        return new LlmConversationBuilder();
    }

    /**
     * Add a turn to the conversation.
     * <p>
     * Not yet implemented — will be available in milestone M2.
     *
     * @return this builder
     * @throws UnsupportedOperationException always, until M2 is implemented
     */
    public LlmConversationBuilder turn() {
        throw new UnsupportedOperationException("LLM conversations are implemented in milestone M2");
    }
}
