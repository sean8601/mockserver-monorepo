package org.mockserver.load;

import org.mockserver.model.ObjectWithJsonToString;

/**
 * The ramp / load profile of a {@link LoadScenario}: how many virtual users (VUs) the
 * scenario drives over its run, and for how long.
 *
 * <p>v1 supports two shapes (the {@code STEP} / {@code SPIKE} / {@code SOAK} shapes are
 * a deferred extension point):
 * <ul>
 *   <li>{@link Type#CONSTANT} — a fixed {@code vus} for the whole {@code durationMillis}.</li>
 *   <li>{@link Type#LINEAR} — ramp linearly from {@code startVus} to {@code endVus} across
 *       {@code durationMillis}.</li>
 * </ul>
 *
 * <p>{@link #targetVusAt(long)} is the single source of truth for the setpoint: the
 * orchestrator's control tick reads it and grows/retires VUs to match. It is pure and
 * deterministic so it can be unit-tested without driving real traffic.
 */
public class LoadProfile extends ObjectWithJsonToString {

    public enum Type {
        CONSTANT,
        LINEAR
    }

    private Type type = Type.CONSTANT;
    private long durationMillis;
    private int vus;
    private int startVus;
    private int endVus;
    private Long iterationPacingMillis;

    public static LoadProfile loadProfile() {
        return new LoadProfile();
    }

    public static LoadProfile constant(int vus, long durationMillis) {
        return new LoadProfile().withType(Type.CONSTANT).withVus(vus).withDurationMillis(durationMillis);
    }

    public static LoadProfile linear(int startVus, int endVus, long durationMillis) {
        return new LoadProfile().withType(Type.LINEAR).withStartVus(startVus).withEndVus(endVus).withDurationMillis(durationMillis);
    }

    /**
     * The target number of concurrent virtual users at {@code elapsedMillis} into the run.
     *
     * <ul>
     *   <li>{@code CONSTANT}: always {@code vus}.</li>
     *   <li>{@code LINEAR}: {@code round(startVus + min(1, elapsed/duration) * (endVus - startVus))},
     *       clamped so the fraction never exceeds 1 (after the duration it stays at {@code endVus}).</li>
     * </ul>
     */
    public int targetVusAt(long elapsedMillis) {
        if (type == Type.LINEAR) {
            double fraction = durationMillis <= 0 ? 1.0 : Math.min(1.0, (double) Math.max(0, elapsedMillis) / durationMillis);
            return (int) Math.round(startVus + fraction * (endVus - startVus));
        }
        return vus;
    }

    public Type getType() {
        return type;
    }

    public LoadProfile withType(Type type) {
        this.type = type;
        return this;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public LoadProfile withDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
        return this;
    }

    public int getVus() {
        return vus;
    }

    public LoadProfile withVus(int vus) {
        this.vus = vus;
        return this;
    }

    public int getStartVus() {
        return startVus;
    }

    public LoadProfile withStartVus(int startVus) {
        this.startVus = startVus;
        return this;
    }

    public int getEndVus() {
        return endVus;
    }

    public LoadProfile withEndVus(int endVus) {
        this.endVus = endVus;
        return this;
    }

    public Long getIterationPacingMillis() {
        return iterationPacingMillis;
    }

    public LoadProfile withIterationPacingMillis(Long iterationPacingMillis) {
        this.iterationPacingMillis = iterationPacingMillis;
        return this;
    }

    /**
     * The maximum concurrency this profile will ever request, used to enforce the
     * VU hard cap up-front at validation regardless of ramp shape.
     */
    public int peakVus() {
        if (type == Type.LINEAR) {
            return Math.max(startVus, endVus);
        }
        return vus;
    }
}
