package org.mockserver.netty.xds;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Tests for xDS RDS server lifecycle integration with MockServer.
 * Verifies that:
 * <ul>
 *   <li>When xdsEnabled=false (default), no xDS server is started</li>
 *   <li>When xdsEnabled=true and xdsPort>0, the xDS server starts</li>
 *   <li>The xDS server is stopped during MockServer shutdown</li>
 *   <li>Fail-soft: a bind failure on xdsPort does not crash the main server</li>
 * </ul>
 */
public class XdsLifecycleTest {

    @Test
    public void shouldNotStartXdsWhenDisabled() {
        Configuration configuration = configuration().xdsEnabled(false);
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("main server should be running", server.getLocalPort(), is(greaterThan(0)));
            assertThat("xDS should not be started when disabled", server.getXdsPort(), is(-1));
        } finally {
            server.stop();
        }
    }

    @Test
    public void shouldNotStartXdsWhenEnabledButPortIsZero() {
        Configuration configuration = configuration().xdsEnabled(true).xdsPort(0);
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("main server should be running", server.getLocalPort(), is(greaterThan(0)));
            assertThat("xDS should not be started when port is 0", server.getXdsPort(), is(-1));
        } finally {
            server.stop();
        }
    }

    @Test
    public void shouldStartXdsWhenEnabledWithEphemeralPort() {
        int xdsPort = findAvailableTcpPort();
        Configuration configuration = configuration().xdsEnabled(true).xdsPort(xdsPort);
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("main server should be running", server.getLocalPort(), is(greaterThan(0)));
            assertThat("xDS server should be started", server.getXdsPort(), is(greaterThan(0)));
        } finally {
            server.stop();
        }
    }

    @Test
    public void shouldStopXdsOnShutdown() {
        int xdsPort = findAvailableTcpPort();
        Configuration configuration = configuration().xdsEnabled(true).xdsPort(xdsPort);
        MockServer server = new MockServer(configuration, 0);
        assertThat("xDS should be started", server.getXdsPort(), is(greaterThan(0)));

        server.stop();
        assertThat("xDS should be stopped after shutdown", server.getXdsPort(), is(-1));
    }

    @Test
    public void shouldNotCrashWhenXdsPortBindFails() {
        // Start a first MockServer that binds an xDS port
        int xdsPort = findAvailableTcpPort();
        Configuration config1 = configuration().xdsEnabled(true).xdsPort(xdsPort);
        MockServer server1 = new MockServer(config1, 0);

        try {
            int boundXdsPort = server1.getXdsPort();
            assertThat("first server xDS should start", boundXdsPort, is(greaterThan(0)));

            // Start a second MockServer using the same xDS port -- the xDS bind
            // should fail but the main server should still start (fail-soft)
            Configuration config2 = configuration().xdsEnabled(true).xdsPort(boundXdsPort);
            MockServer server2 = new MockServer(config2, 0);
            try {
                assertThat("second main server should start", server2.isRunning(), is(true));
                assertThat("second main server should have a port", server2.getLocalPort(), is(greaterThan(0)));
                // The xDS port on the second server should be -1 (bind failed)
                assertThat("second xDS should fail-soft", server2.getXdsPort(), is(-1));
            } finally {
                server2.stop();
            }
        } finally {
            server1.stop();
        }
    }

    @Test
    public void shouldExposeXdsPortAccessor() {
        Configuration configuration = configuration();
        MockServer server = new MockServer(configuration, 0);
        try {
            assertThat("getXdsPort should return -1 when not configured",
                server.getXdsPort(), is(-1));
        } finally {
            server.stop();
        }
    }

    /**
     * Find an available TCP port by binding to port 0 and then closing.
     */
    private static int findAvailableTcpPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }
}
