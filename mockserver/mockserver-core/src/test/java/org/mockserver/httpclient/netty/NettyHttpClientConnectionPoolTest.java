package org.mockserver.httpclient.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behaviour tests for the opt-in upstream connection pool ({@code forwardConnectionPoolEnabled}).
 * <p>
 * A small counting HTTP/1.1 upstream server records how many TCP connections it accepts so the
 * tests can assert that pooling reuses a single keep-alive connection across a burst, that a
 * {@code Connection: close} response is never reused, that a server-closed channel is never reused,
 * and that pooling does not corrupt responses under concurrent same-upstream load.
 */
public class NettyHttpClientConnectionPoolTest {

    private static EventLoopGroup clientEventLoopGroup;
    private CountingUpstreamServer upstream;
    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(NettyHttpClientConnectionPoolTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    @Before
    public void startUpstream() {
        upstream = new CountingUpstreamServer();
    }

    @After
    public void stopUpstream() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    private NettyHttpClient pooledClient() {
        Configuration configuration = configuration().forwardConnectionPoolEnabled(true);
        return new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, false);
    }

    private NettyHttpClient unpooledClient() {
        return new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, null, false);
    }

    @Test
    public void shouldReuseSingleConnectionForSequentialBurstWhenPoolingEnabled() throws Exception {
        // given
        NettyHttpClient client = pooledClient();

        // when - 5 sequential requests to the same upstream
        for (int i = 0; i < 5; i++) {
            HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));
        }

        // then - the upstream accepted only one connection (keep-alive reuse)
        assertThat(upstream.acceptedConnections(), is(1));
    }

    @Test
    public void shouldOpenFreshConnectionPerRequestWhenPoolingDisabled() throws Exception {
        // given
        NettyHttpClient client = unpooledClient();

        // when - 5 sequential requests to the same upstream
        for (int i = 0; i < 5; i++) {
            HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));
        }

        // then - each request opened its own connection (historical behaviour preserved)
        assertThat(upstream.acceptedConnections(), is(5));
    }

    @Test
    public void shouldNotReuseConnectionWhenUpstreamReturnsConnectionClose() throws Exception {
        // given
        upstream.respondWithConnectionClose();
        NettyHttpClient client = pooledClient();

        // when - 3 sequential requests, each receives Connection: close
        for (int i = 0; i < 3; i++) {
            HttpResponse response = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
                .get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode(), is(200));
        }

        // then - no reuse: a fresh connection per request
        assertThat(upstream.acceptedConnections(), is(3));
    }

    @Test
    public void shouldNotReuseChannelTheServerClosedAfterResponding() throws Exception {
        // given - upstream sends keep-alive header but then closes the socket
        upstream.closeAfterResponding();
        NettyHttpClient client = pooledClient();

        // when - first request returns and the server then drops the connection
        HttpResponse first = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
            .get(10, TimeUnit.SECONDS);
        assertThat(first.getStatusCode(), is(200));

        // deterministically wait until the server-side connection has been fully torn down so the
        // client has processed the FIN and evicted the (now dead) idle channel from the pool
        assertThat("server connection closed within timeout", upstream.awaitConnectionClosed(10, TimeUnit.SECONDS), is(true));

        // a subsequent request must succeed on a fresh connection (the dead channel is not reused)
        HttpResponse second = client.sendRequest(request().withHeader("Host", "127.0.0.1:" + upstream.port()))
            .get(10, TimeUnit.SECONDS);
        assertThat(second.getStatusCode(), is(200));

        // then - two connections were opened (the first was not reusable)
        assertThat(upstream.acceptedConnections(), is(2));
    }

    @Test
    public void shouldNotCorruptResponsesUnderConcurrentSameUpstreamLoad() throws Exception {
        // given
        NettyHttpClient client = pooledClient();
        int threads = 8;
        int requestsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();

        // when - many concurrent requests to the same upstream
        Future<?>[] futures = new Future<?>[threads];
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            futures[t] = executor.submit(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    try {
                        String marker = "thread-" + threadIndex + "-req-" + i;
                        HttpResponse response = client.sendRequest(
                            request()
                                .withHeader("Host", "127.0.0.1:" + upstream.port())
                                .withHeader("X-Marker", marker)
                        ).get(20, TimeUnit.SECONDS);
                        if (response.getStatusCode() != 200 || !marker.equals(response.getFirstHeader("X-Marker"))) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            });
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // then - every response is correct and correlated to its own request (no cross-talk)
        assertThat("no corrupted/cross-talked responses", failures.get(), is(0));
        // and reuse actually happened: fewer connections were opened than total requests
        assertThat(upstream.acceptedConnections(), lessThan(threads * requestsPerThread));
    }

    /**
     * A minimal HTTP/1.1 upstream that counts accepted TCP connections and echoes the request's
     * {@code X-Marker} header back. By default it keeps connections alive; it can be configured to
     * send {@code Connection: close} or to close the socket after the response.
     */
    private static final class CountingUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final CountDownLatch connectionClosedLatch = new CountDownLatch(1);
        private final Channel serverChannel;
        private volatile boolean connectionClose;
        private volatile boolean closeAfterResponding;

        CountingUpstreamServer() {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        accepted.incrementAndGet();
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                connectionClosedLatch.countDown();
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                                FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    io.netty.handler.codec.http.HttpResponseStatus.OK,
                                    ctx.alloc().buffer().writeBytes(body)
                                );
                                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                String marker = msg.headers().get("X-Marker");
                                if (marker != null) {
                                    response.headers().set("X-Marker", marker);
                                }
                                boolean close = connectionClose || closeAfterResponding;
                                response.headers().set(
                                    HttpHeaderNames.CONNECTION,
                                    connectionClose ? HttpHeaderValues.CLOSE : HttpHeaderValues.KEEP_ALIVE
                                );
                                if (close) {
                                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                } else {
                                    ctx.writeAndFlush(response);
                                }
                            }
                        });
                    }
                });
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        boolean awaitConnectionClosed(long timeout, TimeUnit unit) throws InterruptedException {
            return connectionClosedLatch.await(timeout, unit);
        }

        void respondWithConnectionClose() {
            this.connectionClose = true;
        }

        void closeAfterResponding() {
            this.closeAfterResponding = true;
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }
}
