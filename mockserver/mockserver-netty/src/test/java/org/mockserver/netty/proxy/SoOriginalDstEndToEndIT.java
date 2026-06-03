package org.mockserver.netty.proxy;

import org.junit.Assume;
import org.junit.Test;

/**
 * End-to-end integration test for SO_ORIGINAL_DST resolution via iptables
 * REDIRECT. This test is designed to run <b>only</b> in Linux CI environments
 * where Docker is available and the container can use the {@code NET_ADMIN}
 * capability to create iptables REDIRECT rules.
 * <p>
 * <b>Current status: documented placeholder.</b> The test is marked as
 * permanently skipped via {@code Assume.assumeTrue(false)} because a correct
 * iptables + container harness cannot be reliably authored and validated
 * without execution on a Linux host with Docker. The approach was judged too
 * fragile to ship without iterative CI validation.
 * <p>
 * <h3>Manual/CI verification procedure</h3>
 * <p>
 * To validate SO_ORIGINAL_DST resolution end-to-end on Linux:
 * <ol>
 *   <li>Build the MockServer fat JAR:
 *       {@code ./mvnw -pl mockserver-netty package -DskipTests -Djacoco.skip}</li>
 *   <li>Run a privileged container with NET_ADMIN:
 *       <pre>{@code
 * docker run --rm --cap-add=NET_ADMIN -v $(pwd)/mockserver-netty/target:/app \
 *   eclipse-temurin:17-jre bash -c '
 *     apt-get update && apt-get install -y iptables curl
 *     # Start MockServer on port 1080 with an expectation
 *     java -jar /app/mockserver-netty-*-jar-with-dependencies.jar \
 *       -serverPort 1080 -logLevel INFO &
 *     sleep 5
 *     # Set an expectation matching any request
 *     curl -s -X PUT http://localhost:1080/mockserver/expectation -d "{
 *       \"httpRequest\": {\"path\": \"/.*\"},
 *       \"httpResponse\": {\"statusCode\": 200, \"body\": \"original-dst-ok\"}
 *     }"
 *     # Create iptables REDIRECT: traffic to 10.99.99.1:8080 -> localhost:1080
 *     iptables -t nat -A OUTPUT -d 10.99.99.1 -p tcp --dport 8080 \
 *       -j REDIRECT --to-port 1080
 *     # Add a route for the dummy IP so the kernel delivers to loopback
 *     ip addr add 10.99.99.1/32 dev lo
 *     # Issue a request to the dummy IP — MockServer should accept it
 *     # via epoll (SO_ORIGINAL_DST resolves 10.99.99.1:8080)
 *     RESULT=$(curl -s -o /dev/null -w "%{http_code}" http://10.99.99.1:8080/test)
 *     echo "HTTP status: $RESULT"
 *     [ "$RESULT" = "200" ] && echo "PASS" || echo "FAIL"
 *   '
 *       }</pre>
 *   </li>
 *   <li>The request to 10.99.99.1:8080 is redirected by iptables to
 *       MockServer's port 1080. With epoll transport active, the
 *       {@code SoOriginalDstResolver} reads the original destination
 *       (10.99.99.1:8080) from the socket via {@code getsockopt(SO_ORIGINAL_DST)}.
 *       The request matches the expectation and returns 200.</li>
 *   <li>Verify via MockServer logs or the verification API that the request
 *       was received. The original destination should appear in the log
 *       entries when transparent proxy mode is enabled.</li>
 * </ol>
 * <p>
 * <h3>Testcontainers-based automation (future work)</h3>
 * When this test is eventually automated with Testcontainers:
 * <ul>
 *   <li>Gate on {@code DockerClientFactory.instance().isDockerAvailable()}</li>
 *   <li>Use a {@code GenericContainer} with {@code NET_ADMIN} capability</li>
 *   <li>Copy the fat JAR into the container</li>
 *   <li>Execute the iptables setup and curl verification as container commands</li>
 *   <li>Assert the curl exit code and response body</li>
 * </ul>
 * Adding Testcontainers (org.testcontainers:testcontainers:1.20.6) as a
 * test-scoped dependency to mockserver-netty will be required.
 */
public class SoOriginalDstEndToEndIT {

    @Test
    public void shouldResolveOriginalDestinationViaIptablesRedirect() {
        // This test is a documented placeholder — see class Javadoc for the
        // manual verification procedure and the Testcontainers automation plan.
        // It cannot be reliably authored without iterative validation on Linux+Docker.
        Assume.assumeTrue(
            "SO_ORIGINAL_DST end-to-end test requires Linux + Docker + NET_ADMIN; " +
                "see class Javadoc for manual verification procedure",
            false
        );

        // When automated, the test body would:
        // 1. Start a GenericContainer with NET_ADMIN + iptables + curl + the fat JAR
        // 2. Set up iptables REDIRECT rule inside the container
        // 3. Start MockServer inside the container with an expectation
        // 4. curl the dummy IP from inside the container
        // 5. Assert HTTP 200 response (proving SO_ORIGINAL_DST resolution worked)
    }
}
