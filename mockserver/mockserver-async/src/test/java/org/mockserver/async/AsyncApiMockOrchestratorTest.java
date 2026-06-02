package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.PublishOptions;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link AsyncApiMockOrchestrator}.
 */
public class AsyncApiMockOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private MessagePublisher publisher;

    @Before
    public void setUp() {
        openMocks(this);
    }

    @Test
    public void shouldPublishExampleForEachChannel() throws Exception {
        JsonNode example1 = MAPPER.readTree("{\"userId\": \"abc\"}");
        JsonNode example2 = MAPPER.readTree("{\"orderId\": 42}");

        AsyncApiChannel channel1 = new AsyncApiChannel("users", List.of(example1), null);
        AsyncApiChannel channel2 = new AsyncApiChannel("orders", List.of(example2), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel1, channel2));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        verify(publisher).publish(eq("users"), eq("{\"userId\":\"abc\"}"), any(PublishOptions.class));
        verify(publisher).publish(eq("orders"), eq("{\"orderId\":42}"), any(PublishOptions.class));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    public void shouldPublishSynthesizedPayloadWhenNoExample() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}"
        );

        AsyncApiChannel channel = new AsyncApiChannel("events", List.of(), schema);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        verify(publisher).publish(eq("events"), eq("{\"name\":\"string\"}"), any(PublishOptions.class));
    }

    @Test
    public void shouldPublishEmptyObjectWhenNoExampleAndNoSchema() {
        AsyncApiChannel channel = new AsyncApiChannel("empty", List.of(), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        verify(publisher).publish(eq("empty"), eq("{}"), any(PublishOptions.class));
    }

    @Test
    public void shouldDoNothingWhenNoChannels() {
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of());

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        verifyNoInteractions(publisher);
    }

    @Test
    public void shouldStartAndStopScheduledPublishing() throws Exception {
        JsonNode example = MAPPER.readTree("{\"v\": 1}");
        AsyncApiChannel channel = new AsyncApiChannel("tick", List.of(example), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.startPublishing(50);

        // Allow a few cycles
        Thread.sleep(200);
        orchestrator.stop();

        // Should have been called at least twice
        verify(publisher, atLeast(2)).publish(eq("tick"), eq("{\"v\":1}"), any(PublishOptions.class));
    }

    // ---- PublishOptions threading tests ----

    @Test
    public void shouldThreadMqttBindingsToPublisher() throws Exception {
        JsonNode example = MAPPER.readTree("{\"temp\": 22}");
        AsyncApiChannel channel = new AsyncApiChannel("sensors/temp", List.of(example), null,
            2, true, null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(publisher).publish(eq("sensors/temp"), eq("{\"temp\":22}"), optionsCaptor.capture());

        PublishOptions captured = optionsCaptor.getValue();
        assertThat(captured.getQos(), is(2));
        assertThat(captured.getRetain(), is(true));
        assertThat(captured.getKey(), is(nullValue()));
    }

    @Test
    public void shouldThreadKafkaKeyToPublisher() throws Exception {
        JsonNode example = MAPPER.readTree("{\"orderId\": 42}");
        AsyncApiChannel channel = new AsyncApiChannel("orders", List.of(example), null,
            null, null, "order-key");
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(publisher).publish(eq("orders"), eq("{\"orderId\":42}"), optionsCaptor.capture());

        PublishOptions captured = optionsCaptor.getValue();
        assertThat(captured.getKey(), is("order-key"));
        assertThat(captured.getQos(), is(nullValue()));
    }

    @Test
    public void shouldPassNoneOptionsWhenChannelHasNoBindings() throws Exception {
        JsonNode example = MAPPER.readTree("{\"v\": 1}");
        AsyncApiChannel channel = new AsyncApiChannel("plain", List.of(example), null);
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(publisher).publish(eq("plain"), eq("{\"v\":1}"), optionsCaptor.capture());

        assertThat(optionsCaptor.getValue().isEmpty(), is(true));
    }

    // ---- Multi-message channel tests ----

    @Test
    public void shouldPublishOnePerMessageForMultiMessageChannel() throws Exception {
        JsonNode example1 = MAPPER.readTree("{\"userId\": \"u1\"}");
        JsonNode example2 = MAPPER.readTree("{\"orderId\": 42}");
        JsonNode schema1 = MAPPER.readTree("{\"type\": \"object\", \"properties\": {\"userId\": {\"type\": \"string\"}}}");
        JsonNode schema2 = MAPPER.readTree("{\"type\": \"object\", \"properties\": {\"orderId\": {\"type\": \"integer\"}}}");

        AsyncApiMessage msg1 = new AsyncApiMessage("userEvent", schema1, List.of(example1), "user-key");
        AsyncApiMessage msg2 = new AsyncApiMessage("orderEvent", schema2, List.of(example2), "order-key");

        AsyncApiChannel channel = new AsyncApiChannel("events", List.of(example1), schema1,
            null, null, "user-key", List.of(msg1, msg2));
        AsyncApiSpec spec = new AsyncApiSpec("3.0.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        // Should have 2 publishes — one per message
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(publisher, times(2)).publish(eq("events"), payloadCaptor.capture(), optionsCaptor.capture());

        List<String> payloads = payloadCaptor.getAllValues();
        assertThat(payloads.get(0), is("{\"userId\":\"u1\"}"));
        assertThat(payloads.get(1), is("{\"orderId\":42}"));

        List<PublishOptions> options = optionsCaptor.getAllValues();
        assertThat(options.get(0).getKey(), is("user-key"));
        assertThat(options.get(1).getKey(), is("order-key"));
    }

    @Test
    public void shouldPublishMultiMessageWithChannelLevelMqttBindings() throws Exception {
        JsonNode example1 = MAPPER.readTree("{\"temp\": 22}");
        JsonNode example2 = MAPPER.readTree("{\"humidity\": 60}");

        AsyncApiMessage msg1 = new AsyncApiMessage("tempMsg", null, List.of(example1), null);
        AsyncApiMessage msg2 = new AsyncApiMessage("humidMsg", null, List.of(example2), null);

        // Channel has MQTT bindings; messages have no kafka key
        AsyncApiChannel channel = new AsyncApiChannel("sensors", List.of(example1), null,
            2, true, null, List.of(msg1, msg2));
        AsyncApiSpec spec = new AsyncApiSpec("3.0.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(publisher, times(2)).publish(eq("sensors"), any(String.class), optionsCaptor.capture());

        // Both publishes should carry the channel-level MQTT bindings
        for (PublishOptions opts : optionsCaptor.getAllValues()) {
            assertThat(opts.getQos(), is(2));
            assertThat(opts.getRetain(), is(true));
            assertThat(opts.getKey(), is(nullValue()));
        }
    }

    @Test
    public void shouldPublishSingleMessageFromChannelWithNoExplicitMessages() throws Exception {
        // A channel without explicit messages — getMessages() synthesizes one
        JsonNode example = MAPPER.readTree("{\"v\": 1}");
        AsyncApiChannel channel = new AsyncApiChannel("single", List.of(example), null,
            null, null, "single-key");
        AsyncApiSpec spec = new AsyncApiSpec("2.6.0", "Test", List.of(channel));

        AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher);
        orchestrator.publishAll();

        // Exactly one publish
        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(publisher, times(1)).publish(eq("single"), eq("{\"v\":1}"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getKey(), is("single-key"));
        verifyNoMoreInteractions(publisher);
    }
}
