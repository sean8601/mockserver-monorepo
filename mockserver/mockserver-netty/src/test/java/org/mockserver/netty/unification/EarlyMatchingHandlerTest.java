package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class EarlyMatchingHandlerTest {

    private EmbeddedChannel channel;

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldDispatchEarlyResponseWhenMatcherHasRespondBeforeBody() {
        // given
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger(configuration, EarlyMatchingHandlerTest.class);
        Scheduler scheduler = new Scheduler(configuration, logger);
        HttpState httpState = new HttpState(configuration, logger, scheduler);
        HttpActionHandler actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        httpState.add(new Expectation(
            request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403)));

        channel = new EmbeddedChannel(
            new EarlyMatchingHandler(configuration, httpState, actionHandler, false)
        );

        // when - send headers
        DefaultHttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, "10000");
        channel.writeInbound(req);

        // then - EARLY_DISPATCHED is set (attribute persists even after channel close)
        assertThat(channel.attr(EarlyMatchingHandler.EARLY_DISPATCHED).get(), is(true));

        // request was consumed by EarlyMatchingHandler, not propagated downstream
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void shouldPassThroughWhenNoMatchingEarlyExpectation() {
        // given
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger(configuration, EarlyMatchingHandlerTest.class);
        Scheduler scheduler = new Scheduler(configuration, logger);
        HttpState httpState = new HttpState(configuration, logger, scheduler);
        HttpActionHandler actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        // no expectations with respondBeforeBody=true

        channel = new EmbeddedChannel(
            new EarlyMatchingHandler(configuration, httpState, actionHandler, false)
        );

        // when
        DefaultHttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        channel.writeInbound(req);

        // then - request propagated downstream, EARLY_DISPATCHED not set
        assertThat(channel.attr(EarlyMatchingHandler.EARLY_DISPATCHED).get(), is(nullValue()));
        Object propagated = channel.readInbound();
        assertThat(propagated, is(notNullValue()));
        assertThat(propagated, is(req));
        io.netty.util.ReferenceCountUtil.release(propagated);
    }

    @Test
    public void shouldBypassEarlyMatchingForConnectMethod() {
        // given
        Configuration configuration = configuration();
        MockServerLogger logger = new MockServerLogger(configuration, EarlyMatchingHandlerTest.class);
        Scheduler scheduler = new Scheduler(configuration, logger);
        HttpState httpState = new HttpState(configuration, logger, scheduler);
        HttpActionHandler actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        httpState.add(new Expectation(
            request().withRespondBeforeBody(true)
        ).thenRespond(response().withStatusCode(403)));

        channel = new EmbeddedChannel(
            new EarlyMatchingHandler(configuration, httpState, actionHandler, false)
        );

        // when - CONNECT request
        DefaultHttpRequest connect = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, "example.com:443");
        channel.writeInbound(connect);

        // then - bypassed, passed through downstream, EARLY_DISPATCHED not set
        assertThat(channel.attr(EarlyMatchingHandler.EARLY_DISPATCHED).get(), is(nullValue()));
        Object propagated = channel.readInbound();
        assertThat(propagated, is(notNullValue()));
        io.netty.util.ReferenceCountUtil.release(propagated);
    }
}
