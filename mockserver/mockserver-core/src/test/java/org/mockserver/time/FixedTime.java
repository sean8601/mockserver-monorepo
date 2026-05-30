package org.mockserver.time;

import org.junit.rules.ExternalResource;

/**
 * JUnit rule that pins {@link EpochService#currentTimeMillis()} to a fixed value for the duration of
 * a test (as a {@code @Rule}) or a test class (as a {@code @ClassRule}), on the running thread only,
 * and ALWAYS unpins afterwards - even if the test throws.
 *
 * <p>This replaces the previous {@code @BeforeClass}-sets-a-static-boolean idiom, which left the
 * fixed clock enabled JVM-wide and so could not run concurrently with other test classes under
 * Surefire {@code parallel=classes}. Because the rule guarantees the reset, the fixed clock cannot
 * leak onto a reused Surefire worker thread.</p>
 *
 * <p>Pins both {@link EpochService} and {@link TimeService} for the current thread. For timestamps
 * produced on another thread (e.g. the event-log disruptor consumer) a per-thread pin cannot reach
 * the producing thread - use {@link GlobalFixedTime} instead.</p>
 *
 * <p>Use as a class rule to mirror the old {@code @BeforeClass} scope:</p>
 * <pre>{@code  @ClassRule public static final FixedTime fixedTime = new FixedTime(); }</pre>
 */
public class FixedTime extends ExternalResource {

    @Override
    protected void before() {
        EpochService.fixedTime(true);
        TimeService.fixedTime(true);
    }

    @Override
    protected void after() {
        EpochService.fixedTime(false);
        TimeService.fixedTime(false);
    }
}
