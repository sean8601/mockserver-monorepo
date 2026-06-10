package org.mockserver.netty.responsewriter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests verifying that mock-generated SSE/chunked streaming responses
 * are intercepted by the stream-frame breakpoint engine when they flow through
 * {@link NettyResponseWriter#writeStreamingResponse}. Uses an EmbeddedChannel
 * to verify the actual Netty pipeline behaviour.
 *
 * <p>These tests prove that A1c's hold point already covers mock SSE/chunked
 * streams (A1d's SSE/chunked coverage is inherited, not new code), and that the
 * breakpoint decisions (continue/modify/drop) produce the correct outbound frames.
 */
public class MockStreamBreakpointIntegrationTest {

    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;
    private Configuration configuration;
    private MockServerLogger mockServerLogger;
    private Scheduler scheduler;

    @Before
    public void setup() {
        // EmbeddedChannel needs at least one handler for firstContext() to return non-null
        channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ctx = channel.pipeline().firstContext();
        configuration = Configuration.configuration()
            .breakpointTimeoutMillis(30000L)
            .breakpointMaxHeld(50);
        mockServerLogger = new MockServerLogger();
        scheduler = new Scheduler(configuration, mockServerLogger);
        // Register a catch-all breakpoint matcher for RESPONSE_STREAM so the
        // stream-frame breakpoint engine is active for tests that expect it.
        org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().register(
            HttpRequest.request(), // matches all requests
            java.util.EnumSet.of(org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM),
            configuration, mockServerLogger
        );
    }

    @After
    public void cleanup() {
        StreamFrameBreakpointRegistry.getInstance().reset();
        org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().clear();
        if (channel.isOpen()) {
            channel.close();
        }
        scheduler.shutdown();
    }

    @Test
    public void shouldPauseMockSseFrameWhenBreakpointEnabled() throws Exception {
        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "text/event-stream")
            .withStreamingBody(streamingBody);

        HttpRequest request = HttpRequest.request()
            .withMethod("GET")
            .withPath("/api/events");
        request.withLogCorrelationId("mock-sse-pause");

        NettyResponseWriter writer = new NettyResponseWriter(configuration, mockServerLogger, ctx, scheduler);
        writer.sendResponse(request, response);

        ByteBuf chunk = Unpooled.copiedBuffer("data: hello\n\n", StandardCharsets.UTF_8);
        streamingBody.addChunk(chunk);
        chunk.release();

        channel.runPendingTasks();
        Thread.sleep(100);

        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        assertThat("frame should be held in registry", registry.size(), is(1));

        PausedStreamFrame frame = registry.entries().values().iterator().next();
        assertThat(frame.getStreamId(), containsString("mock-sse-pause"));
        assertThat(new String(frame.getCapturedBytes(), StandardCharsets.UTF_8), is("data: hello\n\n"));

        registry.resolveContinue(frame.getFrameId());
        channel.runPendingTasks();
        Thread.sleep(100);

        assertThat("registry should be empty after resolution", registry.size(), is(0));

        streamingBody.complete();
        channel.runPendingTasks();

        // Verify outbound: DefaultHttpResponse (head), DefaultHttpContent (frame), LastHttpContent
        List<Object> outbound = drainOutbound();
        assertThat("should have outbound messages", outbound.size(), greaterThanOrEqualTo(2));

        boolean foundContent = false;
        for (Object msg : outbound) {
            if (msg instanceof DefaultHttpContent content && !(msg instanceof LastHttpContent)) {
                byte[] bytes = readBytes(content.content());
                if (bytes.length > 0 && new String(bytes, StandardCharsets.UTF_8).contains("hello")) {
                    foundContent = true;
                }
            }
        }
        assertThat("continued frame should be written", foundContent, is(true));

        releaseAll(outbound);
    }

    @Test
    public void shouldModifyMockSseFrame() throws Exception {
        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "text/event-stream")
            .withStreamingBody(streamingBody);

        HttpRequest request = HttpRequest.request()
            .withMethod("GET")
            .withPath("/api/events");
        request.withLogCorrelationId("mock-sse-modify");

        NettyResponseWriter writer = new NettyResponseWriter(configuration, mockServerLogger, ctx, scheduler);
        writer.sendResponse(request, response);

        ByteBuf chunk = Unpooled.copiedBuffer("data: original\n\n", StandardCharsets.UTF_8);
        streamingBody.addChunk(chunk);
        chunk.release();

        channel.runPendingTasks();
        Thread.sleep(100);

        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        PausedStreamFrame frame = registry.entries().values().iterator().next();

        registry.resolveModify(frame.getFrameId(), "data: modified\n\n".getBytes(StandardCharsets.UTF_8));
        channel.runPendingTasks();
        Thread.sleep(100);

        streamingBody.complete();
        channel.runPendingTasks();

        List<Object> outbound = drainOutbound();
        boolean foundModified = false;
        for (Object msg : outbound) {
            if (msg instanceof DefaultHttpContent content && !(msg instanceof LastHttpContent)) {
                byte[] bytes = readBytes(content.content());
                if (new String(bytes, StandardCharsets.UTF_8).contains("modified")) {
                    foundModified = true;
                }
            }
        }
        assertThat("modified frame should be written", foundModified, is(true));

        releaseAll(outbound);
    }

    @Test
    public void shouldDropMockSseFrame() throws Exception {
        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "text/event-stream")
            .withStreamingBody(streamingBody);

        HttpRequest request = HttpRequest.request()
            .withMethod("GET")
            .withPath("/api/events");
        request.withLogCorrelationId("mock-sse-drop");

        NettyResponseWriter writer = new NettyResponseWriter(configuration, mockServerLogger, ctx, scheduler);
        writer.sendResponse(request, response);

        ByteBuf chunk = Unpooled.copiedBuffer("data: drop-me\n\n", StandardCharsets.UTF_8);
        streamingBody.addChunk(chunk);
        chunk.release();

        channel.runPendingTasks();
        Thread.sleep(100);

        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        PausedStreamFrame frame = registry.entries().values().iterator().next();

        registry.resolveDrop(frame.getFrameId());
        channel.runPendingTasks();
        Thread.sleep(100);

        streamingBody.complete();
        channel.runPendingTasks();

        List<Object> outbound = drainOutbound();
        for (Object msg : outbound) {
            if (msg instanceof DefaultHttpContent content && !(msg instanceof LastHttpContent)) {
                byte[] bytes = readBytes(content.content());
                assertThat("dropped frame should not appear",
                    new String(bytes, StandardCharsets.UTF_8), not(containsString("drop-me")));
            }
        }

        releaseAll(outbound);
    }

    @Test
    public void shouldNotPauseWhenBreakpointDisabled() throws Exception {
        // Clear the matcher registry so no breakpoints are active
        org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().clear();
        Configuration disabledConfig = Configuration.configuration();

        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "text/event-stream")
            .withStreamingBody(streamingBody);

        HttpRequest request = HttpRequest.request()
            .withMethod("GET")
            .withPath("/api/events");
        request.withLogCorrelationId("mock-sse-off");

        Scheduler disabledScheduler = new Scheduler(disabledConfig, mockServerLogger);
        NettyResponseWriter writer = new NettyResponseWriter(disabledConfig, mockServerLogger, ctx, disabledScheduler);
        writer.sendResponse(request, response);

        ByteBuf chunk = Unpooled.copiedBuffer("data: pass-through\n\n", StandardCharsets.UTF_8);
        streamingBody.addChunk(chunk);
        chunk.release();

        channel.runPendingTasks();
        Thread.sleep(100);

        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        assertThat("no frames should be held when disabled", registry.size(), is(0));

        List<Object> outbound = drainOutbound();
        boolean foundContent = false;
        for (Object msg : outbound) {
            if (msg instanceof DefaultHttpContent content && !(msg instanceof LastHttpContent)) {
                byte[] bytes = readBytes(content.content());
                if (new String(bytes, StandardCharsets.UTF_8).contains("pass-through")) {
                    foundContent = true;
                }
            }
        }
        assertThat("frame should be written immediately", foundContent, is(true));

        releaseAll(outbound);
        streamingBody.complete();
        channel.runPendingTasks();
        disabledScheduler.shutdown();
    }

    @Test
    public void shouldEvictOnStreamError() throws Exception {
        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "text/event-stream")
            .withStreamingBody(streamingBody);

        HttpRequest request = HttpRequest.request()
            .withMethod("GET")
            .withPath("/api/events");
        request.withLogCorrelationId("mock-sse-error");

        NettyResponseWriter writer = new NettyResponseWriter(configuration, mockServerLogger, ctx, scheduler);
        writer.sendResponse(request, response);

        ByteBuf chunk = Unpooled.copiedBuffer("data: will-error\n\n", StandardCharsets.UTF_8);
        streamingBody.addChunk(chunk);
        chunk.release();

        channel.runPendingTasks();
        Thread.sleep(100);

        StreamFrameBreakpointRegistry registry = StreamFrameBreakpointRegistry.getInstance();
        assertThat("frame should be held", registry.size(), is(1));

        streamingBody.error(new RuntimeException("test error"));
        channel.runPendingTasks();
        Thread.sleep(200);

        assertThat("frames should be evicted on error", registry.size(), is(0));

        List<Object> outbound = drainOutbound();
        releaseAll(outbound);
    }

    // --- Helpers ---

    private List<Object> drainOutbound() {
        List<Object> out = new ArrayList<>();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            out.add(msg);
        }
        return out;
    }

    private byte[] readBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    private void releaseAll(List<Object> messages) {
        for (Object msg : messages) {
            if (msg instanceof io.netty.util.ReferenceCounted rc) {
                if (rc.refCnt() > 0) {
                    rc.release();
                }
            }
        }
    }
}
