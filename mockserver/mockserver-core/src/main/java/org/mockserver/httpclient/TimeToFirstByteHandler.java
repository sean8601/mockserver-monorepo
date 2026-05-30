package org.mockserver.httpclient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Records the wall-clock time when the first inbound byte (response head) arrives
 * from the upstream server. This handler fires once on the first {@code channelRead}
 * and then removes itself from the pipeline so subsequent reads pay zero overhead.
 * <p>
 * The timestamp is stored in the channel attribute {@link #FIRST_BYTE_MILLIS} so
 * that {@link NettyHttpClient} can compute {@code timeToFirstByteInMillis} when
 * building the {@link org.mockserver.model.Timing} object.
 * <p>
 * Extends {@link ChannelInboundHandlerAdapter} (not {@code SimpleChannelInboundHandler})
 * to avoid auto-release of the proxied message. A {@code firstByte} value of 0 means
 * "not captured".
 */
public class TimeToFirstByteHandler extends ChannelInboundHandlerAdapter {

    static final AttributeKey<AtomicLong> FIRST_BYTE_MILLIS = AttributeKey.valueOf("FIRST_BYTE_MILLIS");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Only stamp TTFB on an actual HTTP response object — not on stray
        // frames such as HTTP/2 Http2Settings that may reach the handler
        // after Http2SettingsHandler removed itself.
        if (msg instanceof HttpObject) {
            AtomicLong firstByteMillis = ctx.channel().attr(FIRST_BYTE_MILLIS).get();
            if (firstByteMillis != null) {
                firstByteMillis.compareAndSet(0, System.currentTimeMillis());
            }
            // Remove self after stamping — subsequent reads bypass this handler
            ctx.pipeline().remove(this);
        }
        // Forward the message to the next handler in the pipeline
        ctx.fireChannelRead(msg);
    }
}
