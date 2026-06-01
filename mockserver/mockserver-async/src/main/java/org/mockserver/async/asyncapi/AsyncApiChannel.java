package org.mockserver.async.asyncapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single channel from an AsyncAPI specification,
 * with its name and zero or more message payload examples.
 */
public class AsyncApiChannel {

    private final String name;
    private final List<JsonNode> payloadExamples;
    private final JsonNode payloadSchema;

    public AsyncApiChannel(String name, List<JsonNode> payloadExamples, JsonNode payloadSchema) {
        this.name = name;
        this.payloadExamples = payloadExamples != null
            ? Collections.unmodifiableList(new ArrayList<>(payloadExamples))
            : Collections.emptyList();
        this.payloadSchema = payloadSchema;
    }

    public String getName() {
        return name;
    }

    /**
     * Explicit examples found in the spec for the channel's message payload.
     */
    public List<JsonNode> getPayloadExamples() {
        return payloadExamples;
    }

    /**
     * The JSON Schema describing the payload (may be null if not present).
     */
    public JsonNode getPayloadSchema() {
        return payloadSchema;
    }
}
