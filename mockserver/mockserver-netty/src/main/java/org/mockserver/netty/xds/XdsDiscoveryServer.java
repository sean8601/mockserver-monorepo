package org.mockserver.netty.xds;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.xds.XdsProtoMessages;
import org.mockserver.xds.XdsRouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A standalone Netty HTTP/2 gRPC server that implements the xDS Route Discovery
 * Service (RDS). Binds on the configured {@code xdsPort} and serves Envoy-
 * compatible {@code DiscoveryResponse} messages containing the current
 * MockServer expectations encoded as {@code RouteConfiguration} protobuf.
 * <p>
 * Supported gRPC methods:
 * <ul>
 *   <li>{@code /envoy.service.route.v3.RouteDiscoveryService/StreamRoutes}
 *       (server-streaming; a single full-state response per request)</li>
 *   <li>{@code /envoy.service.route.v3.RouteDiscoveryService/FetchRoutes}
 *       (unary)</li>
 * </ul>
 * <p>
 * <b>Not implemented</b> (honest limitations):
 * <ul>
 *   <li>Delta/incremental xDS (SotW only)</li>
 *   <li>Watch-on-change (server push when expectations change)</li>
 *   <li>LDS, CDS, EDS, ADS</li>
 *   <li>TLS on the xDS port (plaintext H2C only)</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class XdsDiscoveryServer {

    private static final Logger LOG = LoggerFactory.getLogger(XdsDiscoveryServer.class);

    private static final String STREAM_ROUTES_PATH = "/envoy.service.route.v3.RouteDiscoveryService/StreamRoutes";
    private static final String FETCH_ROUTES_PATH = "/envoy.service.route.v3.RouteDiscoveryService/FetchRoutes";

    private final AtomicReference<Channel> channelRef = new AtomicReference<>();
    private volatile NioEventLoopGroup bossGroup;
    private volatile NioEventLoopGroup workerGroup;

    private final HttpState httpState;
    private final XdsRouteBuilder routeBuilder;
    private final AtomicLong versionCounter = new AtomicLong(0);

    public XdsDiscoveryServer(HttpState httpState) {
        this.httpState = httpState;
        this.routeBuilder = new XdsRouteBuilder();
    }

    /**
     * Start the xDS gRPC server on the given port.
     *
     * @param port TCP port to bind; use 0 for an ephemeral port
     * @return the actual bound port
     * @throws Exception if the server cannot start
     */
    public int start(int port) throws Exception {
        NioEventLoopGroup localBossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup localWorkerGroup = new NioEventLoopGroup(2);
        boolean success = false;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(localBossGroup, localWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // H2C (HTTP/2 cleartext) codec
                        Http2Connection connection = new DefaultHttp2Connection(true);
                        HttpToHttp2ConnectionHandlerBuilder h2Builder = new HttpToHttp2ConnectionHandlerBuilder()
                            .frameListener(
                                new DelegatingDecompressorFrameListener(
                                    connection,
                                    new InboundHttp2ToHttpAdapterBuilder(connection)
                                        .maxContentLength(4 * 1024 * 1024)
                                        .propagateSettings(true)
                                        .validateHttpHeaders(false)
                                        .build()
                                )
                            );
                        pipeline.addLast(h2Builder.connection(connection).build());
                        pipeline.addLast(new XdsRdsHandler());
                    }
                });

            Channel ch = bootstrap.bind(new InetSocketAddress(port)).sync().channel();
            int boundPort = ((InetSocketAddress) ch.localAddress()).getPort();
            this.channelRef.set(ch);
            this.bossGroup = localBossGroup;
            this.workerGroup = localWorkerGroup;
            success = true;
            LOG.info("xDS RDS gRPC server started on port: {}", boundPort);
            return boundPort;
        } finally {
            if (!success) {
                localBossGroup.shutdownGracefully();
                localWorkerGroup.shutdownGracefully();
            }
        }
    }

    /**
     * Returns the bound port, or -1 if not started.
     */
    public int getPort() {
        Channel ch = this.channelRef.get();
        if (ch != null && ch.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) ch.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Stop the xDS gRPC server and release resources. This method is idempotent:
     * concurrent or repeated calls are safe. The channel is closed first, then
     * the event loop groups are shut down (correct order to avoid writing to a
     * closed event loop).
     */
    public void stop() {
        // Atomic swap ensures only one thread runs the shutdown sequence
        Channel ch = channelRef.getAndSet(null);
        if (ch != null) {
            try {
                ch.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Shut down event loop groups AFTER the channel is closed
        NioEventLoopGroup bg = this.bossGroup;
        NioEventLoopGroup wg = this.workerGroup;
        this.bossGroup = null;
        this.workerGroup = null;
        if (bg != null) {
            bg.shutdownGracefully();
        }
        if (wg != null) {
            wg.shutdownGracefully();
        }
        LOG.info("xDS RDS gRPC server stopped");
    }

    /**
     * Build a DiscoveryResponse containing the current RouteConfiguration from
     * live expectations.
     */
    byte[] buildDiscoveryResponseBytes() {
        // retrieveActiveExpectations(null) returns a new ArrayList via Collectors.toList(),
        // so this is a snapshot — safe to iterate without concurrent-modification risk.
        List<Expectation> expectations = httpState.getRequestMatchers().retrieveActiveExpectations(null);
        byte[] routeConfigBytes = routeBuilder.buildRouteConfigurationProto(expectations);

        String version = String.valueOf(versionCounter.incrementAndGet());

        XdsProtoMessages.Any anyResource = new XdsProtoMessages.Any(
            XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL,
            routeConfigBytes
        );

        XdsProtoMessages.DiscoveryResponse response = new XdsProtoMessages.DiscoveryResponse();
        response.setVersionInfo(version);
        response.setResources(List.of(anyResource));
        response.setTypeUrl(XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL);
        response.setNonce(version);

        return response.encode();
    }

    /**
     * Netty inbound handler that processes H2C FullHttpRequest objects from
     * Netty's HTTP/2-to-HTTP/1 adapter, routes gRPC RDS requests, and writes
     * gRPC-framed HTTP/2 responses.
     */
    private class XdsRdsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = request.uri();
            if (!STREAM_ROUTES_PATH.equals(path) && !FETCH_ROUTES_PATH.equals(path)) {
                // unimplemented method
                sendGrpcError(ctx, request,
                    GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED,
                    "method not implemented: " + path);
                return;
            }

            try {
                byte[] responseProto = buildDiscoveryResponseBytes();
                byte[] grpcFrame = GrpcFrameCodec.encode(responseProto);

                FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(grpcFrame)
                );
                httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
                httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, grpcFrame.length);
                httpResponse.headers().set(GrpcStatusMapper.GRPC_STATUS_HEADER, "0");

                // Copy the stream ID from the request so the HTTP/2 codec routes
                // the response to the correct stream.
                copyStreamId(request, httpResponse);

                ctx.writeAndFlush(httpResponse);
            } catch (Exception e) {
                LOG.warn("failed to handle RDS request on {}: {}", path, e.getMessage(), e);
                sendGrpcError(ctx, request,
                    GrpcStatusMapper.GrpcStatusCode.INTERNAL,
                    "internal error: " + e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("xDS RDS handler exception: {}", cause.getMessage(), cause);
            ctx.close();
        }

        private void sendGrpcError(ChannelHandlerContext ctx, FullHttpRequest request,
                                   GrpcStatusMapper.GrpcStatusCode code, String message) {
            FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.EMPTY_BUFFER
            );
            httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, GrpcStatusMapper.GRPC_CONTENT_TYPE);
            httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            httpResponse.headers().set(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(code.getCode()));
            httpResponse.headers().set(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message);

            copyStreamId(request, httpResponse);

            ctx.writeAndFlush(httpResponse);
        }

        /**
         * Copy the HTTP/2 stream identifier from request to response so the
         * HTTP/2-to-HTTP codec can multiplex correctly.
         */
        private void copyStreamId(FullHttpRequest request, FullHttpResponse response) {
            String streamId = request.headers().get(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
            if (streamId != null) {
                response.headers().set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
            }
        }
    }
}
