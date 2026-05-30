package org.mockserver.time;

import org.junit.rules.ExternalResource;

/**
 * JUnit rule that pins {@link EpochService#currentTimeMillis()} and {@link TimeService#now()} to a
 * fixed value for ALL threads for the duration of a test class, and always unpins afterwards.
 *
 * <p>Unlike {@link FixedTime} (which pins only the running thread and so is parallel-safe), this rule
 * pins time globally. It is the right choice only for tests that assert on timestamps produced on a
 * thread other than the test thread - most notably {@code MockServerEventLog}, which timestamps log
 * entries on its LMAX-Disruptor consumer thread. Because the pin is JVM-global, a test using this rule
 * MUST run in the sequential (non-parallel) Surefire execution phase, never under {@code parallel=classes}.</p>
 *
 * <p>Pins {@link EpochService} only - matching exactly what the original {@code @BeforeClass} methods
 * did ({@code EpochService.fixedTime = true}). It deliberately does NOT pin {@link TimeService}, whose
 * controllable clock ({@code freeze}/{@code advance}/{@code reset}) is exercised and asserted on by the
 * very tests that use this rule (e.g. {@code HttpStateTest}'s clock-control tests); pinning it would
 * make {@code TimeService.isFrozen()} permanently true and break them.</p>
 *
 * <pre>{@code  @ClassRule public static final GlobalFixedTime fixedTime = new GlobalFixedTime(); }</pre>
 */
public class GlobalFixedTime extends ExternalResource {

    @Override
    protected void before() {
        EpochService.fixedTimeGlobally(true);
    }

    @Override
    protected void after() {
        EpochService.fixedTimeGlobally(false);
    }
}
