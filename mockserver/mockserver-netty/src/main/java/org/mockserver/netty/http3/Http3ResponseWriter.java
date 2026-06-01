package org.mockserver.netty.http3;

import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

/**
 * A {@link ResponseWriter} that serialises the MockServer {@link HttpResponse}
 * as HTTP/3 frames and writes them to a QUIC stream channel.
 * <p>
 * This allows the standard request-processing pipeline ({@code HttpState},
 * {@code HttpActionHandler}) to write responses identically regardless of
 * whether the request arrived via HTTP/1.1, HTTP/2, or HTTP/3.
 */
public class Http3ResponseWriter extends ResponseWriter {

    private final ChannelHandlerContext ctx;

    public Http3ResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        if (response == null) {
            response = HttpResponse.notFoundResponse();
        }

        if (response.getStreamingBody() != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("streaming/SSE response body is not supported over HTTP/3 - " +
                        "falling back to non-streaming response for request:{}")
                    .setArguments(request)
            );
            // fall through to send the non-streaming part of the response (headers + any static body bytes)
        }

        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);
        DefaultHttp3DataFrame dataFrame = Http3RequestBridge.toHttp3DataFrame(response);

        ctx.write(headersFrame);
        if (dataFrame != null) {
            ctx.writeAndFlush(dataFrame)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            ctx.flush();
            // shut down the output side of the stream even when there is no body
            if (ctx.channel() instanceof QuicStreamChannel) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            }
        }
    }
}
