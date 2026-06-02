package org.mockserver.async;

import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.PublishOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates publishing of example messages derived from an AsyncAPI spec
 * to a message broker via a {@link MessagePublisher}.
 * <p>
 * Supports one-shot publishing ({@link #publishAll()}) and scheduled
 * periodic publishing ({@link #startPublishing(long)} / {@link #stop()}).
 */
public class AsyncApiMockOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiMockOrchestrator.class);

    private final AsyncApiSpec spec;
    private final MessagePublisher publisher;
    private final MessageExampleGenerator generator;
    private volatile ScheduledExecutorService scheduler;

    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher) {
        this(spec, publisher, new MessageExampleGenerator());
    }

    /**
     * Constructor for use with a custom generator (used by the control-plane implementation
     * and tests).
     */
    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher, MessageExampleGenerator generator) {
        this.spec = spec;
        this.publisher = publisher;
        this.generator = generator;
    }

    /**
     * Publish the generated example message for each message in each channel,
     * threading any AsyncAPI bindings (MQTT qos/retain, Kafka key) as
     * {@link PublishOptions}.
     * <p>
     * Multi-message channels (v3 multiple {@code messages}, v2 {@code oneOf})
     * result in one publish call per message. Single-message channels behave
     * identically to the previous single-publish behavior.
     */
    public void publishAll() {
        for (AsyncApiChannel ch : spec.getChannels()) {
            List<AsyncApiMessage> messages = ch.getMessages();
            for (AsyncApiMessage msg : messages) {
                String payload = generator.generateExample(msg);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                PublishOptions options = buildPublishOptions(ch, msg);
                LOG.info("Publishing example to channel '{}': {}", ch.getName(), payload);
                publisher.publish(ch.getName(), payload, options);
            }
        }
    }

    /**
     * Build {@link PublishOptions} from per-message Kafka key and channel-level
     * MQTT qos/retain bindings.
     */
    private PublishOptions buildPublishOptions(AsyncApiChannel channel, AsyncApiMessage message) {
        String kafkaKey = message.getKafkaKey();
        Integer mqttQos = channel.getMqttQos();
        Boolean mqttRetain = channel.getMqttRetain();
        if (kafkaKey == null && mqttQos == null && mqttRetain == null) {
            return PublishOptions.none();
        }
        return new PublishOptions(kafkaKey, mqttQos, mqttRetain);
    }

    /**
     * Start periodic publishing at the given interval.
     *
     * @param intervalMillis the interval between publish cycles in milliseconds
     */
    public void startPublishing(long intervalMillis) {
        if (scheduler != null && !scheduler.isShutdown()) {
            LOG.warn("Scheduled publishing already running; stop() first");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "async-mock-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishAll, 0, intervalMillis, TimeUnit.MILLISECONDS);
        LOG.info("Started scheduled publishing every {} ms", intervalMillis);
    }

    /**
     * Stop periodic publishing.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            LOG.info("Stopped scheduled publishing");
        }
    }
}
