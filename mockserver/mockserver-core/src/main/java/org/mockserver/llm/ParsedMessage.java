package org.mockserver.llm;

import org.mockserver.model.ToolUse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ParsedMessage {

    public enum Role {
        USER,
        ASSISTANT,
        TOOL,
        SYSTEM
    }

    private final Role role;
    private final String textContent;
    private final List<ToolUse> toolCalls;
    private final Map<String, String> toolResults;

    public ParsedMessage(Role role, String textContent, List<ToolUse> toolCalls, Map<String, String> toolResults) {
        this.role = role;
        this.textContent = textContent;
        this.toolCalls = toolCalls != null ? Collections.unmodifiableList(toolCalls) : Collections.emptyList();
        this.toolResults = toolResults != null ? Collections.unmodifiableMap(toolResults) : Collections.emptyMap();
    }

    public Role getRole() {
        return role;
    }

    public String getTextContent() {
        return textContent;
    }

    public List<ToolUse> getToolCalls() {
        return toolCalls;
    }

    public Map<String, String> getToolResults() {
        return toolResults;
    }
}
