package org.mockserver.netty.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;

import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;

/**
 * A Netty channel handler that resolves the original destination for transparently
 * intercepted connections and stores it as the {@link org.mockserver.mock.action.http.HttpActionHandler#REMOTE_SOCKET}
 * channel attribute. The downstream proxy/forward logic in {@code HttpActionHandler}
 * then uses this address instead of requiring the Host header to determine the target.
 * <p>
 * Resolution strategy (in order):
 * <ol>
 *   <li>If the OS supports it (Linux), attempt to read the original destination
 *       via the conntrack table ({@link SoOriginalDstHelper#getOriginalDestination}).</li>
 *   <li>If that fails or is unsupported, fall back to Host-header resolution
 *       (deferred to later pipeline handlers -- no REMOTE_SOCKET is set, so
 *       the existing Host-based forwarding logic applies).</li>
 * </ol>
 * <p>
 * This handler is only added to the pipeline when
 * {@link Configuration#transparentProxyEnabled()} is {@code true}.
 * It fires on {@code channelActive} (connection accepted) and sets the attribute
 * before any HTTP data is processed.
 */
public class TransparentProxyHandler extends ChannelInboundHandlerAdapter {

    /**
     * Channel attribute indicating the transparent proxy original destination
     * was resolved via SO_ORIGINAL_DST / conntrack (as opposed to Host header).
     */
    public static final AttributeKey<Boolean> TRANSPARENT_ORIGINAL_DST_RESOLVED =
        AttributeKey.valueOf("TRANSPARENT_ORIGINAL_DST_RESOLVED");

    private final Configuration configuration;
    private final MockServerLogger logger;
    private final OriginalDestinationResolver resolver;

    /**
     * Strategy interface for resolving the original destination. Allows
     * the helper to be replaced in tests without touching real sockets.
     */
    @FunctionalInterface
    public interface OriginalDestinationResolver {
        /**
         * @param channel the accepted Netty channel
         * @return the original destination, or null if unavailable
         * @throws UnsupportedOperationException on unsupported platforms
         */
        InetSocketAddress resolve(io.netty.channel.Channel channel);
    }

    /**
     * Default resolver that delegates to {@link SoOriginalDstHelper}.
     */
    private static final OriginalDestinationResolver DEFAULT_RESOLVER =
        SoOriginalDstHelper::getOriginalDestination;

    public TransparentProxyHandler(Configuration configuration, MockServerLogger logger) {
        this(configuration, logger, DEFAULT_RESOLVER);
    }

    /**
     * Constructor with injectable resolver for unit testing.
     */
    public TransparentProxyHandler(Configuration configuration, MockServerLogger logger,
                                   OriginalDestinationResolver resolver) {
        this.configuration = configuration;
        this.logger = logger;
        this.resolver = resolver;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (Boolean.TRUE.equals(configuration.transparentProxyEnabled())) {
            resolveAndSetOriginalDestination(ctx);
        }
        super.channelActive(ctx);
    }

    private void resolveAndSetOriginalDestination(ChannelHandlerContext ctx) {
        InetSocketAddress originalDst = null;
        boolean resolvedViaConntrack = false;

        try {
            originalDst = resolver.resolve(ctx.channel());
            if (originalDst != null) {
                resolvedViaConntrack = true;
            }
        } catch (UnsupportedOperationException e) {
            // Expected on non-Linux; fall through to Host-header fallback
            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: SO_ORIGINAL_DST not available ({}), will use Host header fallback")
                        .setArguments(e.getMessage())
                );
            }
        } catch (Exception e) {
            // Unexpected error reading conntrack; log and fall back
            if (logger != null && logger.isEnabledForInstance(Level.WARN)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("transparent proxy: failed to read original destination for channel {}: {}")
                        .setArguments(ctx.channel(), e.getMessage())
                );
            }
        }

        if (originalDst != null) {
            // Set the REMOTE_SOCKET so HttpActionHandler forwards to the original destination
            ctx.channel().attr(REMOTE_SOCKET).set(originalDst);
            ctx.channel().attr(PROXYING).set(Boolean.TRUE);
            ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).set(Boolean.TRUE);

            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: resolved original destination {} for channel {} via conntrack")
                        .setArguments(originalDst, ctx.channel())
                );
            }
        } else {
            // Mark the channel as proxying (transparent mode) but without a fixed
            // REMOTE_SOCKET. The Host-header-based resolution in HttpActionHandler
            // will determine the target when the first HTTP request arrives.
            ctx.channel().attr(PROXYING).set(Boolean.TRUE);
            ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).set(Boolean.FALSE);

            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: using Host header fallback for channel {}")
                        .setArguments(ctx.channel())
                );
            }
        }
    }
}
