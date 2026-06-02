package org.mockserver.netty.proxy;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A composite {@link TransparentProxyHandler.OriginalDestinationResolver} that tries
 * an ordered chain of resolution strategies and returns the first non-null result.
 * <p>
 * Each strategy is invoked in order. If a strategy returns {@code null} or throws
 * {@link UnsupportedOperationException} (e.g., platform not supported), the next
 * strategy is tried. Any other exception is caught, logged at the calling layer,
 * and treated as a skip (fall through to the next strategy).
 * <p>
 * <b>Default chain</b> (constructed via {@link #defaultChain()}):
 * <ol>
 *   <li>{@link ConntrackOriginalDestinationResolver} — Linux conntrack table lookup</li>
 *   <li>{@link DnsIntentOriginalDestinationResolver} — recovers the intended hostname
 *       from MockServer's DNS answer cache (DNS-steering mode)</li>
 * </ol>
 * <p>
 * <b>Future strategies (not yet implemented — require native/JNI code):</b>
 * <ul>
 *   <li><b>SO_ORIGINAL_DST getsockopt</b> — direct socket option read via JNI. More
 *       efficient than conntrack parsing (O(1) vs O(n)), but requires a native library
 *       or Netty's {@code EpollChannelOption} extension (not currently exposed).</li>
 *   <li><b>TPROXY (IP_TRANSPARENT)</b> — the TPROXY iptables target preserves the
 *       original destination as the socket's local address. Resolution is trivial
 *       ({@code channel.localAddress()}) but requires {@code CAP_NET_ADMIN} and
 *       TPROXY iptables rules instead of REDIRECT.</li>
 *   <li><b>eBPF socket metadata</b> — an eBPF program attached to the cgroup can store
 *       the original destination in a BPF map keyed by socket cookie. The resolver would
 *       read the map entry via a JNI helper or {@code /sys/fs/bpf/} pinned map.</li>
 * </ul>
 * <p>
 * Note: PROXY protocol v1/v2 resolution is handled separately by
 * {@link ProxyProtocolOriginalDestinationHandler} in the Netty pipeline (it requires
 * reading inbound bytes, not just channel metadata at {@code channelActive} time).
 *
 * @see TransparentProxyHandler
 * @see ConntrackOriginalDestinationResolver
 */
public class CompositeOriginalDestinationResolver implements TransparentProxyHandler.OriginalDestinationResolver {

    private final List<TransparentProxyHandler.OriginalDestinationResolver> strategies;

    /**
     * Creates a composite resolver with the given ordered strategies.
     *
     * @param strategies the resolution strategies to try in order; must not be null or empty
     * @throws IllegalArgumentException if strategies is null or empty
     */
    public CompositeOriginalDestinationResolver(List<TransparentProxyHandler.OriginalDestinationResolver> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("at least one resolver strategy is required");
        }
        this.strategies = Collections.unmodifiableList(new ArrayList<>(strategies));
    }

    /**
     * Returns the default chain: [conntrack, dns-intent].
     * <p>
     * Conntrack (or SO_ORIGINAL_DST) is tried first because a real iptables-REDIRECT
     * original destination is the most authoritative source. The DNS-intent resolver
     * fills the gap when conntrack returns null — it recovers the hostname that
     * MockServer's DNS server mapped to the connection's destination IP.
     */
    public static CompositeOriginalDestinationResolver defaultChain() {
        return new CompositeOriginalDestinationResolver(
            Arrays.asList(
                new ConntrackOriginalDestinationResolver(),
                new DnsIntentOriginalDestinationResolver()
            )
        );
    }

    /**
     * Tries each strategy in order. Returns the first non-null result.
     * <p>
     * If a strategy throws {@link UnsupportedOperationException}, it is skipped
     * (expected on unsupported platforms). Any other exception is also caught and
     * skipped (fail-safe: the caller logs at the appropriate level).
     *
     * @param channel the accepted Netty channel
     * @return the resolved original destination, or {@code null} if no strategy resolved it
     */
    @Override
    public InetSocketAddress resolve(Channel channel) {
        for (TransparentProxyHandler.OriginalDestinationResolver strategy : strategies) {
            try {
                InetSocketAddress result = strategy.resolve(channel);
                if (result != null) {
                    return result;
                }
            } catch (UnsupportedOperationException e) {
                // Expected — strategy not available on this platform; try next
            } catch (Exception e) {
                // Unexpected error — skip this strategy, try next
                // The TransparentProxyHandler's logging will capture the overall outcome
            }
        }
        return null;
    }

    /**
     * Returns the number of strategies in this chain (useful for testing).
     */
    public int strategyCount() {
        return strategies.size();
    }
}
