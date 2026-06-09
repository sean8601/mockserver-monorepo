package org.mockserver.mock.breakpoint;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class BreakpointRegistryTest {

    @After
    public void cleanup() {
        BreakpointRegistry.getInstance().reset();
    }

    private Configuration configWith(boolean enabled, long timeout, int maxHeld) {
        return Configuration.configuration()
            .breakpointEnabled(enabled)
            .breakpointTimeoutMillis(timeout)
            .breakpointMaxHeld(maxHeld);
    }

    @Test
    public void shouldPauseAndResolveContinue() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-1", req, null, config);
        assertThat(exchange, is(notNullValue()));
        assertThat(BreakpointRegistry.getInstance().size(), is(1));
        assertThat(BreakpointRegistry.getInstance().entries().containsKey("corr-1"), is(true));

        // resolve continue
        boolean resolved = BreakpointRegistry.getInstance().resolveContinue("corr-1");
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldPauseAndResolveModify() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-2", req, "exp-1", config);
        assertThat(exchange, is(notNullValue()));
        assertThat(exchange.getMatchedExpectationId(), is("exp-1"));

        HttpRequest modified = request().withMethod("POST").withPath("/api/modified");
        boolean resolved = BreakpointRegistry.getInstance().resolveModify("corr-2", modified);
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedRequest().getPath().getValue(), is("/api/modified"));
    }

    @Test
    public void shouldPauseAndResolveAbort() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("GET").withPath("/api/test");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-3", req, null, config);

        HttpResponse abortResp = response().withStatusCode(403).withBody("forbidden");
        boolean resolved = BreakpointRegistry.getInstance().resolveAbort("corr-3", abortResp);
        assertThat(resolved, is(true));

        BreakpointDecision decision = exchange.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.ABORT));
        assertThat(decision.getAbortResponse().getStatusCode(), is(403));
    }

    @Test
    public void shouldReturnFalseForUnknownCorrelationId() {
        assertThat(BreakpointRegistry.getInstance().resolveContinue("unknown"), is(false));
        assertThat(BreakpointRegistry.getInstance().resolveModify("unknown", request()), is(false));
        assertThat(BreakpointRegistry.getInstance().resolveAbort("unknown", null), is(false));
    }

    @Test
    public void shouldEnforceMaxHeldCap() {
        Configuration config = configWith(true, 30000, 2);

        PausedExchange ex1 = BreakpointRegistry.getInstance().pause("corr-a", request(), null, config);
        PausedExchange ex2 = BreakpointRegistry.getInstance().pause("corr-b", request(), null, config);
        PausedExchange ex3 = BreakpointRegistry.getInstance().pause("corr-c", request(), null, config);

        assertThat("first should succeed", ex1, is(notNullValue()));
        assertThat("second should succeed", ex2, is(notNullValue()));
        assertThat("third should be rejected (cap reached)", ex3, is(nullValue()));
        assertThat(BreakpointRegistry.getInstance().size(), is(2));
    }

    @Test
    public void shouldAutoContinueOnTimeout() throws Exception {
        // use a very short timeout
        Configuration config = configWith(true, 200, 50);

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-timeout", request(), null, config);
        assertThat(exchange, is(notNullValue()));

        // wait for the timeout to fire + some margin
        BreakpointDecision decision = exchange.getDecisionFuture().get(2, TimeUnit.SECONDS);
        assertThat("should auto-continue on timeout", decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldResetAndAutoContinueAllHeld() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        PausedExchange ex1 = BreakpointRegistry.getInstance().pause("corr-r1", request(), null, config);
        PausedExchange ex2 = BreakpointRegistry.getInstance().pause("corr-r2", request(), null, config);

        BreakpointRegistry.getInstance().reset();

        assertThat(BreakpointRegistry.getInstance().size(), is(0));
        assertThat(BreakpointRegistry.getInstance().entries().isEmpty(), is(true));

        // both should have been auto-continued
        BreakpointDecision d1 = ex1.getDecisionFuture().get(1, TimeUnit.SECONDS);
        BreakpointDecision d2 = ex2.getDecisionFuture().get(1, TimeUnit.SECONDS);
        assertThat(d1.getAction(), is(BreakpointDecision.Action.CONTINUE));
        assertThat(d2.getAction(), is(BreakpointDecision.Action.CONTINUE));
    }

    @Test
    public void shouldRemoveFromRegistryAfterResolution() throws Exception {
        Configuration config = configWith(true, 30000, 50);

        BreakpointRegistry.getInstance().pause("corr-cleanup", request(), null, config);
        assertThat(BreakpointRegistry.getInstance().size(), is(1));

        BreakpointRegistry.getInstance().resolveContinue("corr-cleanup");

        // give the whenComplete callback time to fire
        Thread.sleep(100);
        assertThat("should be removed after resolution", BreakpointRegistry.getInstance().size(), is(0));
    }

    @Test
    public void shouldReportAgeInPausedExchange() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-age", request(), null, config);
        Thread.sleep(50);
        assertThat("ageMillis should be positive", exchange.ageMillis(), greaterThanOrEqualTo(40L));

        // cleanup
        BreakpointRegistry.getInstance().resolveContinue("corr-age");
    }

    @Test
    public void shouldStoreCapturedRequest() throws Exception {
        Configuration config = configWith(true, 30000, 50);
        HttpRequest req = request().withMethod("POST").withPath("/data").withBody("payload");

        PausedExchange exchange = BreakpointRegistry.getInstance().pause("corr-capture", req, null, config);
        assertThat(exchange.getCapturedRequest().getMethod().getValue(), is("POST"));
        assertThat(exchange.getCapturedRequest().getPath().getValue(), is("/data"));

        BreakpointRegistry.getInstance().resolveContinue("corr-capture");
    }
}
