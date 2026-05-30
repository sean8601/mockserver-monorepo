package org.mockserver.mock.action.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-wide, stateful request quota for HTTP chaos — a fixed-window rate
 * limiter, the HTTP counterpart of {@link org.mockserver.llm.LlmQuotaRegistry}.
 * Unlike the probabilistic 429 in {@link org.mockserver.model.HttpChaosProfile}
 * (driven by {@code errorProbability}/{@code errorStatus}), this is deterministic
 * and stateful: it counts how many requests have hit a named quota within the
 * current time window and reports when the limit is exceeded, so a test can drive
 * an application into a hard rate-limit (e.g. "the 4th call in 60s gets 429").
 *
 * <p>Quotas are keyed by name, so several expectations that share a {@code quotaName}
 * share one counter (model an upstream account limit), while distinct names are
 * independent. HTTP and LLM quotas are held in separate registries, so a HTTP and an
 * LLM expectation that happen to use the same {@code quotaName} do not collide. State
 * is held in a {@link ConcurrentHashMap} and each acquire is an atomic per-key update,
 * safe under concurrent requests.
 *
 * <p>The time source is injectable so window behaviour is unit-testable without
 * sleeping; production uses {@link System#currentTimeMillis()}. State is cleared on
 * server reset (see {@code HttpState.reset()}).
 */
public class HttpQuotaRegistry {

    private static final HttpQuotaRegistry INSTANCE = new HttpQuotaRegistry(System::currentTimeMillis);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public HttpQuotaRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public static HttpQuotaRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Record one request against the named quota and report whether it is allowed.
     *
     * <p>Fixed-window semantics: the first request in a window starts it; the window
     * expires {@code windowMillis} after it started, after which the next request
     * starts a fresh window. A request is allowed when the in-window count (including
     * itself) is at or below {@code limit}.
     *
     * @return {@code true} if the request is within the quota, {@code false} if it
     * exceeds the limit for the current window.
     */
    public boolean tryAcquire(String name, int limit, long windowMillis) {
        if (name == null || limit < 0 || windowMillis <= 0) {
            // misconfigured quota is a no-op (never rate-limits) — fail open
            return true;
        }
        long now = clock.getAsLong();
        Window updated = windows.compute(name, (key, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(existing.startMillis, existing.count + 1);
        });
        return updated.count <= limit;
    }

    /**
     * Clear all quota state. Called on server reset and for test isolation.
     */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private final long startMillis;
        private final long count; // long (not int) so a never-expiring window can't overflow; compared against int limit by widening

        private Window(long startMillis, long count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }
}
