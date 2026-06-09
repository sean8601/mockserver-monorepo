package org.mockserver.mock.action.http;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.metrics.Metrics;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Safety circuit-breaker for service-scoped chaos: when the number of
 * <em>error-class</em> chaos faults (5xx synthetic errors, dropped connections,
 * and quota-limit responses) within a configurable sliding window exceeds a
 * threshold, all active service-scoped chaos profiles are automatically halted
 * (disabled) via {@link ServiceChaosRegistry#reset()}.
 *
 * <p>Only <b>destructive</b> fault types contribute to the window:
 * {@code "error"} (synthetic 5xx), {@code "drop"} (connection kill), and
 * {@code "quota"} (429/503). Benign fault types such as {@code "latency"},
 * {@code "slow"}, {@code "truncate"}, {@code "malformed"}, and
 * {@code "graphql"} do not count — a latency-only experiment will never
 * auto-halt, which matches the circuit-breaker's purpose.
 *
 * <p>This prevents a chaos experiment from driving a cascading outage — the
 * "steady-state guardrail" SREs expect.
 *
 * <p>The monitor is evaluated per chaos-fault injection (called from
 * {@link org.mockserver.metrics.Metrics#incrementHttpChaosInjected(String)}).
 * It does not block the event loop — the sliding window is maintained in a
 * lock-free {@link ConcurrentLinkedDeque} of timestamps.
 *
 * <p><b>Configuration</b> (all read dynamically from {@link ConfigurationProperties}):
 * <ul>
 *   <li>{@code chaosAutoHaltEnabled} — master switch (default false = inert)</li>
 *   <li>{@code chaosAutoHaltErrorThreshold} — error count to trigger halt (default 50)</li>
 *   <li>{@code chaosAutoHaltWindowMillis} — sliding window (default 60 000 ms)</li>
 * </ul>
 *
 * <p>The singleton instance is shared process-wide, consistent with
 * {@link ServiceChaosRegistry}'s singleton pattern.
 */
public class ChaosAutoHaltMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosAutoHaltMonitor.class);

    /**
     * Only destructive fault types contribute to the auto-halt window:
     * synthetic 5xx errors, dropped connections, and quota-limit responses.
     * Benign faults (latency, slow, truncate, malformed, graphql) are excluded.
     */
    static final Set<String> DESTRUCTIVE_FAULT_TYPES = Set.of("error", "drop", "quota");

    private static final ChaosAutoHaltMonitor INSTANCE = new ChaosAutoHaltMonitor(TimeService::currentTimeMillis);

    private final ConcurrentLinkedDeque<Long> errorTimestamps = new ConcurrentLinkedDeque<>();
    private final LongSupplier clock;
    private final AtomicLong haltCount = new AtomicLong(0);
    /** Tracks window size in O(1) instead of calling ConcurrentLinkedDeque.size() which is O(n). */
    private final AtomicInteger windowSize = new AtomicInteger(0);
    /** Guards against concurrent double-trigger: only one thread performs the halt block per trigger. */
    private final AtomicBoolean halting = new AtomicBoolean(false);

    ChaosAutoHaltMonitor(LongSupplier clock) {
        this.clock = clock;
    }

    public static ChaosAutoHaltMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * Record a chaos-injected fault and evaluate the circuit-breaker.
     * Called after each chaos fault injection (from {@code Metrics.incrementHttpChaosInjected}).
     *
     * <p>Only <b>destructive</b> fault types ({@code "error"}, {@code "drop"},
     * {@code "quota"}) contribute to the sliding window. Benign faults
     * ({@code "latency"}, {@code "slow"}, {@code "truncate"},
     * {@code "malformed"}, {@code "graphql"}) are ignored — a latency-only
     * experiment will never auto-halt.
     *
     * <p>When the feature is disabled ({@code chaosAutoHaltEnabled} is false),
     * this method is a no-op — no timestamps are recorded, no evaluation occurs.
     *
     * @param faultType the fault type string (e.g. "error", "drop", "latency")
     */
    public void recordError(String faultType) {
        if (!ConfigurationProperties.chaosAutoHaltEnabled()) {
            return;
        }

        // Only destructive fault types contribute to the auto-halt window
        if (faultType == null || !DESTRUCTIVE_FAULT_TYPES.contains(faultType)) {
            return;
        }

        long now = clock.getAsLong();
        errorTimestamps.addLast(now);
        windowSize.incrementAndGet();
        int evicted = evictExpired(now);

        long threshold = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        if (threshold <= 0) {
            return;
        }

        int currentSize = windowSize.get();
        if (currentSize >= threshold) {
            // AtomicBoolean guard: only one thread performs the halt block
            if (halting.compareAndSet(false, true)) {
                try {
                    // Re-check after acquiring the guard (another thread may have cleared the window)
                    if (windowSize.get() >= threshold && !ServiceChaosRegistry.getInstance().entries().isEmpty()) {
                        haltCount.incrementAndGet();
                        LOG.warn(
                            "chaos auto-halt triggered: {} error-class faults (5xx/dropped/quota) in the last {} ms "
                                + "exceeded threshold of {} — disabling all active service-scoped chaos profiles",
                            windowSize.get(),
                            ConfigurationProperties.chaosAutoHaltWindowMillis(),
                            threshold
                        );
                        ServiceChaosRegistry.getInstance().reset();
                        Metrics.incrementChaosAutoHalt();
                        // Clear the window after halt so the circuit-breaker does not
                        // re-trigger immediately if new chaos is registered
                        errorTimestamps.clear();
                        windowSize.set(0);
                    }
                } finally {
                    halting.set(false);
                }
            }
        }
    }

    /**
     * Evict timestamps older than the current window from the head of the deque.
     *
     * @return the number of evicted entries
     */
    private int evictExpired(long now) {
        long windowMillis = ConfigurationProperties.chaosAutoHaltWindowMillis();
        long cutoff = now - windowMillis;
        int evicted = 0;
        while (true) {
            Long head = errorTimestamps.peekFirst();
            if (head == null || head > cutoff) {
                break;
            }
            if (errorTimestamps.pollFirst() != null) {
                windowSize.decrementAndGet();
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * Returns the total number of times the auto-halt circuit-breaker has triggered
     * since the process started (or since the last {@link #reset()}).
     */
    public long getHaltCount() {
        return haltCount.get();
    }

    /**
     * Returns the number of error timestamps currently in the sliding window.
     */
    public int currentWindowSize() {
        evictExpired(clock.getAsLong());
        return windowSize.get();
    }

    /**
     * Reset the monitor state. Called on server reset and for test isolation.
     */
    public void reset() {
        errorTimestamps.clear();
        windowSize.set(0);
        haltCount.set(0);
        halting.set(false);
    }
}
