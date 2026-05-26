package org.mockserver.llm;

/**
 * Utility for encoding and decoding LLM conversation isolation metadata
 * in scenario names. The format is:
 * <pre>
 * __llm_conv_&lt;uuid&gt;__iso=&lt;kind&gt;:&lt;name&gt;
 * </pre>
 * where the {@code __iso=} suffix is optional. Without it, the scenario
 * uses legacy single-key behaviour.
 * <p>
 * Scenario names with the {@code __llm_conv_} prefix are reserved by the
 * LLM mocking framework; users must not create scenarios with this prefix
 * directly. Use {@link org.mockserver.client.LlmConversationBuilder} to
 * generate properly-formed scenario names.
 */
public final class LlmScenarioNames {

    public static final String ISOLATION_MARKER = "__iso=";

    private LlmScenarioNames() {
        // utility class
    }

    /**
     * Decode the isolation source from a scenario name that contains the isolation marker.
     *
     * @param scenarioName the scenario name to decode
     * @return the decoded IsolationSource, or null if no isolation marker is present
     */
    public static IsolationSource decodeIsolationSource(String scenarioName) {
        if (scenarioName == null) {
            return null;
        }
        int isoIndex = scenarioName.indexOf(ISOLATION_MARKER);
        if (isoIndex < 0) {
            return null;
        }
        String encoded = scenarioName.substring(isoIndex + ISOLATION_MARKER.length());
        return IsolationSource.decode(encoded);
    }

    /**
     * Extract the base scenario name (without isolation marker suffix) from a full scenario name.
     *
     * @param scenarioName the full scenario name
     * @return the base scenario name
     */
    public static String baseScenarioName(String scenarioName) {
        if (scenarioName == null) {
            return null;
        }
        int isoIndex = scenarioName.indexOf(ISOLATION_MARKER);
        if (isoIndex < 0) {
            return scenarioName;
        }
        return scenarioName.substring(0, isoIndex);
    }
}
