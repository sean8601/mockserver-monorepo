package org.mockserver.uuid;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.RandomBasedGenerator;

import java.security.SecureRandom;

public class UUIDService {

    private static final RandomBasedGenerator RANDOM_BASED_GENERATOR = Generators.randomBasedGenerator(new SecureRandom());
    public static final String FIXED_UUID_FOR_TESTS = RANDOM_BASED_GENERATOR.generate().toString();

    // Single gate covering BOTH ways a test can pin the UUID: per-thread (fixedUUID) and across all
    // threads (fixedUUIDGlobally). Production never pins the UUID, so this stays 0 and getUUID() pays
    // only a single volatile read before generating a random UUID.
    private static volatile int fixedActiveCount = 0;
    // Per-thread pin: lets one parallel test class fix the UUID without other parallel classes seeing it.
    private static final ThreadLocal<Boolean> FIXED_ON_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    // Global pin: needed by integration tests whose ids are generated on the in-process server's own
    // threads (not the test thread), which a per-thread pin cannot reach. JVM-global, so such tests
    // must NOT run under parallel=classes.
    private static volatile boolean fixedGlobally = false;

    public static String getUUID() {
        if (fixedActiveCount == 0) {
            return RANDOM_BASED_GENERATOR.generate().toString();
        }
        return (fixedGlobally || FIXED_ON_THREAD.get()) ? FIXED_UUID_FOR_TESTS : RANDOM_BASED_GENERATOR.generate().toString();
    }

    /**
     * Test-only: pin (or unpin) {@link #getUUID()} to {@link #FIXED_UUID_FOR_TESTS} for the CURRENT
     * thread only, so parallel test classes do not generate colliding ids. Every {@code fixedUUID(true)}
     * MUST be paired with a {@code fixedUUID(false)} on the same thread - prefer the
     * {@code org.mockserver.uuid.FixedUUID} JUnit rule, which guarantees the reset.
     */
    public static synchronized void fixedUUID(boolean fixed) {
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
     * Test-only: pin (or unpin) {@link #getUUID()} for ALL threads. Required by integration tests that
     * assert on ids generated on the in-process server's threads rather than the test thread. Because
     * it is JVM-global it is NOT parallel-safe - use only from tests that run sequentially.
     */
    public static synchronized void fixedUUIDGlobally(boolean fixed) {
        if (fixed && !fixedGlobally) {
            fixedGlobally = true;
            fixedActiveCount++;
        } else if (!fixed && fixedGlobally) {
            fixedGlobally = false;
            fixedActiveCount--;
        }
    }

    public static boolean fixedUUID() {
        return fixedGlobally || FIXED_ON_THREAD.get();
    }

}
