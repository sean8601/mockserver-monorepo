package org.mockserver.configuration;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.ConfigurationProperties.REDACTED_VALUE;
import static org.mockserver.configuration.ConfigurationProperties.SOURCE_DEFAULT;
import static org.mockserver.configuration.ConfigurationProperties.SOURCE_SYSTEM_PROPERTY;

/**
 * End-to-end verification of the effective-configuration diagnostic against REAL system
 * properties: a non-sensitive key set via a JVM system property reports its value and the
 * {@code system-property} source, a sensitive key is redacted, and an unset key reports the
 * built-in default.
 *
 * <p>This test MUTATES global state (sets JVM system properties), so it is registered in the
 * sequential Surefire phase and excluded from the parallel phase in mockserver-core/pom.xml,
 * and restores every property it touches in a {@code finally} block.</p>
 */
public class ConfigurationPropertiesEffectiveConfigGlobalStateTest {

    private static final String NON_SENSITIVE_KEY = "mockserver.maxExpectations";
    private static final String SENSITIVE_KEY = "mockserver.llmApiKey";
    // A recognised key we deliberately leave unset so it reports its built-in default.
    private static final String DEFAULT_KEY = "mockserver.metricsEnabled";

    private static Optional<ConfigurationProperties.ResolvedProperty> find(List<ConfigurationProperties.ResolvedProperty> properties, String name) {
        return properties.stream().filter(property -> name.equals(property.getName())).findFirst();
    }

    @Test
    public void reportsValueAndSourcePerKeyAndRedactsSecrets() throws Exception {
        String previousNonSensitive = System.getProperty(NON_SENSITIVE_KEY);
        String previousSensitive = System.getProperty(SENSITIVE_KEY);
        String previousDefault = System.getProperty(DEFAULT_KEY);
        // effectiveConfiguration is cache-first, so a leaked cached value would win over the
        // system property we set here — clear the cache entries so the tiers are authoritative.
        String previousCachedNonSensitive = getCacheEntry(NON_SENSITIVE_KEY);
        String previousCachedSensitive = getCacheEntry(SENSITIVE_KEY);
        String previousCachedDefault = getCacheEntry(DEFAULT_KEY);
        try {
            clearCacheEntry(NON_SENSITIVE_KEY);
            clearCacheEntry(SENSITIVE_KEY);
            clearCacheEntry(DEFAULT_KEY);
            System.setProperty(NON_SENSITIVE_KEY, "4242");
            System.setProperty(SENSITIVE_KEY, "super-secret-token-value");
            // Ensure the default key really is unset for this assertion.
            System.clearProperty(DEFAULT_KEY);

            List<ConfigurationProperties.ResolvedProperty> properties = ConfigurationProperties.effectiveConfiguration();

            // Non-sensitive key set via system property: exact value and system-property source.
            ConfigurationProperties.ResolvedProperty nonSensitive = find(properties, NON_SENSITIVE_KEY)
                .orElseThrow(() -> new AssertionError("expected " + NON_SENSITIVE_KEY + " in effective configuration"));
            assertThat(nonSensitive.getValue(), is("4242"));
            assertThat(nonSensitive.getSource(), is(SOURCE_SYSTEM_PROPERTY));

            // Sensitive key set via system property: value redacted, source still reported.
            ConfigurationProperties.ResolvedProperty sensitive = find(properties, SENSITIVE_KEY)
                .orElseThrow(() -> new AssertionError("expected " + SENSITIVE_KEY + " in effective configuration"));
            assertThat(sensitive.getValue(), is(REDACTED_VALUE));
            assertThat(sensitive.getValue(), not(containsString("super-secret-token-value")));
            assertThat(sensitive.getSource(), is(SOURCE_SYSTEM_PROPERTY));

            // Unset key reports the built-in default.
            ConfigurationProperties.ResolvedProperty unset = find(properties, DEFAULT_KEY)
                .orElseThrow(() -> new AssertionError("expected " + DEFAULT_KEY + " in effective configuration"));
            assertThat(unset.getValue(), is("(default)"));
            assertThat(unset.getSource(), is(SOURCE_DEFAULT));
        } finally {
            restoreCacheEntry(NON_SENSITIVE_KEY, previousCachedNonSensitive);
            restoreCacheEntry(SENSITIVE_KEY, previousCachedSensitive);
            restoreCacheEntry(DEFAULT_KEY, previousCachedDefault);
            restore(NON_SENSITIVE_KEY, previousNonSensitive);
            restore(SENSITIVE_KEY, previousSensitive);
            restore(DEFAULT_KEY, previousDefault);
        }
    }

    @Test
    public void textOutputRedactsSecretsAndShowsSources() throws Exception {
        String previousNonSensitive = System.getProperty(NON_SENSITIVE_KEY);
        String previousSensitive = System.getProperty(SENSITIVE_KEY);
        String previousCachedNonSensitive = getCacheEntry(NON_SENSITIVE_KEY);
        String previousCachedSensitive = getCacheEntry(SENSITIVE_KEY);
        try {
            clearCacheEntry(NON_SENSITIVE_KEY);
            clearCacheEntry(SENSITIVE_KEY);
            System.setProperty(NON_SENSITIVE_KEY, "777");
            System.setProperty(SENSITIVE_KEY, "do-not-print-me");

            String text = ConfigurationProperties.effectiveConfigurationAsText();

            assertThat(text, containsString(NON_SENSITIVE_KEY + " = 777   [" + SOURCE_SYSTEM_PROPERTY + "]"));
            assertThat(text, containsString(SENSITIVE_KEY + " = " + REDACTED_VALUE + "   [" + SOURCE_SYSTEM_PROPERTY + "]"));
            assertThat(text, not(containsString("do-not-print-me")));
        } finally {
            restoreCacheEntry(NON_SENSITIVE_KEY, previousCachedNonSensitive);
            restoreCacheEntry(SENSITIVE_KEY, previousCachedSensitive);
            restore(NON_SENSITIVE_KEY, previousNonSensitive);
            restore(SENSITIVE_KEY, previousSensitive);
        }
    }

    @Test
    public void jsonOutputRedactsSecretsAndShowsSources() throws Exception {
        String previousSensitive = System.getProperty(SENSITIVE_KEY);
        String previousCachedSensitive = getCacheEntry(SENSITIVE_KEY);
        try {
            clearCacheEntry(SENSITIVE_KEY);
            System.setProperty(SENSITIVE_KEY, "do-not-print-me-json");

            String json = ConfigurationProperties.effectiveConfigurationAsJson();

            assertThat(json, containsString("\"name\":\"" + SENSITIVE_KEY + "\""));
            assertThat(json, containsString("\"value\":\"" + REDACTED_VALUE + "\""));
            assertThat(json, containsString("\"source\":\"" + SOURCE_SYSTEM_PROPERTY + "\""));
            assertThat(json, not(containsString("do-not-print-me-json")));
        } finally {
            restoreCacheEntry(SENSITIVE_KEY, previousCachedSensitive);
            restore(SENSITIVE_KEY, previousSensitive);
        }
    }

    @Test
    public void reportsCachedRuntimeValueSetViaProgrammaticSetter() throws Exception {
        // readPropertyHierarchically is cache-first, so a programmatic setter is what the server
        // actually uses; the diagnostic must reflect the cached value, not a stale tier value.
        String previousNonSensitive = System.getProperty(NON_SENSITIVE_KEY);
        try {
            ConfigurationProperties.maxExpectations(31337);

            ConfigurationProperties.ResolvedProperty resolved = find(ConfigurationProperties.effectiveConfiguration(), NON_SENSITIVE_KEY)
                .orElseThrow(() -> new AssertionError("expected " + NON_SENSITIVE_KEY + " in effective configuration"));
            assertThat(resolved.getValue(), is("31337"));
            // The setter also sets the JVM system property, so the source is attributed to that tier.
            assertThat(resolved.getSource(), is(SOURCE_SYSTEM_PROPERTY));
        } finally {
            // Clear BOTH the cache entry and the system property the setter wrote.
            clearCacheEntry(NON_SENSITIVE_KEY);
            restore(NON_SENSITIVE_KEY, previousNonSensitive);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> propertyCache() throws Exception {
        java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        return cache instanceof java.util.Map ? (java.util.Map<String, String>) cache : null;
    }

    private static String getCacheEntry(String key) throws Exception {
        java.util.Map<String, String> cache = propertyCache();
        return cache != null ? cache.get(key) : null;
    }

    private static void clearCacheEntry(String key) throws Exception {
        java.util.Map<String, String> cache = propertyCache();
        if (cache != null) {
            cache.remove(key);
        }
    }

    private static void restoreCacheEntry(String key, String previousValue) throws Exception {
        java.util.Map<String, String> cache = propertyCache();
        if (cache != null) {
            if (previousValue != null) {
                cache.put(key, previousValue);
            } else {
                cache.remove(key);
            }
        }
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }
}
