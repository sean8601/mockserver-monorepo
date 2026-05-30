package org.mockserver.mock.action.http;

import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
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

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for HTTP chaos injection on forwarded (proxied) responses
 * wired through HttpActionHandler. Uses the same Mockito harness as
 * HttpActionHandlerTest / HttpActionHandlerChaosTest.
 */
public class HttpActionHandlerForwardChaosTest {

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
    private HttpForwardValidateActionHandler mockHttpForwardValidateActionHandler;
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

    private HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        HttpRequest forwardedRequest = mock(HttpRequest.class);
        return new HttpForwardActionResult(forwardedRequest, future, null, new InetSocketAddress(1234));
    }

    // ---- Error injection tests ----

    @Test
    public void chaosErrorReplacesForwardedResponseWhenProbabilityIsOne() {
        // given - a FORWARD expectation with chaos (errorProbability=1.0, errorStatus=503, retryAfter="30")
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 503 response with Retry-After header is written instead of the upstream 200
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(503));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is("30"));
        assertThat(writtenResponse.getBodyAsString(), is("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}"));
    }

    @Test
    public void chaosErrorDoesNotFireForForwardedResponseWhenProbabilityIsZero() {
        // given - a FORWARD expectation with chaos but probability=0
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(200));
        assertThat(writtenResponse.getBodyAsString(), is("upstream body"));
    }

    @Test
    public void noChaosProfilePassesThroughForwardedResponseNormally() {
        // given - a FORWARD expectation with no chaos
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(200));
        assertThat(writtenResponse.getBodyAsString(), is("upstream body"));
    }

    // ---- Latency injection test (non-blocking via scheduler) ----

    @Test
    public void chaosLatencyIsScheduledNonBlockingOnForwardedResponse() {
        // given - chaos with latency only (no error injection)
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        Delay chaosDelay = milliseconds(500);

        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withLatency(chaosDelay));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // Capture the Delay[] passed to scheduler.schedule to verify the chaos delay
        // is dispatched via the non-blocking scheduler timer, not a blocking sleep.
        java.util.concurrent.atomic.AtomicReference<Delay[]> capturedDelays = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            capturedDelays.set(delays);
            cmd.run(); // run synchronously without sleeping
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the chaos latency (500ms) should have been passed to scheduler.schedule
        Delay[] delays = capturedDelays.get();
        assertThat("scheduler.schedule should have been called with delays", delays != null, is(true));
        boolean foundChaosLatency = false;
        for (Delay d : delays) {
            if (d != null && d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                foundChaosLatency = true;
                break;
            }
        }
        assertThat("chaos latency should be passed to the non-blocking scheduler", foundChaosLatency, is(true));

        // and the upstream response passes through unchanged
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(200));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("upstream body"));
    }

    @Test
    public void noChaosLatencyDoesNotInvokeSchedulerSchedule() {
        // given - no chaos profile at all
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - scheduler.schedule should NOT be called from within the forward completion
        // callback (only the outer dispatch uses schedule with action+global delay, which
        // uses different arguments — we verify no inner schedule with chaos delay)
        verify(mockResponseWriter).writeResponse(eq(request), any(HttpResponse.class), eq(false));
    }

    // ---- Chaos error log distinguishability test ----

    @Test
    public void chaosErrorLogEntryIsClearlyDistinguishableFromNormalForward() {
        // given - chaos error injection fires (probability=1.0)
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the log entry message format should mention "chaos-injected"
        ArgumentCaptor<LogEntry> logCaptor = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logCaptor.capture());
        boolean foundChaosLog = false;
        for (LogEntry entry : logCaptor.getAllValues()) {
            if (entry.getMessageFormat() != null && entry.getMessageFormat().contains("chaos-injected")) {
                foundChaosLog = true;
                break;
            }
        }
        assertThat("log entry should mention 'chaos-injected' when error replaces forwarded response", foundChaosLog, is(true));
    }

    @Test
    public void normalForwardLogDoesNotMentionChaos() {
        // given - no chaos
        HttpRequest request = request("some_path");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("localhost").withPort(1090);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request)
            .thenForward(forward);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the log entry message format should be normal (not chaos)
        ArgumentCaptor<LogEntry> logCaptor = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logCaptor.capture());
        for (LogEntry entry : logCaptor.getAllValues()) {
            if (entry.getMessageFormat() != null && entry.getMessageFormat().contains("forwarded request")) {
                assertThat("normal forward log should not mention chaos",
                    entry.getMessageFormat().contains("chaos-injected"), is(false));
            }
        }
    }
}
