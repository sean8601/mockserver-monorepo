package org.mockserver.model;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpChaosProfileTest {

    // --- validation tests ---

    @Test
    public void withErrorProbabilityAcceptsNull() {
        httpChaosProfile().withErrorProbability(null);
    }

    @Test
    public void withErrorProbabilityAcceptsValidRange() {
        httpChaosProfile().withErrorProbability(0.0);
        httpChaosProfile().withErrorProbability(0.5);
        httpChaosProfile().withErrorProbability(1.0);
    }

    @Test
    public void withErrorProbabilityRejectsBelowZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorProbability(-0.1));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got -0.1"));
    }

    @Test
    public void withErrorProbabilityRejectsAboveOne() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorProbability(1.1));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got 1.1"));
    }

    @Test
    public void withErrorProbabilityRejectsNaN() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorProbability(Double.NaN));
        assertThat(exception.getMessage(), is("errorProbability must be between 0.0 and 1.0, got NaN"));
    }

    @Test
    public void withErrorStatusAcceptsNull() {
        httpChaosProfile().withErrorStatus(null);
    }

    @Test
    public void withErrorStatusAcceptsValidRange() {
        httpChaosProfile().withErrorStatus(100);
        httpChaosProfile().withErrorStatus(503);
        httpChaosProfile().withErrorStatus(599);
    }

    @Test
    public void withErrorStatusRejectsBelowOneHundred() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorStatus(99));
        assertThat(exception.getMessage(), is("errorStatus must be between 100 and 599, got 99"));
    }

    @Test
    public void withErrorStatusRejectsAboveFiveNineNine() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withErrorStatus(600));
        assertThat(exception.getMessage(), is("errorStatus must be between 100 and 599, got 600"));
    }

    // --- equals/hashCode tests ---

    @Test
    public void equalsIsReflexive() {
        HttpChaosProfile profile = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.5)
            .withRetryAfter("30")
            .withLatency(Delay.milliseconds(100))
            .withSeed(42L);
        assertThat(profile, is(equalTo(profile)));
    }

    @Test
    public void equalProfilesAreEqual() {
        HttpChaosProfile a = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.5)
            .withRetryAfter("30")
            .withLatency(Delay.milliseconds(100))
            .withSeed(42L);
        HttpChaosProfile b = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.5)
            .withRetryAfter("30")
            .withLatency(Delay.milliseconds(100))
            .withSeed(42L);
        assertThat(a, is(equalTo(b)));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    @Test
    public void differentProfilesAreNotEqual() {
        HttpChaosProfile a = httpChaosProfile().withErrorStatus(503);
        HttpChaosProfile b = httpChaosProfile().withErrorStatus(500);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void latencyIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withLatency(Delay.milliseconds(100));
        HttpChaosProfile b = httpChaosProfile().withLatency(Delay.milliseconds(200));
        assertThat(a, is(not(equalTo(b))));
    }

    // --- serialization round-trip test ---

    @Test
    public void expectationWithChaosRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30")
                .withSeed(42L));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getErrorStatus(), is(503));
        assertThat(deserialized[0].getChaos().getErrorProbability(), is(1.0));
        assertThat(deserialized[0].getChaos().getRetryAfter(), is("30"));
        assertThat(deserialized[0].getChaos().getSeed(), is(42L));
    }

    @Test
    public void expectationWithChaosLatencyRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withLatency(Delay.milliseconds(500)));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getLatency().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(deserialized[0].getChaos().getLatency().getValue(), is(500L));
    }

    @Test
    public void expectationWithoutChaosDeserializesWithNullChaos() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos() == null, is(true));
    }

    // --- succeedFirst validation tests ---

    @Test
    public void withSucceedFirstAcceptsNull() {
        httpChaosProfile().withSucceedFirst(null);
    }

    @Test
    public void withSucceedFirstAcceptsZero() {
        httpChaosProfile().withSucceedFirst(0);
    }

    @Test
    public void withSucceedFirstAcceptsPositive() {
        httpChaosProfile().withSucceedFirst(5);
    }

    @Test
    public void withSucceedFirstRejectsNegative() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withSucceedFirst(-1));
        assertThat(exception.getMessage(), is("succeedFirst must be >= 0, got -1"));
    }

    // --- failRequestCount validation tests ---

    @Test
    public void withFailRequestCountAcceptsNull() {
        httpChaosProfile().withFailRequestCount(null);
    }

    @Test
    public void withFailRequestCountAcceptsOne() {
        httpChaosProfile().withFailRequestCount(1);
    }

    @Test
    public void withFailRequestCountAcceptsLargeValue() {
        httpChaosProfile().withFailRequestCount(1000);
    }

    @Test
    public void withFailRequestCountRejectsZero() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withFailRequestCount(0));
        assertThat(exception.getMessage(), is("failRequestCount must be >= 1, got 0"));
    }

    @Test
    public void withFailRequestCountRejectsNegative() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> httpChaosProfile().withFailRequestCount(-1));
        assertThat(exception.getMessage(), is("failRequestCount must be >= 1, got -1"));
    }

    // --- countWindowEligible tests ---

    @Test
    public void countWindowEligibleBothNullAlwaysEligible() {
        HttpChaosProfile profile = httpChaosProfile();
        assertThat(profile.countWindowEligible(0), is(true));
        assertThat(profile.countWindowEligible(1), is(true));
        assertThat(profile.countWindowEligible(100), is(true));
    }

    @Test
    public void countWindowEligibleSucceedFirstOnly() {
        HttpChaosProfile profile = httpChaosProfile().withSucceedFirst(2);
        assertThat(profile.countWindowEligible(1), is(false));
        assertThat(profile.countWindowEligible(2), is(false));
        assertThat(profile.countWindowEligible(3), is(true));
        assertThat(profile.countWindowEligible(100), is(true));
    }

    @Test
    public void countWindowEligibleFailRequestCountOnly() {
        HttpChaosProfile profile = httpChaosProfile().withFailRequestCount(2);
        assertThat(profile.countWindowEligible(1), is(true));
        assertThat(profile.countWindowEligible(2), is(true));
        assertThat(profile.countWindowEligible(3), is(false));
    }

    @Test
    public void countWindowEligibleBothFieldsSet() {
        HttpChaosProfile profile = httpChaosProfile()
            .withSucceedFirst(2)
            .withFailRequestCount(3);
        assertThat(profile.countWindowEligible(1), is(false));
        assertThat(profile.countWindowEligible(2), is(false));
        assertThat(profile.countWindowEligible(3), is(true));
        assertThat(profile.countWindowEligible(4), is(true));
        assertThat(profile.countWindowEligible(5), is(true));
        assertThat(profile.countWindowEligible(6), is(false));
    }

    @Test
    public void countWindowEligibleFailOnlyNthRequest() {
        // succeedFirst=2, failRequestCount=1 → only #3 fails
        HttpChaosProfile profile = httpChaosProfile()
            .withSucceedFirst(2)
            .withFailRequestCount(1);
        assertThat(profile.countWindowEligible(1), is(false));
        assertThat(profile.countWindowEligible(2), is(false));
        assertThat(profile.countWindowEligible(3), is(true));
        assertThat(profile.countWindowEligible(4), is(false));
    }

    // --- equals/hashCode with new fields ---

    @Test
    public void succeedFirstIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withSucceedFirst(1);
        HttpChaosProfile b = httpChaosProfile().withSucceedFirst(2);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void failRequestCountIncludedInEquals() {
        HttpChaosProfile a = httpChaosProfile().withFailRequestCount(1);
        HttpChaosProfile b = httpChaosProfile().withFailRequestCount(2);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    public void equalProfilesWithCountFieldsAreEqual() {
        HttpChaosProfile a = httpChaosProfile()
            .withErrorStatus(503)
            .withSucceedFirst(2)
            .withFailRequestCount(3);
        HttpChaosProfile b = httpChaosProfile()
            .withErrorStatus(503)
            .withSucceedFirst(2)
            .withFailRequestCount(3);
        assertThat(a, is(equalTo(b)));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    // --- serialization round-trip with count fields ---

    @Test
    public void expectationWithChaosCountFieldsRoundTripsViaSerializer() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2)
                .withFailRequestCount(5));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos(), is(equalTo(original.getChaos())));
        assertThat(deserialized[0].getChaos().getSucceedFirst(), is(2));
        assertThat(deserialized[0].getChaos().getFailRequestCount(), is(5));
    }

    @Test
    public void expectationWithChaosNoCountFieldsRoundTripsWithNulls() {
        ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

        Expectation original = new Expectation(request("/test"))
            .thenRespond(response("ok"))
            .withChaos(httpChaosProfile()
                .withErrorStatus(503));

        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getChaos().getSucceedFirst() == null, is(true));
        assertThat(deserialized[0].getChaos().getFailRequestCount() == null, is(true));
    }
}
