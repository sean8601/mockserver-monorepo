package org.mockserver.model;

import java.util.List;
import java.util.Objects;

public class HttpResponseModifier extends ObjectWithJsonToString {

    private int hashCode;
    private HeadersModifier headers;
    private CookiesModifier cookies;
    private HttpResponseModifierCondition condition;
    private List<HttpResponseModifier> modifiers;

    public static HttpResponseModifier responseModifier() {
        return new HttpResponseModifier();
    }

    public HeadersModifier getHeaders() {
        return headers;
    }

    public HttpResponseModifier withHeaders(HeadersModifier headers) {
        this.headers = headers;
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifier withHeaders(List<Header> add, List<Header> replace, List<String> remove) {
        this.headers = new HeadersModifier()
            .withAdd(new Headers(add))
            .withReplace(new Headers(replace))
            .withRemove(remove);
        this.hashCode = 0;
        return this;
    }

    public CookiesModifier getCookies() {
        return cookies;
    }

    public HttpResponseModifier withCookies(CookiesModifier cookies) {
        this.cookies = cookies;
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifier withCookies(List<Cookie> add, List<Cookie> replace, List<String> remove) {
        this.cookies = new CookiesModifier()
            .withAdd(new Cookies(add))
            .withReplace(new Cookies(replace))
            .withRemove(remove);
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifierCondition getCondition() {
        return condition;
    }

    /**
     * Restrict this modifier so it only applies when the supplied condition holds against the
     * in-flight response (and, where available, the original request). When {@code null} the
     * modifier always applies — the historical behaviour.
     */
    public HttpResponseModifier withCondition(HttpResponseModifierCondition condition) {
        this.condition = condition;
        this.hashCode = 0;
        return this;
    }

    public List<HttpResponseModifier> getModifiers() {
        return modifiers;
    }

    /**
     * Configure an ordered chain of modifiers. Each is applied in order to the same response, so a
     * later modifier observes the output of the earlier ones. When a chain is present the
     * {@code headers}/{@code cookies} of this (wrapping) modifier are ignored — the chain is the
     * unit of work; the wrapping modifier's {@code condition} still gates the whole chain.
     */
    public HttpResponseModifier withModifiers(List<HttpResponseModifier> modifiers) {
        this.modifiers = modifiers;
        this.hashCode = 0;
        return this;
    }

    /**
     * Apply this modifier to {@code response}, honouring any condition and chain.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>if a {@link #getCondition() condition} is configured and does not match, do nothing;</li>
     *   <li>if a {@link #getModifiers() chain} is configured, apply each child in order (each sees the
     *       prior child's output);</li>
     *   <li>otherwise apply this modifier's own header/cookie edits.</li>
     * </ol>
     *
     * @param response the in-flight response to mutate in place
     * @param request  the original request (may be {@code null} when not available)
     */
    public void applyTo(HttpResponse response, HttpRequest request) {
        if (response == null) {
            return;
        }
        if (condition != null && !condition.matches(response, request)) {
            return;
        }
        if (modifiers != null && !modifiers.isEmpty()) {
            for (HttpResponseModifier modifier : modifiers) {
                if (modifier != null) {
                    modifier.applyTo(response, request);
                }
            }
            return;
        }
        if (headers != null) {
            response.withHeaders(headers.update(response.getHeaders()));
        }
        if (cookies != null) {
            response.withCookies(cookies.update(response.getCookies()));
        }
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        HttpResponseModifier that = (HttpResponseModifier) o;
        return Objects.equals(headers, that.headers) &&
            Objects.equals(cookies, that.cookies) &&
            Objects.equals(condition, that.condition) &&
            Objects.equals(modifiers, that.modifiers);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(headers, cookies, condition, modifiers);
        }
        return hashCode;
    }

}
