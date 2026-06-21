package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.LoadScenarioOrchestrator;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end routing tests for {@code PUT/GET/DELETE /mockserver/loadScenario} exercised through
 * {@link HttpState#handle}: authentication is required, the PUT returns 403 when the feature is
 * disabled, 200 when enabled, GET reports status, DELETE stops, and a malformed body is 400.
 *
 * <p>State-mutating: flips the static {@code loadGenerationEnabled} property and drives the
 * process-wide {@link LoadScenarioOrchestrator} singleton, so it must run in the sequential
 * Surefire phase. A synchronous fake sender is installed so no real network traffic is generated.
 */
public class HttpStateLoadScenarioEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private boolean originalEnabled;

    private static class FakeResponseWriter extends ResponseWriter {
        public HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @Before
    public void setUp() {
        originalEnabled = ConfigurationProperties.loadGenerationEnabled();
        LoadScenarioOrchestrator.getInstance().reset();
        // Install a synchronous fake sender so the orchestrator can start without a Netty runtime.
        LoadScenarioOrchestrator.getInstance().setSender(req -> CompletableFuture.completedFuture(response().withStatusCode(200)));
        rebuildHttpState(true);
    }

    @After
    public void tearDown() {
        LoadScenarioOrchestrator.getInstance().reset();
        LoadScenarioOrchestrator.getInstance().setSender(null);
        ConfigurationProperties.loadGenerationEnabled(originalEnabled);
    }

    private void rebuildHttpState(boolean enabled) {
        ConfigurationProperties.loadGenerationEnabled(enabled);
        Configuration configuration = configuration().loadGenerationEnabled(enabled);
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateLoadScenarioEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateLoadScenarioEndpointTest.class), scheduler);
        LoadScenarioOrchestrator.getInstance().setConfiguration(configuration);
    }

    private HttpResponse put(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/loadScenario").withMethod("PUT");
        if (body != null) {
            req.withBody(body);
        }
        assertThat("route handled", httpState.handle(req, rw, false), is(true));
        return rw.response;
    }

    private HttpResponse get() {
        FakeResponseWriter rw = new FakeResponseWriter();
        assertThat("route handled", httpState.handle(request("/mockserver/loadScenario").withMethod("GET"), rw, false), is(true));
        return rw.response;
    }

    private HttpResponse delete() {
        FakeResponseWriter rw = new FakeResponseWriter();
        assertThat("route handled", httpState.handle(request("/mockserver/loadScenario").withMethod("DELETE"), rw, false), is(true));
        return rw.response;
    }

    private static String scenarioJson() {
        return "{ \"name\": \"smoke\", " +
            "\"profile\": { \"type\": \"CONSTANT\", \"vus\": 1, \"durationMillis\": 60000 }, " +
            "\"steps\": [ { \"request\": { \"path\": \"/health\", \"headers\": { \"Host\": [\"target\"] } } } ] }";
    }

    @Test
    public void putReturns403WhenDisabled() throws Exception {
        rebuildHttpState(false);
        HttpResponse response = put(scenarioJson());
        assertThat(response.getStatusCode(), is(403));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("error").asText(), containsString("load generation not enabled"));
    }

    @Test
    public void putReturns200AndStartsWhenEnabled() throws Exception {
        HttpResponse response = put(scenarioJson());
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("status").asText(), is("started"));
        assertThat(body.get("name").asText(), is("smoke"));
        // immediately stop so the singleton scheduler does not keep driving traffic
        delete();
    }

    @Test
    public void getReportsRunningStatusThenStopped() throws Exception {
        put(scenarioJson());
        HttpResponse running = get();
        assertThat(running.getStatusCode(), is(200));
        JsonNode runningBody = objectMapper.readTree(running.getBodyAsString());
        assertThat(runningBody.get("state").asText(), is("running"));
        assertThat(runningBody.get("name").asText(), is("smoke"));

        HttpResponse stopResponse = delete();
        assertThat(stopResponse.getStatusCode(), is(200));
        assertThat(objectMapper.readTree(stopResponse.getBodyAsString()).get("status").asText(), is("stopped"));

        JsonNode stoppedBody = objectMapper.readTree(get().getBodyAsString());
        assertThat(stoppedBody.get("state").asText(), is("stopped"));
    }

    @Test
    public void getReportsNoneWhenNothingStarted() throws Exception {
        HttpResponse response = get();
        assertThat(response.getStatusCode(), is(200));
        JsonNode body = objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("state").asText(), is("none"));
        // no run exists, so the scenario definition must be omitted entirely
        assertThat(body.has("definition"), is(false));
    }

    @Test
    public void getEchoesRunningScenarioDefinition() throws Exception {
        put(scenarioJson());
        try {
            JsonNode runningBody = objectMapper.readTree(get().getBodyAsString());
            assertThat(runningBody.get("state").asText(), is("running"));

            // the GET response must carry the full scenario definition, matching what was PUT, so a
            // client/dashboard that did not start the run can still load it into an author form
            JsonNode definition = runningBody.get("definition");
            assertThat("definition is present", definition != null && definition.isObject(), is(true));
            assertThat(definition.get("name").asText(), is("smoke"));
            assertThat(definition.get("profile").get("vus").asInt(), is(1));
            assertThat(definition.get("profile").get("durationMillis").asLong(), is(60000L));
            assertThat(definition.get("steps").get(0).get("request").get("path").asText(), is("/health"));
        } finally {
            delete();
        }
    }

    @Test
    public void getDefinitionRoundTripsAsAValidPutBody() throws Exception {
        put(scenarioJson());
        try {
            // the echoed definition is itself a valid LoadScenario body — re-submitting it as a PUT
            // (re-starting the same scenario) must succeed and start a run with the same name
            JsonNode definition = objectMapper.readTree(get().getBodyAsString()).get("definition");
            assertThat("definition is present", definition != null && definition.isObject(), is(true));

            HttpResponse rePut = put(objectMapper.writeValueAsString(definition));
            assertThat(rePut.getStatusCode(), is(200));
            JsonNode rePutBody = objectMapper.readTree(rePut.getBodyAsString());
            assertThat(rePutBody.get("status").asText(), is("started"));
            assertThat(rePutBody.get("name").asText(), is("smoke"));
        } finally {
            delete();
        }
    }

    @Test
    public void getEchoesDefinitionForTerminatedRun() throws Exception {
        put(scenarioJson());
        delete();
        // even after the run is stopped, the most-recent definition is still echoed so it can be edited
        JsonNode stoppedBody = objectMapper.readTree(get().getBodyAsString());
        assertThat(stoppedBody.get("state").asText(), is("stopped"));
        JsonNode definition = stoppedBody.get("definition");
        assertThat("definition is present", definition != null && definition.isObject(), is(true));
        assertThat(definition.get("name").asText(), is("smoke"));
    }

    @Test
    public void putReturns400ForMalformedBody() {
        HttpResponse response = put("{ not valid json");
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid load scenario definition"));
    }

    @Test
    public void putReturns400ForBlankBody() {
        HttpResponse response = put(null);
        assertThat(response.getStatusCode(), is(400));
    }

    @Test
    public void putReturns400WhenCapExceeded() throws Exception {
        ConfigurationProperties.loadGenerationMaxVirtualUsers(2);
        try {
            rebuildHttpState(true);
            String body = "{ \"name\": \"big\", " +
                "\"profile\": { \"type\": \"CONSTANT\", \"vus\": 100, \"durationMillis\": 1000 }, " +
                "\"steps\": [ { \"request\": { \"path\": \"/x\" } } ] }";
            HttpResponse response = put(body);
            assertThat(response.getStatusCode(), is(400));
            assertThat(response.getBodyAsString(), containsString("exceeding the maximum of 2"));
        } finally {
            ConfigurationProperties.loadGenerationMaxVirtualUsers(50);
        }
    }

    @Test
    public void putRequiresControlPlaneAuthentication() {
        AuthenticationHandler rejectingHandler = req -> false;
        httpState.setControlPlaneAuthenticationHandler(rejectingHandler);

        HttpResponse response = put(scenarioJson());
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("Unauthorized for control plane"));
    }
}
