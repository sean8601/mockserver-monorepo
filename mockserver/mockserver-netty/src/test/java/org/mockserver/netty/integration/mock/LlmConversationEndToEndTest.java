package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.llm.IsolationSource;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.client.LlmConversationBuilder.conversation;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.Provider.ANTHROPIC;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end test for multi-turn LLM conversation mocking.
 * Registers a 2-turn conversation (turn 1 returns tool_use, turn 2 after
 * tool_result returns final answer), makes two POST requests, and verifies
 * both responses match.
 */
public class LlmConversationEndToEndTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static int mockServerPort;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    @Test
    public void shouldHandleTwoTurnConversation() throws Exception {
        // Register 2-turn conversation: turn 1 = tool_use, turn 2 = final answer
        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .turn()
                .whenTurnIndex(0)
                .respondingWith(completion()
                    .withText("Let me search for that.")
                    .withToolCall(toolUse("search").withArguments("{\"q\":\"weather paris\"}"))
                    .withStopReason("tool_use"))
            .andThen()
            .turn()
                .whenContainsToolResultFor("search")
                .respondingWith(completion()
                    .withText("It is 18C and sunny in Paris.")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Turn 1: user sends initial question
        String turn1Body = "{\n" +
            "  \"model\": \"claude-sonnet-4-20250514\",\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"What is the weather in Paris?\"}\n" +
            "  ]\n" +
            "}";
        String turn1Response = sendPost("/v1/messages", turn1Body);

        assertThat(turn1Response, containsString("200"));
        String turn1Json = extractJsonBody(turn1Response);
        JsonNode turn1Node = OBJECT_MAPPER.readTree(turn1Json);
        assertThat(turn1Node.get("type").asText(), is("message"));
        assertThat(turn1Node.get("stop_reason").asText(), is("tool_use"));

        // Verify tool_use block is present
        boolean hasToolUse = false;
        for (JsonNode contentBlock : turn1Node.get("content")) {
            if ("tool_use".equals(contentBlock.get("type").asText())) {
                assertThat(contentBlock.get("name").asText(), is("search"));
                hasToolUse = true;
            }
        }
        assertThat("Response should contain tool_use block", hasToolUse, is(true));

        // Turn 2: user sends tool result
        String turn2Body = "{\n" +
            "  \"model\": \"claude-sonnet-4-20250514\",\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"What is the weather in Paris?\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"text\", \"text\": \"Let me search for that.\"},\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_123\", \"name\": \"search\", \"input\": {\"q\": \"weather paris\"}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_123\", \"content\": \"18C and sunny in Paris\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}";
        String turn2Response = sendPost("/v1/messages", turn2Body);

        assertThat(turn2Response, containsString("200"));
        String turn2Json = extractJsonBody(turn2Response);
        JsonNode turn2Node = OBJECT_MAPPER.readTree(turn2Json);
        assertThat(turn2Node.get("type").asText(), is("message"));
        assertThat(turn2Node.get("stop_reason").asText(), is("end_turn"));

        // Verify text content
        boolean hasText = false;
        for (JsonNode contentBlock : turn2Node.get("content")) {
            if ("text".equals(contentBlock.get("type").asText())) {
                assertThat(contentBlock.get("text").asText(), containsString("18C and sunny"));
                hasText = true;
            }
        }
        assertThat("Response should contain text block", hasText, is(true));
    }

    @Test
    public void shouldIsolateConversationsByHeader() throws Exception {
        // Register isolated conversation
        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .isolateBy(IsolationSource.header("x-session-id"))
            .turn()
                .respondingWith(completion()
                    .withText("Turn 1 response")
                    .withStopReason("end_turn"))
            .andThen()
            .turn()
                .respondingWith(completion()
                    .withText("Turn 2 response")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        String body = "{\"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}";

        // Session A: first request should get turn 1
        String responseA1 = sendPostWithHeader("/v1/messages", body, "x-session-id", "session-A");
        assertThat(responseA1, containsString("200"));
        assertThat(extractJsonBody(responseA1), containsString("Turn 1 response"));

        // Session B: first request should ALSO get turn 1 (independent state)
        String responseB1 = sendPostWithHeader("/v1/messages", body, "x-session-id", "session-B");
        assertThat(responseB1, containsString("200"));
        assertThat(extractJsonBody(responseB1), containsString("Turn 1 response"));

        // Session A: second request should get turn 2
        String responseA2 = sendPostWithHeader("/v1/messages", body, "x-session-id", "session-A");
        assertThat(responseA2, containsString("200"));
        assertThat(extractJsonBody(responseA2), containsString("Turn 2 response"));

        // Session B: second request should also get turn 2
        String responseB2 = sendPostWithHeader("/v1/messages", body, "x-session-id", "session-B");
        assertThat(responseB2, containsString("200"));
        assertThat(extractJsonBody(responseB2), containsString("Turn 2 response"));
    }

    @Test
    public void shouldPredicateOnWireDifferentiatesExpectations() throws Exception {
        // Two expectations on the same scenario state, differentiated ONLY by predicates.
        // One matches whenLatestMessageContains("foo"), the other has no predicate (catch-all).
        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .turn()
                .whenLatestMessageContains("foo")
                .respondingWith(completion()
                    .withText("Matched foo predicate")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .turn()
                .respondingWith(completion()
                    .withText("Catch-all response")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        // Request with "foo" should match the predicate expectation
        String fooBody = "{\"messages\": [{\"role\": \"user\", \"content\": \"tell me about foo bar\"}]}";
        String fooResponse = sendPost("/v1/messages", fooBody);
        assertThat(fooResponse, containsString("200"));
        assertThat(extractJsonBody(fooResponse), containsString("Matched foo predicate"));

        // Request without "foo" should match the catch-all
        String otherBody = "{\"messages\": [{\"role\": \"user\", \"content\": \"tell me about baz\"}]}";
        String otherResponse = sendPost("/v1/messages", otherBody);
        assertThat(otherResponse, containsString("200"));
        assertThat(extractJsonBody(otherResponse), containsString("Catch-all response"));
    }

    @Test
    public void shouldFallBackToSharedKeyWhenIsolationHeaderMissing() throws Exception {
        // Configure isolation by header x-session-id.
        // 3 requests: x-session-id: A, x-session-id: B, no header.
        // A and B should have independent state. No-header falls back to shared key.
        conversation()
            .withPath("/v1/messages")
            .withProvider(ANTHROPIC)
            .isolateBy(IsolationSource.header("x-session-id"))
            .turn()
                .respondingWith(completion()
                    .withText("Turn 1 response")
                    .withStopReason("end_turn"))
            .andThen()
            .turn()
                .respondingWith(completion()
                    .withText("Turn 2 response")
                    .withStopReason("end_turn"))
            .andThen()
            .applyTo(mockServerClient);

        String body = "{\"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}";

        // Session A: turn 1
        String responseA1 = sendPostWithHeader("/v1/messages", body, "x-session-id", "A");
        assertThat(responseA1, containsString("200"));
        assertThat(extractJsonBody(responseA1), containsString("Turn 1 response"));

        // Session B: turn 1 (independent of A)
        String responseB1 = sendPostWithHeader("/v1/messages", body, "x-session-id", "B");
        assertThat(responseB1, containsString("200"));
        assertThat(extractJsonBody(responseB1), containsString("Turn 1 response"));

        // No header: turn 1 (falls back to shared key, independent of A and B)
        String responseNoHeader1 = sendPost("/v1/messages", body);
        assertThat(responseNoHeader1, containsString("200"));
        assertThat(extractJsonBody(responseNoHeader1), containsString("Turn 1 response"));

        // Session A: turn 2 (A has already advanced past turn 1)
        String responseA2 = sendPostWithHeader("/v1/messages", body, "x-session-id", "A");
        assertThat(responseA2, containsString("200"));
        assertThat(extractJsonBody(responseA2), containsString("Turn 2 response"));

        // No header: turn 2 (shared key has also advanced past turn 1)
        String responseNoHeader2 = sendPost("/v1/messages", body);
        assertThat(responseNoHeader2, containsString("200"));
        assertThat(extractJsonBody(responseNoHeader2), containsString("Turn 2 response"));

        // Session B: turn 2 (B is independent, advanced after its first hit)
        String responseB2 = sendPostWithHeader("/v1/messages", body, "x-session-id", "B");
        assertThat(responseB2, containsString("200"));
        assertThat(extractJsonBody(responseB2), containsString("Turn 2 response"));
    }

    private String sendPost(String path, String body) throws Exception {
        return sendPostWithHeader(path, body, null, null);
    }

    private String sendPostWithHeader(String path, String body, String headerName, String headerValue) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServerPort).append("\r\n");
            request.append("Content-Type: application/json\r\n");
            request.append("Connection: close\r\n");
            if (headerName != null && headerValue != null) {
                request.append(headerName).append(": ").append(headerValue).append("\r\n");
            }
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                output.write(bodyBytes);
            }
            output.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Extract the JSON body from an HTTP response string (after the blank line).
     */
    private String extractJsonBody(String httpResponse) {
        int bodyStart = httpResponse.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            bodyStart = httpResponse.indexOf("\n\n");
            if (bodyStart < 0) {
                return httpResponse;
            }
            return httpResponse.substring(bodyStart + 2);
        }
        return httpResponse.substring(bodyStart + 4);
    }
}
