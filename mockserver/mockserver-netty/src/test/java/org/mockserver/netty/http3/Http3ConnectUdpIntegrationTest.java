package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ClientConnectionHandler;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration tests for the HTTP/3 CONNECT-UDP handler with a real QUIC
 * server and client.
 * <p>
 * Tests skip gracefully when the native QUIC transport (BoringSSL) is not
 * available on the build platform.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ConnectUdpIntegrationTest {

    private MockServer mockServer;
    private NioEventLoopGroup clientGroup;

    @Before
    public void setUp() {
        assumeQuicAvailable();
    }

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldRejectConnectRequestWith501WhenFlagEnabled() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3ConnectUdpEnabled(true);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // Send a CONNECT request over HTTP/3
        // Note: the codec validates that CONNECT only has :method + :authority
        // (no :path or :scheme), so we send exactly that
        String[] result = sendHttp3ConnectRequest(http3Port, "target.example.com:443");
        assertThat("status should be 501", result[0], is("501"));
        assertThat("body should explain CONNECT-UDP is not implemented",
            result[1], containsString("CONNECT-UDP (MASQUE) not yet implemented"));
        assertThat("body should mention the codec limitation",
            result[1], containsString(":protocol pseudo-header"));
    }

    @Test
    public void shouldStillServeNormalRequestsWhenFlagEnabled() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3ConnectUdpEnabled(true);

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // Set up an expectation via HTTP/1.1 client API
        MockServerClient client = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        client.when(
            request().withMethod("GET").withPath("/normal")
        ).respond(
            response()
                .withStatusCode(200)
                .withBody("normal-response")
        );

        // Send a normal GET request over HTTP/3
        String[] result = sendHttp3Request(http3Port, "GET", "/normal", null);
        assertThat("status should be 200 for normal GET", result[0], is("200"));
        assertThat("body should be the expectation response",
            result[1], containsString("normal-response"));
    }

    @Test
    public void shouldNotInterceptConnectWhenFlagDisabled() throws Exception {
        int udpPort = findAvailableUdpPort();
        Configuration config = configuration()
            .http3Port(udpPort)
            .http3ConnectUdpEnabled(false); // explicitly disabled

        mockServer = new MockServer(config, 0);

        int http3Port = mockServer.getHttp3Port();
        Assume.assumeTrue("HTTP/3 server did not start", http3Port > 0);

        // Send a CONNECT request -- without the handler in the pipeline,
        // the normal mock handler will process it (likely returning 404 or
        // trying to match an expectation)
        String[] result = sendHttp3ConnectRequest(http3Port, "target.example.com:443");

        // Without the CONNECT-UDP handler, the request goes to the normal
        // pipeline which does not understand CONNECT and returns a non-501 status
        assertThat("status should NOT be 501 when flag is disabled",
            result[0], is(not("501")));
    }

    @Test
    public void shouldDefaultToDisabled() {
        Configuration config = configuration();
        assertThat("http3ConnectUdpEnabled should default to false",
            config.http3ConnectUdpEnabled(), is(false));
    }

    // ---- helper methods ----

    /**
     * Send an HTTP/3 CONNECT request to the given port and return [status, body].
     * CONNECT requests per RFC 9114 section 4.4 only include :method and :authority.
     */
    private String[] sendHttp3ConnectRequest(int port, String authority) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(trustAllManager())
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(5, TimeUnit.SECONDS);

        BlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> bodyQueue = new LinkedBlockingQueue<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        // CONNECT per HTTP/3 = :method + :authority only (no :path, no :scheme)
        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method("CONNECT");
        requestHeaders.headers().authority(authority);

        requestStream.writeAndFlush(requestHeaders)
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            .sync();

        String status = statusQueue.poll(5, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(5, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new String[]{
            status != null ? status : "null",
            responseBody != null ? responseBody : ""
        };
    }

    /**
     * Send a normal HTTP/3 request (GET/POST etc.) and return [status, body].
     */
    private String[] sendHttp3Request(int port, String method, String path, byte[] body) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(trustAllManager())
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channel(NioDatagramChannel.class)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(5, TimeUnit.SECONDS);

        BlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> bodyQueue = new LinkedBlockingQueue<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        if (body != null && body.length > 0) {
            requestHeaders.headers().add("content-type", "application/json");
            requestStream.write(requestHeaders).sync();
            requestStream.writeAndFlush(new io.netty.incubator.codec.http3.DefaultHttp3DataFrame(
                Unpooled.wrappedBuffer(body)
            )).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();
        } else {
            requestStream.writeAndFlush(requestHeaders)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        }

        String status = statusQueue.poll(5, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(5, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new String[]{
            status != null ? status : "null",
            responseBody != null ? responseBody : ""
        };
    }

    private static int findAvailableUdpPort() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.incubator.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 test",
                t
            );
        }
    }

    @SuppressWarnings("TrustAllX509TrustManager")
    private static TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
    }
}
