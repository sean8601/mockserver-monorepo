package org.mockserver.async.publish;

/**
 * Publishes a message payload to a named channel (topic).
 * <p>
 * Implementations exist for Kafka ({@link KafkaMessagePublisher}) and MQTT ({@link MqttMessagePublisher}).
 */
public interface MessagePublisher {

    /**
     * Publish the given payload to the specified channel.
     *
     * @param channel the channel / topic name
     * @param payload the message payload (typically JSON)
     */
    void publish(String channel, String payload);

    /**
     * Release any resources held by this publisher (producer connections, etc.).
     */
    void close();
}
