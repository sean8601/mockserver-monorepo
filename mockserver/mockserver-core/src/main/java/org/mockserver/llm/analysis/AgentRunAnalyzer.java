package org.mockserver.llm.analysis;

import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic, read-only analysis of an agent run reconstructed from recorded
 * LLM requests. Each request is decoded with the provider's
 * {@link ProviderCodec} into a {@link ParsedConversation}; the richest
 * conversation (most messages — i.e. the latest snapshot of the dialogue) is
 * treated as the canonical run.
 * <p>
 * Pure (no network, no LLM): it inspects the structure the codecs already
 * produce. Powers the {@code verify_tool_call} and {@code explain_agent_run}
 * MCP tools.
 */
public class AgentRunAnalyzer {

    /** A single tool call the assistant made. */
    public static final class ToolCallMatch {
        private final String name;
        private final String arguments;

        public ToolCallMatch(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() {
            return name;
        }

        public String getArguments() {
            return arguments;
        }
    }

    /** Result of a {@code verify_tool_call}-style inspection. */
    public static final class ToolCallReport {
        private final int count;
        private final List<ToolCallMatch> matches;

        public ToolCallReport(int count, List<ToolCallMatch> matches) {
            this.count = count;
            this.matches = matches;
        }

        public int getCount() {
            return count;
        }

        public List<ToolCallMatch> getMatches() {
            return matches;
        }
    }

    /** Structural summary of an agent run (for {@code explain_agent_run}). */
    public static final class RunSummary {
        private final int messageCount;
        private final int assistantTurnCount;
        private final List<String> toolCallSequence;
        private final List<String> toolResultsFor;
        private final String latestMessageRole;

        public RunSummary(int messageCount, int assistantTurnCount, List<String> toolCallSequence,
                          List<String> toolResultsFor, String latestMessageRole) {
            this.messageCount = messageCount;
            this.assistantTurnCount = assistantTurnCount;
            this.toolCallSequence = toolCallSequence;
            this.toolResultsFor = toolResultsFor;
            this.latestMessageRole = latestMessageRole;
        }

        public int getMessageCount() {
            return messageCount;
        }

        public int getAssistantTurnCount() {
            return assistantTurnCount;
        }

        public List<String> getToolCallSequence() {
            return toolCallSequence;
        }

        public List<String> getToolResultsFor() {
            return toolResultsFor;
        }

        public String getLatestMessageRole() {
            return latestMessageRole;
        }
    }

    /**
     * Count the assistant tool calls matching {@code toolName} (and, optionally,
     * whose arguments match {@code argumentsRegex}) across the canonical
     * conversation decoded from the given requests.
     *
     * @param argumentsRegex optional regex matched (via find) against the tool
     *                       call's argument JSON string; null/empty means any
     */
    public ToolCallReport inspectToolCalls(List<HttpRequest> requests, Provider provider,
                                           String toolName, String argumentsRegex) {
        Pattern argsPattern = compileOrNull(argumentsRegex);
        List<ToolCallMatch> matches = new ArrayList<>();
        ParsedConversation canonical = canonicalConversation(requests, provider);
        if (canonical != null) {
            for (ParsedMessage message : canonical.getMessages()) {
                if (message.getRole() != ParsedMessage.Role.ASSISTANT) {
                    continue;
                }
                for (ToolUse toolCall : message.getToolCalls()) {
                    if (toolName != null && !toolName.equals(toolCall.getName())) {
                        continue;
                    }
                    if (argsPattern != null) {
                        String args = toolCall.getArguments();
                        if (args == null || !argsPattern.matcher(args).find()) {
                            continue;
                        }
                    }
                    matches.add(new ToolCallMatch(toolCall.getName(), toolCall.getArguments()));
                }
            }
        }
        return new ToolCallReport(matches.size(), matches);
    }

    /**
     * Summarise the canonical conversation's structure: message and assistant
     * turn counts, the ordered sequence of tool-call names, the tool names a
     * result was returned for, and the latest message's role. Returns empty when
     * no conversation can be decoded.
     */
    public Optional<RunSummary> summarise(List<HttpRequest> requests, Provider provider) {
        ParsedConversation canonical = canonicalConversation(requests, provider);
        if (canonical == null || canonical.getMessages().isEmpty()) {
            return Optional.empty();
        }
        List<ParsedMessage> messages = canonical.getMessages();
        int assistantTurns = 0;
        List<String> toolCallSequence = new ArrayList<>();
        Set<String> toolResultsFor = new LinkedHashSet<>();
        for (ParsedMessage message : messages) {
            if (message.getRole() == ParsedMessage.Role.ASSISTANT) {
                assistantTurns++;
                for (ToolUse toolCall : message.getToolCalls()) {
                    toolCallSequence.add(toolCall.getName());
                }
            }
            if (message.getRole() == ParsedMessage.Role.TOOL) {
                toolResultsFor.addAll(message.getToolResults().keySet());
            }
        }
        String latestRole = messages.get(messages.size() - 1).getRole().name();
        return Optional.of(new RunSummary(messages.size(), assistantTurns, toolCallSequence,
            new ArrayList<>(toolResultsFor), latestRole));
    }

    /**
     * Decode each request and return the conversation with the most messages —
     * the richest/latest snapshot of the dialogue. Null if none decode.
     */
    private ParsedConversation canonicalConversation(List<HttpRequest> requests, Provider provider) {
        if (requests == null || provider == null) {
            return null;
        }
        Optional<ProviderCodec> codecOpt = ProviderCodecRegistry.getInstance().lookup(provider);
        if (!codecOpt.isPresent()) {
            return null;
        }
        ProviderCodec codec = codecOpt.get();
        ParsedConversation richest = null;
        // Requests arrive in log order; on an equal message count the later one wins
        // (>=), so "richest" resolves to the latest snapshot of the dialogue.
        for (HttpRequest request : requests) {
            try {
                ParsedConversation parsed = codec.decode(request);
                if (parsed != null && (richest == null || parsed.getMessages().size() >= richest.getMessages().size())) {
                    richest = parsed;
                }
            } catch (Exception e) {
                // skip requests that do not decode for this provider
            }
        }
        return richest;
    }

    private Pattern compileOrNull(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid argumentsRegex: " + regex, e);
        }
    }
}
