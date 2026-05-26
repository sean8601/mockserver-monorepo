package org.mockserver.llm;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.Matchers.*;

public class IsolationSourceTest {

    @Test
    public void shouldCreateHeaderSource() {
        IsolationSource source = IsolationSource.header("x-session-id");
        assertThat(source.getKind(), is(IsolationSource.Kind.HEADER));
        assertThat(source.getName(), is("x-session-id"));
    }

    @Test
    public void shouldCreateQueryParameterSource() {
        IsolationSource source = IsolationSource.queryParameter("agent");
        assertThat(source.getKind(), is(IsolationSource.Kind.QUERY_PARAMETER));
        assertThat(source.getName(), is("agent"));
    }

    @Test
    public void shouldCreateCookieSource() {
        IsolationSource source = IsolationSource.cookie("sid");
        assertThat(source.getKind(), is(IsolationSource.Kind.COOKIE));
        assertThat(source.getName(), is("sid"));
    }

    @Test
    public void shouldEncodeHeader() {
        IsolationSource source = IsolationSource.header("x-session-id");
        assertThat(source.encode(), is("header:x-session-id"));
    }

    @Test
    public void shouldEncodeQueryParameter() {
        IsolationSource source = IsolationSource.queryParameter("agent");
        assertThat(source.encode(), is("query_parameter:agent"));
    }

    @Test
    public void shouldEncodeCookie() {
        IsolationSource source = IsolationSource.cookie("sid");
        assertThat(source.encode(), is("cookie:sid"));
    }

    @Test
    public void shouldDecodeHeader() {
        IsolationSource decoded = IsolationSource.decode("header:x-session-id");
        assertThat(decoded, is(IsolationSource.header("x-session-id")));
    }

    @Test
    public void shouldDecodeQueryParameter() {
        IsolationSource decoded = IsolationSource.decode("query_parameter:agent");
        assertThat(decoded, is(IsolationSource.queryParameter("agent")));
    }

    @Test
    public void shouldDecodeCookie() {
        IsolationSource decoded = IsolationSource.decode("cookie:sid");
        assertThat(decoded, is(IsolationSource.cookie("sid")));
    }

    @Test
    public void shouldReturnNullForInvalidEncoding() {
        assertThat(IsolationSource.decode(null), is(nullValue()));
        assertThat(IsolationSource.decode(""), is(nullValue()));
        assertThat(IsolationSource.decode("noseparator"), is(nullValue()));
        assertThat(IsolationSource.decode("unknown:name"), is(nullValue()));
        assertThat(IsolationSource.decode("header:"), is(nullValue()));
    }

    @Test
    public void shouldRoundTripEncodeDecode() {
        IsolationSource original = IsolationSource.header("x-request-id");
        IsolationSource roundTripped = IsolationSource.decode(original.encode());
        assertThat(roundTripped, is(original));
    }

    @Test
    public void shouldBeEqualWhenSameKindAndName() {
        IsolationSource a = IsolationSource.header("x-id");
        IsolationSource b = IsolationSource.header("x-id");
        assertThat(a, is(b));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentKind() {
        IsolationSource a = IsolationSource.header("name");
        IsolationSource b = IsolationSource.cookie("name");
        assertThat(a, is(not(b)));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentName() {
        IsolationSource a = IsolationSource.header("a");
        IsolationSource b = IsolationSource.header("b");
        assertThat(a, is(not(b)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullName() {
        IsolationSource.header(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyName() {
        IsolationSource.header("");
    }
}
