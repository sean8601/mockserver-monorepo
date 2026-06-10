package org.mockserver.netty.responsewriter;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class NettyResponseWriter extends ResponseWriter {

    private final ChannelHandlerContext ctx;
    private final Scheduler scheduler;
    // Request-received time for the latency histogram; -1 when metrics are
    // disabled so sendResponse() adds nothing to the hot path. A new
    // NettyResponseWriter is created per request, so this is race-free.
    private final long startNanos;

    public NettyResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
        this.scheduler = scheduler;
        this.startNanos = configuration.metricsEnabled() ? System.nanoTime() : -1L;
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (startNanos >= 0) {
            double durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            Metrics.observeRequestDurationSeconds(durationSeconds);
            Metrics.observeRequestDurationByMethodSeconds(durationSeconds, request != null ? request.getMethod("") : null);
        }
        if (response.getStreamingBody() != null) {
            writeStreamingResponse(ctx, request, response);
        } else {
            writeAndCloseSocket(ctx, request, response);
        }
    }

    private void writeStreamingResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();

        // Build a Netty DefaultHttpResponse head (not Full)
        int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 200;
        HttpResponseStatus status;
        if (response.getReasonPhrase() != null && !response.getReasonPhrase().isEmpty()) {
            status = new HttpResponseStatus(statusCode, response.getReasonPhrase());
        } else {
            status = HttpResponseStatus.valueOf(statusCode);
        }
        DefaultHttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);

        // Copy headers from the MockServer response
        if (response.getHeaderMultimap() != null) {
            response.getHeaderMultimap().entries().forEach(entry ->
                nettyResponse.headers().add(entry.getKey().getValue(), entry.getValue().getValue())
            );
        }

        // Ensure chunked transfer encoding
        if (!nettyResponse.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)) {
            HttpUtil.setTransferEncodingChunked(nettyResponse, true);
        }

        // Send the response head
        ctx.writeAndFlush(nettyResponse);

        // Determine if stream-frame breakpoints are active for this response
        final org.mockserver.mock.breakpoint.BreakpointMatcher streamBreakpointMatcher = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM);
        final boolean streamBreakpointsActive = streamBreakpointMatcher != null;
        // Stream identifier and request metadata are only needed when breakpoints
        // are active — keep them out of the default-off hot path (zero allocation).
        final String streamId;
        final String reqMethod;
        final String reqPath;
        // WS-callback dispatch: when the matched breakpoint has a non-null clientId
        // AND the per-server WS registry is available on the channel, dispatch over WS
        final boolean useWsDispatch;
        final String breakpointClientId;
        final org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry wsRegistry;
        if (streamBreakpointsActive) {
            streamId = request.getLogCorrelationId() != null
                ? request.getLogCorrelationId() + "-stream"
                : java.util.UUID.randomUUID() + "-stream";
            reqMethod = request.getMethod() != null ? request.getMethod().getValue() : null;
            reqPath = request.getPath() != null ? request.getPath().getValue() : null;
            breakpointClientId = streamBreakpointMatcher.getClientId();
            wsRegistry = ctx.channel().attr(org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WS_REGISTRY_KEY).get();
            useWsDispatch = breakpointClientId != null && wsRegistry != null;
        } else {
            streamId = null;
            reqMethod = null;
            reqPath = null;
            useWsDispatch = false;
            breakpointClientId = null;
            wsRegistry = null;
        }

        // Subscribe to the streaming body to forward chunks as they arrive.
        // After each chunk write completes, call streamingBody.requestMore() to trigger
        // the next upstream read — this implements backpressure so a slow client does not
        // cause unbounded buffering on the server channel.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                if (!ctx.channel().isActive()) {
                    // Channel is no longer active; still request more so the upstream can
                    // detect the closed channel on the next read and clean up.
                    streamingBody.requestMore();
                    return;
                }

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write the frame immediately (no interception)
                    DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(chunk));
                    ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                    return;
                }

                // --- Stream-frame breakpoint path ---
                // Copy the chunk bytes for the registry (the ByteBuf is owned by the caller)
                byte[] chunkBytes = new byte[chunk.readableBytes()];
                chunk.getBytes(chunk.readerIndex(), chunkBytes);

                // WS-callback dispatch (clientId is always present — required since 7b)
                final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;
                int seq = StreamFrameBreakpointRegistry.getInstance()
                    .nextSequenceNumber(streamId);
                java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        breakpointClientId, streamBreakpointMatcher.getId(), streamId, seq,
                        PausedStreamFrame.Direction.OUTBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                        chunkBytes, reqMethod, reqPath,
                        wsRegistry,
                        configuration, mockServerLogger
                    );
                if (wsFuture == null) {
                    // Cap reached or client not connected — write immediately
                    DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(chunk));
                    ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                    return;
                }
                decisionFuture = wsFuture;

                // Frame is parked (either in registry or dispatched via WS). The original
                // chunk ByteBuf is NOT retained — we copied the bytes above. The chunk will
                // be released by StreamingBody after onChunk returns.
                // We do NOT call streamingBody.requestMore() — this stops the upstream from
                // sending more chunks (backpressure). We will call it after the frame is resolved.

                // When the decision future completes (from control-plane API, WS reply, or timeout),
                // execute the action on the channel's event loop to ensure thread safety.
                decisionFuture.thenAccept(decision -> {
                    // Marshal onto the channel's event loop
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            streamingBody.requestMore();
                            return;
                        }
                        switch (decision.getAction()) {
                            case CONTINUE: {
                                DefaultHttpContent content = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(chunkBytes));
                                ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                                break;
                            }
                            case MODIFY: {
                                DefaultHttpContent content = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(decision.getReplacementBody()));
                                ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                                break;
                            }
                            case DROP: {
                                // Discard the frame — do not write anything to the client
                                streamingBody.requestMore();
                                break;
                            }
                            case INJECT: {
                                // Write the original frame, then inject an additional frame
                                DefaultHttpContent originalContent = new DefaultHttpContent(
                                    Unpooled.wrappedBuffer(chunkBytes));
                                ctx.writeAndFlush(originalContent).addListener(future -> {
                                    if (ctx.channel().isActive()) {
                                        DefaultHttpContent injectedContent = new DefaultHttpContent(
                                            Unpooled.wrappedBuffer(decision.getInjectedBody()));
                                        ctx.writeAndFlush(injectedContent).addListener(f2 -> streamingBody.requestMore());
                                    } else {
                                        streamingBody.requestMore();
                                    }
                                });
                                break;
                            }
                            case CLOSE: {
                                // End the stream: send LastHttpContent and close
                                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
                                    ctx.close();
                                    // Do NOT request more — stream is ended
                                });
                                break;
                            }
                            default: {
                                // Unrecognised action — log a warning and request more to avoid
                                // hanging the stream if a future action type is added without
                                // updating this switch.
                                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                                    mockServerLogger.logEvent(new LogEntry()
                                        .setLogLevel(WARN)
                                        .setMessageFormat("unrecognised stream frame breakpoint action: " + decision.getAction())
                                    );
                                }
                                streamingBody.requestMore();
                                break;
                            }
                        }
                    });
                });
            },
            // onComplete
            () -> {
                if (streamBreakpointsActive) {
                    // Evict any remaining held frames for this stream (prevents leaks)
                    StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                }
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
                        boolean closeChannel;
                        ConnectionOptions connectionOptions = response.getConnectionOptions();
                        if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
                            closeChannel = connectionOptions.getCloseSocket();
                        } else {
                            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
                        }
                        if (closeChannel || configuration.alwaysCloseSocketConnections()) {
                            ctx.close();
                        }
                    });
                }
            },
            // onError
            error -> {
                if (streamBreakpointsActive) {
                    // Evict any remaining held frames for this stream (prevents leaks)
                    StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                }
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> ctx.close());
                }
            }
        );
    }

    private void writeAndCloseSocket(final ChannelHandlerContext ctx, final HttpRequest request, HttpResponse response) {
        boolean closeChannel;

        ConnectionOptions connectionOptions = response.getConnectionOptions();
        if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
            closeChannel = connectionOptions.getCloseSocket();
        } else {
            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
        }

        Delay chunkDelay = connectionOptions != null ? connectionOptions.getChunkDelay() : null;
        Integer chunkSize = connectionOptions != null ? connectionOptions.getChunkSize() : null;
        if (chunkDelay != null && chunkSize != null && chunkSize > 0) {
            writeChunkedResponseWithDelay(ctx, response, connectionOptions, closeChannel, chunkDelay);
        } else {
            ChannelFuture channelFuture = ctx.writeAndFlush(response);
            addCloseSocketListener(channelFuture, connectionOptions, closeChannel);
        }
    }

    private void writeChunkedResponseWithDelay(
        final ChannelHandlerContext ctx,
        HttpResponse response,
        ConnectionOptions connectionOptions,
        boolean closeChannel,
        Delay chunkDelay
    ) {
        List<DefaultHttpObject> httpObjects = new org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse(mockServerLogger)
            .mapMockServerResponseToNettyResponse(response);
        if (httpObjects.size() <= 1) {
            ChannelFuture channelFuture = ctx.writeAndFlush(response);
            addCloseSocketListener(channelFuture, connectionOptions, closeChannel);
            return;
        }
        ChannelFuture headerFuture = ctx.writeAndFlush(httpObjects.get(0));
        headerFuture.addListener(f -> {
            if (!f.isSuccess()) {
                for (int i = 1; i < httpObjects.size(); i++) {
                    ReferenceCountUtil.release(httpObjects.get(i));
                }
                addCloseSocketListener(headerFuture, connectionOptions, closeChannel);
                return;
            }
            long cumulativeDelayMs = 0;
            for (int i = 1; i < httpObjects.size(); i++) {
                final DefaultHttpObject chunk = httpObjects.get(i);
                final boolean isLast = (i == httpObjects.size() - 1);
                cumulativeDelayMs += chunkDelay.sampleValueMillis();
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive()) {
                        ChannelFuture chunkFuture = ctx.writeAndFlush(chunk);
                        if (isLast) {
                            addCloseSocketListener(chunkFuture, connectionOptions, closeChannel);
                        }
                    } else {
                        ReferenceCountUtil.release(chunk);
                    }
                }, cumulativeDelayMs, TimeUnit.MILLISECONDS);
            }
        });
    }

    private void addCloseSocketListener(ChannelFuture channelFuture, ConnectionOptions connectionOptions, boolean closeChannel) {
        if (closeChannel || configuration.alwaysCloseSocketConnections()) {
            channelFuture.addListener((ChannelFutureListener) future -> {
                Delay closeSocketDelay = connectionOptions != null ? connectionOptions.getCloseSocketDelay() : null;
                if (closeSocketDelay == null) {
                    disconnectAndCloseChannel(future);
                } else {
                    scheduler.schedule(() -> disconnectAndCloseChannel(future), false, closeSocketDelay);
                }
            });
        }
    }

    private void disconnectAndCloseChannel(ChannelFuture future) {
        future
            .channel()
            .disconnect()
            .addListener(disconnectFuture -> {
                    if (disconnectFuture.isSuccess()) {
                        future
                            .channel()
                            .close()
                            .addListener(closeFuture -> {
                                if (disconnectFuture.isSuccess()) {
                                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                                        mockServerLogger
                                            .logEvent(new LogEntry()
                                                .setLogLevel(TRACE)
                                                .setMessageFormat("disconnected and closed socket " + future.channel().localAddress())
                                            );
                                    }
                                } else {
                                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                                        mockServerLogger
                                            .logEvent(new LogEntry()
                                                .setLogLevel(WARN)
                                                .setMessageFormat("exception closing socket " + future.channel().localAddress())
                                                .setThrowable(disconnectFuture.cause())
                                            );
                                    }
                                }
                            });
                    } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                        mockServerLogger
                            .logEvent(new LogEntry()
                                .setLogLevel(WARN)
                                .setMessageFormat("exception disconnecting socket " + future.channel().localAddress())
                                .setThrowable(disconnectFuture.cause()));
                    }
                }
            );
    }

}
