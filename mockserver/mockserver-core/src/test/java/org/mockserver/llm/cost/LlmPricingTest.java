package org.mockserver.llm.cost;

import org.junit.Test;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class LlmPricingTest {

    private static final double EPS = 1e-9;

    @Test
    public void shouldPriceAnthropicSonnet() {
        // 1M input @ $3, 1M output @ $15 = $18
        Double cost = LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-sonnet-4-20250514", 1_000_000, 1_000_000);
        assertThat(cost, is(closeTo(18.0, EPS)));
    }

    @Test
    public void shouldPriceAnthropicOpusAndHaiku() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-opus-4-1", 1_000_000, 0),
            is(closeTo(15.0, EPS)));
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-haiku-4", 0, 1_000_000),
            is(closeTo(4.0, EPS)));
    }

    @Test
    public void shouldDistinguishGpt4oMiniFromGpt4o() {
        // ordering matters: gpt-4o-mini must NOT bill at gpt-4o's rate
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "gpt-4o-mini", 1_000_000, 0),
            is(closeTo(0.15, EPS)));
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "gpt-4o", 1_000_000, 0),
            is(closeTo(2.5, EPS)));
    }

    @Test
    public void shouldPriceO3() {
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "o3", 0, 1_000_000),
            is(closeTo(60.0, EPS)));
    }

    @Test
    public void shouldPriceGemini() {
        assertThat(LlmPricing.estimateCostUsd(Provider.GEMINI, "gemini-2.0-flash", 1_000_000, 1_000_000),
            is(closeTo(0.5, EPS)));
    }

    @Test
    public void shouldPriceOllamaAsFree() {
        assertThat(LlmPricing.estimateCostUsd(Provider.OLLAMA, "llama3", 5_000_000, 5_000_000),
            is(closeTo(0.0, EPS)));
    }

    @Test
    public void shouldPriceBedrockViaAnthropicAfterStrippingPrefix() {
        assertThat(LlmPricing.estimateCostUsd(Provider.BEDROCK, "anthropic.claude-sonnet-4-20250514", 1_000_000, 0),
            is(closeTo(3.0, EPS)));
    }

    @Test
    public void shouldBeCaseInsensitive() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "CLAUDE-SONNET-4", 1_000_000, 0),
            is(closeTo(3.0, EPS)));
    }

    @Test
    public void shouldReturnZeroForZeroTokensOnKnownModel() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, "claude-sonnet-4", 0, 0),
            is(closeTo(0.0, EPS)));
    }

    @Test
    public void shouldReturnNullForUnknownModel() {
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENAI, "some-future-model", 100, 100), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForAzureDeploymentName() {
        // Azure deployments are user-defined names, not canonical model ids
        assertThat(LlmPricing.estimateCostUsd(Provider.AZURE_OPENAI, "my-gpt4o-prod", 100, 100), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullModel() {
        assertThat(LlmPricing.estimateCostUsd(Provider.ANTHROPIC, null, 100, 100), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullProvider() {
        assertThat(LlmPricing.estimateCostUsd(null, "claude-sonnet-4", 100, 100), is(nullValue()));
    }
}
