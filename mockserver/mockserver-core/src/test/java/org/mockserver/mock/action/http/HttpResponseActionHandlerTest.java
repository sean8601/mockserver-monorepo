package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.FileBody;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.MediaType;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class HttpResponseActionHandlerTest {

    private final HttpResponseActionHandler httpResponseActionHandler = new HttpResponseActionHandler(new MockServerLogger(), new Configuration());

    @Test
    public void shouldHandleHttpRequests() {
        // given
        HttpResponse httpResponse = mock(HttpResponse.class);

        // when
        httpResponseActionHandler.handle(httpResponse);

        // then
        verify(httpResponse).clone();
    }

    @Test
    public void shouldRenderMustacheTemplatedFileBodyAgainstRequest() {
        // given - a static response whose body file is processed as a Mustache template
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/templates/sample_mustache_body.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE)
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse, request().withMethod("PUT").withPath("/somePath"));

        // then - placeholders resolved from the request, content type preserved
        assertThat(actual.getBodyAsString(), containsString("\"method\": \"PUT\""));
        assertThat(actual.getBodyAsString(), containsString("\"path\": \"/somePath\""));
        assertThat(actual.getBody().getContentType(), containsString("application/json"));
    }

    @Test
    public void shouldReturnFileBodyVerbatimWhenNoTemplateType() {
        // given - a plain FileBody (no templateType) must be returned untouched
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/templates/sample_mustache_body.json", MediaType.APPLICATION_JSON)
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse, request().withMethod("PUT").withPath("/somePath"));

        // then - body remains a FileBody, no rendering performed
        assertThat(actual.getBody(), is(instanceOf(FileBody.class)));
    }

    @Test
    public void shouldNotRenderTemplatedFileBodyWhenRequestUnavailable() {
        // given - the no-request overload (e.g. early/secondary paths) must not template
        HttpResponse httpResponse = response().withBody(
            new FileBody("org/mockserver/templates/sample_mustache_body.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE)
        );

        // when
        HttpResponse actual = httpResponseActionHandler.handle(httpResponse);

        // then - body remains an (unrendered) FileBody
        assertThat(actual.getBody(), is(instanceOf(FileBody.class)));
    }

}
