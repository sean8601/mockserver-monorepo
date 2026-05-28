package org.mockserver.mappers;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * @author jamesdbloom
 */
public class FullHttpResponseToMockServerHttpResponseTest {

    private final FullHttpResponseToMockServerHttpResponse mapper =
        new FullHttpResponseToMockServerHttpResponse(new MockServerLogger());

    // --- status code ---

    @Test
    public void shouldMapStatusCode200() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(200));
            assertThat(result.getReasonPhrase(), equalTo("OK"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapStatusCode404() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(404));
            assertThat(result.getReasonPhrase(), equalTo("Not Found"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapCustomReasonPhrase() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            new HttpResponseStatus(299, "Custom Reason")
        );

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(299));
            assertThat(result.getReasonPhrase(), equalTo("Custom Reason"));
        } finally {
            nettyResponse.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapHeaders() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("headerName1", "headerValue1");
        nettyResponse.headers().add("headerName2", "headerValue2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getFirstHeader("headerName1"), equalTo("headerValue1"));
            assertThat(result.getFirstHeader("headerName2"), equalTo("headerValue2"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapMultipleHeaderValues() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("headerName1", "value1");
        nettyResponse.headers().add("headerName1", "value2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            List<Header> headers = result.getHeaderList();
            boolean found = false;
            for (Header h : headers) {
                if (h.getName().getValue().equals("headerName1")) {
                    assertThat(h.getValues().size(), equalTo(2));
                    found = true;
                }
            }
            assertThat("headerName1 should be present", found, is(true));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldHandleNoHeaders() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getHeaderList(), empty());
        } finally {
            nettyResponse.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapSetCookieHeaders() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("Set-Cookie", "cookieName1=cookieValue1");
        nettyResponse.headers().add("Set-Cookie", "cookieName2=cookieValue2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasSize(2));
            assertThat(cookies, hasItem(new Cookie("cookieName1", "cookieValue1")));
            assertThat(cookies, hasItem(new Cookie("cookieName2", "cookieValue2")));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapCookieHeaderInResponse() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("Cookie", "cookieName1=cookieValue1; cookieName2=cookieValue2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasSize(2));
            assertThat(cookies, hasItem(new Cookie("cookieName1", "cookieValue1")));
            assertThat(cookies, hasItem(new Cookie("cookieName2", "cookieValue2")));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldHandleNoCookies() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getCookieList(), empty());
        } finally {
            nettyResponse.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        byte[] bodyBytes = "response body content".getBytes(StandardCharsets.UTF_8);
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer(bodyBytes)
        );
        nettyResponse.headers().set(CONTENT_TYPE, "text/plain; charset=utf-8");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsString(), equalTo("response body content"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x10, 0x20, 0x30, 0x40};
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer(binaryData)
        );
        nettyResponse.headers().set(CONTENT_TYPE, "application/octet-stream");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(binaryData));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(new byte[0]));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapJsonBody() {
        // given
        String json = "{\"key\":\"value\"}";
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer(json, StandardCharsets.UTF_8)
        );
        nettyResponse.headers().set(CONTENT_TYPE, "application/json; charset=utf-8");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsString(), equalTo(json));
        } finally {
            nettyResponse.release();
        }
    }

    // --- null response ---

    @Test
    public void shouldHandleNullResponse() {
        // when
        HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(null);

        // then
        assertThat(result, is(notNullValue()));
    }

    // --- status codes ---

    @Test
    public void shouldMapStatusCode500() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR
        );

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(500));
            assertThat(result.getReasonPhrase(), equalTo("Internal Server Error"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapStatusCode302() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND
        );
        nettyResponse.headers().add("Location", "http://example.com/redirect");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(302));
            assertThat(result.getFirstHeader("Location"), equalTo("http://example.com/redirect"));
        } finally {
            nettyResponse.release();
        }
    }
}
