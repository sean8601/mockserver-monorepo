package org.mockserver.netty.grpc;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;

import java.security.cert.Certificate;

/**
 * Per-stream child initializer used with {@link io.netty.handler.codec.http2.Http2MultiplexHandler}
 * when the gRPC bidi-streaming multiplex pipeline is enabled.
 * <p>
 * <strong>Phase 0:</strong> installs {@link Http2StreamFrameToHttpObjectCodec} followed by
 * {@link HttpObjectAggregator} so that individual HTTP/2 stream frames are re-aggregated into
 * {@code FullHttpRequest}/{@code FullHttpResponse} objects. The downstream handler chain
 * (MockServerHttpServerCodec, gRPC handlers, HttpRequestHandler, etc.) is identical to the
 * existing connection-level {@code InboundHttp2ToHttpAdapter} path, so behaviour is
 * byte-for-byte equivalent for unary and server-streaming RPCs.
 * <p>
 * In a future phase this initializer will be extended to support true client-streaming and
 * bidirectional-streaming gRPC by handling individual DATA frames without aggregation.
 */
public class GrpcMultiplexChildInitializer extends ChannelInitializer<Http2StreamChannel> {

    private final Configuration configuration;
    private final LifeCycle server;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final MockServerLogger mockServerLogger;
    private final McpSessionManager mcpSessionManager;
    private final boolean sslEnabled;
    private final Certificate[] clientCertificates;

    // Sharable handler instances — reused across child channels (same as the existing h2 branch)
    private final CallbackWebSocketServerHandler callbackWebSocketServerHandler;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;
    private final TraceContextHandler traceContextHandler;
    private final GrpcToHttpResponseHandler grpcToHttpResponseHandler;
    private final GrpcToHttpRequestHandler grpcToHttpRequestHandler;
    private final HttpRequestHandler httpRequestHandler;
    private final McpStreamableHttpHandler mcpStreamableHttpHandler;

    public GrpcMultiplexChildInitializer(
        Configuration configuration,
        LifeCycle server,
        HttpState httpState,
        HttpActionHandler actionHandler,
        MockServerLogger mockServerLogger,
        McpSessionManager mcpSessionManager,
        boolean sslEnabled,
        Certificate[] clientCertificates
    ) {
        this.configuration = configuration;
        this.server = server;
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.mockServerLogger = mockServerLogger;
        this.mcpSessionManager = mcpSessionManager;
        this.sslEnabled = sslEnabled;
        this.clientCertificates = clientCertificates;

        // Pre-build sharable handlers — mirrors the instances created in switchToHttp2/switchToH2c
        this.callbackWebSocketServerHandler = new CallbackWebSocketServerHandler(httpState);
        this.dashboardWebSocketHandler = new DashboardWebSocketHandler(httpState, sslEnabled, false);
        this.traceContextHandler = new TraceContextHandler(configuration);
        this.httpRequestHandler = new HttpRequestHandler(configuration, server, httpState, actionHandler);

        GrpcProtoDescriptorStore descriptorStore = httpState.getGrpcDescriptorStore();
        if (descriptorStore != null && descriptorStore.hasServices()) {
            this.grpcToHttpResponseHandler = new GrpcToHttpResponseHandler(mockServerLogger, descriptorStore);
            this.grpcToHttpRequestHandler = new GrpcToHttpRequestHandler(mockServerLogger, descriptorStore);
        } else {
            this.grpcToHttpResponseHandler = null;
            this.grpcToHttpRequestHandler = null;
        }

        if (configuration.mcpEnabled()) {
            this.mcpStreamableHttpHandler = new McpStreamableHttpHandler(httpState, server, mcpSessionManager);
        } else {
            this.mcpStreamableHttpHandler = null;
        }
    }

    @Override
    protected void initChannel(Http2StreamChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP/2 child channels do NOT inherit parent-channel attributes, so propagate the
        // LOCAL_HOST_HEADERS attribute that HttpRequestHandler/HttpActionHandler use to tell
        // local (mock-server-addressed) requests apart from requests that should be proxied.
        // Without this the child would see an empty local-host set and could mis-proxy.
        ch.attr(HttpRequestHandler.LOCAL_HOST_HEADERS).set(ch.parent().attr(HttpRequestHandler.LOCAL_HOST_HEADERS).get());

        // Phase 0: re-aggregate stream frames into FullHttpRequest/FullHttpResponse
        // so the downstream chain sees the same objects as InboundHttp2ToHttpAdapter produces
        pipeline.addLast(new Http2StreamFrameToHttpObjectCodec(true));
        pipeline.addLast(new HttpObjectAggregator(configuration.maxRequestBodySize()));

        // Downstream chain — identical to the existing switchToHttp2/switchToH2c post-adapter chain
        pipeline.addLast(callbackWebSocketServerHandler);
        pipeline.addLast(dashboardWebSocketHandler);
        if (mcpStreamableHttpHandler != null) {
            pipeline.addLast(mcpStreamableHttpHandler);
        }
        // MockServerHttpServerCodec is NOT @Sharable — create a fresh instance per child channel
        pipeline.addLast(new MockServerHttpServerCodec(
            configuration, mockServerLogger, sslEnabled, clientCertificates, ch.parent().localAddress()
        ));
        pipeline.addLast(traceContextHandler);
        if (grpcToHttpResponseHandler != null) {
            pipeline.addLast(grpcToHttpResponseHandler);
            pipeline.addLast(grpcToHttpRequestHandler);
        }
        pipeline.addLast(httpRequestHandler);
    }
}
