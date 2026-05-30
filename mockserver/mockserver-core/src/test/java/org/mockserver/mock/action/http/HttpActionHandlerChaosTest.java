package org.mockserver.mock.action.http;

import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.EpochService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for HTTP chaos injection wired through HttpActionHandler.
 * Uses the same Mockito harness as HttpActionHandlerTest.
 */
public class HttpActionHandlerChaosTest {

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
    @Mock
    private HttpResponseTemplateActionHandler mockHttpResponseTemplateActionHandler;
    @Mock
    private HttpResponseClassCallbackActionHandler mockHttpResponseClassCallbackActionHandler;
    @Mock
    private HttpResponseObjectCallbackActionHandler mockHttpResponseObjectCallbackActionHandler;
    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @Mock
    private HttpForwardTemplateActionHandler mockHttpForwardTemplateActionHandler;
    @Mock
    private HttpForwardClassCallbackActionHandler mockHttpForwardClassCallbackActionHandler;
    @Mock
    private HttpForwardObjectCallbackActionHandler mockHttpForwardObjectCallbackActionHandler;
    @Mock
    private HttpOverrideForwardedRequestActionHandler mockHttpOverrideForwardedRequestActionHandler;
    @Mock
    private HttpErrorActionHandler mockHttpErrorActionHandler;
    @Mock
    private ResponseWriter mockResponseWriter;
    @Mock
    private MockServerLogger mockServerLogger;
    @Spy
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
    @Mock
    private NettyHttpClient mockNettyHttpClient;
    private HttpState mockHttpStateHandler;
    @InjectMocks
    private HttpActionHandler actionHandler;
    private Configuration configuration;

    @BeforeClass
    public static void fixTime() {
        EpochService.fixedTime = true;
    }

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @Test
    public void chaosErrorReplacesResponseWhenProbabilityIsOne() {
        // given - an expectation with RESPONSE action and chaos (errorProbability=1.0, errorStatus=503, retryAfter="30")
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 503 response with Retry-After header is written instead of the normal 200
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(503));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is("30"));
        assertThat(writtenResponse.getBodyAsString(), is("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}"));
    }

    @Test
    public void chaosErrorDoesNotFireWhenProbabilityIsZero() {
        // given - an expectation with chaos but probability=0
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosWithNoErrorStatusDoesNotInjectError() {
        // given - chaos profile with no errorStatus (only latency could apply)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written (no error injection without errorStatus)
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void noChaosProfilePassesThroughNormally() {
        // given - no chaos
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosLatencyReachesSchedulerWhenNoErrorInjected() {
        // given - chaos with latency only (no error injection); use doAnswer to
        // capture delays without actually sleeping
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(500)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // capture the Delay[] arg from the inner schedule call (the one with varargs delays)
        java.util.concurrent.atomic.AtomicReference<Delay[]> capturedDelays = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            if (delays.length > 0) {
                capturedDelays.set(delays);
            }
            cmd.run(); // run synchronously without sleeping
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the chaos latency (500ms) should be among the delays passed to the scheduler
        Delay[] delays = capturedDelays.get();
        assertThat("delays should have been captured", delays != null, is(true));
        boolean foundChaosLatency = false;
        for (Delay d : delays) {
            if (d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                foundChaosLatency = true;
                break;
            }
        }
        assertThat("chaos latency should be passed to the scheduler", foundChaosLatency, is(true));

        // also verify the normal response was written through
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosErrorWithNoRetryAfterHeaderOmitsIt() {
        // given
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(500)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 500 response without Retry-After
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(500));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is(""));
    }

    // --- Count-based stateful chaos tests ---
    //
    // These tests create a real Expectation with Times.unlimited() and call
    // consumeMatch() to increment matchCount before each processAction call.
    // This mirrors the real flow where RequestMatchers.firstMatchingExpectation
    // calls consumeMatch() (which increments matchCount via AtomicInteger).

    /**
     * Helper: simulates the matching flow by calling consumeMatch() on the
     * expectation (which increments matchCount) and then dispatches the request.
     * Returns the HttpResponse that was written to the response writer.
     */
    private HttpResponse dispatchAndCapture(HttpRequest request, Expectation expectation, HttpResponse normalResponse) {
        // simulate the matching flow: consumeMatch increments matchCount
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // reset to capture this invocation
        reset(mockResponseWriter);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    @Test
    public void failFirstTwoThenRecover() {
        // succeedFirst=0, failRequestCount=2, errorStatus=503
        // → matches #1,#2 return 503, #3 returns the mocked 200
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(0)
                .withFailRequestCount(2));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 should be 503", r1.getStatusCode(), is(503));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 should be 503", r2.getStatusCode(), is(503));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 should recover to 200", r3.getBodyAsString(), is("normal body"));
    }

    @Test
    public void succeedFirstTwoThenFail() {
        // succeedFirst=2, failRequestCount=null, errorStatus=503
        // → #1,#2 = 200, #3 = 503
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 should succeed", r1.getBodyAsString(), is("normal body"));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 should succeed", r2.getBodyAsString(), is("normal body"));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 should be 503", r3.getStatusCode(), is(503));
    }

    @Test
    public void failOnlyTheNthRequest() {
        // succeedFirst=2, failRequestCount=1 → only #3 fails
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withSucceedFirst(2)
                .withFailRequestCount(1));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 should succeed", r1.getBodyAsString(), is("normal body"));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #2 should succeed", r2.getBodyAsString(), is("normal body"));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 should be 503", r3.getStatusCode(), is(503));

        HttpResponse r4 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #4 should recover to 200", r4.getBodyAsString(), is("normal body"));
    }

    @Test
    public void countWindowLatencyAppliesOnlyWithinWindow() {
        // succeedFirst=1, failRequestCount=1, latency=500ms
        // → match #1: no latency, match #2: latency applies, match #3: no latency
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(500))
                .withSucceedFirst(1)
                .withFailRequestCount(1));

        // Capture delays for each invocation
        java.util.List<Delay[]> capturedDelaysList = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            capturedDelaysList.add(delays);
            cmd.run();
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // match #1: outside window (succeedFirst=1), no chaos latency
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat("scheduler should have been invoked for match #1", capturedDelaysList.isEmpty(), is(false));
        boolean found500msInMatch1 = false;
        for (Delay d : capturedDelaysList.get(capturedDelaysList.size() - 1)) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                found500msInMatch1 = true;
                break;
            }
        }
        assertThat("match #1 should NOT have chaos latency", found500msInMatch1, is(false));

        // match #2: within window, chaos latency should apply
        capturedDelaysList.clear();
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat("scheduler should have been invoked for match #2", capturedDelaysList.isEmpty(), is(false));
        boolean found500msInMatch2 = false;
        for (Delay d : capturedDelaysList.get(capturedDelaysList.size() - 1)) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                found500msInMatch2 = true;
                break;
            }
        }
        assertThat("match #2 should have chaos latency", found500msInMatch2, is(true));

        // match #3: outside window (beyond failRequestCount), no chaos latency
        capturedDelaysList.clear();
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat("scheduler should have been invoked for match #3", capturedDelaysList.isEmpty(), is(false));
        boolean found500msInMatch3 = false;
        for (Delay d : capturedDelaysList.get(capturedDelaysList.size() - 1)) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                found500msInMatch3 = true;
                break;
            }
        }
        assertThat("match #3 should NOT have chaos latency", found500msInMatch3, is(false));
    }

    @Test
    public void backwardCompatNoCountFieldsBehavesLikeBefore() {
        // No succeedFirst/failRequestCount → errorProbability governs (always inject)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        // All 3 matches should get chaos error
        for (int i = 1; i <= 3; i++) {
            HttpResponse r = dispatchAndCapture(request, expectation, normalResponse);
            assertThat("match #" + i + " should be 503", r.getStatusCode(), is(503));
        }
    }
}
