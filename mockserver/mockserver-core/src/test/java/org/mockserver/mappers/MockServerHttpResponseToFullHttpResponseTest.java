package org.mockserver.mappers;

import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCounted;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class MockServerHttpResponseToFullHttpResponseTest {

    private final MockServerHttpResponseToFullHttpResponse mapper =
        new MockServerHttpResponseToFullHttpResponse(new MockServerLogger());

    // --- status code ---

    @Test
    public void shouldMapStatusCode200() {
        // given
        HttpResponse httpResponse = response().withStatusCode(200);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        assertThat(result, hasSize(1));
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(200));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapStatusCode404() {
        // given
        HttpResponse httpResponse = response().withStatusCode(404);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(404));
            assertThat(fullResponse.status().reasonPhrase(), equalTo("Not Found"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapCustomReasonPhrase() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withReasonPhrase("Custom Reason");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().reasonPhrase(), equalTo("Custom Reason"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldDefaultToStatus200WhenNull() {
        // given
        HttpResponse httpResponse = response();

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.status().code(), equalTo(200));
        } finally {
            fullResponse.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapHeaders() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeaders(
                new Header("headerName1", "headerValue1"),
                new Header("headerName2", "headerValue2")
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get("headerName1"), equalTo("headerValue1"));
            assertThat(fullResponse.headers().get("headerName2"), equalTo("headerValue2"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapMultipleHeaderValues() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("headerName1", "value1", "value2");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().getAll("headerName1"), hasSize(2));
            assertThat(fullResponse.headers().getAll("headerName1"), contains("value1", "value2"));
        } finally {
            fullResponse.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("some body content");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            byte[] bodyBytes = new byte[fullResponse.content().readableBytes()];
            fullResponse.content().readBytes(bodyBytes);
            assertThat(new String(bodyBytes, StandardCharsets.UTF_8), equalTo("some body content"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x10, 0x20, 0x30};
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody(binaryData);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            byte[] bodyBytes = new byte[fullResponse.content().readableBytes()];
            fullResponse.content().readBytes(bodyBytes);
            assertThat(bodyBytes, equalTo(binaryData));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        HttpResponse httpResponse = response().withStatusCode(200);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.content().readableBytes(), equalTo(0));
        } finally {
            fullResponse.release();
        }
    }

    // --- content length ---

    @Test
    public void shouldSetContentLengthForBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("12345");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("5"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldOverrideContentLengthFromConnectionOptions() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("12345")
            .withConnectionOptions(
                new ConnectionOptions()
                    .withContentLengthHeaderOverride(999)
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("999"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldSuppressContentLengthHeader() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("12345")
            .withConnectionOptions(
                new ConnectionOptions()
                    .withSuppressContentLengthHeader(true)
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH), is(nullValue()));
        } finally {
            fullResponse.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapCookies() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withCookie("cookieName1", "cookieValue1")
            .withCookie("cookieName2", "cookieValue2");

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            List<String> setCookieHeaders = fullResponse.headers().getAll(HttpHeaderNames.SET_COOKIE);
            assertThat(setCookieHeaders, hasSize(2));
            assertThat(setCookieHeaders.toString(), containsString("cookieName1=cookieValue1"));
            assertThat(setCookieHeaders.toString(), containsString("cookieName2=cookieValue2"));
        } finally {
            fullResponse.release();
        }
    }

    // --- content type from body ---

    @Test
    public void shouldSetContentTypeFromBody() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody(new StringBody("json content", null, false, MediaType.APPLICATION_JSON));

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("application/json"));
        } finally {
            fullResponse.release();
        }
    }

    @Test
    public void shouldNotOverrideExplicitContentTypeHeader() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/html")
            .withBody(new StringBody("some content", null, false, MediaType.APPLICATION_JSON));

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("text/html"));
        } finally {
            fullResponse.release();
        }
    }

    // --- chunked transfer encoding ---

    @Test
    public void shouldMapChunkedResponse() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("some chunked body content")
            .withConnectionOptions(
                new ConnectionOptions()
                    .withChunkSize(5)
            );

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        assertThat(result.size(), greaterThan(1));
        DefaultHttpResponse firstPart = (DefaultHttpResponse) result.get(0);
        assertThat(firstPart.status().code(), equalTo(200));
        assertThat(firstPart.headers().get(HttpHeaderNames.TRANSFER_ENCODING), equalTo("chunked"));

        // release every reference-counted element. The first element is a DefaultHttpResponse
        // (header object), the remainder are DefaultHttpContent chunks (last one is
        // DefaultLastHttpContent). Guarding only on DefaultHttpContent would leak the header.
        for (DefaultHttpObject httpObject : result) {
            if (httpObject instanceof ReferenceCounted) {
                ((ReferenceCounted) httpObject).release();
            }
        }
    }

    // --- stream id for HTTP/2 ---

    @Test
    public void shouldSetStreamIdForHTTP2() {
        // given
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("body")
            .withStreamId(5);

        // when
        List<DefaultHttpObject> result = mapper.mapMockServerResponseToNettyResponse(httpResponse);

        // then
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) result.get(0);
        try {
            assertThat(fullResponse.headers().getInt("x-http2-stream-id"), equalTo(5));
        } finally {
            fullResponse.release();
        }
    }
}
