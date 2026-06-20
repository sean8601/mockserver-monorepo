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
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
        Configuration configuration = configuration().forwardConnectionPoolEnabled(false);
        return new NettyHttpClient(configuration, mockServerLogger, clientEventLoopGroup, null, false);
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

        // wait for the server side to fully tear down its end of the connection
        assertThat("server connection closed within timeout", upstream.awaitConnectionClosed(10, TimeUnit.SECONDS), is(true));

        // A subsequent request must succeed on a FRESH connection - the dead channel is never reused.
        //
        // There is an inherent race the test must not depend on: the server closing its socket and the
        // CLIENT actually observing the FIN (so the pooled channel reports !isActive()) are independent
        // events. If the second request is acquired and dispatched in the window after the server closed
        // but before the client's event loop has read the FIN, the pool hands back the about-to-die
        // channel; the write then fails as the FIN arrives and the request completes with a clean
        // SocketConnectionException. This is the deliberate, documented production behaviour (no silent
        // auto-retry, to avoid double-sending non-idempotent requests) - the caller is expected to retry.
        //
        // We mirror that contract: retry the second request, tolerating only the clean
        // SocketConnectionException, until it succeeds on a fresh connection. The retry loop is bounded
        // and deterministic - once the client observes the FIN, acquire() discards the dead channel and
        // opens a fresh one, so the loop always terminates quickly without sleeps.
        HttpResponse second = sendUntilFreshConnection(client, upstream.port());
        assertThat(second.getStatusCode(), is(200));

        // then - the server-closed channel was never reused. Had the dead keep-alive channel been reused
        // and the request silently succeeded on it, only one connection would ever have been accepted, so
        // the count is at least 2. The EXACT total is race-dependent (see the comment above): when the
        // second request is dispatched on the dead channel before the client observes the FIN, the
        // failed-then-retried attempt opens an additional fresh connection, so a correct run legitimately
        // accepts 2 - or, when that race fires, 3 - connections. We therefore assert the invariant that
        // matters (never reused => >= 2) rather than the race-sensitive exact count, which previously made
        // this test intermittently fail with "expected 2 but was 3".
        assertThat(upstream.acceptedConnections(), is(greaterThanOrEqualTo(2)));
    }

    /**
     * Sends requests to the upstream until one succeeds on a fresh connection, tolerating only the
     * failures that signal "the pool raced a server-side close on a reused keep-alive channel". This
     * is the documented production contract: when a request is dispatched on a pooled channel that the
     * server has just closed (but whose FIN the client has not yet observed), the request fails cleanly
     * rather than being silently auto-retried (which could double-send a non-idempotent request) - the
     * caller is expected to retry. That failure surfaces either as a {@link SocketConnectionException}
     * (handler removed before a response) or as a {@code Connection reset}/{@code Broken pipe}
     * {@link java.net.SocketException} (the OS rejected the write to the half-closed socket). Both are
     * the same race and are retried here; any other failure is propagated. Bounded so the loop cannot
     * hang if the contract is broken.
     */
    private HttpResponse sendUntilFreshConnection(NettyHttpClient client, int port) throws Exception {
        Throwable lastRace = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return client.sendRequest(request().withHeader("Host", "127.0.0.1:" + port))
                    .get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (isServerClosedReuseRace(e.getCause())) {
                    // raced the server-side close on the reused channel - retry on a fresh connection
                    lastRace = e.getCause();
                } else {
                    throw e;
                }
            }
        }
        throw new AssertionError("request never succeeded on a fresh connection after a server-closed reuse race", lastRace);
    }

    /**
     * True when the cause is the clean failure produced by dispatching a request on a pooled keep-alive
     * channel the server has already closed - either the pool's own {@link SocketConnectionException} or
     * a {@code Connection reset}/{@code Broken pipe} socket error from writing to the half-closed socket.
     */
    private static boolean isServerClosedReuseRace(Throwable cause) {
        if (cause instanceof SocketConnectionException) {
            return true;
        }
        if (cause instanceof java.net.SocketException) {
            String message = cause.getMessage();
            return message != null
                && (message.contains("Connection reset") || message.contains("Broken pipe") || message.contains("broken pipe"));
        }
        return false;
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
     * send {@code Connection: close} on every response, or to close the socket after the response on
     * the FIRST connection only (so the replacement connection is stably reusable).
     */
    private static final class CountingUpstreamServer {

        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final AtomicInteger accepted = new AtomicInteger();
        private final CountDownLatch connectionClosedLatch = new CountDownLatch(1);
        private final Channel serverChannel;
        private volatile boolean connectionClose;
        /**
         * When set, the upstream sends a keep-alive header then closes the socket after responding,
         * but does so for the FIRST accepted connection only. This deterministically exercises
         * "server closed a pooled keep-alive channel" without making every replacement connection
         * close too (which would make the accepted-connection count non-deterministic under retries).
         */
        private final java.util.concurrent.atomic.AtomicBoolean closeNextAfterResponding = new java.util.concurrent.atomic.AtomicBoolean(false);

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
                                // close-after-responding fires once (for the first connection that
                                // serves a request); the replacement connection then stays keep-alive
                                boolean closeThisConnection = closeNextAfterResponding.getAndSet(false);
                                boolean close = connectionClose || closeThisConnection;
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
            this.closeNextAfterResponding.set(true);
        }

        void stop() {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }
}
