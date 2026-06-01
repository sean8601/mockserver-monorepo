package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.AsyncApiControlPlane;
import org.mockserver.async.AsyncApiControlPlaneRegistry;
import org.mockserver.async.AsyncApiMockOrchestrator;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiParser;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.KafkaMessagePublisher;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.MqttMessagePublisher;
import org.mockserver.async.subscribe.KafkaMessageSubscriber;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.MqttMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.async.validation.AsyncApiSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of {@link AsyncApiControlPlane} that lives in the mockserver-async
 * module and is registered into the core's {@link AsyncApiControlPlaneRegistry}
 * at server startup.
 * <p>
 * Handles:
 * <ul>
 *   <li>Loading AsyncAPI specs via the REST control-plane</li>
 *   <li>Creating publishers and subscribers for each channel</li>
 *   <li>Schema validation of generated and consumed messages</li>
 *   <li>Returning status including recorded messages</li>
 *   <li>Resetting all state on server reset</li>
 * </ul>
 */
public class AsyncApiControlPlaneImpl implements AsyncApiControlPlane {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiControlPlaneImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;

    /**
     * Maximum number of validation issue records retained. Prevents unbounded
     * memory growth if many channels produce schema-invalid examples.
     */
    static final int MAX_VALIDATION_ISSUES = 1000;

    private final AsyncApiParser parser = new AsyncApiParser();
    private final MessageExampleGenerator generator = new MessageExampleGenerator();
    private final AsyncApiSchemaValidator schemaValidator = new AsyncApiSchemaValidator();

    // Active state
    private volatile AsyncApiSpec loadedSpec;
    private volatile BrokerConfig activeBrokerConfig;
    private final List<AsyncApiMockOrchestrator> activeOrchestrators = new CopyOnWriteArrayList<>();
    private final List<MessagePublisher> activePublishers = new CopyOnWriteArrayList<>();
    private final List<MessageSubscriber> activeSubscribers = new CopyOnWriteArrayList<>();
    private final List<SchemaValidationRecord> validationIssues = new CopyOnWriteArrayList<>();

    /**
     * Register this implementation into the core registry.
     * Call at server startup (e.g. from MockServerLifeCycle or Main).
     */
    public static void registerIfAvailable() {
        try {
            AsyncApiControlPlaneImpl impl = new AsyncApiControlPlaneImpl();
            AsyncApiControlPlaneRegistry.getInstance().register(impl);
            LOG.info("AsyncAPI control-plane registered");
        } catch (Exception e) {
            LOG.debug("AsyncAPI control-plane not available: {}", e.getMessage());
        }
    }

    @Override
    public JsonNode load(String requestBody) {
        // Reset any previous state
        resetInternal();

        try {
            // Parse the request body: either a plain spec or a wrapper
            String specContent;
            BrokerConfig brokerConfig;

            JsonNode bodyNode = tryParseJson(requestBody);
            if (bodyNode != null && bodyNode.has("spec")) {
                specContent = bodyNode.get("spec").isTextual()
                    ? bodyNode.get("spec").asText()
                    : MAPPER.writeValueAsString(bodyNode.get("spec"));
                brokerConfig = parseBrokerConfig(bodyNode.get("brokerConfig"));
            } else {
                // Plain spec
                specContent = requestBody;
                brokerConfig = BrokerConfig.defaultConfig();
            }

            AsyncApiSpec spec = parser.parse(specContent);
            this.loadedSpec = spec;
            this.activeBrokerConfig = brokerConfig;

            // Create publishers and subscribers for each channel
            for (AsyncApiChannel channel : spec.getChannels()) {
                // Validate generated examples against schema
                String example = generator.generateExample(channel);
                if (channel.getPayloadSchema() != null) {
                    AsyncApiSchemaValidator.ValidationResult result = schemaValidator.validate(example, channel.getPayloadSchema());
                    if (!result.isValid()) {
                        addValidationIssue(new SchemaValidationRecord(
                            channel.getName(), "generated_example", result.getErrors()));
                        LOG.warn("Generated example for channel '{}' does not conform to schema: {}",
                            channel.getName(), truncate(String.valueOf(result.getErrors())));
                    }
                }
            }

            // Create broker connections based on config
            createBrokerConnections(spec, brokerConfig);

            // Build response
            return buildLoadResponse(spec);

        } catch (Exception e) {
            // Clean up any partially-created brokers on failure
            resetInternal();
            throw new RuntimeException("Failed to load AsyncAPI spec: " + e.getMessage(), e);
        }
    }

    /**
     * Create publisher/subscriber connections. Extracted so partial-failure cleanup
     * is handled by the caller's catch block calling {@link #resetInternal()}.
     */
    private void createBrokerConnections(AsyncApiSpec spec, BrokerConfig brokerConfig) {
        if (brokerConfig.kafkaBootstrapServers != null) {
            MessagePublisher publisher = new KafkaMessagePublisher(brokerConfig.kafkaBootstrapServers);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            // One-shot publish
            if (brokerConfig.publishOnLoad) {
                orchestrator.publishAll();
            }
            // Scheduled publish
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                String groupId = brokerConfig.kafkaGroupId != null
                    ? brokerConfig.kafkaGroupId : "mockserver-async-consumer";
                KafkaMessageSubscriber subscriber = new KafkaMessageSubscriber(
                    brokerConfig.kafkaBootstrapServers, groupId);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }

        if (brokerConfig.mqttBrokerUrl != null) {
            String pubClientId = brokerConfig.mqttClientId != null
                ? brokerConfig.mqttClientId + "-pub" : "mockserver-mqtt-pub";
            int qos = brokerConfig.mqttQos >= 0 ? brokerConfig.mqttQos : 1;
            MessagePublisher publisher = new MqttMessagePublisher(
                brokerConfig.mqttBrokerUrl, pubClientId, qos);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            if (brokerConfig.publishOnLoad) {
                orchestrator.publishAll();
            }
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                String subClientId = brokerConfig.mqttClientId != null
                    ? brokerConfig.mqttClientId + "-sub" : "mockserver-mqtt-sub";
                MqttMessageSubscriber subscriber = new MqttMessageSubscriber(
                    brokerConfig.mqttBrokerUrl, subClientId, qos);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }
    }

    @Override
    public JsonNode status() {
        ObjectNode result = MAPPER.createObjectNode();

        if (loadedSpec == null) {
            result.put("loaded", false);
            result.putArray("channels");
            result.put("publishers", 0);
            result.put("subscribers", 0);
            result.putArray("recordedMessages");
            return result;
        }

        result.put("loaded", true);
        result.put("specTitle", loadedSpec.getTitle());
        result.put("specVersion", loadedSpec.getAsyncApiVersion());

        ArrayNode channelsArray = result.putArray("channels");
        for (AsyncApiChannel channel : loadedSpec.getChannels()) {
            ObjectNode channelNode = MAPPER.createObjectNode();
            channelNode.put("name", channel.getName());
            channelNode.put("hasSchema", channel.getPayloadSchema() != null);
            channelNode.put("exampleCount", channel.getPayloadExamples().size());
            channelsArray.add(channelNode);
        }

        result.put("publishers", activePublishers.size());
        result.put("subscribers", activeSubscribers.size());

        // Recorded messages from subscribers
        ArrayNode recordedArray = result.putArray("recordedMessages");
        for (MessageSubscriber subscriber : activeSubscribers) {
            for (RecordedMessage msg : subscriber.getAllRecordedMessages()) {
                ObjectNode msgNode = MAPPER.createObjectNode();
                msgNode.put("channel", msg.getChannel());
                if (msg.getKey() != null) {
                    msgNode.put("key", msg.getKey());
                }
                msgNode.put("payload", msg.getPayload());
                if (!msg.getHeaders().isEmpty()) {
                    ObjectNode headersNode = msgNode.putObject("headers");
                    msg.getHeaders().forEach(headersNode::put);
                }
                msgNode.put("timestamp", msg.getTimestamp().toString());

                // Validate recorded message against schema
                AsyncApiChannel matchingChannel = findChannel(msg.getChannel());
                if (matchingChannel != null && matchingChannel.getPayloadSchema() != null) {
                    AsyncApiSchemaValidator.ValidationResult validationResult =
                        schemaValidator.validate(msg.getPayload(), matchingChannel.getPayloadSchema());
                    msgNode.put("schemaValid", validationResult.isValid());
                    if (!validationResult.isValid()) {
                        msgNode.put("schemaErrors", validationResult.getErrors());
                    }
                }

                recordedArray.add(msgNode);
            }
        }

        // Validation issues from example generation
        if (!validationIssues.isEmpty()) {
            ArrayNode issuesArray = result.putArray("validationIssues");
            for (SchemaValidationRecord issue : validationIssues) {
                ObjectNode issueNode = MAPPER.createObjectNode();
                issueNode.put("channel", issue.channel);
                issueNode.put("context", issue.context);
                issueNode.put("errors", issue.errors);
                issuesArray.add(issueNode);
            }
        }

        return result;
    }

    @Override
    public void reset() {
        resetInternal();
        LOG.info("AsyncAPI control-plane reset");
    }

    private void resetInternal() {
        for (AsyncApiMockOrchestrator orchestrator : activeOrchestrators) {
            try {
                orchestrator.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping orchestrator: {}", e.getMessage());
            }
        }
        for (MessagePublisher publisher : activePublishers) {
            try {
                publisher.close();
            } catch (Exception e) {
                LOG.warn("Error closing publisher: {}", e.getMessage());
            }
        }
        for (MessageSubscriber subscriber : activeSubscribers) {
            try {
                subscriber.close();
            } catch (Exception e) {
                LOG.warn("Error closing subscriber: {}", e.getMessage());
            }
        }
        activeOrchestrators.clear();
        activePublishers.clear();
        activeSubscribers.clear();
        validationIssues.clear();
        loadedSpec = null;
        activeBrokerConfig = null;
    }

    /**
     * Add a validation issue, enforcing the bounded cap.
     */
    private void addValidationIssue(SchemaValidationRecord record) {
        // CopyOnWriteArrayList size check + add is not atomic, but for a cap this
        // is fine — at worst we overshoot by a small number under concurrency
        while (validationIssues.size() >= MAX_VALIDATION_ISSUES) {
            validationIssues.remove(0);
        }
        validationIssues.add(record);
    }

    private JsonNode buildLoadResponse(AsyncApiSpec spec) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("loaded", true);
        result.put("specTitle", spec.getTitle());
        result.put("specVersion", spec.getAsyncApiVersion());
        result.put("channelCount", spec.getChannels().size());

        ArrayNode channelsArray = result.putArray("channels");
        for (AsyncApiChannel channel : spec.getChannels()) {
            ObjectNode channelNode = MAPPER.createObjectNode();
            channelNode.put("name", channel.getName());
            channelNode.put("hasSchema", channel.getPayloadSchema() != null);
            channelsArray.add(channelNode);
        }

        result.put("publishers", activePublishers.size());
        result.put("subscribers", activeSubscribers.size());

        if (!validationIssues.isEmpty()) {
            ArrayNode issuesArray = result.putArray("validationIssues");
            for (SchemaValidationRecord issue : validationIssues) {
                ObjectNode issueNode = MAPPER.createObjectNode();
                issueNode.put("channel", issue.channel);
                issueNode.put("context", issue.context);
                issueNode.put("errors", issue.errors);
                issuesArray.add(issueNode);
            }
        }

        return result;
    }

    private JsonNode tryParseJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (IOException e) {
            return null;
        }
    }

    private BrokerConfig parseBrokerConfig(JsonNode node) {
        if (node == null) {
            return BrokerConfig.defaultConfig();
        }
        BrokerConfig config = new BrokerConfig();
        config.kafkaBootstrapServers = textOrNull(node, "kafkaBootstrapServers");
        config.mqttBrokerUrl = textOrNull(node, "mqttBrokerUrl");
        config.mqttClientId = textOrNull(node, "mqttClientId");
        config.kafkaGroupId = textOrNull(node, "kafkaGroupId");
        config.publishOnLoad = boolOrDefault(node, "publishOnLoad", true);
        config.consume = boolOrDefault(node, "consume", false);
        config.publishIntervalMillis = longOrDefault(node, "publishIntervalMillis", 0);
        config.mqttQos = intOrDefault(node, "mqttQos", 1);
        return config;
    }

    private AsyncApiChannel findChannel(String name) {
        if (loadedSpec == null) {
            return null;
        }
        for (AsyncApiChannel channel : loadedSpec.getChannels()) {
            if (channel.getName().equals(name)) {
                return channel;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    private static boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isBoolean()) ? child.asBoolean() : defaultValue;
    }

    private static long longOrDefault(JsonNode node, String field, long defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asLong() : defaultValue;
    }

    private static int intOrDefault(JsonNode node, String field, int defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asInt() : defaultValue;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOG_PAYLOAD_LENGTH
            ? value
            : value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...(" + value.length() + " chars)";
    }

    /**
     * Broker connection configuration, parsed from the optional {@code brokerConfig}
     * field in the PUT request body.
     */
    static class BrokerConfig {
        String kafkaBootstrapServers;
        String kafkaGroupId;
        String mqttBrokerUrl;
        String mqttClientId;
        boolean publishOnLoad = true;
        boolean consume = false;
        long publishIntervalMillis = 0;
        int mqttQos = 1;

        static BrokerConfig defaultConfig() {
            return new BrokerConfig();
        }
    }

    /**
     * Record of a schema validation issue for reporting.
     */
    static class SchemaValidationRecord {
        final String channel;
        final String context;
        final String errors;

        SchemaValidationRecord(String channel, String context, String errors) {
            this.channel = channel;
            this.context = context;
            this.errors = errors;
        }
    }
}
