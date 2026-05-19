package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.netty.integration.NoDependenciesJarRunner;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Smoke test that boots the executable {@code mockserver-netty-no-dependencies}
 * jar via {@code java -jar} and verifies it serves mocked responses end-to-end.
 *
 * <p>Intentionally uses only the JDK ({@link java.net.http.HttpClient}) so the
 * test JVM never loads {@code MockServerClient} / {@code NettyHttpClient} — if
 * it did, the shaded jar (which contains those classes with {@code shaded_packa
 * ge.io.netty.*} parameter types) would clash with the unshaded
 * {@code mockserver-core} on the test classpath.
 *
 * <p>What this guards against:
 * <ul>
 *   <li>{@code Main-Class} manifest entry missing or wrong (jar would fail to launch)
 *   <li>CLI argument parser regression (the {@code -serverPort} arg is rejected)
 *   <li>Shaded jar missing classes needed for boot (e.g. shade-plugin filter mistake)
 *   <li>Port binding / Netty pipeline regression in the shaded build
 *   <li>REST API regression on expectation create or mocked GET
 * </ul>
 */
public class ExtendedNoDependenciesJarMockingIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static NoDependenciesJarRunner runner;
    private static int port;

    @BeforeClass
    public static void startServer() {
        port = findFreePort();
        runner = NoDependenciesJarRunner.startServerUsingNoDependenciesJar(port);
    }

    @AfterClass
    public static void stopServer() {
        if (runner != null) {
            runner.stop();
        }
    }

    @Test
    public void shouldServeMockedExpectationFromForkedJar() throws Exception {
        HttpRequest createExpectation = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/expectation"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(
                "{" +
                "  \"httpRequest\": { \"method\": \"GET\", \"path\": \"/smoke-test/hello\" }," +
                "  \"httpResponse\": {" +
                "    \"statusCode\": 200," +
                "    \"headers\": { \"X-Smoke\": [\"ok\"] }," +
                "    \"body\": \"smoke-test-response\"" +
                "  }" +
                "}"))
            .build();
        HttpResponse<String> createResponse = HTTP.send(createExpectation, HttpResponse.BodyHandlers.ofString());
        assertTrue(
            "PUT /mockserver/expectation returned " + createResponse.statusCode() + " body=" + createResponse.body(),
            createResponse.statusCode() == 201 || createResponse.statusCode() == 200);

        HttpRequest invokeMock = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/smoke-test/hello"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> mockedResponse = HTTP.send(invokeMock, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, mockedResponse.statusCode());
        assertEquals("smoke-test-response", mockedResponse.body());
        assertEquals("ok", mockedResponse.headers().firstValue("X-Smoke").orElse(null));
    }

    @Test
    public void shouldReportStatusOk() throws Exception {
        HttpRequest status = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/status"))
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = HTTP.send(status, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        // Body is JSON like {"ports":[<port>]} — assert it mentions the port we bound to.
        assertTrue("status body did not contain port: " + response.body(),
            response.body().contains(Integer.toString(port)));
    }

    /**
     * Matches the {@code org.mockserver.socket.PortFactory.findFreePort()} pattern
     * used elsewhere in the project: bind {@code ServerSocket(0)} to get an OS-
     * assigned ephemeral port, then close and hope the forked JVM grabs it before
     * anyone else does. The window is microseconds; the runner's
     * {@code waitUntilReady} will surface a {@code BindException} as a fast
     * process exit if the race ever loses.
     */
    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not allocate a free port for the test", e);
        }
    }
}
