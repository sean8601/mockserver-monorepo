package org.mockserver.netty.http3;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * HTTP/3 CONNECT-UDP (MASQUE, RFC 9298) handler stub.
 * <p>
 * This handler intercepts HTTP/3 CONNECT requests when the
 * {@code http3ConnectUdpEnabled} configuration flag is set to {@code true}.
 * It is inserted into the QUIC stream pipeline <strong>before</strong> the
 * regular {@link Http3MockServerHandler} so that CONNECT requests are handled
 * here and non-CONNECT requests pass through to the normal mock pipeline.
 * <p>
 * <strong>Current status (DEFERRED):</strong> the bundled
 * {@code netty-incubator-codec-http3} (0.0.30.Final) does not support the
 * {@code :protocol} pseudo-header (RFC 9220 extended CONNECT for HTTP/3) and
 * actively rejects it in {@code Http3HeadersSink.validate()}. This means a
 * compliant CONNECT-UDP client sending {@code :protocol = connect-udp} would
 * have its request rejected by the codec before this handler ever sees it.
 * <p>
 * For now, when this handler sees a plain HTTP/3 CONNECT request (without
 * {@code :protocol}, which is the only kind the codec allows through), it
 * responds with {@code 501 Not Implemented} and a descriptive body explaining
 * that the extended CONNECT / MASQUE protocol is not yet supported.
 * <p>
 * <strong>What a full implementation needs (blocked on codec upgrade):</strong>
 * <ol>
 *   <li>A codec version that includes {@code :protocol} in
 *       {@code Http3Headers.PseudoHeaderName} and
 *       {@code SETTINGS_ENABLE_CONNECT_PROTOCOL (0x08)} in
 *       {@code Http3SettingsFrame}.</li>
 *   <li>Enable QUIC datagrams on the server codec via
 *       {@code QuicServerCodecBuilder.datagram(recvQueueLen, sendQueueLen)}.
 *       The bundled QUIC codec (0.0.73.Final) already supports this.</li>
 *   <li>Advertise {@code SETTINGS_ENABLE_CONNECT_PROTOCOL=1} and
 *       {@code SETTINGS_H3_DATAGRAM=1} (0xffd277) in the server's SETTINGS
 *       frame.</li>
 *   <li>Parse the target URI from the extended-CONNECT request
 *       ({@code :authority} or path-based target per RFC 9298).</li>
 *   <li>Open a UDP {@code DatagramChannel} (Netty NIO) to the target
 *       authority.</li>
 *   <li>Bridge QUIC datagrams (received as {@code ByteBuf} on the
 *       {@code QuicChannel} pipeline) to the UDP socket and vice-versa,
 *       optionally using the HTTP Datagram capsule framing (RFC 9297).</li>
 *   <li>Tear down the UDP socket and QUIC stream on close, error, or
 *       idle timeout.</li>
 * </ol>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298 - Proxying UDP in HTTP (CONNECT-UDP)</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220 - Bootstrapping WebSockets with HTTP/3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
public class Http3ConnectUdpHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(Http3ConnectUdpHandler.class);

    /**
     * JSON body included in the 501 response to explain why CONNECT-UDP is not
     * yet functional.
     */
    private static final String NOT_IMPLEMENTED_BODY =
        "{\"error\":\"CONNECT-UDP (MASQUE) not yet implemented\"," +
        "\"reason\":\"The bundled netty-incubator-codec-http3 (0.0.30.Final) does not support " +
        "the :protocol pseudo-header (RFC 9220 extended CONNECT) or HTTP Datagrams (RFC 9297). " +
        "A codec upgrade is required to enable full CONNECT-UDP relay.\"," +
        "\"flag\":\"http3ConnectUdpEnabled\"}";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http3HeadersFrame) {
            Http3HeadersFrame headersFrame = (Http3HeadersFrame) msg;
            CharSequence method = headersFrame.headers().method();

            if (method != null && "CONNECT".equalsIgnoreCase(method.toString())) {
                // This is a CONNECT request. The bundled codec only lets through
                // plain CONNECT (not extended CONNECT with :protocol) because it
                // does not recognise :protocol as a valid pseudo-header.
                handleConnectRequest(ctx, headersFrame);
                return;
            }
        }

        // Not a CONNECT request -- pass through to the next handler
        // (Http3MockServerHandler for normal mock/proxy processing)
        ctx.fireChannelRead(msg);
    }

    /**
     * Handle a CONNECT request by responding with 501 Not Implemented.
     * <p>
     * A plain CONNECT on HTTP/3 (without :protocol) is used for TCP tunnelling
     * (RFC 9114 section 4.4). Extended CONNECT with :protocol=connect-udp is
     * used for UDP proxying (MASQUE, RFC 9298). Neither is currently supported
     * because the codec lacks extended-CONNECT support and MockServer is not a
     * TCP forward proxy.
     */
    private void handleConnectRequest(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
        CharSequence authority = headersFrame.headers().authority();
        LOG.info("HTTP/3 CONNECT request received for authority '{}' -- returning 501 Not Implemented " +
            "(CONNECT-UDP / MASQUE not yet supported by bundled codec)", authority);

        byte[] bodyBytes = NOT_IMPLEMENTED_BODY.getBytes(StandardCharsets.UTF_8);

        DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
        responseHeaders.headers().status("501");
        responseHeaders.headers().add("content-type", "application/json; charset=utf-8");
        responseHeaders.headers().addInt("content-length", bodyBytes.length);
        responseHeaders.headers().add("server", "mockserver-http3-experimental");

        ctx.write(responseHeaders);
        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bodyBytes)))
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }
}
