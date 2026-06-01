package org.mockserver.async.publish;

import java.util.Map;

/**
 * Publishes a message payload to a named channel (topic).
 * <p>
 * Implementations exist for Kafka ({@link KafkaMessagePublisher}) and MQTT ({@link MqttMessagePublisher}).
 */
public interface MessagePublisher {

    /**
     * Publish the given payload to the specified channel with no key or headers.
     *
     * @param channel the channel / topic name
     * @param payload the message payload (typically JSON)
     */
    void publish(String channel, String payload);

    /**
     * Publish the given payload with an optional key and headers.
     * Default implementation delegates to {@link #publish(String, String)},
     * ignoring key and headers — implementations that support them override this.
     *
     * @param channel the channel / topic name
     * @param key     the message key (may be null)
     * @param payload the message payload (typically JSON)
     * @param headers optional headers (may be null)
     */
    default void publish(String channel, String key, String payload, Map<String, String> headers) {
        publish(channel, payload);
    }

    /**
     * Release any resources held by this publisher (producer connections, etc.).
     */
    void close();
}
