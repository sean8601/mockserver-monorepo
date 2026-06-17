package org.mockserver.persistence;

import org.mockserver.mock.Expectation;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opt-in post-processing of recorded (proxy SPY/CAPTURE) expectations that
 * (a) <b>deduplicates</b> structurally-identical recorded request/response pairs,
 * keeping a single representative, and (b) <b>templatizes</b> variable path
 * segments so that, for example, recorded calls to {@code /users/1},
 * {@code /users/2} and {@code /users/3} collapse into a single expectation that
 * matches {@code /users/{id}} (a declared MockServer path parameter, which the
 * matcher normalises to a {@code .*} regex segment).
 *
 * <p>This is a pure function over {@code List<Expectation>} so it can be tested
 * in isolation and invoked from a recording/retrieve path without side effects.
 * It is intentionally <b>conservative</b>:
 * <ul>
 *   <li>Only HTTP requests with a concrete {@link HttpResponse} are considered;
 *       any other expectation (callbacks, forwards, OpenAPI matchers, etc.) is
 *       passed through unchanged and never merged.</li>
 *   <li>A path segment is only treated as variable when it <i>looks like an id</i>
 *       — all digits, or a canonical UUID. Nothing else is collapsed.</li>
 *   <li>A group of requests is only collapsed into one templated expectation
 *       when the non-variable parts (method, every fixed path segment, and the
 *       request body shape) match exactly, the variable segments occupy the same
 *       positions, and <b>all responses in the group are equal</b>. If responses
 *       differ, the group is left as distinct expectations (we never merge
 *       differing responses under one matcher).</li>
 *   <li>Exact duplicates (same request signature and equal response) always
 *       collapse to a single expectation, even when there is no variable
 *       segment to templatize.</li>
 * </ul>
 *
 * <p>Order is preserved: the first expectation of each retained group keeps its
 * original position in the output.
 */
public class RecordedExpectationPostProcessor {

    /**
     * Matches a path segment that is purely numeric, e.g. {@code 123}.
     */
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d+");

    /**
     * Matches a canonical UUID path segment (8-4-4-4-12 hex), case-insensitive.
     */
    private static final Pattern UUID_ID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Placeholder used internally to key a path template by position; the
     * rendered path parameter name is derived per-position (see
     * {@link #parameterName(int)}). A NUL character is used because it can never
     * appear in a (decoded) URL path segment, so it can never collide with a
     * literal segment value (a literal space, for example, can).
     */
    private static final String VARIABLE_PLACEHOLDER = "\u0000";

    private RecordedExpectationPostProcessor() {
    }

    /**
     * Deduplicate and templatize a list of recorded expectations. Pure function:
     * the input list and its elements are not mutated.
     *
     * @param expectations recorded expectations (may be {@code null} or empty)
     * @return a new list with structurally-identical requests deduplicated and
     * variable id path segments collapsed into templated expectations
     */
    public static List<Expectation> deduplicateAndTemplatize(List<Expectation> expectations) {
        List<Expectation> result = new ArrayList<>();
        if (expectations == null || expectations.isEmpty()) {
            return result;
        }

        // First pass: bucket eligible (HTTP request + concrete response)
        // expectations by their structural signature (method + templatized path
        // shape + body shape). Ineligible expectations are not grouped.
        Map<String, List<Expectation>> groups = new LinkedHashMap<>();
        for (Expectation expectation : expectations) {
            if (!isEligible(expectation)) {
                continue;
            }
            String signature = structuralSignature((HttpRequest) expectation.getHttpRequest());
            groups.computeIfAbsent(signature, k -> new ArrayList<>()).add(expectation);
        }

        // Second pass: emit in ORIGINAL first-seen order. Ineligible items stay
        // in place; the first time a group's signature is encountered, that
        // group's collapsed output is emitted at that position. This preserves
        // the relative order of every output element, which matters because
        // MockServer matches expectations in insertion order.
        Set<String> emitted = new HashSet<>();
        for (Expectation expectation : expectations) {
            if (!isEligible(expectation)) {
                result.add(expectation);
                continue;
            }
            String signature = structuralSignature((HttpRequest) expectation.getHttpRequest());
            if (emitted.add(signature)) {
                result.addAll(collapseGroup(groups.get(signature)));
            }
        }

        return result;
    }

    private static boolean isEligible(Expectation expectation) {
        return expectation != null
            && expectation.getHttpRequest() instanceof HttpRequest
            && expectation.getHttpResponse() != null;
    }

    /**
     * Collapse a group of expectations that share a structural signature. The
     * group is partitioned by response: each distinct response yields one
     * expectation. Templatization is decided <b>per partition</b>: a partition
     * is only templatized when it has a variable path segment <i>and</i> spans
     * more than one distinct concrete path. A partition with a single concrete
     * id keeps its concrete path, so we never widen a single recorded id to a
     * {@code {id}} wildcard (which would have no de-duplication benefit).
     */
    private static List<Expectation> collapseGroup(List<Expectation> group) {
        List<Expectation> output = new ArrayList<>();
        if (group.size() == 1) {
            output.add(group.get(0));
            return output;
        }

        // Partition by response so differing responses are never merged.
        // LinkedHashMap keyed by response preserves first-seen order.
        Map<HttpResponse, List<Expectation>> byResponse = new LinkedHashMap<>();
        for (Expectation expectation : group) {
            byResponse.computeIfAbsent(expectation.getHttpResponse(), k -> new ArrayList<>()).add(expectation);
        }

        for (List<Expectation> partition : byResponse.values()) {
            Expectation representative = partition.get(0);
            if (hasVariableSegment((HttpRequest) representative.getHttpRequest())
                && partitionSpansMultiplePaths(partition)) {
                output.add(templatize(representative));
            } else {
                // No templatization: exact-duplicate dedup keeps the first.
                output.add(representative);
            }
        }
        return output;
    }

    /**
     * True when this response partition contains more than one distinct concrete
     * path. This guards against templatizing a single recorded id within a
     * partition (which would over-widen the matcher with no de-duplication
     * benefit) while still allowing the {@code /users/1,2,3} case to collapse.
     */
    private static boolean partitionSpansMultiplePaths(List<Expectation> partition) {
        String firstPath = ((HttpRequest) partition.get(0).getHttpRequest()).getPath().getValue();
        for (Expectation expectation : partition) {
            String path = ((HttpRequest) expectation.getHttpRequest()).getPath().getValue();
            if (firstPath == null ? path != null : !firstPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasVariableSegment(HttpRequest request) {
        for (String segment : pathSegments(request)) {
            if (isVariableSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Structural signature = method + templatized path shape + body shape. Two
     * requests share a signature when they could collapse into one templated
     * matcher (fixed segments equal, variable segments in the same positions).
     */
    private static String structuralSignature(HttpRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("M:").append(nullableValue(request.getMethod())).append('\n');
        builder.append("P:");
        String[] segments = pathSegments(request);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(isVariableSegment(segments[i]) ? VARIABLE_PLACEHOLDER : segments[i]);
        }
        if (endsWithSlash(request)) {
            builder.append('/');
        }
        builder.append('\n');
        builder.append("B:").append(bodyShape(request));
        return builder.toString();
    }

    /**
     * Build a templated copy of the representative request, replacing each
     * variable segment with a {@code {paramN}} path parameter and declaring the
     * parameter so MockServer's path matcher treats it as a wildcard segment.
     * The response and all other request properties are preserved.
     */
    private static Expectation templatize(Expectation representative) {
        HttpRequest original = (HttpRequest) representative.getHttpRequest();
        HttpRequest templated = original.clone();

        String[] segments = pathSegments(original);
        List<String> renderedSegments = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
        for (String segment : segments) {
            if (isVariableSegment(segment)) {
                String name = parameterName(parameterNames.size());
                parameterNames.add(name);
                renderedSegments.add("{" + name + "}");
            } else {
                renderedSegments.add(segment);
            }
        }
        String leading = original.getPath().getValue().startsWith("/") ? "/" : "";
        String joined = leading + String.join("/", trimLeadingEmpty(renderedSegments));
        if (endsWithSlash(original) && !joined.endsWith("/")) {
            joined = joined + "/";
        }
        templated.withPath(joined);
        // Declare each path parameter; the matcher normalises {name} to a
        // wildcard segment, and a declared parameter keeps the path valid.
        for (String name : parameterNames) {
            templated.withPathParameter(name, ".*");
        }

        return rebuild(representative, templated);
    }

    /**
     * Recreate an Expectation with a replacement request, copying the response
     * and the identifying/priority fields. We never mutate the original.
     */
    private static Expectation rebuild(Expectation source, RequestDefinition newRequest) {
        Expectation rebuilt = new Expectation(
            newRequest,
            source.getTimes() != null ? source.getTimes().clone() : null,
            source.getTimeToLive(),
            source.getPriority()
        );
        rebuilt.thenRespond(source.getHttpResponse());
        return rebuilt;
    }

    // ----- path / body helpers -----------------------------------------------

    private static String[] pathSegments(HttpRequest request) {
        String value = request.getPath() != null ? request.getPath().getValue() : "";
        if (value == null) {
            value = "";
        }
        // split keeps leading empty element for a leading slash; we normalise in callers
        return value.split("/", -1);
    }

    private static boolean endsWithSlash(HttpRequest request) {
        String value = request.getPath() != null ? request.getPath().getValue() : "";
        return value != null && value.length() > 1 && value.endsWith("/");
    }

    private static List<String> trimLeadingEmpty(List<String> segments) {
        List<String> copy = new ArrayList<>(segments);
        if (!copy.isEmpty() && copy.get(0).isEmpty()) {
            copy.remove(0);
        }
        // drop a trailing empty element produced by a trailing slash; the slash
        // is re-added by the caller based on the original path
        if (!copy.isEmpty() && copy.get(copy.size() - 1).isEmpty()) {
            copy.remove(copy.size() - 1);
        }
        return copy;
    }

    private static boolean isVariableSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        return NUMERIC_ID.matcher(segment).matches() || UUID_ID.matcher(segment).matches();
    }

    private static String parameterName(int index) {
        return index == 0 ? "id" : "id" + index;
    }

    private static String bodyShape(HttpRequest request) {
        Body<?> body = request.getBody();
        if (body == null) {
            return "<none>";
        }
        Object value = body.getValue();
        return body.getType() + ":" + (value == null ? "" : value);
    }

    private static String nullableValue(NottableString value) {
        if (value == null) {
            return "";
        }
        return (value.isNot() ? "!" : "") + value.getValue();
    }
}
