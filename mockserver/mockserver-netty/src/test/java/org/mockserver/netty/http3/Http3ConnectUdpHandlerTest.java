package org.mockserver.netty.http3;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link Http3ConnectUdpHandler}.
 * <p>
 * These tests use an {@link EmbeddedChannel} so they do not require the native
 * QUIC transport and will run on all platforms.
 */
public class Http3ConnectUdpHandlerTest {

    @Test
    public void shouldRejectConnectRequestWith501() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Send a CONNECT request
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().authority("target.example.com:443");

        channel.writeInbound(connectHeaders);

        // Read the response headers
        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write response headers", responseHeaders);
        assertThat("status should be 501",
            responseHeaders.headers().status().toString(), is("501"));
        assertThat("content-type should be JSON",
            responseHeaders.headers().get("content-type").toString(),
            containsString("application/json"));
        assertThat("server header should be set",
            responseHeaders.headers().get("server").toString(),
            is("mockserver-http3-experimental"));

        // Read the response body
        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write response body", responseBody);
        String body = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should explain CONNECT-UDP is not implemented",
            body, containsString("CONNECT-UDP (MASQUE) not yet implemented"));
        assertThat("body should explain the codec limitation",
            body, containsString(":protocol pseudo-header"));
        assertThat("body should mention the flag",
            body, containsString("http3ConnectUdpEnabled"));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldPassThroughNonConnectRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Send a normal GET request
        DefaultHttp3HeadersFrame getHeaders = new DefaultHttp3HeadersFrame();
        getHeaders.headers().method("GET");
        getHeaders.headers().path("/hello");
        getHeaders.headers().scheme("https");
        getHeaders.headers().authority("example.com");

        channel.writeInbound(getHeaders);

        // The handler should NOT have written any outbound response
        assertNull("should not write outbound for non-CONNECT", channel.readOutbound());

        // The frame should have been passed to the next handler (inbound)
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("GET request should pass through to the next handler", passedThrough);
        assertThat("method should be GET", passedThrough.headers().method().toString(), is("GET"));
        assertThat("path should be /hello", passedThrough.headers().path().toString(), is("/hello"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughPostRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        DefaultHttp3HeadersFrame postHeaders = new DefaultHttp3HeadersFrame();
        postHeaders.headers().method("POST");
        postHeaders.headers().path("/api/data");
        postHeaders.headers().scheme("https");
        postHeaders.headers().authority("example.com");

        channel.writeInbound(postHeaders);

        // POST should pass through
        assertNull("should not write outbound for POST", channel.readOutbound());
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("POST request should pass through", passedThrough);
        assertThat("method should be POST", passedThrough.headers().method().toString(), is("POST"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughDataFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Data frames are not Http3HeadersFrame, so they should pass through
        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            io.netty.buffer.Unpooled.wrappedBuffer("test data".getBytes(StandardCharsets.UTF_8))
        );

        channel.writeInbound(dataFrame);

        assertNull("should not write outbound for data frame", channel.readOutbound());
        Http3DataFrame passedThrough = channel.readInbound();
        assertNotNull("data frame should pass through", passedThrough);
        String content = passedThrough.content().toString(StandardCharsets.UTF_8);
        assertThat("content should match", content, is("test data"));
        passedThrough.release();

        channel.finish();
    }

    @Test
    public void shouldHandleConnectWithDifferentAuthorities() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // CONNECT to a specific host:port
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().authority("10.0.0.1:8080");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should respond to CONNECT", responseHeaders);
        assertThat("status should be 501",
            responseHeaders.headers().status().toString(), is("501"));

        // Read and release body
        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should have response body", responseBody);
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldBeCaseInsensitiveForConnectMethod() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler());

        // Lower-case "connect"
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("connect");
        connectHeaders.headers().authority("example.com:443");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should handle lower-case CONNECT", responseHeaders);
        assertThat("status should be 501",
            responseHeaders.headers().status().toString(), is("501"));

        // Read and release body
        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should have response body", responseBody);
        responseBody.release();

        channel.finish();
    }
}
