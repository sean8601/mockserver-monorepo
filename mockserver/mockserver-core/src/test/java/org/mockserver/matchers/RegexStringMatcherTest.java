package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.NottableString;

import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class RegexStringMatcherTest {

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, NottableString.not("not_value")), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcher() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_value"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchUnMatchingNottedMatcherAndNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("not_matcher"), false).matches((MatchDifference) null, NottableString.not("not_value")), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, NottableString.not("some_value")), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcher() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), false).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchMatchingNottedMatcherAndNottedString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), NottableString.not("some_value"), false).matches((MatchDifference) null, NottableString.not("some_value")), is(true));
    }

    @Test
    public void shouldNotMatchMatchingString() {
        assertThat(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("some_value"), is(false));
    }

    @Test
    public void shouldMatchMatchingStringWithRegexSymbols() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), false).matches("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), is(true));
    }

    @Test
    public void shouldMatchMatchingRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{5}"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string(null), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string(""), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectString() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("not_matching"), is(false));
    }

    @Test
    public void shouldMatchIncorrectString() {
        assertThat(notMatcher(new RegexStringMatcher(new MockServerLogger(), string("some_value"), true)).matches("not_matching"), is(true));
    }

    @Test
    public void shouldNotMatchMatchingControlPlaneRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("some_[a-z]{5}"), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectStringWithRegexSymbols() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), false).matches("text/html,application/xhtml+xml,application/xml;q=0.9;q=0.8"), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectRegex() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{4}"), false).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches((MatchDifference) null, string(null)), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches(""), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectationAndTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("/{{}"), is(false));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("some_value"), is(false));
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("/{}"), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForExpectation() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("/{}"), false).matches("some_value"), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexPatternForTest() {
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("/{}"), is(false));
    }

    @Test
    public void shouldReturnFalseOnReDoSPatternRatherThanHanging() {
        // (a+)+b backtracks exponentially on a long run of 'a' followed by a non-match.
        // Without the regex timeout this call would hang far longer than any test budget.
        long previousTimeout = org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis();
        try {
            org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis(200L);
            String evilInput = repeat('a', 40) + "c";
            long start = System.nanoTime();
            boolean matched = new RegexStringMatcher(new MockServerLogger(), string("(a+)+b"), false).matches(evilInput);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertThat(matched, is(false));
            // generous upper bound: even at the 200ms timeout plus thread scheduling slack,
            // we should never approach minutes (which is what unbounded backtracking would cost).
            assertThat("regex evaluation took " + elapsedMs + "ms, expected to be bounded by timeout", elapsedMs < 5_000L, is(true));
        } finally {
            org.mockserver.configuration.ConfigurationProperties.regexMatchingTimeoutMillis(previousTimeout);
        }
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }

    // --- pure-ASCII literal short-circuit (does not enter the timeout-protected executor pool) ---

    @Test
    public void shouldNotEnterExecutorPoolForPureAsciiLiteralNonMatch() throws Exception {
        long submittedBefore = executorSubmittedTaskCount();
        // pure-ASCII literal matcher value, no regex metacharacters: the equalsIgnoreCase check
        // already decides this is a non-match, so the regex/executor path must be skipped entirely.
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_value"), false).matches("not_matching"), is(false));
        long submittedAfter = executorSubmittedTaskCount();
        assertThat("pure-ASCII literal non-match must not submit any task to the matching executor pool",
            submittedAfter - submittedBefore, is(0L));
    }

    @Test
    public void shouldEnterExecutorPoolForRealRegexNonMatch() throws Exception {
        long submittedBefore = executorSubmittedTaskCount();
        // a real regex (contains metacharacters) that does not match still exercises the executor path
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("some_[a-z]{4}"), false).matches("some_value"), is(false));
        long submittedAfter = executorSubmittedTaskCount();
        assertThat("a real regex must still be evaluated via the timeout-protected executor pool",
            submittedAfter - submittedBefore > 0L, is(true));
    }

    @Test
    public void shouldStillMatchAsciiLiteralCaseInsensitively() {
        // exact and case-insensitive matches are decided before any regex/short-circuit logic
        assertThat(new RegexStringMatcher(new MockServerLogger(), string("Some_Value"), false).matches("some_value"), is(true));
    }

    @Test
    public void shouldKeepRegexPathForNonAsciiLiteralValue() throws Exception {
        // 'İ' (U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE) folds differently under UNICODE_CASE
        // than under String.equalsIgnoreCase, so non-ASCII values must keep the regex path.
        long submittedBefore = executorSubmittedTaskCount();
        // a non-matching non-ASCII literal still goes through the executor (proving it is not short-circuited)
        new RegexStringMatcher(new MockServerLogger(), string("café"), false).matches("teapot");
        long submittedAfter = executorSubmittedTaskCount();
        assertThat("a non-ASCII literal must keep the timeout-protected regex path",
            submittedAfter - submittedBefore > 0L, is(true));
    }

    // --- looksLikeRegex helper ---

    @Test
    public void looksLikeRegexShouldDetectMetacharacters() throws Exception {
        for (String withMeta : new String[]{
            "a.b", "a*", "a+", "a?", "^a", "a$", "a|b", "(a)", "[a]", "{2}", "a\\d", "a]", "a}", "a)"
        }) {
            assertThat("expected '" + withMeta + "' to look like a regex", invokeLooksLikeRegex(withMeta), is(true));
        }
    }

    @Test
    public void looksLikeRegexShouldRejectPlainLiterals() throws Exception {
        for (String plain : new String[]{"some_value", "Some Value 123", "a-b_c", "", "café", "text/html"}) {
            assertThat("expected '" + plain + "' to NOT look like a regex", invokeLooksLikeRegex(plain), is(false));
        }
    }

    private static boolean invokeLooksLikeRegex(String s) throws Exception {
        java.lang.reflect.Method m = RegexStringMatcher.class.getDeclaredMethod("looksLikeRegex", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, s);
    }

    // RegexStringMatcherTest runs in the sequential (parallel=none, forkCount=1) Surefire phase, so
    // no other test submits to the shared pool concurrently. MatchingTimeoutExecutor.submittedTaskCount()
    // is an explicit AtomicLong incremented on successful submit (not the JDK's documented-as-approximate
    // ThreadPoolExecutor.getTaskCount()), making these before/after deltas exact.
    private static long executorSubmittedTaskCount() {
        return MatchingTimeoutExecutor.submittedTaskCount();
    }
}
