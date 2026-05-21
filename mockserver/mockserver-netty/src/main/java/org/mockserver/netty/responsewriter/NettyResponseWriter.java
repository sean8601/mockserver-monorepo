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
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

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

    public NettyResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
        this.scheduler = scheduler;
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
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

        // Subscribe to the streaming body to forward chunks as they arrive.
        // After each chunk write completes, call streamingBody.requestMore() to trigger
        // the next upstream read — this implements backpressure so a slow client does not
        // cause unbounded buffering on the server channel.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                if (ctx.channel().isActive()) {
                    DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(chunk));
                    ctx.writeAndFlush(content).addListener(future -> streamingBody.requestMore());
                } else {
                    // Channel is no longer active; still request more so the upstream can
                    // detect the closed channel on the next read and clean up.
                    streamingBody.requestMore();
                }
            },
            // onComplete
            () -> {
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
