package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.GrpcStreamMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests gRPC bidirectional-streaming inbound breakpoints (client-to-server DATA frame interception).
 * <p>
 * Validates:
 * <ul>
 *   <li>Inbound DATA frames are parked in the registry when an INBOUND_STREAM breakpoint matcher is registered</li>
 *   <li>Continue/modify/drop/inject/close decision actions work correctly</li>
 *   <li>HTTP/2 flow control is safe: the Http2DataFrame is released IMMEDIATELY (refunding
 *       the flow-control window), and backpressure uses the existing pull-based model
 *       (withholding ctx.read())</li>
 *   <li>ByteBuf refcounts are balanced (no leak, no use-after-free)</li>
 *   <li>Stream-close evicts held inbound frames</li>
 *   <li>Default-off (no inbound breakpoint matcher or null inboundStreamId) processes frames normally</li>
 *   <li>Ordering is preserved across multiple parked frames</li>
 * </ul>
 */
public class GrpcBidiInboundBreakpointTest {

    private StreamFrameBreakpointRegistry registry;

    @Before
    public void setup() {
        registry = StreamFrameBreakpointRegistry.getInstance();
        registry.reset();
    }

    @After
    public void cleanup() {
        registry.reset();
    }

    private static GrpcProtoDescriptorStore loadDescriptorStore() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));
        return store;
    }

    private static Configuration inboundOnConfig() {
        return Configuration.configuration()
            .breakpointTimeoutMillis(30_000L)
            .breakpointMaxHeld(50);
    }

    /**
     * Encode a HelloRequest JSON body as a gRPC-framed DATA payload.
     */
    private static byte[] grpcFrame(GrpcJsonMessageConverter converter, Descriptors.MethodDescriptor method, String json) {
        byte[] proto = converter.toProtobuf(json, method.getInputType());
        return GrpcFrameCodec.encode(proto);
    }

    /**
     * Decode a captured Http2DataFrame's content as a HelloResponse JSON string.
     */
    private static String decodeResponse(GrpcJsonMessageConverter converter, Descriptors.MethodDescriptor method, Http2DataFrame frame) {
        byte[] content = new byte[frame.content().readableBytes()];
        frame.content().getBytes(frame.content().readerIndex(), content);
        List<byte[]> decoded = GrpcFrameCodec.decode(content);
        assertThat("should decode exactly one gRPC message", decoded, hasSize(1));
        return converter.toJson(decoded.get(0), method.getOutputType());
    }

    // ===== Test: inbound frame is parked and continue resumes processing =====

    @Test
    public void shouldParkInboundFrameAndContinue() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-continue";

        // Echo responder: maps HelloRequest -> HelloResponse
        Function<String, List<String>> echoResponder = json -> {
            String transformed = json.replace("\"name\"", "\"greeting\"");
            return Collections.singletonList(transformed);
        };

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Response HEADERS should be written
        assertThat("response HEADERS should be written", outbound.size(), greaterThanOrEqualTo(1));
        assertThat(outbound.get(0), instanceOf(Http2HeadersFrame.class));

        // Feed a DATA frame (inbound message)
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        ByteBuf buf = Unpooled.wrappedBuffer(msgFrame);
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(buf, false);
        assertThat("dataFrame refCnt before write", dataFrame.refCnt(), is(1));

        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        // The dataFrame should be released IMMEDIATELY (flow-control window refunded)
        assertThat("Http2DataFrame must be released immediately", dataFrame.refCnt(), is(0));

        // No response DATA should have been written yet (frame is parked)
        long dataFrameCount = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("no DATA response while frame is parked", dataFrameCount, is(0L));

        // Verify the frame is in the registry
        assertThat("registry should have 1 held frame", registry.size(), is(1));
        Map<String, PausedStreamFrame> entries = registry.entries();
        PausedStreamFrame paused = entries.values().iterator().next();
        assertThat(paused.getDirection(), is(PausedStreamFrame.Direction.INBOUND));
        assertThat(paused.getStreamId(), is(streamId));

        // Resolve as CONTINUE
        boolean resolved = registry.resolveContinue(paused.getFrameId());
        assertThat("should resolve continue", resolved, is(true));

        // Run pending tasks on the embedded channel's event loop
        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // Now a DATA response should have been written
        long dataFrameCountAfter = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("DATA response should be written after continue", dataFrameCountAfter, is(1L));

        // The response should contain "Alice" (echoed)
        Http2DataFrame responseFrame = outbound.stream()
            .filter(o -> o instanceof Http2DataFrame)
            .map(o -> (Http2DataFrame) o)
            .findFirst().orElseThrow();
        String responseJson = decodeResponse(converter, chatMethod, responseFrame);
        assertThat(responseJson, containsString("Alice"));

        // Registry should be empty now
        Thread.sleep(100);
        assertThat("registry should be empty after resolve", registry.size(), is(0));

        channel.finishAndReleaseAll();
    }

    // ===== Test: MODIFY action replaces the inbound frame bytes =====

    @Test
    public void shouldModifyInboundFrame() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-modify";

        Function<String, List<String>> echoResponder = json -> {
            String transformed = json.replace("\"name\"", "\"greeting\"");
            return Collections.singletonList(transformed);
        };

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA with "Alice"
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("dataFrame must be released immediately", dataFrame.refCnt(), is(0));
        assertThat("registry should have 1 frame", registry.size(), is(1));

        // Resolve with MODIFY: replace with "Bob"
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        byte[] replacementBytes = grpcFrame(converter, chatMethod, "{\"name\": \"Bob\"}");
        boolean resolved = registry.resolveModify(paused.getFrameId(), replacementBytes);
        assertThat("should resolve modify", resolved, is(true));

        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // Response should contain "Bob" (the modified content), not "Alice"
        Http2DataFrame responseFrame = outbound.stream()
            .filter(o -> o instanceof Http2DataFrame)
            .map(o -> (Http2DataFrame) o)
            .findFirst().orElseThrow();
        String responseJson = decodeResponse(converter, chatMethod, responseFrame);
        assertThat(responseJson, containsString("Bob"));
        assertThat(responseJson, not(containsString("Alice")));

        channel.finishAndReleaseAll();
    }

    // ===== Test: DROP action discards the inbound frame =====

    @Test
    public void shouldDropInboundFrame() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-drop";

        Function<String, List<String>> echoResponder = json -> {
            String transformed = json.replace("\"name\"", "\"greeting\"");
            return Collections.singletonList(transformed);
        };

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("dataFrame must be released immediately", dataFrame.refCnt(), is(0));

        // Resolve as DROP
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        boolean resolved = registry.resolveDrop(paused.getFrameId());
        assertThat("should resolve drop", resolved, is(true));

        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // No DATA response should have been written (frame was dropped)
        long dataFrameCount = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("no DATA response on DROP", dataFrameCount, is(0L));

        channel.finishAndReleaseAll();
    }

    // ===== Test: INJECT action processes original + injected bytes =====

    @Test
    public void shouldInjectAfterInboundFrame() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-inject";

        Function<String, List<String>> echoResponder = json -> {
            String transformed = json.replace("\"name\"", "\"greeting\"");
            return Collections.singletonList(transformed);
        };

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA with "Alice"
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("dataFrame must be released immediately", dataFrame.refCnt(), is(0));

        // Resolve as INJECT: original "Alice" + injected "Bob"
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        byte[] injectedBytes = grpcFrame(converter, chatMethod, "{\"name\": \"Bob\"}");
        boolean resolved = registry.resolveInject(paused.getFrameId(), injectedBytes);
        assertThat("should resolve inject", resolved, is(true));

        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // Two DATA responses: one for "Alice", one for "Bob"
        List<Http2DataFrame> dataFrames = outbound.stream()
            .filter(o -> o instanceof Http2DataFrame)
            .map(o -> (Http2DataFrame) o)
            .toList();
        assertThat("should have 2 DATA responses (original + injected)", dataFrames, hasSize(2));

        String resp1 = decodeResponse(converter, chatMethod, dataFrames.get(0));
        String resp2 = decodeResponse(converter, chatMethod, dataFrames.get(1));
        assertThat(resp1, containsString("Alice"));
        assertThat(resp2, containsString("Bob"));

        channel.finishAndReleaseAll();
    }

    // ===== Test: CLOSE action sends error trailer and evicts stream =====

    @Test
    public void shouldCloseStreamOnCloseDecision() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-close";

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("dataFrame must be released immediately", dataFrame.refCnt(), is(0));

        // Resolve as CLOSE
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        boolean resolved = registry.resolveClose(paused.getFrameId());
        assertThat("should resolve close", resolved, is(true));

        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // Should see trailing HEADERS with grpc-status (CANCELLED)
        boolean hasTrailingHeaders = outbound.stream()
            .filter(o -> o instanceof Http2HeadersFrame)
            .map(o -> (Http2HeadersFrame) o)
            .anyMatch(h -> h.isEndStream() && h.headers().contains(GrpcStatusMapper.GRPC_STATUS_HEADER));
        assertThat("should have trailing HEADERS with grpc-status", hasTrailingHeaders, is(true));

        // No DATA response (dropped on close)
        long dataFrameCount = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("no DATA response on CLOSE", dataFrameCount, is(0L));

        // Registry should be empty
        Thread.sleep(100);
        assertThat("registry should be empty after close", registry.size(), is(0));

        channel.finishAndReleaseAll();
    }

    // ===== Test: default-off (no config) processes frames normally =====

    @Test
    public void shouldProcessNormallyWhenInboundBreakpointsDisabled() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // No configuration/streamId -> default-off
        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(chatMethod, converter, echoResponder);

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        // Frame should be processed immediately (no parking)
        assertThat("registry should be empty (default-off)", registry.size(), is(0));

        // DATA response should be present
        long dataFrameCount = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("DATA response should be written immediately", dataFrameCount, is(1L));

        channel.finishAndReleaseAll();
    }

    // ===== Test: no inbound breakpoint matcher means frames process normally =====

    @Test
    public void shouldProcessNormallyWhenNoInboundBreakpointMatcher() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = Configuration.configuration();
        // null inboundStreamId simulates no matching breakpoint at construction time
        String streamId = null;

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS + DATA
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        // No frame parked
        assertThat("registry should be empty", registry.size(), is(0));

        // Response written immediately
        long dataFrameCount = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("DATA response should be written immediately", dataFrameCount, is(1L));

        channel.finishAndReleaseAll();
    }

    // ===== Test: Http2DataFrame is released immediately (flow-control safety) =====

    @Test
    public void shouldReleaseHttp2DataFrameImmediately() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-refcnt";

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Create a DATA frame and track its refcount
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        ByteBuf buf = Unpooled.wrappedBuffer(msgFrame);
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(buf, false);
        assertThat("refCnt before inbound write", dataFrame.refCnt(), is(1));

        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        // CRITICAL: the DataFrame's ByteBuf MUST be released immediately after channelRead
        // returns, even though the frame is parked in the breakpoint registry.
        // This is what refunds the HTTP/2 flow-control window.
        assertThat("Http2DataFrame MUST be released immediately (flow-control safety)", dataFrame.refCnt(), is(0));

        // The byte[] copy lives independently in the registry
        assertThat("registry should hold the frame", registry.size(), is(1));
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        assertThat("captured bytes should be non-null", paused.getCapturedBytes(), is(notNullValue()));
        assertThat("captured bytes length should match", paused.getCapturedBytes().length, is(msgFrame.length));

        channel.finishAndReleaseAll();
    }

    // ===== Test: ordering is preserved for multiple parked frames =====

    @Test
    public void shouldPreserveOrderingForMultipleInboundFrames() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-ordering";

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed first DATA frame (Alice)
        byte[] msg1Frame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame1 = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msg1Frame), false);
        channel.writeOneInbound(dataFrame1);
        channel.flushInbound();

        assertThat("first dataFrame released", dataFrame1.refCnt(), is(0));
        assertThat("registry should have 1 frame", registry.size(), is(1));

        // Resolve first frame to allow second to arrive
        PausedStreamFrame paused1 = registry.entries().values().iterator().next();
        registry.resolveContinue(paused1.getFrameId());
        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // Feed second DATA frame (Bob) - after first is resumed, ctx.read() was called
        byte[] msg2Frame = grpcFrame(converter, chatMethod, "{\"name\": \"Bob\"}");
        DefaultHttp2DataFrame dataFrame2 = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msg2Frame), false);
        channel.writeOneInbound(dataFrame2);
        channel.flushInbound();

        assertThat("second dataFrame released", dataFrame2.refCnt(), is(0));

        // Resolve second frame
        Thread.sleep(50);
        if (registry.size() > 0) {
            PausedStreamFrame paused2 = registry.entries().values().iterator().next();
            registry.resolveContinue(paused2.getFrameId());
            channel.runPendingTasks();
            Thread.sleep(100);
            channel.runPendingTasks();
        }

        // Both DATA responses should be present, in order
        List<Http2DataFrame> dataFrames = outbound.stream()
            .filter(o -> o instanceof Http2DataFrame)
            .map(o -> (Http2DataFrame) o)
            .toList();
        assertThat("should have 2 DATA responses", dataFrames, hasSize(2));

        String resp1 = decodeResponse(converter, chatMethod, dataFrames.get(0));
        String resp2 = decodeResponse(converter, chatMethod, dataFrames.get(1));
        assertThat("first response should contain Alice", resp1, containsString("Alice"));
        assertThat("second response should contain Bob", resp2, containsString("Bob"));

        channel.finishAndReleaseAll();
    }

    // ===== Test: channel close evicts held inbound frames =====

    @Test
    public void shouldEvictHeldFramesOnChannelClose() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-evict";

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS + DATA
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("registry should have 1 frame before close", registry.size(), is(1));

        // Close the channel -> triggers channelInactive -> evicts held frames
        channel.close().sync();
        channel.runPendingTasks();
        Thread.sleep(200);

        // All held frames should be evicted
        assertThat("registry should be empty after channel close", registry.size(), is(0));

        channel.finishAndReleaseAll();
    }

    // ===== Test: cap enforcement skips parking when cap is reached =====

    @Test
    public void shouldSkipParkingWhenCapReached() {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Cap of 1 frame
        Configuration config = Configuration.configuration()
            .breakpointTimeoutMillis(30_000L)
            .breakpointMaxHeld(1);
        String streamId = "grpc-bidi-inbound-test-cap";

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Pre-fill the registry with a frame from a different stream to hit cap
        registry.pauseFrame("other-stream", "dummy".getBytes(StandardCharsets.UTF_8),
            "GET", "/dummy", config, PausedStreamFrame.Direction.OUTBOUND);
        assertThat("pre-filled registry should have 1 frame", registry.size(), is(1));

        // Feed DATA - should be processed immediately (cap reached, can't park)
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        // Registry still has 1 (the pre-filled one), not 2
        assertThat("registry should still have only the pre-filled frame", registry.size(), is(1));

        // Response should have been written immediately
        long dataFrameCount = outbound.stream().filter(o -> o instanceof Http2DataFrame).count();
        assertThat("DATA response should be written immediately when cap reached", dataFrameCount, is(1L));

        channel.finishAndReleaseAll();
    }

    // ===== Test: timeout auto-continues held frame =====

    @Test
    public void shouldAutoContinueOnTimeout() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        // Short timeout
        Configuration config = Configuration.configuration()
            .breakpointTimeoutMillis(200L)
            .breakpointMaxHeld(50);
        String streamId = "grpc-bidi-inbound-test-timeout";

        Function<String, List<String>> echoResponder = json ->
            Collections.singletonList(json.replace("\"name\"", "\"greeting\""));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, echoResponder, null, new org.mockserver.grpc.IncrementalGrpcFrameDecoder(),
            null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("registry should have 1 frame", registry.size(), is(1));

        // Wait for timeout auto-continue
        Thread.sleep(500);
        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // The frame should have been auto-continued and a DATA response written
        // (the timeout scheduler resolves the future, then the event loop callback processes it)
        assertThat("registry should be empty after timeout", registry.size(), is(0));

        channel.finishAndReleaseAll();
    }

    // ===== Test: completion callback still fires with inbound breakpoints =====

    @Test
    public void shouldInvokeCompletionCallbackWithBreakpoints() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-callback";
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withRules(GrpcBidiRule.grpcBidiRule(".*")
                .withResponses(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"echo\"}")));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, bidiConfig, () -> callbackInvoked.set(true),
            config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS + DATA (endStream=true)
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), true);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        // Resolve as continue
        assertThat("registry should have 1 frame", registry.size(), is(1));
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        registry.resolveContinue(paused.getFrameId());
        channel.runPendingTasks();
        Thread.sleep(200);
        channel.runPendingTasks();

        // The stream should finish (endStream=true) and completion callback should fire
        assertThat("completion callback should have been invoked", callbackInvoked.get(), is(true));

        channel.finishAndReleaseAll();
    }

    // ===== Test: Phase 3b (rule-driven) with inbound breakpoints =====

    @Test
    public void shouldWorkWithPhase3bRuleDrivenConfig() throws Exception {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        GrpcJsonMessageConverter converter = store.getConverter();
        Descriptors.MethodDescriptor chatMethod = store.getMethod("com.example.grpc.GreetingService", "Chat");

        Configuration config = inboundOnConfig();
        String streamId = "grpc-bidi-inbound-test-phase3b";

        GrpcBidiResponse bidiConfig = GrpcBidiResponse.grpcBidiResponse()
            .withRules(GrpcBidiRule.grpcBidiRule(".*Alice.*")
                .withResponses(GrpcStreamMessage.grpcStreamMessage("{\"greeting\": \"Hello Alice!\"}")));

        List<Object> outbound = new ArrayList<>();
        FrameCaptureHandler captureHandler = new FrameCaptureHandler(outbound);

        GrpcBidiStreamHandler handler = new GrpcBidiStreamHandler(
            chatMethod, converter, bidiConfig, null, config, streamId
        );

        EmbeddedChannel channel = new EmbeddedChannel(captureHandler, handler);

        // Feed HEADERS
        DefaultHttp2Headers reqHeaders = new DefaultHttp2Headers();
        reqHeaders.method("POST");
        reqHeaders.path("/com.example.grpc.GreetingService/Chat");
        channel.writeOneInbound(new DefaultHttp2HeadersFrame(reqHeaders, false));
        channel.flushInbound();

        // Feed DATA
        byte[] msgFrame = grpcFrame(converter, chatMethod, "{\"name\": \"Alice\"}");
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msgFrame), false);
        channel.writeOneInbound(dataFrame);
        channel.flushInbound();

        assertThat("dataFrame must be released", dataFrame.refCnt(), is(0));
        assertThat("registry should have 1 frame", registry.size(), is(1));

        // Resolve as continue
        PausedStreamFrame paused = registry.entries().values().iterator().next();
        registry.resolveContinue(paused.getFrameId());
        channel.runPendingTasks();
        Thread.sleep(100);
        channel.runPendingTasks();

        // DATA response with the rule's configured response
        Http2DataFrame responseFrame = outbound.stream()
            .filter(o -> o instanceof Http2DataFrame)
            .map(o -> (Http2DataFrame) o)
            .findFirst().orElseThrow();
        String responseJson = decodeResponse(converter, chatMethod, responseFrame);
        assertThat(responseJson, containsString("Hello Alice!"));

        channel.finishAndReleaseAll();
    }

    /**
     * Outbound handler that captures all written Http2StreamFrame objects.
     */
    private static class FrameCaptureHandler extends ChannelOutboundHandlerAdapter {
        private final List<Object> captured;

        FrameCaptureHandler(List<Object> captured) {
            this.captured = captured;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http2StreamFrame) {
                captured.add(msg);
            }
            promise.setSuccess();
        }
    }
}
