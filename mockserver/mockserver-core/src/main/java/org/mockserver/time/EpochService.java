package org.mockserver.time;

public class EpochService {

    public static final long FIXED_TIME_FOR_TESTS = System.currentTimeMillis();

    // Single gate covering BOTH ways a test can pin time: per-thread (fixedTime) and across all
    // threads (fixedTimeGlobally). Production never pins time, so this stays 0 and currentTimeMillis()
    // pays only a single volatile read on its hot path (a LogEntry is timestamped on every request).
    private static volatile int fixedActiveCount = 0;
    // Per-thread pin: lets one parallel test class fix time without other parallel classes observing it.
    private static final ThreadLocal<Boolean> FIXED_ON_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    // Global pin: needed by tests that assert on timestamps produced on a DIFFERENT thread (e.g. the
    // MockServerEventLog disruptor consumer thread), which a per-thread pin cannot reach. A global pin
    // affects every thread, so such tests MUST run in the sequential (non-parallel) Surefire phase.
    private static volatile boolean fixedGlobally = false;

    public static long currentTimeMillis() {
        if (fixedActiveCount == 0) {
            return System.currentTimeMillis();
        }
        return (fixedGlobally || FIXED_ON_THREAD.get()) ? FIXED_TIME_FOR_TESTS : System.currentTimeMillis();
    }

    /**
     * Test-only: pin (or unpin) {@link #currentTimeMillis()} to {@link #FIXED_TIME_FOR_TESTS} for the
     * CURRENT thread only, so parallel test classes do not observe each other's fixed clock. Prefer the
     * {@code org.mockserver.time.FixedTime} JUnit rule, which guarantees the reset even on failure.
     */
    public static synchronized void fixedTime(boolean fixed) {
        boolean current = FIXED_ON_THREAD.get();
        if (fixed && !current) {
            FIXED_ON_THREAD.set(Boolean.TRUE);
            fixedActiveCount++;
        } else if (!fixed && current) {
            FIXED_ON_THREAD.set(Boolean.FALSE);
            fixedActiveCount--;
        }
    }

    /**
     * Test-only: pin (or unpin) {@link #currentTimeMillis()} for ALL threads. Required only by tests
     * that assert on timestamps generated off the test thread (e.g. the event-log disruptor). Because
     * it is JVM-global it is NOT parallel-safe - such tests must run in the sequential Surefire phase.
     * Prefer the {@code org.mockserver.time.GlobalFixedTime} JUnit rule, which guarantees the reset.
     */
    public static synchronized void fixedTimeGlobally(boolean fixed) {
        if (fixed && !fixedGlobally) {
            fixedGlobally = true;
            fixedActiveCount++;
        } else if (!fixed && fixedGlobally) {
            fixedGlobally = false;
            fixedActiveCount--;
        }
    }

    public static boolean fixedTime() {
        return fixedGlobally || FIXED_ON_THREAD.get();
    }

}
