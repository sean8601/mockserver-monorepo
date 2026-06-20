package org.mockserver.oidc;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit tests for {@link OidcAuthorizationStore} authorization-code TTL and single-use semantics.
 *
 * <p>These use a private store instance with a controllable clock (not the process-wide singleton)
 * so they are deterministic and safe to run in parallel without touching shared state.
 */
public class OidcAuthorizationStoreTest {

    /** A store whose clock can be advanced deterministically. */
    private static class ClockDrivenStore extends OidcAuthorizationStore {
        private long now;

        ClockDrivenStore(long startMillis) {
            this.now = startMillis;
        }

        void advance(long millis) {
            this.now += millis;
        }

        @Override
        long currentTimeMillis() {
            return now;
        }
    }

    private static OidcAuthorizationStore.AuthorizationCode newCode() {
        return new OidcAuthorizationStore.AuthorizationCode("https://app/cb", null, null, "openid", null);
    }

    @Test
    public void freshCodeIsConsumable() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putCode("c1", newCode());

        assertThat(store.consumeCode("c1"), is(notNullValue()));
    }

    @Test
    public void codeIsSingleUse() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putCode("c1", newCode());

        assertThat(store.consumeCode("c1"), is(notNullValue()));
        assertThat("second consume of the same code must fail", store.consumeCode("c1"), is(nullValue()));
    }

    @Test
    public void unredeemedCodePastTtlIsRejectedAtConsume() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putCode("c1", newCode());

        // advance just past the TTL
        store.advance(OidcAuthorizationStore.CODE_TTL_MILLIS + 1);

        assertThat("an expired code must be treated as not-found", store.consumeCode("c1"), is(nullValue()));
    }

    @Test
    public void codeWithinTtlIsStillConsumable() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putCode("c1", newCode());

        store.advance(OidcAuthorizationStore.CODE_TTL_MILLIS - 1);

        assertThat(store.consumeCode("c1"), is(notNullValue()));
    }

    @Test
    public void expiredCodesAreEvictedOnWriteSoMapDoesNotGrowUnbounded() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);

        // issue a code, let it expire unredeemed
        store.putCode("stale", newCode());
        store.advance(OidcAuthorizationStore.CODE_TTL_MILLIS + 1);

        // a subsequent write opportunistically evicts the stale code
        store.putCode("fresh", newCode());

        // only the fresh code is retained — the stale code was physically evicted (not merely shadowed
        // or expired-on-consume), so the map cannot grow unbounded with never-redeemed codes.
        assertThat("expired code must be evicted from the map on write", store.codeCount(), is(1));
        assertThat(store.consumeCode("stale"), is(nullValue()));
        assertThat(store.consumeCode("fresh"), is(notNullValue()));
    }

    // --- Wave 2: device-code TTL + single-use semantics ---

    private static OidcAuthorizationStore.DeviceCode newDeviceCode() {
        return new OidcAuthorizationStore.DeviceCode("USER-CODE", "openid", 0);
    }

    @Test
    public void freshDeviceCodeIsPeekableAndConsumable() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putDeviceCode("d1", newDeviceCode());

        assertThat(store.peekDeviceCode("d1"), is(notNullValue()));
        assertThat(store.consumeDeviceCode("d1"), is(notNullValue()));
    }

    @Test
    public void deviceCodeIsSingleUseOnConsume() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putDeviceCode("d1", newDeviceCode());

        assertThat(store.consumeDeviceCode("d1"), is(notNullValue()));
        assertThat("second consume of the same device code must fail",
            store.consumeDeviceCode("d1"), is(nullValue()));
    }

    @Test
    public void unredeemedDeviceCodePastTtlIsRejected() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);
        store.putDeviceCode("d1", newDeviceCode());

        store.advance(OidcAuthorizationStore.DEVICE_CODE_TTL_MILLIS + 1);

        assertThat("expired device code must be treated as not-found", store.peekDeviceCode("d1"), is(nullValue()));
    }

    @Test
    public void expiredDeviceCodesAreEvictedOnWrite() {
        ClockDrivenStore store = new ClockDrivenStore(1_000L);

        store.putDeviceCode("stale", newDeviceCode());
        store.advance(OidcAuthorizationStore.DEVICE_CODE_TTL_MILLIS + 1);
        store.putDeviceCode("fresh", newDeviceCode());

        assertThat("expired device code must be evicted from the map on write", store.deviceCodeCount(), is(1));
        assertThat(store.peekDeviceCode("stale"), is(nullValue()));
        assertThat(store.peekDeviceCode("fresh"), is(notNullValue()));
    }
}
