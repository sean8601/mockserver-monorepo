package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates example JSON payloads for each channel in an {@link AsyncApiSpec}.
 * <p>
 * Uses the following precedence for each channel:
 * <ol>
 *     <li>The first explicit example from the spec</li>
 *     <li>A minimal example synthesized from the JSON Schema payload (type-based defaults)</li>
 *     <li>An empty JSON object {@code {}} as a last-resort fallback</li>
 * </ol>
 */
public class MessageExampleGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(MessageExampleGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Generate example payloads for all channels in the spec.
     *
     * @return a map from channel name to JSON string payload
     */
    public Map<String, String> generateExamples(AsyncApiSpec spec) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AsyncApiChannel channel : spec.getChannels()) {
            result.put(channel.getName(), generateExample(channel));
        }
        return result;
    }

    /**
     * Generate an example payload for a single channel.
     */
    public String generateExample(AsyncApiChannel channel) {
        // 1. Try explicit example
        if (!channel.getPayloadExamples().isEmpty()) {
            JsonNode example = channel.getPayloadExamples().get(0);
            try {
                return MAPPER.writeValueAsString(example);
            } catch (Exception e) {
                LOG.warn("Failed to serialize payload example for channel {}: {}",
                    channel.getName(), e.getMessage());
            }
        }

        // 2. Try synthesizing from schema
        if (channel.getPayloadSchema() != null) {
            JsonNode schema = channel.getPayloadSchema();
            // Skip if the schema itself has an "example" field (already tried above via
            // AsyncApiParser extracting it into payloadExamples)
            JsonNode synthesized = synthesizeFromSchema(schema);
            if (synthesized != null) {
                try {
                    return MAPPER.writeValueAsString(synthesized);
                } catch (Exception e) {
                    LOG.warn("Failed to serialize synthesized example for channel {}: {}",
                        channel.getName(), e.getMessage());
                }
            }
        }

        // 3. Fallback: empty object
        return "{}";
    }

    /**
     * Synthesize a minimal JSON value from a JSON Schema node using type-based defaults.
     */
    JsonNode synthesizeFromSchema(JsonNode schema) {
        if (schema == null) {
            return null;
        }

        String type = textOrNull(schema, "type");
        if (type == null) {
            // If there are properties, assume object
            if (schema.has("properties")) {
                type = "object";
            } else {
                return MAPPER.createObjectNode();
            }
        }

        switch (type) {
            case "object":
                return synthesizeObject(schema);
            case "array":
                return synthesizeArray(schema);
            case "string":
                return MAPPER.getNodeFactory().textNode("string");
            case "integer":
                return MAPPER.getNodeFactory().numberNode(0);
            case "number":
                return MAPPER.getNodeFactory().numberNode(0.0);
            case "boolean":
                return MAPPER.getNodeFactory().booleanNode(false);
            case "null":
                return MAPPER.getNodeFactory().nullNode();
            default:
                return MAPPER.createObjectNode();
        }
    }

    private JsonNode synthesizeObject(JsonNode schema) {
        ObjectNode result = MAPPER.createObjectNode();
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                JsonNode propValue = synthesizeFromSchema(entry.getValue());
                if (propValue != null) {
                    result.set(entry.getKey(), propValue);
                }
            });
        }
        return result;
    }

    private JsonNode synthesizeArray(JsonNode schema) {
        ArrayNode result = MAPPER.createArrayNode();
        JsonNode items = schema.get("items");
        if (items != null) {
            JsonNode itemExample = synthesizeFromSchema(items);
            if (itemExample != null) {
                result.add(itemExample);
            }
        }
        return result;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode child = node.get(fieldName);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
