package org.mockserver.llm;

import java.util.Objects;

/**
 * Describes where to extract the isolation key from an inbound HTTP request
 * for per-session conversation state isolation.
 * <p>
 * Usage:
 * <pre>
 * isolateBy(IsolationSource.header("x-session-id"))
 * isolateBy(IsolationSource.queryParameter("agent"))
 * isolateBy(IsolationSource.cookie("sid"))
 * </pre>
 */
public final class IsolationSource {

    public enum Kind {
        HEADER,
        QUERY_PARAMETER,
        COOKIE
    }

    private final Kind kind;
    private final String name;

    private IsolationSource(Kind kind, String name) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be null or empty");
        }
        this.kind = kind;
        this.name = name;
    }

    public static IsolationSource header(String name) {
        return new IsolationSource(Kind.HEADER, name);
    }

    public static IsolationSource queryParameter(String name) {
        return new IsolationSource(Kind.QUERY_PARAMETER, name);
    }

    public static IsolationSource cookie(String name) {
        return new IsolationSource(Kind.COOKIE, name);
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    /**
     * Encode the isolation source as a string for embedding in scenario names.
     * Format: "kind:name" (e.g. "header:x-session-id").
     */
    public String encode() {
        return kind.name().toLowerCase() + ":" + name;
    }

    /**
     * Decode an isolation source from its encoded string form.
     *
     * @param encoded the encoded string (e.g. "header:x-session-id")
     * @return the decoded IsolationSource, or null if the format is invalid
     */
    public static IsolationSource decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        int colonIndex = encoded.indexOf(':');
        if (colonIndex < 0 || colonIndex == encoded.length() - 1) {
            return null;
        }
        String kindStr = encoded.substring(0, colonIndex);
        String nameStr = encoded.substring(colonIndex + 1);
        switch (kindStr) {
            case "header":
                return header(nameStr);
            case "query_parameter":
                return queryParameter(nameStr);
            case "cookie":
                return cookie(nameStr);
            default:
                return null;
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
        IsolationSource that = (IsolationSource) o;
        return kind == that.kind && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name);
    }

    @Override
    public String toString() {
        return "IsolationSource{" + kind + ", " + name + "}";
    }
}
