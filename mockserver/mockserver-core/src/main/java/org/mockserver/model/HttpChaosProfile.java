package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative HTTP fault/chaos injection for mocked and forwarded responses:
 * probabilistic connection-drop injection, error status injection (e.g. 500,
 * 503, 429 with an optional {@code Retry-After} header), latency injection, and
 * response-body corruption ({@code truncateBodyAtFraction} keeps only a leading
 * fraction of the body bytes; {@code malformedBody} appends a broken-JSON
 * fragment) for testing client-side body-parsing resilience.
 * <p>
 * Body corruption is deterministic (no probability draw): it applies to the
 * real (non-error) response whenever the count and time windows are eligible,
 * and is skipped for streaming bodies. Connection-drop and error injection take
 * priority — when an error is injected the synthetic error body is returned
 * uncorrupted.
 * <p>
 * Attach to an {@link org.mockserver.mock.Expectation} via
 * {@code expectation.withChaos(httpChaosProfile()...)} to inject faults into
 * the following action types:
 * <ul>
 *   <li>Mocked responses: RESPONSE, RESPONSE_TEMPLATE, RESPONSE_CLASS_CALLBACK</li>
 *   <li>Forward actions: FORWARD, FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK,
 *       FORWARD_REPLACE, FORWARD_VALIDATE</li>
 * </ul>
 * Not yet covered: RESPONSE_OBJECT_CALLBACK and FORWARD_OBJECT_CALLBACK (both
 * use their own callback-driven write path) and the anonymous/unmatched
 * proxy-pass path.
 * <p>
 * Determinism: with {@code errorProbability} of {@code 1.0} (always) or
 * {@code 0.0}/null (never) the error decision is fully deterministic. A
 * fractional probability draws once per response; set {@code seed} to make that
 * single draw reproducible (note: a fixed seed yields the same decision every
 * time).
 * <p>
 * Count-based stateful fault window: {@code succeedFirst} and
 * {@code failRequestCount} define a window over the expectation's 1-based
 * match count where chaos is eligible:
 * <ul>
 *   <li>Matches 1..succeedFirst are NOT eligible (chaos is suppressed).</li>
 *   <li>Matches (succeedFirst+1)..(succeedFirst+failRequestCount) ARE eligible.</li>
 *   <li>Matches beyond succeedFirst+failRequestCount recover (no chaos).</li>
 * </ul>
 * When both fields are {@code null} every match is eligible, preserving
 * backward compatibility. The window check is deterministic and composes
 * with the probabilistic error draw: a match must be within the window AND
 * pass the probability check to receive an injected fault.
 * <p>
 * Time-based outage window: {@code outageAfterMillis} and
 * {@code outageDurationMillis} define a self-healing window, measured relative
 * to the expectation's first match, during which chaos is active. The window
 * opens {@code outageAfterMillis} ms after the first match and (when a duration
 * is set) closes after {@code outageDurationMillis} ms, after which the service
 * behaves normally again. The window is measured with the controllable clock
 * ({@link org.mockserver.time.TimeService}), so freezing/advancing the clock
 * (e.g. via {@code PUT /mockserver/clock}) makes it deterministic in tests. It
 * composes with the count window and the probability draws: a fault fires only
 * when the request is inside the time window AND the count window AND the draw
 * passes. When both fields are {@code null} there is no time gate.
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips
 * without a bespoke (de)serializer.
 */
public class HttpChaosProfile extends ObjectWithJsonToString {

    private int hashCode;
    private Integer errorStatus;       // HTTP status to inject (e.g. 500, 503, 429)
    private String retryAfter;         // optional Retry-After header value on injected error
    private Double errorProbability;   // 0.0-1.0; null/0 = never inject an error
    private Double dropConnectionProbability; // 0.0-1.0; null/0 = never drop the connection
    private Delay latency;             // optional injected latency
    private Long seed;                 // optional, makes a fractional errorProbability reproducible
    private Integer succeedFirst;      // first N matches are NOT eligible for chaos (>= 0; null = 0)
    private Integer failRequestCount;  // after succeedFirst, next M matches ARE eligible (>= 1; null = unlimited)
    private Long outageAfterMillis;    // chaos starts this many ms after the first match (>= 0; null = 0)
    private Long outageDurationMillis; // after outageAfterMillis, chaos stays active for this long then self-heals (>= 1; null = unbounded)
    private Double truncateBodyAtFraction; // 0.0-1.0 fraction of the response body bytes to keep; null = don't truncate
    private Boolean malformedBody;     // append a broken-JSON fragment to corrupt the response body; null/false = don't corrupt

    public static HttpChaosProfile httpChaosProfile() {
        return new HttpChaosProfile();
    }

    public HttpChaosProfile withErrorStatus(Integer errorStatus) {
        if (errorStatus != null && (errorStatus < 100 || errorStatus > 599)) {
            throw new IllegalArgumentException("errorStatus must be between 100 and 599, got " + errorStatus);
        }
        this.errorStatus = errorStatus;
        this.hashCode = 0;
        return this;
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public HttpChaosProfile withRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        this.hashCode = 0;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public HttpChaosProfile withErrorProbability(Double errorProbability) {
        if (errorProbability != null && (Double.isNaN(errorProbability) || errorProbability < 0.0 || errorProbability > 1.0)) {
            throw new IllegalArgumentException("errorProbability must be between 0.0 and 1.0, got " + errorProbability);
        }
        this.errorProbability = errorProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public HttpChaosProfile withDropConnectionProbability(Double dropConnectionProbability) {
        if (dropConnectionProbability != null && (Double.isNaN(dropConnectionProbability) || dropConnectionProbability < 0.0 || dropConnectionProbability > 1.0)) {
            throw new IllegalArgumentException("dropConnectionProbability must be between 0.0 and 1.0, got " + dropConnectionProbability);
        }
        this.dropConnectionProbability = dropConnectionProbability;
        this.hashCode = 0;
        return this;
    }

    public Double getDropConnectionProbability() {
        return dropConnectionProbability;
    }

    public HttpChaosProfile withLatency(Delay latency) {
        this.latency = latency;
        this.hashCode = 0;
        return this;
    }

    public Delay getLatency() {
        return latency;
    }

    public HttpChaosProfile withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public HttpChaosProfile withSucceedFirst(Integer succeedFirst) {
        if (succeedFirst != null && succeedFirst < 0) {
            throw new IllegalArgumentException("succeedFirst must be >= 0, got " + succeedFirst);
        }
        this.succeedFirst = succeedFirst;
        this.hashCode = 0;
        return this;
    }

    public Integer getSucceedFirst() {
        return succeedFirst;
    }

    public HttpChaosProfile withFailRequestCount(Integer failRequestCount) {
        if (failRequestCount != null && failRequestCount < 1) {
            throw new IllegalArgumentException("failRequestCount must be >= 1, got " + failRequestCount);
        }
        this.failRequestCount = failRequestCount;
        this.hashCode = 0;
        return this;
    }

    public Integer getFailRequestCount() {
        return failRequestCount;
    }

    public HttpChaosProfile withOutageAfterMillis(Long outageAfterMillis) {
        if (outageAfterMillis != null && outageAfterMillis < 0) {
            throw new IllegalArgumentException("outageAfterMillis must be >= 0, got " + outageAfterMillis);
        }
        this.outageAfterMillis = outageAfterMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getOutageAfterMillis() {
        return outageAfterMillis;
    }

    public HttpChaosProfile withOutageDurationMillis(Long outageDurationMillis) {
        if (outageDurationMillis != null && outageDurationMillis < 1) {
            throw new IllegalArgumentException("outageDurationMillis must be >= 1, got " + outageDurationMillis);
        }
        this.outageDurationMillis = outageDurationMillis;
        this.hashCode = 0;
        return this;
    }

    public Long getOutageDurationMillis() {
        return outageDurationMillis;
    }

    public HttpChaosProfile withTruncateBodyAtFraction(Double truncateBodyAtFraction) {
        if (truncateBodyAtFraction != null && (Double.isNaN(truncateBodyAtFraction) || truncateBodyAtFraction < 0.0 || truncateBodyAtFraction > 1.0)) {
            throw new IllegalArgumentException("truncateBodyAtFraction must be between 0.0 and 1.0, got " + truncateBodyAtFraction);
        }
        this.truncateBodyAtFraction = truncateBodyAtFraction;
        this.hashCode = 0;
        return this;
    }

    public Double getTruncateBodyAtFraction() {
        return truncateBodyAtFraction;
    }

    public HttpChaosProfile withMalformedBody(Boolean malformedBody) {
        this.malformedBody = malformedBody;
        this.hashCode = 0;
        return this;
    }

    public Boolean getMalformedBody() {
        return malformedBody;
    }

    /**
     * Returns {@code true} when the request falls within the time-based outage
     * window defined by {@code outageAfterMillis} and {@code outageDurationMillis},
     * measured relative to the expectation's first match. When both fields are
     * {@code null} there is no time gate and this returns {@code true} (backward
     * compatible). The window opens {@code outageAfterMillis} ms after the first
     * match and, when {@code outageDurationMillis} is set, closes (self-heals)
     * after that duration. The instants are supplied by the controllable clock
     * ({@link org.mockserver.time.TimeService}) so the window is deterministic
     * under clock freeze/advance.
     *
     * @param firstMatchEpochMillis epoch-ms of the expectation's first match (0 when not yet recorded)
     * @param nowEpochMillis        the current epoch-ms (from the controllable clock)
     * @return {@code true} if this request is inside the outage window (or there is no window)
     */
    public boolean timeWindowEligible(long firstMatchEpochMillis, long nowEpochMillis) {
        if (outageAfterMillis == null && outageDurationMillis == null) {
            return true;
        }
        if (firstMatchEpochMillis <= 0L) {
            return true;
        }
        long elapsed = nowEpochMillis - firstMatchEpochMillis;
        long start = outageAfterMillis != null ? outageAfterMillis : 0L;
        if (elapsed < start) {
            return false;
        }
        if (outageDurationMillis != null && elapsed >= start + outageDurationMillis) {
            return false;
        }
        return true;
    }

    /**
     * Returns {@code true} when the given 1-based match count falls within the
     * chaos-eligible window defined by {@code succeedFirst} and
     * {@code failRequestCount}. When both fields are {@code null} this returns
     * {@code true} for any {@code matchCount} (backward compatible), including
     * {@code matchCount == 0} which the handler passes when chaos is null (the
     * no-chaos overloads).
     *
     * @param matchCount 1-based match count from the expectation (0 when unknown)
     * @return {@code true} if this match is eligible for chaos injection
     */
    public boolean countWindowEligible(int matchCount) {
        if (succeedFirst == null && failRequestCount == null) {
            return true;
        }
        int after = succeedFirst != null ? succeedFirst : 0;
        if (matchCount <= after) {
            return false;
        }
        if (failRequestCount != null && (long) matchCount > (long) after + failRequestCount) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        HttpChaosProfile that = (HttpChaosProfile) o;
        return Objects.equals(errorStatus, that.errorStatus) &&
            Objects.equals(retryAfter, that.retryAfter) &&
            Objects.equals(errorProbability, that.errorProbability) &&
            Objects.equals(dropConnectionProbability, that.dropConnectionProbability) &&
            Objects.equals(latency, that.latency) &&
            Objects.equals(seed, that.seed) &&
            Objects.equals(succeedFirst, that.succeedFirst) &&
            Objects.equals(failRequestCount, that.failRequestCount) &&
            Objects.equals(outageAfterMillis, that.outageAfterMillis) &&
            Objects.equals(outageDurationMillis, that.outageDurationMillis) &&
            Objects.equals(truncateBodyAtFraction, that.truncateBodyAtFraction) &&
            Objects.equals(malformedBody, that.malformedBody);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(errorStatus, retryAfter, errorProbability, dropConnectionProbability, latency, seed, succeedFirst, failRequestCount, outageAfterMillis, outageDurationMillis, truncateBodyAtFraction, malformedBody);
        }
        return hashCode;
    }
}
