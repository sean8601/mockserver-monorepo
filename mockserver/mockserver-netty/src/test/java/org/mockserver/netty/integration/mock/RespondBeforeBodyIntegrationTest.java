package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertTrue;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Reproduces the okhttp/okhttp#1001 scenario (issue #1831):
 * server responds to a POST with a large body before consuming the body, then closes the connection.
 */
public class RespondBeforeBodyIntegrationTest {

    private ClientAndServer mockServerClient;
    private int port;

    @Before
    public void startServer() {
        mockServerClient = startClientAndServer();
        port = mockServerClient.getPort();
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Test
    public void shouldRespondBeforeConsumingLargeRequestBody() throws Exception {
        // given
        mockServerClient
            .when(request()
                .withMethod("POST")
                .withPath("/upload")
                .withRespondBeforeBody(true)
            )
            .respond(response()
                .withStatusCode(403)
                .withBody("forbidden")
                .withConnectionOptions(connectionOptions().withCloseSocket(true))
            );

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Advertise a large body but only send headers + a tiny preamble.
            int advertisedContentLength = 50_000_000;
            String requestHead =
                "POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + advertisedContentLength + "\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n";
            out.write(requestHead.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Send 1 KB of body
            byte[] preamble = new byte[1024];
            out.write(preamble);
            out.flush();

            // Now read the response
            byte[] buf = new byte[8192];
            int read = in.read(buf);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue("expected response bytes, got " + read, read > 0);
            String responseText = new String(buf, 0, read, StandardCharsets.UTF_8);
            assertThat(responseText, containsString("403"));
            assertThat(responseText, containsString("forbidden"));

            // Response should arrive promptly, not after the full 50MB body is read
            assertThat("response should arrive quickly, elapsed=" + elapsed + "ms", elapsed, lessThan(5_000L));

            // Read should return -1 fairly soon (peer closed connection)
            int next = in.read();
            assertThat("server should close socket after early response", next, lessThanOrEqualTo(0));
        }
    }

    @Test
    public void shouldRejectExpectationWithRespondBeforeBodyAndBodyMatcher() throws IOException {
        // given a raw PUT to control plane with an invalid expectation
        String body = "{ \"httpRequest\":{ \"method\":\"POST\", \"path\":\"/upload\", \"respondBeforeBody\":true, \"body\":\"oops\" }," +
            "  \"httpResponse\":{ \"statusCode\":200 } }";

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String requestHead =
                "PUT /mockserver/expectation HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
            out.write(requestHead.getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[8192];
            int read = in.read(buf);
            assertTrue(read > 0);
            String responseText = new String(buf, 0, read, StandardCharsets.UTF_8);
            assertThat(responseText, containsString("400"));
            assertThat(responseText, containsString("respondBeforeBody"));
        }
    }
}
