package org.mockserver.mock.action.http;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class HttpQuotaRegistryTest {

    /** A controllable clock so window behaviour is deterministic without sleeping. */
    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000L);

        long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    @Test
    public void shouldAllowRequestsUpToLimitThenReject() {
        FakeClock clock = new FakeClock();
        HttpQuotaRegistry registry = new HttpQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 3, 60_000), is(true));   // 1
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(true));   // 2
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(true));   // 3
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(false));  // 4 -> over
        assertThat(registry.tryAcquire("acct", 3, 60_000), is(false));  // 5 -> still over
    }

    @Test
    public void shouldResetCountWhenWindowElapses() {
        FakeClock clock = new FakeClock();
        HttpQuotaRegistry registry = new HttpQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));   // 1
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));  // 2 -> over

        clock.advance(60_000);                                          // window elapses
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));   // fresh window
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));  // over again
    }

    @Test
    public void shouldNotResetBeforeWindowElapses() {
        FakeClock clock = new FakeClock();
        HttpQuotaRegistry registry = new HttpQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));
        clock.advance(59_999);                                          // still inside the window
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));
    }

    @Test
    public void shouldKeepSeparateCountersPerName() {
        FakeClock clock = new FakeClock();
        HttpQuotaRegistry registry = new HttpQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("a", 1, 60_000), is(true));
        assertThat(registry.tryAcquire("a", 1, 60_000), is(false));
        // a different name has its own counter
        assertThat(registry.tryAcquire("b", 1, 60_000), is(true));
    }

    @Test
    public void shouldFailOpenForMisconfiguredQuota() {
        FakeClock clock = new FakeClock();
        HttpQuotaRegistry registry = new HttpQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire(null, 1, 60_000), is(true));
        assertThat(registry.tryAcquire("x", -1, 60_000), is(true));
        assertThat(registry.tryAcquire("x", 1, 0), is(true));
    }

    @Test
    public void shouldClearStateOnReset() {
        FakeClock clock = new FakeClock();
        HttpQuotaRegistry registry = new HttpQuotaRegistry(clock::get);

        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(false));
        registry.reset();
        assertThat(registry.tryAcquire("acct", 1, 60_000), is(true));   // counter cleared
    }
}
