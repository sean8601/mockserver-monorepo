package org.mockserver.netty.integration;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Boots a MockServer instance in a forked JVM via {@code java -jar} against
 * the locally-built {@code mockserver-netty-no-dependencies-X.Y.Z.jar}.
 *
 * <p>The runner deliberately uses only the JDK ({@link ProcessBuilder} and
 * {@link HttpClient}) and avoids loading {@code MockServerClient} or any
 * shaded MockServer class on the test JVM's classpath — otherwise the test
 * JVM would end up with two copies of {@code org.mockserver.httpclient.
 * NettyHttpClient}: one unshaded from {@code mockserver-core}, one shaded
 * from this module's own jar — and binary-incompatible signatures on the
 * Netty types in the constructor cause a runtime {@code NoSuchMethodError}.
 */
public class NoDependenciesJarRunner {

    public static final boolean DEBUG = false;
    private static final String NO_DEPS_ARTIFACT_PREFIX = "mockserver-netty-no-dependencies-";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(250);

    private final Process process;
    private final int port;

    private NoDependenciesJarRunner(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public Process getProcess() {
        return process;
    }

    /**
     * Locate the shaded jar built by this module, fork a JVM to run it, and
     * block until {@code /mockserver/status} responds 200 (or fail after
     * {@link #READY_TIMEOUT}).
     */
    public static NoDependenciesJarRunner startServerUsingNoDependenciesJar(int mockServerPort) {
        File jarFile = locateShadedJar();
        List<String> arguments = new ArrayList<>(Collections.singletonList(getJavaBin()));
        arguments.add("-Dfile.encoding=UTF-8");
        if (DEBUG) {
            arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005");
        }
        arguments.add("-jar");
        arguments.add(jarFile.getAbsolutePath());
        arguments.add("-serverPort");
        arguments.add(Integer.toString(mockServerPort));

        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        processBuilder.redirectErrorStream(true);
        // Stream the child's stdout/stderr to the test JVM's stdout so any
        // CLI parsing errors or boot failures land in the surefire/failsafe
        // log instead of being silently swallowed.
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to start " + jarFile.getAbsolutePath(), ioe);
        }

        waitUntilReady(mockServerPort, process);
        return new NoDependenciesJarRunner(process, mockServerPort);
    }

    /** Stop the forked JVM. Idempotent. */
    public void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static File locateShadedJar() {
        String version = System.getProperty("project.version", "");
        String basedir = System.getProperty("project.basedir", ".");
        if (version.isEmpty()) {
            // Fall back to whatever version sits next to the test class on disk.
            File targetDir = new File(basedir, "target");
            File[] candidates = targetDir.listFiles((d, name) ->
                name.startsWith(NO_DEPS_ARTIFACT_PREFIX) && name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
            if (candidates != null && candidates.length > 0) {
                return candidates[0];
            }
            throw new RuntimeException("Cannot locate " + NO_DEPS_ARTIFACT_PREFIX + "*.jar under " + targetDir.getAbsolutePath());
        }
        File jarFile = new File(basedir + "/target/" + NO_DEPS_ARTIFACT_PREFIX + version + ".jar");
        if (jarFile.exists()) {
            return jarFile;
        }
        File alt = new File(basedir + "/mockserver-netty-no-dependencies/target/" + NO_DEPS_ARTIFACT_PREFIX + version + ".jar");
        if (alt.exists()) {
            return alt;
        }
        throw new RuntimeException("Can't find jar file in the following locations: " +
            Arrays.asList(jarFile.getAbsolutePath(), alt.getAbsolutePath()));
    }

    private static void waitUntilReady(int port, Process process) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/status"))
            .timeout(Duration.ofSeconds(2))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();

        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new RuntimeException("MockServer process exited before becoming ready (exit code "
                    + process.exitValue() + "). Check the test log for the jar's stdout.");
            }
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException notListeningYet) {
                // server not yet listening — keep polling
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("Interrupted while waiting for MockServer to start", ie);
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("Interrupted while waiting for MockServer to start", ie);
            }
        }
        process.destroyForcibly();
        throw new RuntimeException("MockServer did not respond on port " + port + " within " + READY_TIMEOUT);
    }

    private static String getJavaBin() {
        File javaHomeDirectory = new File(System.getProperty("java.home"));
        for (String javaExecutable : new String[]{"java", "java.exe"}) {
            File javaExeLocation = new File(javaHomeDirectory, "bin" + File.separator + javaExecutable);
            if (javaExeLocation.exists() && javaExeLocation.isFile()) {
                return javaExeLocation.getAbsolutePath();
            }
        }
        return "java";
    }
}
