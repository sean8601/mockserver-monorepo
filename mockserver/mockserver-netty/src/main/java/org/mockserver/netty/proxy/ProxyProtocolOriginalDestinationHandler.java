package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.netty.proxy.TransparentProxyHandler.TRANSPARENT_ORIGINAL_DST_RESOLVED;

/**
 * Inbound handler that detects and parses PROXY protocol v1 (text format) headers
 * prepended to TCP connections by upstream load balancers (e.g., AWS GWLB, HAProxy,
 * nginx with {@code proxy_protocol on}).
 * <p>
 * <b>Placement:</b> This handler must be added FIRST in the transparent-proxy pipeline
 * (before {@link TransparentProxyHandler} and the port-unification handler). It inspects
 * the first inbound bytes:
 * <ul>
 *   <li>If they start with the PROXY v1 ASCII signature {@code "PROXY "}, the handler
 *       parses the header line, sets {@code REMOTE_SOCKET} / {@code PROXYING} /
 *       {@code TRANSPARENT_ORIGINAL_DST_RESOLVED} channel attributes, consumes the
 *       header bytes, and removes itself from the pipeline.</li>
 *   <li>If the bytes are NOT a PROXY header, the handler removes itself and passes
 *       the bytes through unchanged (no-op for non-PROXY traffic).</li>
 * </ul>
 * <p>
 * <b>PROXY v1 format (RFC draft / HAProxy spec):</b>
 * <pre>
 * PROXY TCP4 &lt;srcIP&gt; &lt;dstIP&gt; &lt;srcPort&gt; &lt;dstPort&gt;\r\n
 * PROXY TCP6 &lt;srcIP&gt; &lt;dstIP&gt; &lt;srcPort&gt; &lt;dstPort&gt;\r\n
 * PROXY UNKNOWN\r\n
 * </pre>
 * Maximum header length is 107 bytes (per the spec). The handler enforces this bound.
 * <p>
 * <b>Fail-safe:</b> Malformed, oversized, or unrecognised headers cause the handler to
 * remove itself and pass through all bytes unchanged, logging a warning. The connection
 * continues as if no PROXY header was present.
 *
 * @see TransparentProxyHandler
 * @see CompositeOriginalDestinationResolver
 */
public class ProxyProtocolOriginalDestinationHandler extends ChannelInboundHandlerAdapter {

    /** PROXY protocol v1 ASCII signature. */
    private static final String PROXY_V1_SIGNATURE = "PROXY ";
    private static final byte[] PROXY_V1_SIGNATURE_BYTES = PROXY_V1_SIGNATURE.getBytes(StandardCharsets.US_ASCII);

    /**
     * Maximum length of a PROXY v1 header line (including CRLF).
     * Per the HAProxy PROXY protocol spec, the maximum line is 107 bytes.
     * We use 108 to be slightly generous with the bound check.
     */
    static final int MAX_PROXY_V1_LINE_LENGTH = 108;

    private final MockServerLogger logger;

    /** Accumulates bytes until we can determine if a PROXY header is present. */
    private ByteBuf cumulation;

    public ProxyProtocolOriginalDestinationHandler(MockServerLogger logger) {
        this.logger = logger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            // Non-ByteBuf message — pass through and remove self
            removeSelfAndFireRead(ctx, msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;

        // Accumulate bytes
        if (cumulation == null) {
            cumulation = buf;
        } else {
            cumulation = ctx.alloc().compositeBuffer(2)
                .addComponent(true, cumulation)
                .addComponent(true, buf);
        }

        int readable = cumulation.readableBytes();

        // Check if we have enough bytes to determine if this is a PROXY header
        if (readable < PROXY_V1_SIGNATURE_BYTES.length) {
            // Need more bytes to decide
            return;
        }

        // Check for PROXY v1 signature
        if (!matchesSignature(cumulation)) {
            // Not a PROXY header — pass through all accumulated bytes.
            // Clear cumulation BEFORE removeSelf to prevent double-release in handlerRemoved.
            ByteBuf passThrough = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, passThrough);
            return;
        }

        // We have the signature; now look for the \r\n terminator
        int crlfIndex = findCrLf(cumulation);
        if (crlfIndex < 0) {
            // No CRLF yet — check if we've exceeded the max line length
            if (readable > MAX_PROXY_V1_LINE_LENGTH) {
                logWarning("PROXY protocol v1 header exceeds maximum length ({} bytes) without CRLF terminator, passing through", readable);
                ByteBuf passThrough = cumulation;
                cumulation = null;
                removeSelfAndFireRead(ctx, passThrough);
                return;
            }
            // Need more bytes
            return;
        }

        // We have a complete PROXY header line
        int headerLength = crlfIndex + 2; // include \r\n
        byte[] headerBytes = new byte[crlfIndex];
        cumulation.getBytes(cumulation.readerIndex(), headerBytes, 0, crlfIndex);
        String headerLine = new String(headerBytes, StandardCharsets.US_ASCII);

        // Advance past the header (consume it)
        cumulation.skipBytes(headerLength);

        // Parse the header
        boolean parsed = parseAndApply(ctx, headerLine);

        if (!parsed) {
            logWarning("malformed PROXY protocol v1 header: \"{}\", passing through", headerLine);
        }

        // Pass through any remaining bytes after the header
        if (cumulation.isReadable()) {
            ByteBuf remaining = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, remaining);
        } else {
            cumulation.release();
            cumulation = null;
            removeSelf(ctx);
        }
    }

    /**
     * Checks whether the first bytes of the buffer match the PROXY v1 signature.
     */
    private boolean matchesSignature(ByteBuf buf) {
        int readerIndex = buf.readerIndex();
        for (int i = 0; i < PROXY_V1_SIGNATURE_BYTES.length; i++) {
            if (buf.getByte(readerIndex + i) != PROXY_V1_SIGNATURE_BYTES[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scans the buffer for {@code \r\n} starting from the current reader index.
     *
     * @return the index (relative to reader index 0) of {@code \r}, or -1 if not found
     */
    private int findCrLf(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex();
        for (int i = start; i < end - 1; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') {
                return i - start;
            }
        }
        return -1;
    }

    /**
     * Parses a PROXY v1 header line and sets the channel attributes.
     * <p>
     * Format: {@code PROXY TCP4|TCP6|UNKNOWN <srcIP> <dstIP> <srcPort> <dstPort>}
     *
     * @return true if the header was successfully parsed and attributes set
     */
    private boolean parseAndApply(ChannelHandlerContext ctx, String headerLine) {
        // Split on whitespace: "PROXY", protocol, srcIP, dstIP, srcPort, dstPort
        String[] parts = headerLine.split("\\s+");

        if (parts.length < 2 || !"PROXY".equals(parts[0])) {
            return false;
        }

        String protocol = parts[1];

        // Handle UNKNOWN protocol — no address info, just mark as transparent proxy
        if ("UNKNOWN".equals(protocol)) {
            // PROXY UNKNOWN — no destination info; let downstream resolution handle it
            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: PROXY protocol UNKNOWN received for channel {}, deferring to downstream resolution")
                        .setArguments(ctx.channel())
                );
            }
            return true;
        }

        // TCP4 or TCP6 — must have 6 parts
        if (parts.length < 6) {
            return false;
        }

        if (!"TCP4".equals(protocol) && !"TCP6".equals(protocol)) {
            return false;
        }

        String dstIp = parts[3];
        int dstPort;
        try {
            dstPort = Integer.parseInt(parts[5]);
        } catch (NumberFormatException e) {
            return false;
        }

        if (dstPort < 0 || dstPort > 65535) {
            return false;
        }

        InetSocketAddress originalDst = new InetSocketAddress(dstIp, dstPort);

        ctx.channel().attr(REMOTE_SOCKET).set(originalDst);
        ctx.channel().attr(PROXYING).set(Boolean.TRUE);
        ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).set(Boolean.TRUE);

        if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
            logger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("transparent proxy: resolved original destination {} for channel {} via PROXY protocol v1 ({})")
                    .setArguments(originalDst, ctx.channel(), protocol)
            );
        }

        return true;
    }

    private void removeSelfAndFireRead(ChannelHandlerContext ctx, Object msg) {
        removeSelf(ctx);
        ctx.fireChannelRead(msg);
    }

    private void removeSelf(ChannelHandlerContext ctx) {
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
    }

    private void logWarning(String format, Object... args) {
        if (logger != null && logger.isEnabledForInstance(Level.WARN)) {
            logger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("transparent proxy: " + format)
                    .setArguments(args)
            );
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // Release any accumulated bytes if the handler is removed unexpectedly
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }
}
