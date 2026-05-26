package org.mockserver.llm.codec;

import org.junit.Test;
import org.mockserver.model.StreamingPhysics;
import org.mockserver.model.Usage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LlmFailureValidationTest {

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTokensPerSecondZero() {
        StreamingPhysics.streamingPhysics().withTokensPerSecond(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTokensPerSecondNegative() {
        StreamingPhysics.streamingPhysics().withTokensPerSecond(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTokensPerSecondAbove10000() {
        StreamingPhysics.streamingPhysics().withTokensPerSecond(10001);
    }

    @Test
    public void shouldAcceptTokensPerSecondAtBounds() {
        // lower bound
        StreamingPhysics p1 = StreamingPhysics.streamingPhysics().withTokensPerSecond(1);
        assertThat(p1.getTokensPerSecond(), is(1));

        // upper bound
        StreamingPhysics p2 = StreamingPhysics.streamingPhysics().withTokensPerSecond(10000);
        assertThat(p2.getTokensPerSecond(), is(10000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeJitter() {
        StreamingPhysics.streamingPhysics().withJitter(-0.01);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectJitterAboveOne() {
        StreamingPhysics.streamingPhysics().withJitter(1.01);
    }

    @Test
    public void shouldAcceptJitterAtBounds() {
        // lower bound
        StreamingPhysics p1 = StreamingPhysics.streamingPhysics().withJitter(0.0);
        assertThat(p1.getJitter(), is(0.0));

        // upper bound
        StreamingPhysics p2 = StreamingPhysics.streamingPhysics().withJitter(1.0);
        assertThat(p2.getJitter(), is(1.0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeInputTokens() {
        Usage.usage().withInputTokens(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeOutputTokens() {
        Usage.usage().withOutputTokens(-1);
    }

    @Test
    public void shouldAcceptZeroTokens() {
        Usage u = Usage.usage().withInputTokens(0).withOutputTokens(0);
        assertThat(u.getInputTokens(), is(0));
        assertThat(u.getOutputTokens(), is(0));
    }

    @Test
    public void shouldAcceptNullTokensPerSecond() {
        StreamingPhysics p = StreamingPhysics.streamingPhysics().withTokensPerSecond(null);
        assertThat(p.getTokensPerSecond() == null, is(true));
    }

    @Test
    public void shouldAcceptNullJitter() {
        StreamingPhysics p = StreamingPhysics.streamingPhysics().withJitter(null);
        assertThat(p.getJitter() == null, is(true));
    }
}
