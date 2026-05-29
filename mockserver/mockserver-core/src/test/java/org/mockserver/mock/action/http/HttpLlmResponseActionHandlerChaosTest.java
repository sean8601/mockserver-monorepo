package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LlmChaosProfile;
import org.mockserver.model.Provider;
import org.mockserver.model.SseEvent;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class HttpLlmResponseActionHandlerChaosTest {

    private final HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());

    private static HttpLlmResponse withChaos(LlmChaosProfile chaos) {
        return HttpLlmResponse.llmResponse().withProvider(Provider.OPENAI).withChaos(chaos);
    }

    @Test
    public void noChaosProfileProducesNoError() {
        assertThat(handler.chaosErrorResponseOrNull(HttpLlmResponse.llmResponse().withProvider(Provider.OPENAI)),
            is(nullValue()));
    }

    @Test
    public void errorStatusWithoutProbabilityAlwaysFires() {
        HttpResponse response = handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorStatus(429).withRetryAfter("30")));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(429));
        assertThat(response.getFirstHeader("Retry-After"), is("30"));
        assertThat(response.getBodyAsString(), containsString("chaos_injected"));
    }

    @Test
    public void zeroProbabilityNeverFires() {
        assertThat(handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorStatus(500).withErrorProbability(0.0))), is(nullValue()));
    }

    @Test
    public void probabilityOneAlwaysFires() {
        assertThat(handler.chaosErrorResponseOrNull(withChaos(
            LlmChaosProfile.llmChaosProfile().withErrorStatus(529).withErrorProbability(1.0))), is(notNullValue()));
    }

    @Test
    public void seededFractionalProbabilityIsDeterministic() {
        LlmChaosProfile chaos = LlmChaosProfile.llmChaosProfile().withErrorStatus(500).withErrorProbability(0.5).withSeed(42L);
        boolean first = handler.chaosErrorResponseOrNull(withChaos(chaos)) != null;
        boolean second = handler.chaosErrorResponseOrNull(withChaos(chaos)) != null;
        assertThat(first, is(second));
    }

    private static List<SseEvent> events(int n) {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add(SseEvent.sseEvent().withData("{\"i\":" + i + "}"));
        }
        return events;
    }

    @Test
    public void truncationKeepsLeadingFraction() {
        List<SseEvent> result = handler.applyStreamingChaos(events(10),
            LlmChaosProfile.llmChaosProfile().withTruncateMode(LlmChaosProfile.TruncateMode.MID_STREAM).withTruncateAtFraction(0.3));
        assertThat(result.size(), is(3));
    }

    @Test
    public void truncationDefaultsToHalf() {
        List<SseEvent> result = handler.applyStreamingChaos(events(10),
            LlmChaosProfile.llmChaosProfile().withTruncateMode(LlmChaosProfile.TruncateMode.MID_STREAM));
        assertThat(result.size(), is(5));
    }

    @Test
    public void malformedSseAppendsBrokenChunk() {
        List<SseEvent> result = handler.applyStreamingChaos(events(2),
            LlmChaosProfile.llmChaosProfile().withMalformedSse(true));
        assertThat(result.size(), is(3));
        assertThat(result.get(result.size() - 1).getData(), is("{\"malformed\":true"));
    }

    @Test
    public void truncateThenMalformedCombine() {
        List<SseEvent> result = handler.applyStreamingChaos(events(10),
            LlmChaosProfile.llmChaosProfile()
                .withTruncateMode(LlmChaosProfile.TruncateMode.MID_STREAM).withTruncateAtFraction(0.5)
                .withMalformedSse(true));
        // 5 kept + 1 malformed
        assertThat(result.size(), is(6));
    }

    @Test
    public void noStreamingChaosReturnsInputUnchanged() {
        List<SseEvent> input = events(4);
        assertThat(handler.applyStreamingChaos(input, LlmChaosProfile.llmChaosProfile()).size(), is(4));
        assertThat(handler.applyStreamingChaos(input, null).size(), is(4));
    }
}
