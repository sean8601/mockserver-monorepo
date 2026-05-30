package org.mockserver.codec;

import io.netty.channel.CombinedChannelDuplexHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.function.BooleanSupplier;

/**
 * @author jamesdbloom
 */
public class MockServerHttpServerCodec extends CombinedChannelDuplexHandler<NettyHttpToMockServerHttpRequestDecoder, MockServerHttpToNettyHttpResponseEncoder> {

    public MockServerHttpServerCodec(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, SocketAddress socketAddress) {
        this(configuration, mockServerLogger, isSecure, clientCertificates, socketAddress instanceof InetSocketAddress ? ((InetSocketAddress) socketAddress).getPort() : null);
    }

    public MockServerHttpServerCodec(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port) {
        this(configuration, mockServerLogger, isSecure, clientCertificates, port, null);
    }

    public MockServerHttpServerCodec(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, SocketAddress socketAddress, BooleanSupplier hasBodyMatchers) {
        this(configuration, mockServerLogger, isSecure, clientCertificates, socketAddress instanceof InetSocketAddress ? ((InetSocketAddress) socketAddress).getPort() : null, hasBodyMatchers);
    }

    public MockServerHttpServerCodec(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port, BooleanSupplier hasBodyMatchers) {
        init(new NettyHttpToMockServerHttpRequestDecoder(configuration, mockServerLogger, isSecure, clientCertificates, port, hasBodyMatchers), new MockServerHttpToNettyHttpResponseEncoder(mockServerLogger));
    }

}
