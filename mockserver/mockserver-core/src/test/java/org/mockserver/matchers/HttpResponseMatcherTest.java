package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.StringBody.exact;

/**
 * Tests for {@link HttpResponseMatcher}.
 */
public class HttpResponseMatcherTest {

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, HttpResponseMatcherTest.class);

    @Test
    public void shouldMatchWhenTemplateIsNull() {
        // null template matches anything
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger, null);
        assertThat(matcher.matches(response().withStatusCode(200)), is(true));
        assertThat(matcher.matches(response().withStatusCode(500)), is(true));
        assertThat(matcher.matches(null), is(true));
    }

    @Test
    public void shouldMatchWhenTemplateIsEmpty() {
        // empty template matches anything
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger, response());
        assertThat(matcher.matches(response().withStatusCode(200)), is(true));
        assertThat(matcher.matches(response().withStatusCode(500)), is(true));
    }

    @Test
    public void shouldMatchStatusCode() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withStatusCode(200));
        assertThat(matcher.matches(response().withStatusCode(200)), is(true));
        assertThat(matcher.matches(response().withStatusCode(404)), is(false));
    }

    @Test
    public void shouldNotMatchNullActualWhenTemplateHasStatusCode() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withStatusCode(200));
        assertThat(matcher.matches(null), is(false));
    }

    @Test
    public void shouldMatchReasonPhrase() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withReasonPhrase("OK"));
        assertThat(matcher.matches(response().withReasonPhrase("OK")), is(true));
        assertThat(matcher.matches(response().withReasonPhrase("Not Found")), is(false));
    }

    @Test
    public void shouldMatchReasonPhraseRegex() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withReasonPhrase(".*Found.*"));
        assertThat(matcher.matches(response().withReasonPhrase("Not Found")), is(true));
        assertThat(matcher.matches(response().withReasonPhrase("OK")), is(false));
    }

    @Test
    public void shouldMatchHeaders() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withHeaders(new Header("Content-Type", "application/json")));
        assertThat(matcher.matches(
            response().withHeaders(
                new Header("Content-Type", "application/json"),
                new Header("X-Extra", "value")
            )), is(true));
        assertThat(matcher.matches(
            response().withHeaders(
                new Header("Content-Type", "text/html")
            )), is(false));
    }

    @Test
    public void shouldMatchJsonBody() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withBody(json("{\"status\": \"ok\"}")));
        assertThat(matcher.matches(
            response().withBody("{\"status\": \"ok\"}")), is(true));
        assertThat(matcher.matches(
            response().withBody("{\"status\": \"error\"}")), is(false));
    }

    @Test
    public void shouldMatchJsonBodyWithExtraFields() {
        // JSON matching with ONLY_MATCHING_FIELDS (default) tolerates extra fields in actual
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withBody(json("{\"status\": \"ok\"}")));
        assertThat(matcher.matches(
            response().withBody("{\"status\": \"ok\", \"extra\": 1}")), is(true));
    }

    @Test
    public void shouldMatchStringBodyOnResponse() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withBody("some response text"));
        assertThat(matcher.matches(
            response().withBody("some response text")), is(true));
        assertThat(matcher.matches(
            response().withBody("different text")), is(false));
    }

    @Test
    public void shouldMatchExactStringBody() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withBody(exact("hello world")));
        assertThat(matcher.matches(
            response().withBody("hello world")), is(true));
        assertThat(matcher.matches(
            response().withBody("goodbye")), is(false));
    }

    @Test
    public void shouldMatchStatusCodeAndHeaders() {
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response()
                .withStatusCode(200)
                .withHeaders(new Header("Content-Type", "application/json")));
        assertThat(matcher.matches(
            response()
                .withStatusCode(200)
                .withHeaders(new Header("Content-Type", "application/json"))),
            is(true));
        assertThat(matcher.matches(
            response()
                .withStatusCode(404)
                .withHeaders(new Header("Content-Type", "application/json"))),
            is(false));
        assertThat(matcher.matches(
            response()
                .withStatusCode(200)
                .withHeaders(new Header("Content-Type", "text/html"))),
            is(false));
    }

    @Test
    public void shouldIgnoreUnsetTemplateFields() {
        // template only specifies statusCode -- headers and body should not constrain
        HttpResponseMatcher matcher = new HttpResponseMatcher(configuration, mockServerLogger,
            response().withStatusCode(200));
        assertThat(matcher.matches(
            response()
                .withStatusCode(200)
                .withHeaders(new Header("X-Any", "value"))
                .withBody("any body content")),
            is(true));
    }
}
