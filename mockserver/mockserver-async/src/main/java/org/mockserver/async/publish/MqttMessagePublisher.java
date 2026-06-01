package org.mockserver.async.publish;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * A {@link MessagePublisher} that delegates to an MQTT {@link MqttClient}.
 * The channel name maps directly to an MQTT topic.
 */
public class MqttMessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MqttMessagePublisher.class);
    private static final int DEFAULT_QOS = 1;

    private final MqttClient client;
    private final int qos;

    /**
     * Create a publisher connected to the given MQTT broker.
     *
     * @param brokerUrl the MQTT broker URL (e.g. {@code tcp://localhost:1883})
     * @param clientId  the client identifier
     */
    public MqttMessagePublisher(String brokerUrl, String clientId) {
        try {
            this.client = new MqttClient(brokerUrl, clientId);
            this.client.connect();
            this.qos = DEFAULT_QOS;
        } catch (MqttException e) {
            throw new RuntimeException("Failed to connect to MQTT broker: " + brokerUrl, e);
        }
    }

    /**
     * Package-private constructor for injecting a mock client in tests.
     */
    MqttMessagePublisher(MqttClient client, int qos) {
        this.client = client;
        this.qos = qos;
    }

    @Override
    public void publish(String channel, String payload) {
        try {
            LOG.debug("Publishing to MQTT topic '{}': {}", channel, payload);
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            client.publish(channel, message);
        } catch (MqttException e) {
            throw new RuntimeException("Failed to publish to MQTT topic: " + channel, e);
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            LOG.warn("Error closing MQTT client: {}", e.getMessage());
        }
    }
}
