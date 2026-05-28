package org.mockserver.mappers;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Header;
import org.mockserver.model.MediaType;
import org.mockserver.model.Protocol;
import org.mockserver.model.StringBody;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class MockServerHttpRequestToFullHttpRequestTest {

    private final MockServerHttpRequestToFullHttpRequest mapper =
        new MockServerHttpRequestToFullHttpRequest(new MockServerLogger(), null);

    // --- method ---

    @Test
    public void shouldMapGetMethod() {
        // given
        HttpRequest httpRequest = request().withMethod("GET").withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.method().name(), equalTo("GET"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldMapPostMethod() {
        // given
        HttpRequest httpRequest = request().withMethod("POST").withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.method().name(), equalTo("POST"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldMapPutMethod() {
        // given
        HttpRequest httpRequest = request().withMethod("PUT").withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.method().name(), equalTo("PUT"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldDefaultToGetWhenNoMethodSet() {
        // given
        HttpRequest httpRequest = request().withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.method().name(), equalTo("GET"));
        } finally {
            result.release();
        }
    }

    // --- URI / path ---

    @Test
    public void shouldMapPath() {
        // given
        HttpRequest httpRequest = request().withPath("/some/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.uri(), equalTo("/some/path"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldMapPathWithQueryParameters() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/some/path")
            .withQueryStringParameter("paramOne", "valueOne")
            .withQueryStringParameter("paramTwo", "valueTwo");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.uri(), containsString("/some/path"));
            assertThat(result.uri(), containsString("paramOne=valueOne"));
            assertThat(result.uri(), containsString("paramTwo=valueTwo"));
        } finally {
            result.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapCustomHeaders() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withHeader("X-Custom-Header", "customValue");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get("X-Custom-Header"), equalTo("customValue"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldSetHostHeader() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withHeader("Host", "localhost:8080");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get(HttpHeaderNames.HOST), equalTo("localhost:8080"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldSetConnectionHeader() {
        // given
        HttpRequest httpRequest = request().withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get(HttpHeaderNames.CONNECTION), is(notNullValue()));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldSetAcceptEncodingHeader() {
        // given
        HttpRequest httpRequest = request().withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            String acceptEncoding = result.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
            assertThat(acceptEncoding, containsString("gzip"));
            assertThat(acceptEncoding, containsString("deflate"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldNotSetContentLengthAsUserHeader() {
        // given - Content-Length is a hop-by-hop header that gets replaced
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withHeader("Content-Length", "999");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then - Content-Length should reflect actual body size, not user-supplied value
            assertThat(result.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("0"));
        } finally {
            result.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withBody("some body content");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            byte[] bodyBytes = new byte[result.content().readableBytes()];
            result.content().readBytes(bodyBytes);
            assertThat(new String(bodyBytes, StandardCharsets.UTF_8), equalTo("some body content"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, 0x03};
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withBody(binaryData);

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            byte[] resultBytes = new byte[result.content().readableBytes()];
            result.content().readBytes(resultBytes);
            assertThat(resultBytes, equalTo(binaryData));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        HttpRequest httpRequest = request().withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.content().readableBytes(), equalTo(0));
            assertThat(result.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("0"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldSetContentLengthForBody() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withBody("12345");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get(HttpHeaderNames.CONTENT_LENGTH), equalTo("5"));
        } finally {
            result.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapCookies() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withCookie("cookieName1", "cookieValue1")
            .withCookie("cookieName2", "cookieValue2");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            String cookieHeader = result.headers().get(HttpHeaderNames.COOKIE);
            assertThat(cookieHeader, containsString("cookieName1=cookieValue1"));
            assertThat(cookieHeader, containsString("cookieName2=cookieValue2"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldHandleNoCookies() {
        // given
        HttpRequest httpRequest = request().withPath("/path");

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get(HttpHeaderNames.COOKIE), is(nullValue()));
        } finally {
            result.release();
        }
    }

    // --- content type from body ---

    @Test
    public void shouldSetContentTypeFromBody() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withBody(new StringBody("json content", null, false, MediaType.APPLICATION_JSON));

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("application/json"));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldNotOverrideExplicitContentTypeHeader() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withHeader("Content-Type", "text/html")
            .withBody(new StringBody("some content", null, false, MediaType.APPLICATION_JSON));

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get(HttpHeaderNames.CONTENT_TYPE), equalTo("text/html"));
        } finally {
            result.release();
        }
    }

    // --- HTTP/2 ---

    @Test
    public void shouldSetSchemeForHTTP2Secure() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withSecure(true)
            .withProtocol(Protocol.HTTP_2)
            .withStreamId(3);

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get("x-http2-scheme"), equalTo("https"));
            assertThat(result.headers().getInt("x-http2-stream-id"), equalTo(3));
        } finally {
            result.release();
        }
    }

    @Test
    public void shouldSetSchemeForHTTP2NonSecure() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/path")
            .withSecure(false)
            .withProtocol(Protocol.HTTP_2);

        // when
        FullHttpRequest result = mapper.mapMockServerRequestToNettyRequest(httpRequest);

        try {
            // then
            assertThat(result.headers().get("x-http2-scheme"), equalTo("http"));
        } finally {
            result.release();
        }
    }

    // --- getURI ---

    @Test
    public void shouldBuildURIWithPath() {
        // given
        HttpRequest httpRequest = request().withPath("/test/path");

        // when
        String uri = mapper.getURI(httpRequest, null);

        // then
        assertThat(uri, equalTo("/test/path"));
    }

    @Test
    public void shouldBuildURIWithQueryParameters() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/test")
            .withQueryStringParameter("key", "value");

        // when
        String uri = mapper.getURI(httpRequest, null);

        // then
        assertThat(uri, containsString("/test"));
        assertThat(uri, containsString("key=value"));
    }

    @Test
    public void shouldBuildURIWithHTTPProxy() {
        // given
        HttpRequest httpRequest = request()
            .withPath("/test")
            .withHeader("Host", "example.com")
            .withSecure(false);

        // when
        String uri = mapper.getURI(httpRequest, Collections.singletonMap(
            org.mockserver.proxyconfiguration.ProxyConfiguration.Type.HTTP,
            org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration(
                org.mockserver.proxyconfiguration.ProxyConfiguration.Type.HTTP,
                "localhost:1090"
            )
        ));

        // then
        assertThat(uri, startsWith("http://example.com/test"));
    }
}
