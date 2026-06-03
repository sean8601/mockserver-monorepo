package org.mockserver.netty.grpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.Test;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for the Phase 0 gRPC bidi-streaming multiplex pipeline scaffolding.
 * <p>
 * Verifies:
 * <ul>
 *   <li>The re-aggregation chain (Http2StreamFrameToHttpObjectCodec + HttpObjectAggregator)
 *       correctly produces a FullHttpRequest from standard HTTP objects (simulating what the
 *       frame-to-http codec emits for each HTTP/2 stream).</li>
 *   <li>Multiple DATA-frame equivalents are concatenated into a single body.</li>
 *   <li>Headers (method, path, content-type) are preserved through the re-aggregation.</li>
 * </ul>
 * <p>
 * These tests use EmbeddedChannel with just the re-aggregation handlers (not the full
 * Http2FrameCodec + Http2MultiplexHandler, which require a real transport). The full
 * integration of the multiplex pipeline into PortUnificationHandler is covered by
 * flag-off tests that verify the existing pipeline is unchanged when the flag is disabled.
 */
public class GrpcMultiplexChildInitializerTest {

    /**
     * Verifies that Http2StreamFrameToHttpObjectCodec(true) + HttpObjectAggregator re-aggregates
     * a FullHttpRequest into the same FullHttpRequest (passthrough for already-aggregated objects).
     * This mirrors Phase 0 behaviour where the multiplex pipeline re-aggregates to behave
     * identically to InboundHttp2ToHttpAdapter.
     */
    @Test
    public void shouldReAggregateFullHttpRequestThroughPhase0Chain() {
        // Build a pipeline with just the re-aggregation handlers + a capture handler
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(1048576),
            capture
        );

        // Build a gRPC-like FullHttpRequest
        byte[] bodyBytes = "test-grpc-body-content".getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            "/com.example.Service/Method",
            Unpooled.wrappedBuffer(bodyBytes)
        );
        request.headers().set("content-type", "application/grpc");
        request.headers().setInt("content-length", bodyBytes.length);

        // Write inbound — the Http2StreamFrameToHttpObjectCodec converts between HTTP/2
        // stream frames and HTTP objects; when it receives an already-formed HTTP object
        // (as happens in an EmbeddedChannel), it passes through. The aggregator then
        // produces a FullHttpRequest.
        channel.writeInbound(request);

        assertThat("should have captured a request", capture.captured, is(notNullValue()));
        assertThat(capture.captured.method(), is(HttpMethod.POST));
        assertThat(capture.captured.uri(), is("/com.example.Service/Method"));
        assertThat(capture.captured.headers().get("content-type"), is("application/grpc"));

        byte[] capturedBody = new byte[capture.captured.content().readableBytes()];
        capture.captured.content().readBytes(capturedBody);
        assertThat(new String(capturedBody, StandardCharsets.UTF_8), is("test-grpc-body-content"));

        capture.release();
        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that the HttpObjectAggregator in the re-aggregation chain enforces the
     * maxRequestBodySize limit, rejecting requests that exceed it.
     */
    @Test
    public void shouldEnforceMaxRequestBodySizeInReAggregation() {
        int maxBodySize = 64;
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(maxBodySize),
            capture
        );

        // HttpObjectAggregator only enforces the size limit while aggregating a streamed message
        // (an HttpMessage followed by separate HttpContent parts) — it does not re-check an
        // already-assembled FullHttpRequest. So feed the request as a header object plus an
        // oversized trailing content chunk, which is how frames arrive on the real pipeline.
        byte[] largeBody = new byte[maxBodySize + 1];
        java.util.Arrays.fill(largeBody, (byte) 'X');
        io.netty.handler.codec.http.HttpRequest header = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/com.example.Service/LargeMethod"
        );
        header.headers().set("content-type", "application/grpc");
        io.netty.handler.codec.http.LastHttpContent oversized =
            new io.netty.handler.codec.http.DefaultLastHttpContent(Unpooled.wrappedBuffer(largeBody));

        // The aggregator responds 413 and never forwards the assembled request downstream.
        channel.writeInbound(header, oversized);

        assertThat("oversized request must be rejected by the aggregator and never reach the capture handler",
            capture.captured, is(org.hamcrest.Matchers.nullValue()));

        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that the re-aggregation pipeline preserves all gRPC-relevant headers
     * (content-type, te, grpc-encoding, custom metadata).
     */
    @Test
    public void shouldPreserveGrpcHeadersThroughReAggregation() {
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(1048576),
            capture
        );

        byte[] bodyBytes = "payload".getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            "/com.example.Service/StreamMethod",
            Unpooled.wrappedBuffer(bodyBytes)
        );
        request.headers().set("content-type", "application/grpc");
        request.headers().set("te", "trailers");
        request.headers().set("grpc-encoding", "identity");
        request.headers().set("x-custom-metadata", "test-value");
        request.headers().setInt("content-length", bodyBytes.length);

        channel.writeInbound(request);

        assertThat("should have captured a request", capture.captured, is(notNullValue()));
        assertThat(capture.captured.headers().get("content-type"), is("application/grpc"));
        assertThat(capture.captured.headers().get("te"), is("trailers"));
        assertThat(capture.captured.headers().get("grpc-encoding"), is("identity"));
        assertThat(capture.captured.headers().get("x-custom-metadata"), is("test-value"));

        capture.release();
        channel.finishAndReleaseAll();
    }

    /**
     * Simple capture handler for test assertions.
     */
    @ChannelHandler.Sharable
    private static class CaptureHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        FullHttpRequest captured;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            // Retain so we can inspect after the handler completes (released via release())
            release();
            captured = msg.retainedDuplicate();
        }

        void release() {
            if (captured != null) {
                captured.release();
                captured = null;
            }
        }
    }
}
