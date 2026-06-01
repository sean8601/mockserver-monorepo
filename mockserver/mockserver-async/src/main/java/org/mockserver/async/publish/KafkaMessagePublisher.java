package org.mockserver.async.publish;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * A {@link MessagePublisher} that delegates to a Kafka {@link KafkaProducer}.
 * The channel name maps directly to a Kafka topic.
 */
public class KafkaMessagePublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaMessagePublisher.class);

    private final KafkaProducer<String, String> producer;

    /**
     * Create a publisher connected to the given Kafka bootstrap servers.
     *
     * @param bootstrapServers comma-separated list of host:port pairs
     */
    public KafkaMessagePublisher(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Package-private constructor for injecting a mock producer in tests.
     */
    KafkaMessagePublisher(KafkaProducer<String, String> producer) {
        this.producer = producer;
    }

    @Override
    public void publish(String channel, String payload) {
        LOG.debug("Publishing to Kafka topic '{}': {}", channel, payload);
        producer.send(new ProducerRecord<>(channel, payload));
    }

    @Override
    public void close() {
        producer.close();
    }
}
