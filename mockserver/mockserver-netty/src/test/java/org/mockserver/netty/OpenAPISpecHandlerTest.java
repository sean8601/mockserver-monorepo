package org.mockserver.netty;

import com.google.common.io.ByteStreams;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Tests for the OpenAPI spec endpoint handler.
 */
public class OpenAPISpecHandlerTest {

    private ChannelHandlerContext mockContext() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);
        return ctx;
    }

    private HttpResponse captureResponse(ChannelHandlerContext ctx) {
        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(ctx).writeAndFlush(captor.capture());
        return captor.getValue();
    }

    /**
     * The OpenAPI spec version is stamped by the release process, so the
     * expected value is read from the spec resource itself rather than pinned
     * to a literal. The assertion then tracks the version automatically and
     * never needs a manual update when a release bumps it.
     *
     * The {@code ^  version: } pattern mirrors the sed expression that
     * scripts/release/finalize.sh uses to stamp this same line, so the test
     * and the release tooling stay in agreement on the spec's layout.
     */
    private static String expectedSpecVersion() throws Exception {
        try (InputStream inputStream = OpenAPISpecHandler.class
                .getResourceAsStream("/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml")) {
            assertThat("OpenAPI spec resource must be on the classpath", inputStream, is(notNullValue()));
            String spec = new String(ByteStreams.toByteArray(inputStream), UTF_8);
            Matcher matcher = Pattern.compile("(?m)^  version: (.+)$").matcher(spec);
            assertThat("OpenAPI spec resource must declare an info.version", matcher.find(), is(true));
            return matcher.group(1).trim();
        }
    }

    @Test
    public void shouldReturnOpenAPISpecWithCorrectContentType() throws Exception {
        // given
        OpenAPISpecHandler handler = new OpenAPISpecHandler();
        ChannelHandlerContext ctx = mockContext();
        HttpRequest getRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        handler.renderOpenAPISpec(ctx, getRequest);

        // then
        HttpResponse response = captureResponse(ctx);
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), is("application/yaml; charset=utf-8"));
        assertThat(response.getBodyAsString(), containsString("openapi: 3.0.0"));
        assertThat(response.getBodyAsString(), containsString("title: MockServer API"));
        assertThat(response.getBodyAsString(), containsString("version: " + expectedSpecVersion()));
        assertThat(response.getBodyAsString(), containsString("/mockserver/expectation"));
        assertThat(response.getBodyAsString(), containsString("/mockserver/openapi.yaml"));
    }

    @Test
    public void shouldReturnContentLengthHeader() throws Exception {
        // given
        OpenAPISpecHandler handler = new OpenAPISpecHandler();
        ChannelHandlerContext ctx = mockContext();
        HttpRequest getRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        handler.renderOpenAPISpec(ctx, getRequest);

        // then
        HttpResponse response = captureResponse(ctx);
        String contentLength = response.getFirstHeader("content-length");
        assertThat(Integer.parseInt(contentLength) > 0, is(true));
        assertThat(response.getBodyAsString().getBytes("UTF-8").length, is(Integer.parseInt(contentLength)));
    }

    @Test
    public void shouldReturn404ForNonGetRequest() throws Exception {
        // given
        OpenAPISpecHandler handler = new OpenAPISpecHandler();
        ChannelHandlerContext ctx = mockContext();
        HttpRequest putRequest = request("/mockserver/openapi.yaml").withMethod("PUT");

        // when
        handler.renderOpenAPISpec(ctx, putRequest);

        // then
        HttpResponse response = captureResponse(ctx);
        assertThat(response.getStatusCode(), is(404));
    }

    @Test
    public void shouldIncludeConnectionKeepAliveHeaderWhenRequested() throws Exception {
        // given
        OpenAPISpecHandler handler = new OpenAPISpecHandler();
        ChannelHandlerContext ctx = mockContext();
        HttpRequest getRequest = request("/mockserver/openapi.yaml").withMethod("GET").withKeepAlive(true);

        // when
        handler.renderOpenAPISpec(ctx, getRequest);

        // then
        HttpResponse response = captureResponse(ctx);
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("connection"), is("keep-alive"));
    }

    @Test
    public void shouldReturnConsistentResponsesFromCache() throws Exception {
        // given
        OpenAPISpecHandler handler = new OpenAPISpecHandler();
        ChannelHandlerContext ctx1 = mockContext();
        ChannelHandlerContext ctx2 = mockContext();
        HttpRequest getRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when - call twice
        handler.renderOpenAPISpec(ctx1, getRequest);
        handler.renderOpenAPISpec(ctx2, getRequest);

        // then - both responses should have identical content
        HttpResponse response1 = captureResponse(ctx1);
        HttpResponse response2 = captureResponse(ctx2);
        assertThat(response1.getBodyAsString(), is(response2.getBodyAsString()));
    }

    @Test
    public void shouldContainMCPDescription() throws Exception {
        // given
        OpenAPISpecHandler handler = new OpenAPISpecHandler();
        ChannelHandlerContext ctx = mockContext();
        HttpRequest getRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        handler.renderOpenAPISpec(ctx, getRequest);

        // then
        HttpResponse response = captureResponse(ctx);
        assertThat(response.getBodyAsString(), containsString("MCP"));
        assertThat(response.getBodyAsString(), containsString("Model Context Protocol"));
    }

}
