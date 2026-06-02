package org.mockserver.async.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single message definition within an AsyncAPI channel.
 * <p>
 * A channel may contain multiple messages (AsyncAPI 3.x {@code channels.<n>.messages}
 * or AsyncAPI 2.x {@code oneOf} in the operation message). Each message has its own
 * payload schema, examples, and optional Kafka key binding.
 * <p>
 * MQTT qos/retain remain channel-level (operation binding) and are not per-message.
 */
public class AsyncApiMessage {

    private final String name;
    private final JsonNode payloadSchema;
    private final List<JsonNode> payloadExamples;
    private final String kafkaKey;

    /**
     * @param name            the message name (nullable; e.g. the key under {@code messages.<name>} in v3)
     * @param payloadSchema   the JSON Schema for the payload (may be null)
     * @param payloadExamples explicit payload examples from the spec (never null after construction)
     * @param kafkaKey        Kafka message key from message-level bindings (may be null)
     */
    public AsyncApiMessage(String name, JsonNode payloadSchema, List<JsonNode> payloadExamples, String kafkaKey) {
        this.name = name;
        this.payloadSchema = payloadSchema;
        this.payloadExamples = payloadExamples != null
            ? Collections.unmodifiableList(new ArrayList<>(payloadExamples))
            : Collections.emptyList();
        this.kafkaKey = kafkaKey;
    }

    /**
     * The message name, or null for anonymous / unnamed messages.
     */
    public String getName() {
        return name;
    }

    /**
     * The JSON Schema describing the payload (may be null if not present).
     */
    public JsonNode getPayloadSchema() {
        return payloadSchema;
    }

    /**
     * Explicit examples found in the spec for this message's payload.
     */
    public List<JsonNode> getPayloadExamples() {
        return payloadExamples;
    }

    /**
     * Kafka message key from message-level bindings, or null if not derivable.
     */
    public String getKafkaKey() {
        return kafkaKey;
    }
}
