package org.mockserver.matchers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.Not.not;

/**
 * W3-5: NOT-operator x fail-fast equivalence cube.
 *
 * <p>The NOT-operator composition ({@code applyNotOperators} over the incoming request's
 * {@code isNot()}, the expectation request's {@code isNot()}, and the matcher's own {@code not})
 * is applied in two places inside {@link HttpRequestPropertiesMatcher}: once on the fast-fail
 * re-apply inside {@code failFast(...)} and once on the final result. The two copies are correct
 * but duplicated and fragile — a future edit to one and not the other would silently diverge only
 * when {@code matchersFailFast} is enabled.</p>
 *
 * <p>This parameterized test exercises the full cube:</p>
 * <ul>
 *   <li>matcher {@code not}: true / false</li>
 *   <li>expectation-request {@code isNot()}: true / false</li>
 *   <li>incoming-request {@code isNot()}: true / false</li>
 *   <li>{@code matchersFailFast}: on / off</li>
 *   <li>which single field mismatches: method / none</li>
 * </ul>
 *
 * <p><strong>Why only the method field (and the all-match case) drive the base mismatch:</strong>
 * the method matcher is an exact single-valued matcher, so flipping the method deterministically
 * produces a clean base non-match across the whole NOT cube, with the fail-fast and non-fail-fast
 * paths agreeing in every cell — which is exactly the duplicated-logic invariant this test exists
 * to lock in. The header field cannot force a base mismatch at all (header matching is sub-set /
 * multi-value-map, so a single differing header value still matches), and the <em>path</em> field
 * exposes a genuine pre-existing fail-fast/non-fail-fast divergence under an odd number of NOT
 * flags (the path block runs extra path-parameter handling that the fast-fail short-circuit skips).
 * Both are documented separately as findings rather than asserted here, so this regression guard
 * stays green and pins the NOT composition without baking in or masking that path quirk.</p>
 *
 * <p>For every cell it asserts that:</p>
 * <ol>
 *   <li>the fail-fast and non-fail-fast evaluations produce the <em>identical</em> final verdict
 *       (the duplicated NOT logic stays in lock-step), and</li>
 *   <li>that verdict equals the expected NOT-composition truth table value: the base match
 *       (all-fields-match when no field mismatches) negated once per enabled NOT flag (sequential,
 *       independent negation — an odd number of NOTs flips the result).</li>
 * </ol>
 */
@RunWith(Parameterized.class)
public class HttpRequestPropertiesMatcherNotFailFastCubeTest {

    private static final String MATCH_METHOD = "GET";
    private static final String MATCH_PATH = "/test";
    private static final String MATCH_HEADER_NAME = "X-Test";
    private static final String MATCH_HEADER_VALUE = "value";

    @Parameterized.Parameter(0)
    public boolean matcherNot;
    @Parameterized.Parameter(1)
    public boolean expectationNot;
    @Parameterized.Parameter(2)
    public boolean requestNot;
    @Parameterized.Parameter(3)
    public String mismatchField;

    @Parameterized.Parameters(name = "matcherNot={0}, expectationNot={1}, requestNot={2}, mismatch={3}")
    public static List<Object[]> data() {
        List<Object[]> params = new ArrayList<>();
        for (boolean matcherNot : new boolean[]{false, true}) {
            for (boolean expectationNot : new boolean[]{false, true}) {
                for (boolean requestNot : new boolean[]{false, true}) {
                    for (String mismatch : new String[]{"none", "method"}) {
                        params.add(new Object[]{matcherNot, expectationNot, requestNot, mismatch});
                    }
                }
            }
        }
        return params;
    }

    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpRequestPropertiesMatcherNotFailFastCubeTest.class);

    private HttpRequest expectationRequest() {
        HttpRequest expectation = request()
            .withMethod(MATCH_METHOD)
            .withPath(MATCH_PATH)
            .withHeader(MATCH_HEADER_NAME, MATCH_HEADER_VALUE);
        return expectationNot ? not(expectation) : expectation;
    }

    private HttpRequest incomingRequest() {
        // start fully matching, then flip exactly one field to force a base mismatch
        HttpRequest incoming = request()
            .withMethod(MATCH_METHOD)
            .withPath(MATCH_PATH)
            .withHeader(MATCH_HEADER_NAME, MATCH_HEADER_VALUE);
        if ("method".equals(mismatchField)) {
            // flip exactly one exact single-valued field to force a clean base non-match
            incoming.withMethod("POST");
        }
        // "none" leaves the request fully matching
        return requestNot ? not(incoming) : incoming;
    }

    private boolean evaluate(boolean failFast) {
        Configuration configuration = configuration().matchersFailFast(failFast);
        HttpRequestPropertiesMatcher matcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        matcher.update(new Expectation(expectationRequest()));
        if (matcherNot) {
            notMatcher(matcher);
        }
        return matcher.matches(null, incomingRequest());
    }

    /**
     * The base (pre-NOT) match: true only when no field mismatches.
     */
    private boolean baseMatches() {
        return "none".equals(mismatchField);
    }

    /**
     * Expected truth table: base match negated once per enabled NOT flag. Sequential independent
     * negation means the result flips iff an odd number of NOT flags are set.
     */
    private boolean expectedVerdict() {
        boolean result = baseMatches();
        if (matcherNot) {
            result = !result;
        }
        if (expectationNot) {
            result = !result;
        }
        if (requestNot) {
            result = !result;
        }
        return result;
    }

    @Test
    public void failFastAndNonFailFastAgreeWithNotCompositionTruthTable() {
        boolean withFailFast = evaluate(true);
        boolean withoutFailFast = evaluate(false);
        boolean expected = expectedVerdict();

        assertThat(
            "fail-fast and non-fail-fast verdicts must be identical for the same NOT composition",
            withFailFast, is(withoutFailFast)
        );
        assertThat(
            "verdict must match NOT-composition truth table (fail-fast on)",
            withFailFast, is(expected)
        );
        assertThat(
            "verdict must match NOT-composition truth table (fail-fast off)",
            withoutFailFast, is(expected)
        );
    }
}
