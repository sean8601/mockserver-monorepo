package org.mockserver.async.publish;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for the configurable QoS and binary payload support in
 * {@link MqttMessagePublisher}.
 */
public class MqttMessagePublisherQosTest {

    @Mock
    private MqttClient mockClient;

    @Before
    public void setUp() {
        openMocks(this);
    }

    @Test
    public void shouldPublishWithQos0() throws MqttException {
        MqttMessagePublisher publisher = new MqttMessagePublisher(mockClient, 0);
        publisher.publish("topic", "{\"qos\":0}");

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        assertThat(captor.getValue().getQos(), is(0));
    }

    @Test
    public void shouldPublishWithQos2() throws MqttException {
        MqttMessagePublisher publisher = new MqttMessagePublisher(mockClient, 2);
        publisher.publish("topic", "{\"qos\":2}");

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        assertThat(captor.getValue().getQos(), is(2));
    }

    @Test
    public void shouldPublishBinaryPayload() throws MqttException {
        MqttMessagePublisher publisher = new MqttMessagePublisher(mockClient, 1);
        byte[] binaryPayload = new byte[]{0x01, 0x02, 0x03, 0x04};
        publisher.publishBytes("topic", binaryPayload);

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        assertThat(captor.getValue().getPayload(), is(binaryPayload));
    }

    @Test
    public void shouldReportConfiguredQos() {
        MqttMessagePublisher publisher = new MqttMessagePublisher(mockClient, 2);
        assertThat(publisher.getQos(), is(2));
    }

    @Test
    public void shouldConvertStringToUtf8Bytes() throws MqttException {
        MqttMessagePublisher publisher = new MqttMessagePublisher(mockClient, 1);
        publisher.publish("topic", "Hello World");

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("topic"), captor.capture());
        String payload = new String(captor.getValue().getPayload(), StandardCharsets.UTF_8);
        assertThat(payload, is("Hello World"));
    }
}
