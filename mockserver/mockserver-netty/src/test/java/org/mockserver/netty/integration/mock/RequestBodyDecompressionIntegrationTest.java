package org.mockserver.netty.integration.mock;

import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Verifies the {@code decompressRequestBodies} configuration property (issue #2326). By default
 * MockServer decompresses request bodies so expectations match the decompressed content; when the
 * property is disabled the body is recorded exactly as received on the wire.
 */
public class RequestBodyDecompressionIntegrationTest {

    private static byte[] gzip(String content) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    /**
     * Sends a single POST /test with a gzip Content-Encoding and the given (already compressed) body
     * bytes over a one-shot connection (Connection: close), then reads the full response so the
     * request has been processed before recorded requests are retrieved.
     */
    private static void sendGzippedRequest(int port, byte[] compressedBody) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            String head = "POST /test HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Encoding: gzip\r\n" +
                "Connection: close\r\n" +
                "Content-Length: " + compressedBody.length + "\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.UTF_8));
            output.write(compressedBody);
            output.flush();

            // drain the response so the server has finished handling the request
            InputStream input = socket.getInputStream();
            byte[] buf = new byte[4096];
            while (input.read(buf) != -1) {
                // discard
            }
        }
    }

    @Test
    public void shouldRecordCompressedBodyExactlyWhenDecompressionDisabled() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(configuration().decompressRequestBodies(false), 0);
        try {
            mockServer.when(request().withMethod("POST").withPath("/test")).respond(response().withStatusCode(200));
            byte[] compressedBody = gzip("plain");

            sendGzippedRequest(mockServer.getLocalPort(), compressedBody);

            HttpRequest[] recorded = mockServer.retrieveRecordedRequests(request().withMethod("POST").withPath("/test"));
            assertThat(recorded.length, is(1));
            // the raw bytes are exactly what was sent on the wire (still compressed)
            assertThat(recorded[0].getBodyAsRawBytes(), is(compressedBody));
            // the Content-Encoding header is preserved
            assertThat(recorded[0].getFirstHeader("Content-Encoding"), is("gzip"));
        } finally {
            stopQuietly(mockServer);
        }
    }

    @Test
    public void shouldDecompressBodyByDefault() throws Exception {
        ClientAndServer mockServer = ClientAndServer.startClientAndServer(configuration(), 0);
        try {
            mockServer.when(request().withMethod("POST").withPath("/test")).respond(response().withStatusCode(200));

            sendGzippedRequest(mockServer.getLocalPort(), gzip("plain"));

            HttpRequest[] recorded = mockServer.retrieveRecordedRequests(request().withMethod("POST").withPath("/test"));
            assertThat(recorded.length, is(1));
            // by default the recorded body is the decompressed content
            assertThat(new String(recorded[0].getBodyAsRawBytes(), StandardCharsets.UTF_8), is("plain"));
            // the Content-Encoding header is still preserved (re-added after Netty strips it)
            assertThat(recorded[0].getFirstHeader("Content-Encoding"), is("gzip"));
        } finally {
            stopQuietly(mockServer);
        }
    }
}
