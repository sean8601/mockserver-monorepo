package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
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
            .withProfile(LoadProfile.constant(10, 30_000L).withIterationPacingMillis(250L))
            .withSteps(
                new LoadStep()
                    .withRequest(request().withMethod("POST").withPath("/cart/$iteration.index").withBody("payload"))
                    .withThinkTime(Delay.milliseconds(100)));

        String json = serializer.serialize(scenario);
        LoadScenario parsed = serializer.deserialize(json);

        assertThat(parsed.getName(), is("checkout"));
        assertThat(parsed.getTemplateType(), is(HttpTemplate.TemplateType.VELOCITY));
        assertThat(parsed.getMaxRequests(), is(1000));
        assertThat(parsed.getProfile().getType(), is(LoadProfile.Type.CONSTANT));
        assertThat(parsed.getProfile().getVus(), is(10));
        assertThat(parsed.getProfile().getDurationMillis(), is(30_000L));
        assertThat(parsed.getProfile().getIterationPacingMillis(), is(250L));
        assertThat(parsed.getSteps(), hasSize(1));
        LoadStep step = parsed.getSteps().get(0);
        assertThat(step.getRequest().getMethod().getValue(), is("POST"));
        assertThat(step.getRequest().getPath().getValue(), is("/cart/$iteration.index"));
        assertThat(step.getThinkTime().getValue(), is(100L));
        assertThat(step.getThinkTime().getTimeUnit(), is(TimeUnit.MILLISECONDS));
    }

    @Test
    public void roundTripsLinearRampScenario() {
        LoadScenario scenario = new LoadScenario()
            .withName("ramp")
            .withProfile(LoadProfile.linear(1, 25, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        LoadScenario parsed = serializer.deserialize(serializer.serialize(scenario));

        assertThat(parsed.getProfile().getType(), is(LoadProfile.Type.LINEAR));
        assertThat(parsed.getProfile().getStartVus(), is(1));
        assertThat(parsed.getProfile().getEndVus(), is(25));
        assertThat(parsed.getProfile().getDurationMillis(), is(60_000L));
    }

    @Test
    public void parsesMinimalJson() {
        String json = "{ \"name\": \"minimal\", " +
            "\"profile\": { \"type\": \"CONSTANT\", \"vus\": 2, \"durationMillis\": 1000 }, " +
            "\"steps\": [ { \"request\": { \"path\": \"/health\" } } ] }";

        LoadScenario parsed = serializer.deserialize(json);

        assertThat(parsed.getName(), is("minimal"));
        assertThat(parsed.getProfile().getVus(), is(2));
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

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankBody() {
        serializer.deserialize("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedJson() {
        serializer.deserialize("{ not valid json ");
    }
}
