package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Experimental HTTP/3 (QUIC) server for MockServer.
 * <p>
 * This is an MVP implementation that serves a simple echo handler proving the
 * HTTP/3 stack works. Full mock-pipeline bridging (routing requests through
 * HttpState/ActionHandler) is a planned follow-up.
 * <p>
 * The server is OFF by default and only starts when {@code http3Port} is set
 * to a non-zero value in the configuration.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3Server {

    private static final Logger LOG = LoggerFactory.getLogger(Http3Server.class);

    private volatile Channel channel;
    private volatile NioEventLoopGroup group;

    /**
     * Start the HTTP/3 server on the given UDP port.
     *
     * @param port UDP port to bind; use 0 for an ephemeral port
     * @return the actual bound port
     * @throws Exception if the server cannot start
     */
    public int start(int port) throws Exception {
        group = new NioEventLoopGroup(1);

        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(keyPair);

        QuicSslContext sslContext = QuicSslContextBuilder
            .forServer(keyPair.getPrivate(), null, cert)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        ChannelHandler codec = Http3.newQuicServerCodecBuilder()
            .sslContext(sslContext)
            .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
            .initialMaxData(10000000)
            .initialMaxStreamDataBidirectionalLocal(1000000)
            .initialMaxStreamDataBidirectionalRemote(1000000)
            .initialMaxStreamsBidirectional(100)
            .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
            .handler(new Http3ServerConnectionHandler(
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        ch.pipeline().addLast(new Http3EchoRequestHandler());
                    }
                }
            ))
            .build();

        channel = new Bootstrap()
            .group(group)
            .channel(NioDatagramChannel.class)
            .handler(codec)
            .bind(new InetSocketAddress(port))
            .sync()
            .channel();

        int boundPort = ((InetSocketAddress) channel.localAddress()).getPort();
        LOG.info("experimental HTTP/3 (QUIC) server started on UDP port: {}", boundPort);
        return boundPort;
    }

    /**
     * Returns the bound UDP port, or -1 if not started.
     */
    public int getPort() {
        if (channel != null && channel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Stop the HTTP/3 server and release resources.
     */
    public void stop() {
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        LOG.info("HTTP/3 (QUIC) server stopped");
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(256, new SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCert(KeyPair keyPair) throws Exception {
        X500Name issuer = new X500Name("CN=MockServer HTTP/3, O=MockServer");
        BigInteger serial = new BigInteger(64, new SecureRandom());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + TimeUnit.DAYS.toMillis(365));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.getPrivate());
        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
        ).build(signer);

        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    /**
     * MVP request handler: echoes the request path and method in the response body.
     * Full mock-pipeline bridging (HttpState/ActionHandler) is a planned follow-up.
     */
    static class Http3EchoRequestHandler extends Http3RequestStreamInboundHandler {

        @Override
        protected void channelRead(
            ChannelHandlerContext ctx,
            Http3HeadersFrame headersFrame
        ) {
            CharSequence methodSeq = headersFrame.headers().method();
            CharSequence pathSeq = headersFrame.headers().path();
            String method = methodSeq != null ? methodSeq.toString() : "UNKNOWN";
            String path = pathSeq != null ? pathSeq.toString() : "/";

            String responseBody = "MockServer HTTP/3 echo - method: " + method + ", path: " + path;
            byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);

            DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
            responseHeaders.headers().status("200");
            responseHeaders.headers().add("content-type", "text/plain; charset=utf-8");
            responseHeaders.headers().addInt("content-length", bodyBytes.length);
            responseHeaders.headers().add("server", "mockserver-http3-experimental");

            ctx.write(responseHeaders);
            ctx.writeAndFlush(new DefaultHttp3DataFrame(
                Unpooled.wrappedBuffer(bodyBytes)
            )).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }

        @Override
        protected void channelRead(
            ChannelHandlerContext ctx,
            Http3DataFrame dataFrame
        ) {
            // MVP: ignore request body data frames
            ReferenceCountUtil.release(dataFrame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            // stream input closed by peer - nothing to do for the echo handler
        }
    }
}
