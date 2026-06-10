package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Tests the breakpoint matcher REST endpoints in HttpState:
 * register, list, remove, clear, and validation.
 */
public class BreakpointMatcherEndpointTest {

    private static Configuration staticConfiguration;
    private static HttpState staticHttpState;

    @BeforeClass
    public static void setupClass() {
        staticConfiguration = configuration();
        Scheduler scheduler = mock(Scheduler.class);
        staticHttpState = new HttpState(staticConfiguration, new MockServerLogger(staticConfiguration, MockServerLogger.class), scheduler);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (staticHttpState != null) {
            staticHttpState.reset();
            staticHttpState.stop();
            staticHttpState = null;
        }
        // Final cleanup of all breakpoint singletons after the class.
        // Allow async resources (Disruptor, scheduled timeouts) to settle
        // before the next test class creates fresh singletons state.
        Thread.sleep(100);
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

    // Instance references for convenience
    private Configuration configuration;
    private HttpState httpState;

    @Before
    public void setup() {
        resetAllBreakpointSingletons();
        configuration = staticConfiguration;
        httpState = staticHttpState;
    }

    @After
    public void cleanup() {
        resetAllBreakpointSingletons();
    }

    private void resetAllBreakpointSingletons() {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        BreakpointCallbackDispatcher.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();
    }

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

    // --- register ---

    @Test
    public void shouldRegisterBreakpointMatcher() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/api/test\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response, is(notNullValue()));
        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"id\""));
        assertThat(body, containsString("\"REQUEST\""));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));
    }

    @Test
    public void shouldRegisterWithMultiplePhases() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"method\":\"POST\"},\"phases\":[\"REQUEST\",\"RESPONSE\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(201));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("REQUEST"));
        assertThat(body, containsString("RESPONSE"));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));
    }

    // --- validation ---

    @Test
    public void shouldReturn400WhenMissingHttpRequest() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("httpRequest"));
    }

    @Test
    public void shouldReturn400WhenEmptyHttpRequest() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("httpRequest"));
    }

    @Test
    public void shouldReturn400WhenMissingPhases() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"}}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("phases"));
    }

    @Test
    public void shouldReturn400WhenEmptyPhases() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[]}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("phases"));
    }

    @Test
    public void shouldReturn400WhenUnknownPhase() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[\"INVALID_PHASE\"]}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("INVALID_PHASE"));
    }

    @Test
    public void shouldReturn400WhenEmptyBody() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturn400WhenMissingClientId() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[\"REQUEST\"]}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("clientId"));
    }

    @Test
    public void shouldReturn400WhenBlankClientId() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest registerRequest = request("/mockserver/breakpoint/matcher")
            .withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/test\"},\"phases\":[\"REQUEST\"],\"clientId\":\"\"}");
        httpState.handle(registerRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(400));
        assertThat(responseWriter.response.getBodyAsString(), containsString("clientId"));
    }

    // --- list (PUT and GET) ---

    @Test
    public void shouldListRegisteredMatchers() {
        // register two matchers
        registerMatcher("{\"httpRequest\":{\"path\":\"/a\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        registerMatcher("{\"httpRequest\":{\"path\":\"/b\"},\"phases\":[\"RESPONSE\",\"RESPONSE_STREAM\"],\"clientId\":\"test-client\"}");

        // list via PUT
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest listRequest = request("/mockserver/breakpoint/matchers").withMethod("PUT");
        httpState.handle(listRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(body, containsString("\"matchers\""));
        assertThat(body, containsString("/a"));
        assertThat(body, containsString("/b"));
        assertThat(body, containsString("REQUEST"));
        assertThat(body, containsString("RESPONSE"));
        assertThat(body, containsString("RESPONSE_STREAM"));
    }

    @Test
    public void shouldListViaGet() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/get-test\"},\"phases\":[\"INBOUND_STREAM\"],\"clientId\":\"test-client\"}");

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest getRequest = request("/mockserver/breakpoint/matchers").withMethod("GET");
        httpState.handle(getRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("/get-test"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("INBOUND_STREAM"));
    }

    // --- remove ---

    @Test
    public void shouldRemoveMatcher() {
        String id = registerMatcherAndGetId("{\"httpRequest\":{\"path\":\"/removable\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest removeRequest = request("/mockserver/breakpoint/matcher/remove")
            .withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}");
        httpState.handle(removeRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("removed"));
        assertThat(responseWriter.response.getBodyAsString(), containsString(id));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(0));
    }

    @Test
    public void shouldReturn404WhenRemovingNonExistentMatcher() {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest removeRequest = request("/mockserver/breakpoint/matcher/remove")
            .withMethod("PUT")
            .withBody("{\"id\":\"non-existent-id\"}");
        httpState.handle(removeRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(404));
        assertThat(responseWriter.response.getBodyAsString(), containsString("not found"));
    }

    @Test
    public void shouldReturn404OnReRemove() {
        String id = registerMatcherAndGetId("{\"httpRequest\":{\"path\":\"/remove-twice\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");

        // first remove succeeds
        FakeResponseWriter rw1 = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matcher/remove").withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}"), rw1, false);
        assertThat(rw1.response.getStatusCode(), is(200));

        // second remove returns 404
        FakeResponseWriter rw2 = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matcher/remove").withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}"), rw2, false);
        assertThat(rw2.response.getStatusCode(), is(404));
    }

    // --- clear ---

    @Test
    public void shouldClearAllMatchers() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/x\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        registerMatcher("{\"httpRequest\":{\"path\":\"/y\"},\"phases\":[\"RESPONSE\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(2));

        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest clearRequest = request("/mockserver/breakpoint/matcher/clear").withMethod("PUT");
        httpState.handle(clearRequest, responseWriter, false);

        assertThat(responseWriter.response.getStatusCode(), is(200));
        assertThat(responseWriter.response.getBodyAsString(), containsString("cleared"));
        assertThat(responseWriter.response.getBodyAsString(), containsString("\"count\" : 2"));
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(0));
    }

    // --- reset clears ---

    @Test
    public void resetShouldClearMatcherRegistry() {
        registerMatcher("{\"httpRequest\":{\"path\":\"/reset-test\"},\"phases\":[\"REQUEST\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));

        // trigger reset
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest resetRequest = request("/mockserver/reset").withMethod("PUT");
        httpState.handle(resetRequest, responseWriter, false);

        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(0));
    }

    // --- integration: register -> list -> remove -> list empty ---

    @Test
    public void shouldSupportFullLifecycle() {
        // register
        String id = registerMatcherAndGetId("{\"httpRequest\":{\"path\":\"/lifecycle\"},\"phases\":[\"REQUEST\",\"RESPONSE\"],\"clientId\":\"test-client\"}");
        assertThat(BreakpointMatcherRegistry.getInstance().size(), is(1));

        // list shows it
        FakeResponseWriter listRw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), listRw, false);
        assertThat(listRw.response.getBodyAsString(), containsString("/lifecycle"));

        // remove
        FakeResponseWriter removeRw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matcher/remove").withMethod("PUT")
            .withBody("{\"id\":\"" + id + "\"}"), removeRw, false);
        assertThat(removeRw.response.getStatusCode(), is(200));

        // list is now empty
        FakeResponseWriter emptyListRw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/breakpoint/matchers").withMethod("GET"), emptyListRw, false);
        assertThat(emptyListRw.response.getBodyAsString(), containsString("\"matchers\" : [ ]"));
    }

    // --- helpers ---

    private void registerMatcher(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/breakpoint/matcher").withMethod("PUT").withBody(body);
        httpState.handle(req, rw, false);
        assertThat("registration should succeed", rw.response.getStatusCode(), is(201));
    }

    private String registerMatcherAndGetId(String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/breakpoint/matcher").withMethod("PUT").withBody(body);
        httpState.handle(req, rw, false);
        assertThat("registration should succeed", rw.response.getStatusCode(), is(201));
        // extract id from JSON response
        String responseBody = rw.response.getBodyAsString();
        int idStart = responseBody.indexOf("\"id\" : \"") + 8;
        int idEnd = responseBody.indexOf("\"", idStart);
        return responseBody.substring(idStart, idEnd);
    }
}
