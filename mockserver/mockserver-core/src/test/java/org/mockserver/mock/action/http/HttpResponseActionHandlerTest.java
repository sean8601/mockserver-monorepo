package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.FileBody;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.MediaType;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void shouldSynthesizeGraphQLResponseFromRegisteredSchemaWhenResponseHasNoBody() throws Exception {
        // given - a matched GraphQL expectation whose request body carries a schema, and a response with no body
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name email } }")
            .withSchema("type Query { user: User } type User { id: ID name: String email: String age: Int }");
        HttpResponse responseAction = response();

        // when - the incoming request carries the GraphQL query
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name email } }\"}"),
            request().withBody(matchedRequestBody));

        // then - a schema-valid response is synthesized with only the requested fields
        assertThat(actual.getBody().getContentType(), containsString("application/json"));
        JsonNode data = MAPPER.readTree(actual.getBodyAsString()).get("data");
        JsonNode user = data.get("user");
        assertTrue("name requested", user.has("name"));
        assertTrue("email requested", user.has("email"));
        assertThat("age not requested", user.get("age"), is(nullValue()));
        assertTrue(user.get("name").isTextual());
    }

    @Test
    public void shouldNotSynthesizeWhenResponseAlreadyHasBody() {
        // given - the expectation has a schema but the response provides an explicit body
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name } }")
            .withSchema("type Query { user: User } type User { name: String }");
        HttpResponse responseAction = response().withBody("{\"data\":{\"user\":{\"name\":\"Alice\"}}}");

        // when
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name } }\"}"),
            request().withBody(matchedRequestBody));

        // then - the explicit body is preserved verbatim, no synthesis
        assertThat(actual.getBodyAsString(), is("{\"data\":{\"user\":{\"name\":\"Alice\"}}}"));
    }

    @Test
    public void shouldLeaveBodyUnsetWhenNoSchemaRegistered() {
        // given - a GraphQL request body without a schema, response with no body
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name } }");
        HttpResponse responseAction = response();

        // when
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name } }\"}"),
            request().withBody(matchedRequestBody));

        // then - no synthesis occurs, body remains unset
        assertThat(actual.getBody(), is(nullValue()));
    }

    @Test
    public void shouldLeaveBodyUnsetWhenSchemaIsInvalid() {
        // given - an unparseable schema must not fail the request (fail-soft)
        GraphQLBody matchedRequestBody = new GraphQLBody("{ user { name } }")
            .withSchema("this is not valid SDL <<<");
        HttpResponse responseAction = response();

        // when
        HttpResponse actual = httpResponseActionHandler.handle(
            responseAction,
            request().withMethod("POST").withPath("/graphql").withBody("{\"query\":\"{ user { name } }\"}"),
            request().withBody(matchedRequestBody));

        // then - body remains unset rather than throwing
        assertThat(actual.getBody(), is(nullValue()));
    }

}
