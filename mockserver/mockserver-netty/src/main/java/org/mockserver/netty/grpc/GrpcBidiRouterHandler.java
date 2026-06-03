package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.Action;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import java.security.cert.Certificate;

/**
 * Per-stream router that inspects the first {@link Http2HeadersFrame} to determine whether
 * this stream is a true bidirectional-streaming gRPC method. NOT {@code @Sharable} --
 * per-stream stateful (consumes the first HEADERS frame, then replaces itself).
 * <p>
 * <strong>Routing logic (Phase 3b):</strong>
 * <ul>
 *   <li>If the method is both {@code isClientStreaming()} and {@code isServerStreaming()}
 *       (true bidi) AND a matched expectation has a {@link GrpcBidiResponse} action:
 *       replaces itself with {@link GrpcBidiStreamHandler} configured from the matched
 *       expectation, sets autoRead off, and re-fires the HEADERS frame to the new handler.</li>
 *   <li>Otherwise (unary, server-streaming, client-streaming, non-gRPC, or bidi without
 *       a matching GrpcBidiResponse expectation): installs the existing Phase 0 re-aggregating
 *       chain via {@link GrpcMultiplexChildInitializer#installReAggregatingChain} and re-fires
 *       the HEADERS frame. Non-bidi streams are byte-for-byte unchanged.</li>
 * </ul>
 */
public class GrpcBidiRouterHandler extends ChannelInboundHandlerAdapter {

    private final Configuration configuration;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final GrpcJsonMessageConverter converter;
    private final MockServerLogger mockServerLogger;
    private final boolean sslEnabled;
    private final Certificate[] clientCertificates;

    // Sharable handler references for the re-aggregating chain
    private final CallbackWebSocketServerHandler callbackWebSocketServerHandler;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;
    private final McpStreamableHttpHandler mcpStreamableHttpHandler;
    private final TraceContextHandler traceContextHandler;
    private final GrpcToHttpResponseHandler grpcToHttpResponseHandler;
    private final GrpcToHttpRequestHandler grpcToHttpRequestHandler;
    private final HttpRequestHandler httpRequestHandler;

    // HttpState for expectation matching (nullable for testing)
    private final HttpState httpState;

    public GrpcBidiRouterHandler(
        Configuration configuration,
        GrpcProtoDescriptorStore descriptorStore,
        MockServerLogger mockServerLogger,
        boolean sslEnabled,
        Certificate[] clientCertificates,
        CallbackWebSocketServerHandler callbackWebSocketServerHandler,
        DashboardWebSocketHandler dashboardWebSocketHandler,
        McpStreamableHttpHandler mcpStreamableHttpHandler,
        TraceContextHandler traceContextHandler,
        GrpcToHttpResponseHandler grpcToHttpResponseHandler,
        GrpcToHttpRequestHandler grpcToHttpRequestHandler,
        HttpRequestHandler httpRequestHandler
    ) {
        this(configuration, descriptorStore, mockServerLogger, sslEnabled, clientCertificates,
            callbackWebSocketServerHandler, dashboardWebSocketHandler, mcpStreamableHttpHandler,
            traceContextHandler, grpcToHttpResponseHandler, grpcToHttpRequestHandler,
            httpRequestHandler, null);
    }

    public GrpcBidiRouterHandler(
        Configuration configuration,
        GrpcProtoDescriptorStore descriptorStore,
        MockServerLogger mockServerLogger,
        boolean sslEnabled,
        Certificate[] clientCertificates,
        CallbackWebSocketServerHandler callbackWebSocketServerHandler,
        DashboardWebSocketHandler dashboardWebSocketHandler,
        McpStreamableHttpHandler mcpStreamableHttpHandler,
        TraceContextHandler traceContextHandler,
        GrpcToHttpResponseHandler grpcToHttpResponseHandler,
        GrpcToHttpRequestHandler grpcToHttpRequestHandler,
        HttpRequestHandler httpRequestHandler,
        HttpState httpState
    ) {
        this.configuration = configuration;
        this.descriptorStore = descriptorStore;
        this.converter = descriptorStore != null ? descriptorStore.getConverter() : null;
        this.mockServerLogger = mockServerLogger;
        this.sslEnabled = sslEnabled;
        this.clientCertificates = clientCertificates;
        this.callbackWebSocketServerHandler = callbackWebSocketServerHandler;
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
        this.mcpStreamableHttpHandler = mcpStreamableHttpHandler;
        this.traceContextHandler = traceContextHandler;
        this.grpcToHttpResponseHandler = grpcToHttpResponseHandler;
        this.grpcToHttpRequestHandler = grpcToHttpRequestHandler;
        this.httpRequestHandler = httpRequestHandler;
        this.httpState = httpState;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Http2HeadersFrame)) {
            // Not a HEADERS frame -- should not happen on first read; pass through
            ctx.fireChannelRead(msg);
            return;
        }

        Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
        CharSequence pathSeq = headersFrame.headers().path();
        String path = pathSeq != null ? pathSeq.toString() : "";

        // Resolve the gRPC method descriptor from the :path
        Descriptors.MethodDescriptor methodDescriptor = resolveMethod(path);

        if (methodDescriptor != null && methodDescriptor.isClientStreaming() && methodDescriptor.isServerStreaming()) {
            // True bidi method -- check for a matching GrpcBidiResponse expectation
            GrpcBidiResponse bidiResponse = findMatchingBidiResponse(path);

            if (bidiResponse != null) {
                // Install rule-driven GrpcBidiStreamHandler
                ChannelPipeline pipeline = ctx.pipeline();

                GrpcBidiStreamHandler bidiHandler = new GrpcBidiStreamHandler(
                    methodDescriptor, converter, bidiResponse
                );

                pipeline.replace(this, "grpcBidiStream", bidiHandler);

                // Re-fire the HEADERS frame to the newly installed handler
                ctx.fireChannelRead(msg);
            } else {
                // Bidi method but no matching GrpcBidiResponse expectation --
                // fall back to re-aggregating chain (same as non-bidi)
                installReAggregatingChainAndRefire(ctx, msg);
            }
        } else {
            // Non-bidi: install the existing re-aggregating chain
            installReAggregatingChainAndRefire(ctx, msg);
        }
    }

    private void installReAggregatingChainAndRefire(ChannelHandlerContext ctx, Object msg) {
        ChannelPipeline pipeline = ctx.pipeline();

        GrpcMultiplexChildInitializer.installReAggregatingChain(
            pipeline,
            configuration,
            mockServerLogger,
            sslEnabled,
            clientCertificates,
            ctx.channel(),
            callbackWebSocketServerHandler,
            dashboardWebSocketHandler,
            mcpStreamableHttpHandler,
            traceContextHandler,
            grpcToHttpResponseHandler,
            grpcToHttpRequestHandler,
            httpRequestHandler
        );

        // Remove this router (it's been replaced by the re-aggregating chain)
        pipeline.remove(this);

        // Re-fire the HEADERS frame to the newly installed chain
        ctx.fireChannelRead(msg);
    }

    /**
     * Synthesise a MockServer HttpRequest (POST /service/method with gRPC headers, empty body)
     * and probe for a matching expectation whose action is a {@link GrpcBidiResponse}.
     * <p>
     * <strong>Critical:</strong> uses the side-effect-free
     * {@link HttpState#peekFirstMatchingExpectation(org.mockserver.model.RequestDefinition)}
     * so that a NON-bidi expectation on the same gRPC path (the fallback case) is NOT consumed
     * by this routing probe — that would otherwise exhaust a {@code times(1)} expectation before
     * the re-aggregating chain could act on it, or double-count it. The fallback path leaves the
     * match-consuming entirely to {@code HttpActionHandler}.
     * <p>
     * <strong>Known limitation (gated/experimental):</strong> because the probe is read-only and
     * the bidi handler drives the stream directly (it never flows through HttpActionHandler), a
     * matched {@link GrpcBidiResponse} expectation is currently NOT {@code Times}-consumed and the
     * bidi request is NOT recorded in the request log / verification counters / scenario state.
     * Correct Times + logging + {@code responseInProgress} lifecycle for long-lived bidi streams is
     * deferred to a follow-up (see docs/plans/g7-grpc-bidi-streaming.local.md).
     */
    private GrpcBidiResponse findMatchingBidiResponse(String path) {
        if (httpState == null) {
            return null;
        }

        // Synthesise the matching request
        HttpRequest request = HttpRequest.request()
            .withMethod("POST")
            .withPath(path)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);

        // Parse service and method from path for x-grpc-* headers
        String[] parts = GrpcToHttpRequestHandler.parseGrpcPath(path);
        if (parts[0] != null && !parts[0].isEmpty()) {
            request.withHeader("x-grpc-service", parts[0]);
        }
        if (parts[1] != null && !parts[1].isEmpty()) {
            request.withHeader("x-grpc-method", parts[1]);
        }

        // Side-effect-free peek — does NOT consume Times, transition scenarios, or set
        // responseInProgress. This is essential for the FALLBACK case: a non-bidi expectation on
        // this path must be left untouched so HttpActionHandler matches+consumes it exactly once.
        // KNOWN LIMITATION: on the bidi path the matched GrpcBidiResponse expectation is therefore
        // not Times-consumed and the bidi request is not logged/verified — deferred follow-up.
        Expectation matched = httpState.peekFirstMatchingExpectation(request);
        if (matched != null) {
            Action<?> action = matched.getAction();
            if (action instanceof GrpcBidiResponse) {
                return (GrpcBidiResponse) action;
            }
        }
        return null;
    }

    private Descriptors.MethodDescriptor resolveMethod(String path) {
        if (descriptorStore == null || !descriptorStore.hasServices()) {
            return null;
        }
        String[] parts = GrpcToHttpRequestHandler.parseGrpcPath(path);
        String serviceName = parts[0];
        String methodName = parts[1];
        if (serviceName.isEmpty() || methodName.isEmpty()) {
            return null;
        }
        return descriptorStore.getMethod(serviceName, methodName);
    }
}
