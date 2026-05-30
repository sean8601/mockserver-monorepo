package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the two-phase Surefire configuration in {@code mockserver-core/pom.xml} that lets the unit
 * suite run with {@code parallel=classes}. A small set of test classes mutate JVM-global state
 * (ConfigurationProperties system properties, the static Prometheus Metrics registry, or globally
 * fixed time for the event-log disruptor thread) and therefore run in a sequential phase instead of
 * the parallel phase.
 *
 * <p>The dangerous failure mode is drift between the two lists: if a class is excluded from the
 * parallel phase but NOT added to the sequential phase's includes, it silently stops running at all -
 * it would never be executed by either phase and its coverage would vanish unnoticed. This test fails
 * the build if the parallel-phase {@code <excludes>} (minus the integration-test glob) and the
 * sequential-phase {@code <includes>} are not exactly the same set. It is the guardrail that was
 * missing when parallel execution was first enabled and then reverted: it makes list drift a loud,
 * immediate build failure rather than a silent gap.</p>
 */
public class ParallelStaticStateGuardTest {

    // Surefire runs tests with the module directory as the working directory, so a relative path to
    // pom.xml resolves correctly. This breaks only if someone sets a custom <workingDirectory> on
    // surefire - which they should not, and which would itself need a deliberate change here.
    private static final Path POM = Paths.get("pom.xml");
    private static final String INTEGRATION_TEST_GLOB = "**/*IntegrationTest.java";

    @Test
    public void parallelExclusionsExactlyMatchSequentialInclusions() throws IOException {
        assertTrue("expected to find mockserver-core/pom.xml at " + POM.toAbsolutePath(), Files.exists(POM));
        String pom = new String(Files.readAllBytes(POM));

        String pluginConfig = surefirePluginLevelConfiguration(pom);
        Set<String> parallelExcludes = new TreeSet<>(extractTags(pluginConfig, "exclude"));
        parallelExcludes.remove(INTEGRATION_TEST_GLOB);

        String sequentialExecution = sequentialTestsExecution(pom);
        Set<String> sequentialIncludes = new TreeSet<>(extractTags(sequentialExecution, "include"));

        // Guard the parallelism settings themselves, not just the class lists: a future edit that
        // silently dropped parallel=classes (or flipped the sequential phase to anything other than
        // parallel=none) would otherwise pass while defeating the whole point of this configuration.
        assertTrue(
            "the plugin-level surefire <configuration> must set <parallel>classes</parallel> - the "
                + "parallel unit-test phase. Found:\n" + pluginConfig,
            pluginConfig.contains("<parallel>classes</parallel>"));
        assertTrue(
            "the plugin-level surefire <configuration> must set a <threadCount>",
            pluginConfig.contains("<threadCount>"));
        assertTrue(
            "the sequential-tests execution must set <parallel>none</parallel> so its JVM-global-state "
                + "classes never run concurrently. Found:\n" + sequentialExecution,
            sequentialExecution.contains("<parallel>none</parallel>"));

        assertEquals(
            "The set of classes excluded from the parallel Surefire phase must exactly equal the set "
                + "included in the sequential phase, otherwise a class runs twice or (worse) never. "
                + "Update mockserver-core/pom.xml so the parallel <excludes> and sequential <includes> "
                + "match. Parallel-excluded but not run sequentially (would NEVER run): "
                + difference(parallelExcludes, sequentialIncludes)
                + "; run sequentially but not excluded from parallel (would run TWICE): "
                + difference(sequentialIncludes, parallelExcludes),
            sequentialIncludes,
            parallelExcludes);
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> diff = new TreeSet<>(a);
        diff.removeAll(b);
        return diff;
    }

    /** The plugin-level {@code <configuration>} (the default-test phase), excluding any nested execution configs. */
    private static String surefirePluginLevelConfiguration(String pom) {
        String surefire = blockAround(pom, "maven-surefire-plugin");
        // strip the <executions>...</executions> so we only read the plugin-level <configuration>
        String withoutExecutions = surefire.replaceAll("(?s)<executions>.*?</executions>", "");
        Matcher m = Pattern.compile("(?s)<configuration[^>]*>(.*?)</configuration>").matcher(withoutExecutions);
        assertTrue("could not find plugin-level <configuration> for maven-surefire-plugin", m.find());
        return m.group(1);
    }

    /** The {@code <configuration>} of the {@code sequential-tests} execution. */
    private static String sequentialTestsExecution(String pom) {
        Matcher m = Pattern
            .compile("(?s)<execution>\\s*<id>sequential-tests</id>(.*?)</execution>")
            .matcher(pom);
        assertTrue("could not find <execution> with <id>sequential-tests</id> in pom.xml", m.find());
        return m.group(1);
    }

    private static String blockAround(String pom, String marker) {
        int markerIndex = pom.indexOf(marker);
        assertTrue("could not find " + marker + " in pom.xml", markerIndex >= 0);
        int start = pom.lastIndexOf("<plugin>", markerIndex);
        int end = pom.indexOf("</plugin>", markerIndex);
        assertTrue("could not bound the <plugin> element for " + marker, start >= 0 && end > start);
        return pom.substring(start, end);
    }

    private static Set<String> extractTags(String xml, String tag) {
        Set<String> values = new TreeSet<>();
        Matcher m = Pattern.compile("<" + tag + ">\\s*(.*?)\\s*</" + tag + ">").matcher(xml);
        while (m.find()) {
            values.add(m.group(1).trim());
        }
        return values;
    }
}
