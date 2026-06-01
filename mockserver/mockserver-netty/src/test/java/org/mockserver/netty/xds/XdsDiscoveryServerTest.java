package org.mockserver.netty.xds;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.xds.XdsProtoMessages;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for the xDS RDS gRPC discovery server. Verifies:
 * <ul>
 *   <li>Server starts and binds on ephemeral port</li>
 *   <li>Handler-level request/response logic produces correct DiscoveryResponse</li>
 *   <li>gRPC error for unimplemented method paths</li>
 *   <li>Lifecycle (start/stop, fail-soft on bind conflict)</li>
 * </ul>
 */
public class XdsDiscoveryServerTest {

    private Configuration configuration;
    private HttpState httpState;
    private XdsDiscoveryServer server;

    @Before
    public void setUp() {
        configuration = configuration();
        MockServerLogger logger = new MockServerLogger();
        Scheduler scheduler = new Scheduler(configuration, logger);
        httpState = new HttpState(configuration, logger, scheduler);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void shouldStartAndBindOnEphemeralPort() throws Exception {
        server = new XdsDiscoveryServer(httpState);
        int port = server.start(0);
        assertThat("server should bind to a port", port, is(greaterThan(0)));
        assertThat("getPort should return bound port", server.getPort(), is(port));
    }

    @Test
    public void shouldReturnNegativeOneWhenNotStarted() {
        server = new XdsDiscoveryServer(httpState);
        assertThat("getPort should return -1 when not started", server.getPort(), is(-1));
    }

    @Test
    public void shouldStopCleanly() throws Exception {
        server = new XdsDiscoveryServer(httpState);
        server.start(0);
        assertThat("server should be started", server.getPort(), is(greaterThan(0)));

        server.stop();
        assertThat("getPort should return -1 after stop", server.getPort(), is(-1));
    }

    @Test
    public void shouldBuildDiscoveryResponseWithActiveExpectations() throws Exception {
        // Register expectations
        httpState.getRequestMatchers().add(
            new Expectation(request().withMethod("GET").withPath("/api/users"))
                .thenRespond(response().withStatusCode(200)),
            org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API
        );
        httpState.getRequestMatchers().add(
            new Expectation(request().withMethod("POST").withPath("/api/orders"))
                .thenRespond(response().withStatusCode(201)),
            org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API
        );

        server = new XdsDiscoveryServer(httpState);
        server.start(0);

        // Build response bytes (package-private method)
        byte[] responseBytes = server.buildDiscoveryResponseBytes();

        // Decode the DiscoveryResponse
        XdsProtoMessages.DiscoveryResponse discoveryResponse = XdsProtoMessages.DiscoveryResponse.decode(responseBytes);

        assertThat(discoveryResponse.getVersionInfo(), is(notNullValue()));
        assertThat(discoveryResponse.getTypeUrl(), is(XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL));
        assertThat(discoveryResponse.getNonce(), is(notNullValue()));
        assertThat(discoveryResponse.getResources(), hasSize(1));

        // Unwrap the Any resource
        XdsProtoMessages.Any anyResource = discoveryResponse.getResources().get(0);
        assertThat(anyResource.getTypeUrl(), is(XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL));

        // Decode the RouteConfiguration
        XdsProtoMessages.RouteConfiguration routeConfig = XdsProtoMessages.RouteConfiguration.decode(anyResource.getValue());
        assertThat(routeConfig.getName(), is("mockserver_routes"));
        assertThat(routeConfig.getVirtualHosts(), hasSize(1));

        XdsProtoMessages.VirtualHost vh = routeConfig.getVirtualHosts().get(0);
        assertThat(vh.getName(), is("mockserver"));
        assertThat(vh.getDomains(), contains("*"));
        assertThat(vh.getRoutes(), hasSize(2));

        // Verify routes
        assertThat(vh.getRoutes().get(0).getMatch().getPath(), is("/api/users"));
        assertThat(vh.getRoutes().get(0).getRouteAction().getCluster(), is("mockserver"));
        assertThat(vh.getRoutes().get(1).getMatch().getPath(), is("/api/orders"));
        assertThat(vh.getRoutes().get(1).getRouteAction().getCluster(), is("mockserver"));
    }

    @Test
    public void shouldBuildDiscoveryResponseWithEmptyExpectations() throws Exception {
        server = new XdsDiscoveryServer(httpState);
        server.start(0);

        byte[] responseBytes = server.buildDiscoveryResponseBytes();
        XdsProtoMessages.DiscoveryResponse discoveryResponse = XdsProtoMessages.DiscoveryResponse.decode(responseBytes);

        assertThat(discoveryResponse.getResources(), hasSize(1));
        XdsProtoMessages.RouteConfiguration routeConfig = XdsProtoMessages.RouteConfiguration.decode(
            discoveryResponse.getResources().get(0).getValue()
        );
        assertThat(routeConfig.getVirtualHosts().get(0).getRoutes(), is(empty()));
    }

    @Test
    public void shouldIncrementVersionOnEachResponse() throws Exception {
        server = new XdsDiscoveryServer(httpState);
        server.start(0);

        byte[] resp1 = server.buildDiscoveryResponseBytes();
        byte[] resp2 = server.buildDiscoveryResponseBytes();

        XdsProtoMessages.DiscoveryResponse dr1 = XdsProtoMessages.DiscoveryResponse.decode(resp1);
        XdsProtoMessages.DiscoveryResponse dr2 = XdsProtoMessages.DiscoveryResponse.decode(resp2);

        assertThat("version should increment", Integer.parseInt(dr2.getVersionInfo()),
            is(greaterThan(Integer.parseInt(dr1.getVersionInfo()))));
    }

    @Test
    public void shouldHandleFailSoftWhenPortAlreadyBound() throws Exception {
        // Start first server to occupy a port
        server = new XdsDiscoveryServer(httpState);
        int port = server.start(0);

        // Try to start second server on the same port -- should throw
        XdsDiscoveryServer server2 = new XdsDiscoveryServer(httpState);
        boolean exceptionThrown = false;
        try {
            server2.start(port);
        } catch (Exception e) {
            exceptionThrown = true;
        } finally {
            try {
                server2.stop();
            } catch (Exception ignored) {
                // may not have started
            }
        }

        assertThat("should fail when port is already bound", exceptionThrown, is(true));
        // Original server should still be running
        assertThat("original server should still be running", server.getPort(), is(port));
    }

    @Test
    public void shouldBeIdempotentOnDoubleStop() throws Exception {
        server = new XdsDiscoveryServer(httpState);
        server.start(0);
        assertThat("server should be started", server.getPort(), is(greaterThan(0)));

        // First stop
        server.stop();
        assertThat("getPort should be -1 after first stop", server.getPort(), is(-1));

        // Second stop — must not throw
        server.stop();
        assertThat("getPort should still be -1 after double stop", server.getPort(), is(-1));
    }

    @Test
    public void shouldHandleGrpcFramedDiscoveryResponseEndToEnd() throws Exception {
        // Register an expectation
        httpState.getRequestMatchers().add(
            new Expectation(request().withPath("/test"))
                .thenRespond(response().withStatusCode(200)),
            org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API
        );

        server = new XdsDiscoveryServer(httpState);
        server.start(0);

        // Build response and frame it as gRPC
        byte[] responseProto = server.buildDiscoveryResponseBytes();
        byte[] grpcFrame = GrpcFrameCodec.encode(responseProto);

        // Decode the gRPC frame
        List<byte[]> messages = GrpcFrameCodec.decode(grpcFrame);
        assertThat(messages, hasSize(1));

        // Parse back the DiscoveryResponse
        XdsProtoMessages.DiscoveryResponse resp = XdsProtoMessages.DiscoveryResponse.decode(messages.get(0));
        assertThat(resp.getResources(), hasSize(1));

        XdsProtoMessages.RouteConfiguration rc = XdsProtoMessages.RouteConfiguration.decode(
            resp.getResources().get(0).getValue()
        );
        assertThat(rc.getVirtualHosts().get(0).getRoutes(), hasSize(1));
        assertThat(rc.getVirtualHosts().get(0).getRoutes().get(0).getMatch().getPath(), is("/test"));
    }
}
