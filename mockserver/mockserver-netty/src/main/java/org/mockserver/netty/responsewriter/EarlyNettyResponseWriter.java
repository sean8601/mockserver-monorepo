package org.mockserver.netty.responsewriter;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpObject;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.util.List;

import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * Response writer for the early-dispatch path. Writes the response upstream of
 * MockServerHttpServerCodec by targeting the HttpServerCodec pipeline context, so a
 * MockServer HttpResponse can be sent before the request body has been aggregated.
 */
public class EarlyNettyResponseWriter extends ResponseWriter {

    private final ChannelHandlerContext ctx;
    private final Scheduler scheduler;
    private final MockServerHttpResponseToFullHttpResponse mockServerHttpResponseToFullHttpResponse;

    public EarlyNettyResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx, Scheduler scheduler) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
        this.scheduler = scheduler;
        this.mockServerHttpResponseToFullHttpResponse = new MockServerHttpResponseToFullHttpResponse(mockServerLogger);
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        // Always close after early-dispatch responses: the inbound request body has not been
        // consumed, so the connection is in an indeterminate state and cannot safely be reused.
        // Any user-supplied ConnectionOptions.closeSocket value is overridden.
        ConnectionOptions connectionOptions = response.getConnectionOptions();

        // Write from EarlyMatchingHandler's context so the outbound flow reaches HttpServerCodec
        // (which encodes the FullHttpResponse to bytes). Writing from HttpServerCodec's own context
        // would skip the codec itself, since outbound writes flow toward HEAD.
        List<DefaultHttpObject> httpObjects = mockServerHttpResponseToFullHttpResponse.mapMockServerResponseToNettyResponse(response);
        ChannelFuture lastFuture = null;
        for (DefaultHttpObject obj : httpObjects) {
            lastFuture = ctx.writeAndFlush(obj);
        }
        if (lastFuture != null) {
            addCloseSocketListener(lastFuture, connectionOptions);
        }
    }

    private void addCloseSocketListener(ChannelFuture channelFuture, ConnectionOptions connectionOptions) {
        channelFuture.addListener((ChannelFutureListener) future -> {
            Delay closeSocketDelay = connectionOptions != null ? connectionOptions.getCloseSocketDelay() : null;
            if (closeSocketDelay == null) {
                disconnectAndCloseChannel(future);
            } else {
                scheduler.schedule(() -> disconnectAndCloseChannel(future), false, closeSocketDelay);
            }
        });
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
                            if (closeFuture.isSuccess()) {
                                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                                    mockServerLogger
                                        .logEvent(new LogEntry()
                                            .setLogLevel(TRACE)
                                            .setMessageFormat("disconnected and closed socket " + future.channel().localAddress() + " after early response")
                                        );
                                }
                            } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                                mockServerLogger
                                    .logEvent(new LogEntry()
                                        .setLogLevel(WARN)
                                        .setMessageFormat("exception closing socket " + future.channel().localAddress() + " after early response")
                                        .setThrowable(closeFuture.cause())
                                    );
                            }
                        });
                } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger
                        .logEvent(new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("exception disconnecting socket " + future.channel().localAddress())
                            .setThrowable(disconnectFuture.cause())
                        );
                }
            });
    }
}
