package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.JsonEscape;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.StreamingPhysicsExpander;
import org.mockserver.model.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Codec for OpenAI Chat Completions API (version 2025-01).
 * Encodes MockServer Completion objects into OpenAI-format HTTP responses
 * for both non-streaming and streaming (SSE) paths.
 * Also handles OpenAI Embeddings API responses.
 */
public class OpenAiChatCompletionsCodec implements ProviderCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Provider provider() {
        return Provider.OPENAI;
    }

    @Override
    public String apiVersion() {
        return "2025-01";
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("id", "chatcmpl-" + randomId(29));
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", model != null ? model : "unknown");

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);

        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");

        String text = completion.getText();
        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

        // content: null if text is null AND there are tool calls; otherwise use text
        if (text == null && hasToolCalls) {
            message.putNull("content");
        } else {
            message.put("content", text != null ? text : "");
        }

        // tool_calls: omitted entirely when no tool calls; never emit empty array
        if (hasToolCalls) {
            ArrayNode toolCallsArray = message.putArray("tool_calls");
            for (ToolUse toolCall : toolCalls) {
                ObjectNode tc = toolCallsArray.addObject();
                tc.put("id", "call_" + randomId(24));
                tc.put("type", "function");
                ObjectNode function = tc.putObject("function");
                function.put("name", toolCall.getName());
                // arguments is always a JSON-as-string (OpenAI's quirk)
                function.put("arguments", toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
            }
        }

        // finish_reason mapping
        choice.put("finish_reason", mapFinishReason(completion.getStopReason(), hasToolCalls));

        // usage
        ObjectNode usage = root.putObject("usage");
        Usage completionUsage = completion.getUsage();
        int promptTokens = completionUsage != null && completionUsage.getInputTokens() != null ? completionUsage.getInputTokens() : 0;
        int completionTokens = completionUsage != null && completionUsage.getOutputTokens() != null ? completionUsage.getOutputTokens() : 0;
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode OpenAI response", e);
        }
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        List<SseEvent> events = new ArrayList<>();
        String id = "chatcmpl-" + randomId(29);
        long created = System.currentTimeMillis() / 1000;
        String modelName = model != null ? model : "unknown";

        // 1. First chunk: role-only
        events.add(sseEvent().withData(buildChunk(id, created, modelName,
            "{\"role\":\"assistant\",\"content\":\"\"}", null)));

        // 2. Text content chunks
        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            String[] tokens = text.split("(?<=\\s)|(?=\\s)");
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    events.add(sseEvent().withData(buildChunk(id, created, modelName,
                        "{\"content\":\"" + escapeJson(token) + "\"}", null)));
                }
            }
        }

        // 3. Tool call chunks
        List<ToolUse> toolCalls = completion.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (hasToolCalls) {
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolUse toolCall = toolCalls.get(i);
                String toolCallId = "call_" + randomId(24);
                String args = toolCall.getArguments() != null ? toolCall.getArguments() : "{}";
                String delta = "{\"tool_calls\":[{\"index\":" + i + ",\"id\":\"" + toolCallId +
                    "\",\"type\":\"function\",\"function\":{\"name\":\"" + escapeJson(toolCall.getName()) +
                    "\",\"arguments\":\"" + escapeJson(args) + "\"}}]}";
                events.add(sseEvent().withData(buildChunk(id, created, modelName, delta, null)));
            }
        }

        // 4. Final chunk with finish_reason
        String finishReason = mapFinishReason(completion.getStopReason(), hasToolCalls);
        events.add(sseEvent().withData(buildChunk(id, created, modelName, "{}", finishReason)));

        // 5. [DONE] sentinel
        events.add(sseEvent().withData("[DONE]"));

        return StreamingPhysicsExpander.applyPhysics(events, physics);
    }

    @Override
    public ParsedConversation decode(HttpRequest request) {
        try {
            String body = request != null ? request.getBodyAsString() : null;
            if (body == null || body.isEmpty()) {
                return ParsedConversation.empty();
            }
            JsonNode root = OBJECT_MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return ParsedConversation.empty();
            }
            JsonNode messagesNode = root.get("messages");
            if (messagesNode == null || !messagesNode.isArray()) {
                return ParsedConversation.empty();
            }

            List<ParsedMessage> parsed = new ArrayList<>();
            for (JsonNode msgNode : messagesNode) {
                String rawRole = msgNode.has("role") ? msgNode.get("role").asText("") : "";
                JsonNode contentNode = msgNode.get("content");

                String textContent = "";
                List<ToolUse> toolCalls = new ArrayList<>();
                Map<String, String> toolResults = new LinkedHashMap<>();

                // Parse text content
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        textContent = contentNode.asText("");
                    } else if (contentNode.isArray()) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (JsonNode part : contentNode) {
                            String partType = part.has("type") ? part.get("type").asText("") : "";
                            if ("text".equals(partType)) {
                                String text = part.has("text") ? part.get("text").asText("") : "";
                                textBuilder.append(text);
                            }
                        }
                        textContent = textBuilder.toString();
                    } else if (contentNode.isNull()) {
                        textContent = "";
                    }
                }

                // Parse tool_calls on assistant messages
                JsonNode toolCallsNode = msgNode.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        String callId = tcNode.has("id") ? tcNode.get("id").asText("") : null;
                        JsonNode functionNode = tcNode.get("function");
                        if (functionNode != null) {
                            String name = functionNode.has("name") ? functionNode.get("name").asText("") : "";
                            String arguments = functionNode.has("arguments") ? functionNode.get("arguments").asText("") : "{}";
                            ToolUse tu = ToolUse.toolUse(name).withArguments(arguments);
                            if (callId != null && !callId.isEmpty()) {
                                tu.withId(callId);
                            }
                            toolCalls.add(tu);
                        }
                    }
                }

                // Parse tool role messages with tool_call_id
                if ("tool".equalsIgnoreCase(rawRole)) {
                    String toolCallId = msgNode.has("tool_call_id") ? msgNode.get("tool_call_id").asText("") : "";
                    String toolContent = textContent;
                    if (!toolCallId.isEmpty()) {
                        toolResults.put(toolCallId, toolContent);
                    }
                }

                // Map role
                ParsedMessage.Role role = mapOpenAiRole(rawRole);

                parsed.add(new ParsedMessage(
                    role,
                    textContent,
                    toolCalls.isEmpty() ? null : toolCalls,
                    toolResults.isEmpty() ? null : toolResults
                ));
            }

            return ParsedConversation.of(parsed);
        } catch (Exception e) {
            return ParsedConversation.empty();
        }
    }

    private static ParsedMessage.Role mapOpenAiRole(String rawRole) {
        if (rawRole == null) {
            return ParsedMessage.Role.USER;
        }
        switch (rawRole.toLowerCase()) {
            case "assistant":
                return ParsedMessage.Role.ASSISTANT;
            case "user":
                return ParsedMessage.Role.USER;
            case "tool":
                return ParsedMessage.Role.TOOL;
            case "system":
                return ParsedMessage.Role.SYSTEM;
            default:
                return ParsedMessage.Role.USER;
        }
    }

    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        int dimensions = embedding.getDimensions() != null ? embedding.getDimensions() : 1536;
        long seed = embedding.getSeed() != null ? embedding.getSeed() : 0L;
        boolean deterministic = Boolean.TRUE.equals(embedding.getDeterministicFromInput());

        double[] vector;
        if (deterministic && input != null) {
            vector = generateDeterministicVector(input, dimensions, seed);
        } else {
            vector = generateRandomVector(dimensions);
        }

        // L2-normalise
        normalizeL2(vector);

        // Build response
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("object", "list");

        ArrayNode data = root.putArray("data");
        ObjectNode embeddingObj = data.addObject();
        embeddingObj.put("object", "embedding");
        embeddingObj.put("index", 0);
        ArrayNode embeddingArray = embeddingObj.putArray("embedding");
        for (double v : vector) {
            embeddingArray.add(v);
        }

        root.put("model", "text-embedding-3-small");

        // approximate token count from input
        int approxTokens = input != null ? Math.max(1, input.length() / 4) : 0;
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", approxTokens);
        usage.put("total_tokens", approxTokens);

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode OpenAI embedding response", e);
        }
    }

    static double[] generateDeterministicVector(String input, int dimensions, long seed) {
        try {
            byte[] seedBytes = String.valueOf(seed).getBytes(StandardCharsets.UTF_8);
            byte[] dimensionsBytes = String.valueOf(dimensions).getBytes(StandardCharsets.UTF_8);
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seedBytes);
            digest.update((byte) ':');
            digest.update(dimensionsBytes);
            digest.update((byte) ':');
            digest.update(inputBytes);
            byte[] hash = digest.digest();

            // First 8 bytes as big-endian long
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 8);
            long hashLong = buffer.getLong();

            Random random = new Random(hashLong);
            double[] vector = new double[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = random.nextDouble() * 2 - 1;
            }
            return vector;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static double[] generateRandomVector(int dimensions) {
        Random random = new Random();
        double[] vector = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextDouble() * 2 - 1;
        }
        return vector;
    }

    static void normalizeL2(double[] vector) {
        double sumOfSquares = 0;
        for (double v : vector) {
            sumOfSquares += v * v;
        }
        double norm = Math.sqrt(sumOfSquares);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    private String buildChunk(String id, long created, String model, String deltaJson, String finishReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(id).append("\",");
        sb.append("\"object\":\"chat.completion.chunk\",");
        sb.append("\"created\":").append(created).append(",");
        sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
        sb.append("\"choices\":[{\"index\":0,\"delta\":").append(deltaJson).append(",");
        if (finishReason != null) {
            sb.append("\"finish_reason\":\"").append(escapeJson(finishReason)).append("\"");
        } else {
            sb.append("\"finish_reason\":null");
        }
        sb.append("}]}");
        return sb.toString();
    }

    private static String mapFinishReason(String stopReason, boolean hasToolCalls) {
        if (stopReason == null) {
            return hasToolCalls ? "tool_calls" : "stop";
        }
        switch (stopReason) {
            case "end_turn":
                return "stop";
            case "tool_use":
                return "tool_calls";
            case "max_tokens":
                return "length";
            case "stop":
            case "length":
            case "tool_calls":
            case "content_filter":
                return stopReason;
            default:
                return stopReason;
        }
    }

    private static String randomId(int length) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        while (uuid.length() < length) {
            uuid = uuid + UUID.randomUUID().toString().replace("-", "");
        }
        return uuid.substring(0, length);
    }

    private static String escapeJson(String value) {
        return JsonEscape.escape(value);
    }
}
