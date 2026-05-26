package org.mockserver.llm;

import java.util.Collections;
import java.util.List;

public class ParsedConversation {

    private final List<ParsedMessage> messages;

    private ParsedConversation(List<ParsedMessage> messages) {
        this.messages = Collections.unmodifiableList(messages);
    }

    public static ParsedConversation empty() {
        return new ParsedConversation(Collections.emptyList());
    }

    public static ParsedConversation of(List<ParsedMessage> messages) {
        return new ParsedConversation(messages);
    }

    public List<ParsedMessage> getMessages() {
        return messages;
    }
}
