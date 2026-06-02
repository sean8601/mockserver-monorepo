package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.netty.proxy.TransparentProxyHandler.TRANSPARENT_ORIGINAL_DST_RESOLVED;

public class ProxyProtocolOriginalDestinationHandlerTest {

    private final MockServerLogger logger = new MockServerLogger(ProxyProtocolOriginalDestinationHandlerTest.class);

    // --- Valid PROXY v1 TCP4 ---

    @Test
    public void shouldParseValidProxyV1Tcp4Header() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";
        String httpRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader + httpRequest, StandardCharsets.US_ASCII));

        // then — REMOTE_SOCKET set to destination from PROXY header
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(80));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        // handler should have removed itself
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // remaining HTTP bytes should pass through
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        String remainingStr = remaining.toString(StandardCharsets.US_ASCII);
        assertThat(remainingStr, is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldParseValidProxyV1Tcp4WithHttpsPort() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 172.16.0.5 93.184.216.34 12345 443\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("93.184.216.34"));
        assertThat(remoteSocket.getPort(), is(443));

        channel.close();
    }

    // --- Valid PROXY v1 TCP6 ---

    @Test
    public void shouldParseValidProxyV1Tcp6Header() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP6 ::1 ::1 56324 8080\r\n";
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader + httpRequest, StandardCharsets.US_ASCII));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getPort(), is(8080));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        // handler removed
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // remaining bytes pass through
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldParseValidProxyV1Tcp6FullAddress() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP6 2001:db8::1 2001:db8::2 56324 443\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getPort(), is(443));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        channel.close();
    }

    // --- Non-PROXY traffic (pass-through) ---

    @Test
    public void shouldPassThroughNonProxyTraffic() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String httpRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(httpRequest, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET set, handler removed, bytes pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldPassThroughTlsClientHello() {
        // given — TLS ClientHello starts with 0x16 (not 'P')
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] tlsHello = new byte[]{0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x01, 0x00, 0x00};

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(tlsHello));

        // then — pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        byte[] result = new byte[remaining.readableBytes()];
        remaining.readBytes(result);
        assertThat(result, is(tlsHello));
        remaining.release();

        channel.close();
    }

    // --- PROXY UNKNOWN ---

    @Test
    public void shouldHandleProxyUnknown() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY UNKNOWN\r\n";
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader + httpRequest, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET set (UNKNOWN has no address), handler removed
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    // --- Malformed headers (fail-safe pass-through) ---

    @Test
    public void shouldPassThroughMalformedProxyHeader() {
        // given — looks like PROXY but has wrong format
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String malformed = "PROXY BADPROTO 1.2.3.4\r\nGET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(malformed, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET, handler removed, remaining bytes pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // The bytes after the malformed PROXY header line should be available
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldPassThroughMalformedMissingPorts() {
        // given — PROXY TCP4 but missing port fields
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String malformed = "PROXY TCP4 1.2.3.4 5.6.7.8\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(malformed, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET, handler removed
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    @Test
    public void shouldPassThroughInvalidPort() {
        // given — port is not a number
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String malformed = "PROXY TCP4 1.2.3.4 5.6.7.8 abc xyz\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(malformed, StandardCharsets.US_ASCII));

        // then
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    // --- Oversized header (fail-safe) ---

    @Test
    public void shouldPassThroughOversizedHeader() {
        // given — PROXY signature followed by very long line without CRLF
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        StringBuilder sb = new StringBuilder("PROXY TCP4 ");
        // Pad to exceed MAX_PROXY_V1_LINE_LENGTH (108)
        while (sb.length() < 120) {
            sb.append("x");
        }
        // No CRLF

        // when
        channel.writeInbound(Unpooled.copiedBuffer(sb.toString(), StandardCharsets.US_ASCII));

        // then — fail-safe: pass through, handler removed
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.readableBytes(), is(sb.length()));
        remaining.release();

        channel.close();
    }

    // --- Fragmented delivery (bytes arrive in multiple chunks) ---

    @Test
    public void shouldHandleFragmentedProxyHeader() {
        // given — PROXY header arrives in two chunks
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";
        String full = proxyHeader + httpRequest;

        // Split at an arbitrary point inside the PROXY header
        String chunk1 = full.substring(0, 15); // "PROXY TCP4 192."
        String chunk2 = full.substring(15);

        // when — first chunk
        channel.writeInbound(Unpooled.copiedBuffer(chunk1, StandardCharsets.US_ASCII));

        // handler should still be in pipeline (waiting for more bytes)
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(notNullValue()));
        assertThat(channel.readInbound(), is(nullValue())); // no output yet

        // when — second chunk
        channel.writeInbound(Unpooled.copiedBuffer(chunk2, StandardCharsets.US_ASCII));

        // then — PROXY header parsed
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(80));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // remaining HTTP bytes pass through
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    // --- PROXY header only (no trailing bytes) ---

    @Test
    public void shouldHandleProxyHeaderWithNoTrailingBytes() {
        // given — only the PROXY header, no HTTP request bytes yet
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then — REMOTE_SOCKET set, handler removed, no output bytes
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(80));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));
        assertThat(channel.readInbound(), is(nullValue())); // no remaining bytes

        channel.close();
    }

    // --- Port boundary validation ---

    @Test
    public void shouldRejectPortOutOfRange() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 1.2.3.4 5.6.7.8 1234 99999\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then — invalid port, no REMOTE_SOCKET
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }
}
