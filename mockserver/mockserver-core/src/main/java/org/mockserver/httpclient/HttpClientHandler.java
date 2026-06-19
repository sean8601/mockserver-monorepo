package org.mockserver.httpclient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;

import javax.net.ssl.SSLException;
import java.util.Arrays;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.httpclient.NettyHttpClient.CONNECTION_POOL;
import static org.mockserver.httpclient.NettyHttpClient.POOL_KEY;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

@ChannelHandler.Sharable
public class HttpClientHandler extends SimpleChannelInboundHandler<Message> {

    private final List<String> connectionClosedStrings = Arrays.asList(
        "Broken pipe",
        "(broken pipe)",
        "Connection reset"
    );

    HttpClientHandler() {
        super(false);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Message response) {
        Channel channel = ctx.channel();
        java.util.concurrent.CompletableFuture<Message> responseFuture = channel.attr(RESPONSE_FUTURE).get();
        // Return the channel to the pool (or decide to close it) BEFORE completing the future so
        // that, by the time the caller is unblocked, an idle keep-alive connection is already
        // available for the next request to the same upstream. When the channel is not poolable
        // this is byte-identical to the historical "complete then close" behaviour.
        boolean returnedToPool = tryReturnToPool(channel, response);
        if (responseFuture != null) {
            responseFuture.complete(response);
        }
        if (!returnedToPool) {
            ctx.close();
        }
    }

    /**
     * If this channel is poolable (marked with {@link NettyHttpClient#CONNECTION_POOL}) and the
     * response permits HTTP keep-alive reuse, clear the per-request future and offer the channel
     * back to the pool. Returns {@code true} when the channel was returned to the pool (and must
     * NOT be closed), {@code false} when it should be closed (current/default behaviour).
     * <p>
     * A channel is never reused when: pooling is off, the upstream signalled {@code Connection:
     * close}, the channel is no longer active, the message is not a complete {@link HttpResponse},
     * or the pool is saturated for the key.
     */
    private boolean tryReturnToPool(Channel channel, Message response) {
        HttpForwardConnectionPool pool = channel.attr(CONNECTION_POOL).get();
        String key = channel.attr(POOL_KEY).get();
        if (pool == null || key == null || !channel.isActive() || !(response instanceof HttpResponse)) {
            return false;
        }
        if (!permitsKeepAlive((HttpResponse) response)) {
            return false;
        }
        // Detach the completed future so a stale reference cannot be completed again, and so the
        // connection-error handler does not error on a clean idle close.
        channel.attr(RESPONSE_FUTURE).set(null);
        return pool.release(key, channel);
    }

    /**
     * The response permits connection reuse unless it carries a {@code Connection: close} header.
     * MockServer aggregates and forwards HTTP/1.1 responses, whose default is keep-alive, so only
     * an explicit close token disqualifies reuse here.
     */
    private boolean permitsKeepAlive(HttpResponse response) {
        String connectionHeader = response.getFirstHeader("connection");
        return connectionHeader == null || !connectionHeader.toLowerCase().contains("close");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isNotSslException(cause) && isNotConnectionReset(cause)) {
            cause.printStackTrace();
        }
        java.util.concurrent.CompletableFuture<Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
        if (responseFuture != null) {
            responseFuture.completeExceptionally(cause);
        }
        ctx.close();
    }

    private boolean isNotSslException(Throwable cause) {
        return !(cause.getCause() instanceof SSLException || cause instanceof DecoderException | cause instanceof NotSslRecordException);
    }

    private boolean isNotConnectionReset(Throwable cause) {
        return connectionClosedStrings.stream().noneMatch(connectionClosedString ->
            (isNotBlank(cause.getMessage()) && cause.getMessage().contains(connectionClosedString))
                || (cause.getCause() != null && isNotBlank(cause.getCause().getMessage()) && cause.getCause().getMessage().contains(connectionClosedString)));
    }
}
