package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

public class HttpLlmResponseActionHandlerTest {

    @Test
    public void shouldReturn200ForRegisteredCodec() {
        // given — ANTHROPIC codec is registered
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — registered codecs return 200
        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void shouldReturn400ForUnregisteredProvider() {
        // given — GEMINI codec is not registered
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.GEMINI)
            .withCompletion(completion().withText("test"));
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("GEMINI"));
    }

    @Test
    public void shouldReturn200ForRegisteredProviders() {
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/test");

        // ANTHROPIC and OPENAI have registered codecs and should return 200
        for (Provider provider : new Provider[]{Provider.ANTHROPIC, Provider.OPENAI}) {
            HttpLlmResponse llmResponse = llmResponse()
                .withProvider(provider)
                .withCompletion(completion().withText("test"));

            HttpResponse response = handler.handle(llmResponse, request);

            assertThat("expected 200 for registered provider " + provider,
                response.getStatusCode(), is(200));
        }
    }

    @Test
    public void shouldReturn400ForUnregisteredProviders() {
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/test");

        // Unregistered providers should return 400
        for (Provider provider : new Provider[]{Provider.GEMINI, Provider.BEDROCK, Provider.OLLAMA, Provider.OPENAI_RESPONSES, Provider.AZURE_OPENAI}) {
            HttpLlmResponse llmResponse = llmResponse()
                .withProvider(provider)
                .withCompletion(completion().withText("test"));

            HttpResponse response = handler.handle(llmResponse, request);

            assertThat("expected 400 for unregistered provider " + provider,
                response.getStatusCode(), is(400));
            assertThat(
                "expected error message for provider " + provider,
                response.getBodyAsString(),
                containsString(provider.name())
            );
        }
    }

    @Test
    public void shouldReturn400WhenProviderIsNull() {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("null"));
    }
}
