package org.mockserver.serialization.model;

import org.mockserver.load.LoadProfile;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * @author jamesdbloom
 */
public class LoadProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadProfile> {

    private LoadProfile.Type type = LoadProfile.Type.CONSTANT;
    private long durationMillis;
    private int vus;
    private int startVus;
    private int endVus;
    private Long iterationPacingMillis;

    public LoadProfileDTO(LoadProfile profile) {
        if (profile != null) {
            type = profile.getType();
            durationMillis = profile.getDurationMillis();
            vus = profile.getVus();
            startVus = profile.getStartVus();
            endVus = profile.getEndVus();
            iterationPacingMillis = profile.getIterationPacingMillis();
        }
    }

    public LoadProfileDTO() {
    }

    public LoadProfile buildObject() {
        return new LoadProfile()
            .withType(type != null ? type : LoadProfile.Type.CONSTANT)
            .withDurationMillis(durationMillis)
            .withVus(vus)
            .withStartVus(startVus)
            .withEndVus(endVus)
            .withIterationPacingMillis(iterationPacingMillis);
    }

    public LoadProfile.Type getType() {
        return type;
    }

    public LoadProfileDTO setType(LoadProfile.Type type) {
        this.type = type;
        return this;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public LoadProfileDTO setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
        return this;
    }

    public int getVus() {
        return vus;
    }

    public LoadProfileDTO setVus(int vus) {
        this.vus = vus;
        return this;
    }

    public int getStartVus() {
        return startVus;
    }

    public LoadProfileDTO setStartVus(int startVus) {
        this.startVus = startVus;
        return this;
    }

    public int getEndVus() {
        return endVus;
    }

    public LoadProfileDTO setEndVus(int endVus) {
        this.endVus = endVus;
        return this;
    }

    public Long getIterationPacingMillis() {
        return iterationPacingMillis;
    }

    public LoadProfileDTO setIterationPacingMillis(Long iterationPacingMillis) {
        this.iterationPacingMillis = iterationPacingMillis;
        return this;
    }
}
