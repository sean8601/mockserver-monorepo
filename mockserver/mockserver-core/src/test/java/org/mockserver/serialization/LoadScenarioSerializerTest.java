package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStage;
import org.mockserver.load.LoadStageType;
import org.mockserver.load.LoadStep;
import org.mockserver.load.RampCurve;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpTemplate;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behavioural round-trip tests for {@link LoadScenarioSerializer}. Pure (no global state),
 * so they run in the parallel Surefire phase.
 */
public class LoadScenarioSerializerTest {

    private final LoadScenarioSerializer serializer = new LoadScenarioSerializer(new MockServerLogger());

    @Test
    public void roundTripsConstantScenario() {
        LoadScenario scenario = new LoadScenario()
            .withName("checkout")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
            .withMaxRequests(1000)
            .withProfile(LoadProfile.constant(10, 30_000L))
            .withSteps(
                new LoadStep()
                    .withRequest(request().withMethod("POST").withPath("/cart/$iteration.index").withBody("payload"))
                    .withThinkTime(Delay.milliseconds(100)));

        String json = serializer.serialize(scenario);
        LoadScenario parsed = serializer.deserialize(json);

        assertThat(parsed.getName(), is("checkout"));
        assertThat(parsed.getTemplateType(), is(HttpTemplate.TemplateType.VELOCITY));
        assertThat(parsed.getMaxRequests(), is(1000));
        assertThat(parsed.getProfile().getStages(), hasSize(1));
        LoadStage stage = parsed.getProfile().getStages().get(0);
        assertThat(stage.getType(), is(LoadStageType.VU));
        assertThat(stage.getVus(), is(10));
        assertThat(stage.getDurationMillis(), is(30_000L));
        assertThat(parsed.getSteps(), hasSize(1));
        LoadStep step = parsed.getSteps().get(0);
        assertThat(step.getRequest().getMethod().getValue(), is("POST"));
        assertThat(step.getRequest().getPath().getValue(), is("/cart/$iteration.index"));
        assertThat(step.getThinkTime().getValue(), is(100L));
        assertThat(step.getThinkTime().getTimeUnit(), is(TimeUnit.MILLISECONDS));
    }

    @Test
    public void roundTripsMultiStageRampAndRateScenario() {
        LoadScenario scenario = new LoadScenario()
            .withName("ramp")
            .withProfile(LoadProfile.of(
                LoadStage.rampVus(1, 25, 60_000L, RampCurve.EXPONENTIAL),
                LoadStage.pause(5_000L),
                LoadStage.rampRate(10.0, 100.0, 30_000L, RampCurve.QUADRATIC).withMaxVus(40),
                LoadStage.constantRate(100.0, 30_000L)))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        LoadScenario parsed = serializer.deserialize(serializer.serialize(scenario));

        assertThat(parsed.getProfile().getStages(), hasSize(4));

        LoadStage vuRamp = parsed.getProfile().getStages().get(0);
        assertThat(vuRamp.getType(), is(LoadStageType.VU));
        assertThat(vuRamp.getStartVus(), is(1));
        assertThat(vuRamp.getEndVus(), is(25));
        assertThat(vuRamp.getDurationMillis(), is(60_000L));
        assertThat(vuRamp.getCurve(), is(RampCurve.EXPONENTIAL));

        assertThat(parsed.getProfile().getStages().get(1).getType(), is(LoadStageType.PAUSE));

        LoadStage rateRamp = parsed.getProfile().getStages().get(2);
        assertThat(rateRamp.getType(), is(LoadStageType.RATE));
        assertThat(rateRamp.getStartRate(), is(10.0));
        assertThat(rateRamp.getEndRate(), is(100.0));
        assertThat(rateRamp.getCurve(), is(RampCurve.QUADRATIC));
        assertThat(rateRamp.getMaxVus(), is(40));

        LoadStage rateHold = parsed.getProfile().getStages().get(3);
        assertThat(rateHold.getType(), is(LoadStageType.RATE));
        assertThat(rateHold.getRate(), is(100.0));
    }

    @Test
    public void parsesMinimalJson() {
        String json = "{ \"name\": \"minimal\", " +
            "\"profile\": { \"stages\": [ { \"type\": \"VU\", \"vus\": 2, \"durationMillis\": 1000 } ] }, " +
            "\"steps\": [ { \"request\": { \"path\": \"/health\" } } ] }";

        LoadScenario parsed = serializer.deserialize(json);

        assertThat(parsed.getName(), is("minimal"));
        assertThat(parsed.getProfile().getStages().get(0).getVus(), is(2));
        assertThat(parsed.getSteps().get(0).getRequest().getPath().getValue(), is("/health"));
        // templateType defaults to VELOCITY when omitted.
        assertThat(parsed.getTemplateType(), is(HttpTemplate.TemplateType.VELOCITY));
    }

    @Test
    public void roundTripsScenarioAndStepLabelsAndStepName() {
        LoadScenario scenario = new LoadScenario()
            .withName("annotated")
            .withLabel("team", "payments")
            .withLabel("env", "staging")
            .withProfile(LoadProfile.constant(3, 5_000L))
            .withSteps(new LoadStep()
                .withName("create-order")
                .withLabel("critical", "true")
                .withRequest(request().withPath("/api/orders")));

        LoadScenario parsed = serializer.deserialize(serializer.serialize(scenario));

        assertThat(parsed.getLabels(), allOf(hasEntry("team", "payments"), hasEntry("env", "staging")));
        LoadStep step = parsed.getSteps().get(0);
        assertThat(step.getName(), is("create-order"));
        assertThat(step.getLabels(), hasEntry("critical", "true"));
    }

    @Test
    public void omitsLabelsAndNameWhenAbsent() {
        // backward compatible: a scenario with no labels/name serialises without those keys.
        LoadScenario scenario = new LoadScenario()
            .withName("plain")
            .withProfile(LoadProfile.constant(1, 1_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String json = serializer.serialize(scenario);
        assertThat(json, not(containsString("\"labels\"")));
        assertThat(json, not(containsString("\"name\" : \"create")));

        LoadScenario parsed = serializer.deserialize(json);
        assertThat(parsed.getLabels(), is(nullValue()));
        assertThat(parsed.getSteps().get(0).getName(), is(nullValue()));
    }

    @Test
    public void roundTripsThresholdsAndAbortConfig() {
        LoadScenario scenario = new LoadScenario()
            .withName("with-thresholds")
            .withProfile(LoadProfile.constant(5, 30_000L))
            .withAbortOnFail(true)
            .withAbortGraceMillis(2_000L)
            .withThresholds(
                new org.mockserver.load.LoadThreshold()
                    .withMetric(org.mockserver.load.LoadThreshold.Metric.LATENCY_P95)
                    .withComparator(org.mockserver.slo.SloObjective.Comparator.LESS_THAN)
                    .withThreshold(250.0),
                new org.mockserver.load.LoadThreshold()
                    .withMetric(org.mockserver.load.LoadThreshold.Metric.ERROR_RATE)
                    .withComparator(org.mockserver.slo.SloObjective.Comparator.LESS_THAN_OR_EQUAL)
                    .withThreshold(0.01))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        LoadScenario parsed = serializer.deserialize(serializer.serialize(scenario));

        assertThat(parsed.isAbortOnFail(), is(true));
        assertThat(parsed.getAbortGraceMillis(), is(2_000L));
        assertThat(parsed.getThresholds(), hasSize(2));
        org.mockserver.load.LoadThreshold latency = parsed.getThresholds().get(0);
        assertThat(latency.getMetric(), is(org.mockserver.load.LoadThreshold.Metric.LATENCY_P95));
        assertThat(latency.getComparator(), is(org.mockserver.slo.SloObjective.Comparator.LESS_THAN));
        assertThat(latency.getThreshold(), is(250.0));
        org.mockserver.load.LoadThreshold errorRate = parsed.getThresholds().get(1);
        assertThat(errorRate.getMetric(), is(org.mockserver.load.LoadThreshold.Metric.ERROR_RATE));
        assertThat(errorRate.getComparator(), is(org.mockserver.slo.SloObjective.Comparator.LESS_THAN_OR_EQUAL));
        assertThat(errorRate.getThreshold(), is(0.01));
    }

    @Test
    public void omitsThresholdsAndAbortDefaultsWhenAbsent() {
        // backward compatible: a scenario with no thresholds and default abort config omits those keys.
        LoadScenario scenario = new LoadScenario()
            .withName("plain")
            .withProfile(LoadProfile.constant(1, 1_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String json = serializer.serialize(scenario);
        assertThat(json, not(containsString("\"thresholds\"")));
        assertThat(json, not(containsString("\"abortOnFail\"")));
        assertThat(json, not(containsString("\"abortGraceMillis\"")));

        LoadScenario parsed = serializer.deserialize(json);
        assertThat(parsed.getThresholds(), is(nullValue()));
        assertThat(parsed.isAbortOnFail(), is(false));
        assertThat(parsed.getAbortGraceMillis(), is(0L));
    }

    @Test
    public void roundTripsStepCaptures() {
        LoadScenario scenario = new LoadScenario()
            .withName("login-flow")
            .withTemplateType(HttpTemplate.TemplateType.MUSTACHE)
            .withProfile(LoadProfile.constant(5, 30_000L))
            .withSteps(
                new LoadStep()
                    .withRequest(request().withMethod("POST").withPath("/login"))
                    .withCapture(new org.mockserver.load.LoadCapture()
                        .withName("token")
                        .withSource(org.mockserver.load.LoadCapture.Source.BODY_JSONPATH)
                        .withExpression("$.token"))
                    .withCapture(new org.mockserver.load.LoadCapture()
                        .withName("loc")
                        .withSource(org.mockserver.load.LoadCapture.Source.HEADER)
                        .withExpression("Location")
                        .withDefaultValue("/")),
                new LoadStep()
                    .withRequest(request().withMethod("GET").withPath("/account")
                        .withHeader("Authorization", "Bearer {{iteration.captured.token}}")));

        String json = serializer.serialize(scenario);
        LoadScenario parsed = serializer.deserialize(json);

        assertThat(parsed.getSteps(), hasSize(2));
        LoadStep first = parsed.getSteps().get(0);
        assertThat(first.getCaptures(), hasSize(2));
        org.mockserver.load.LoadCapture tokenCapture = first.getCaptures().get(0);
        assertThat(tokenCapture.getName(), is("token"));
        assertThat(tokenCapture.getSource(), is(org.mockserver.load.LoadCapture.Source.BODY_JSONPATH));
        assertThat(tokenCapture.getExpression(), is("$.token"));
        assertThat(tokenCapture.getDefaultValue(), is(nullValue()));
        org.mockserver.load.LoadCapture locCapture = first.getCaptures().get(1);
        assertThat(locCapture.getName(), is("loc"));
        assertThat(locCapture.getSource(), is(org.mockserver.load.LoadCapture.Source.HEADER));
        assertThat(locCapture.getExpression(), is("Location"));
        assertThat(locCapture.getDefaultValue(), is("/"));
        // The second step declares no captures.
        assertThat(parsed.getSteps().get(1).getCaptures(), is(nullValue()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankBody() {
        serializer.deserialize("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedJson() {
        serializer.deserialize("{ not valid json ");
    }
}
