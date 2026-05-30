package org.mockserver.llm.cost;

import org.mockserver.model.Provider;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Estimated cost calculation for LLM API usage.
 *
 * <p>A pure lookup over a pricing table keyed on (provider, model) with
 * input/output rates in USD per million tokens. Returns {@code null} for an
 * unknown model so callers can distinguish "free/known-zero" from "unpriceable".
 *
 * <p><strong>Kept in sync with the dashboard pricing table</strong>
 * {@code mockserver-ui/src/lib/llmPricing.ts} — the prefixes, ordering, and
 * rates here mirror that file. When refreshing rates, update both.
 *
 * <p><strong>Pricing source / freshness:</strong> rates are public provider
 * list prices captured 2025-Q4. They WILL drift — treat any total as an
 * estimate, not an invoice. There is no automated drift check. Sources:
 * Anthropic (anthropic.com/pricing), OpenAI (openai.com/api/pricing),
 * Gemini (ai.google.dev/pricing).
 */
public final class LlmPricing {

    /** Input/output list price in USD per million tokens. */
    public static final class PricingEntry {
        private final double inputPerMillion;
        private final double outputPerMillion;

        public PricingEntry(double inputPerMillion, double outputPerMillion) {
            this.inputPerMillion = inputPerMillion;
            this.outputPerMillion = outputPerMillion;
        }

        public double getInputPerMillion() {
            return inputPerMillion;
        }

        public double getOutputPerMillion() {
            return outputPerMillion;
        }
    }

    // Prefix-keyed tables. The lookup walks each list in order and uses the
    // first entry whose key is a prefix of the model id (case-insensitive), so
    // MORE-SPECIFIC PREFIXES MUST COME FIRST (e.g. gpt-4o-mini before gpt-4o,
    // otherwise every gpt-4o-mini model would silently bill at gpt-4o's rate).
    private static final List<Map.Entry<String, PricingEntry>> ANTHROPIC_PRICING = Arrays.asList(
        entry("claude-opus-4", new PricingEntry(15.0, 75.0)),
        entry("claude-sonnet-4", new PricingEntry(3.0, 15.0)),
        entry("claude-haiku-4", new PricingEntry(0.8, 4.0))
    );

    private static final List<Map.Entry<String, PricingEntry>> OPENAI_PRICING = Arrays.asList(
        entry("gpt-4o-mini", new PricingEntry(0.15, 0.6)),
        entry("gpt-4o", new PricingEntry(2.5, 10.0)),
        entry("o3", new PricingEntry(15.0, 60.0))
    );

    private static final List<Map.Entry<String, PricingEntry>> GEMINI_PRICING = Arrays.asList(
        entry("gemini-2.0-flash", new PricingEntry(0.1, 0.4))
    );

    // Ollama is always free (local models).
    private static final PricingEntry OLLAMA_PRICING = new PricingEntry(0.0, 0.0);

    private LlmPricing() {
    }

    private static Map.Entry<String, PricingEntry> entry(String prefix, PricingEntry pricing) {
        return new SimpleImmutableEntry<>(prefix, pricing);
    }

    private static PricingEntry findPricing(List<Map.Entry<String, PricingEntry>> table, String model) {
        if (model == null) {
            return null;
        }
        String lower = model.toLowerCase();
        for (Map.Entry<String, PricingEntry> e : table) {
            if (lower.startsWith(e.getKey().toLowerCase())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Look up pricing for a provider and model, or {@code null} if unrecognised.
     */
    public static PricingEntry getPricing(Provider provider, String model) {
        if (provider == null) {
            return null;
        }
        switch (provider) {
            case ANTHROPIC:
                return findPricing(ANTHROPIC_PRICING, model);
            case BEDROCK:
                // Bedrock Anthropic model ids look like "anthropic.claude-sonnet-4-…".
                return findPricing(ANTHROPIC_PRICING, model == null ? null : model.replaceFirst("^anthropic\\.", ""));
            case OPENAI:
            case AZURE_OPENAI:
                // Azure OpenAI uses user-defined deployment names rather than canonical
                // model ids, so this returns null for most real Azure deployments.
                return findPricing(OPENAI_PRICING, model);
            case OPENAI_RESPONSES:
                return findPricing(OPENAI_PRICING, model);
            case GEMINI:
                return findPricing(GEMINI_PRICING, model);
            case OLLAMA:
                return OLLAMA_PRICING;
            default:
                return null;
        }
    }

    /**
     * Estimate the cost in USD for a provider, model, and token counts.
     *
     * @return the estimated cost in USD, or {@code null} if the model is
     * unknown. Returns {@code 0.0} for zero tokens on a known (priced) model.
     */
    public static Double estimateCostUsd(Provider provider, String model, long inputTokens, long outputTokens) {
        PricingEntry pricing = getPricing(provider, model);
        if (pricing == null) {
            return null;
        }
        if (inputTokens == 0 && outputTokens == 0) {
            return 0.0;
        }
        return (inputTokens / 1_000_000.0) * pricing.getInputPerMillion()
            + (outputTokens / 1_000_000.0) * pricing.getOutputPerMillion();
    }
}
