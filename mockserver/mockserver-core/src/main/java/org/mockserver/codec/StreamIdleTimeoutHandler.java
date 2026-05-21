package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

/**
 * Closes the channel when an {@link IdleStateEvent} fires, which indicates that no
 * streaming chunk has been received within the configured idle timeout period.
 */
public class StreamIdleTimeoutHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;

    public StreamIdleTimeoutHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("streaming response idle timeout - closing channel " + ctx.channel())
                );
            }
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
