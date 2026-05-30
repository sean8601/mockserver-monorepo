package org.mockserver.serialization.model;

import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class HttpChaosProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpChaosProfile> {

    private Integer errorStatus;
    private String retryAfter;
    private Double errorProbability;
    private DelayDTO latency;
    private Long seed;
    private Integer succeedFirst;
    private Integer failRequestCount;

    public HttpChaosProfileDTO(HttpChaosProfile httpChaosProfile) {
        if (httpChaosProfile != null) {
            errorStatus = httpChaosProfile.getErrorStatus();
            retryAfter = httpChaosProfile.getRetryAfter();
            errorProbability = httpChaosProfile.getErrorProbability();
            if (httpChaosProfile.getLatency() != null) {
                latency = new DelayDTO(httpChaosProfile.getLatency());
            }
            seed = httpChaosProfile.getSeed();
            succeedFirst = httpChaosProfile.getSucceedFirst();
            failRequestCount = httpChaosProfile.getFailRequestCount();
        }
    }

    public HttpChaosProfileDTO() {
    }

    public HttpChaosProfile buildObject() {
        return HttpChaosProfile.httpChaosProfile()
            .withErrorStatus(errorStatus)
            .withRetryAfter(retryAfter)
            .withErrorProbability(errorProbability)
            .withLatency(latency != null ? latency.buildObject() : null)
            .withSeed(seed)
            .withSucceedFirst(succeedFirst)
            .withFailRequestCount(failRequestCount);
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public HttpChaosProfileDTO setErrorStatus(Integer errorStatus) {
        this.errorStatus = errorStatus;
        return this;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public HttpChaosProfileDTO setRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
        return this;
    }

    public Double getErrorProbability() {
        return errorProbability;
    }

    public HttpChaosProfileDTO setErrorProbability(Double errorProbability) {
        this.errorProbability = errorProbability;
        return this;
    }

    public DelayDTO getLatency() {
        return latency;
    }

    public HttpChaosProfileDTO setLatency(DelayDTO latency) {
        this.latency = latency;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public HttpChaosProfileDTO setSeed(Long seed) {
        this.seed = seed;
        return this;
    }

    public Integer getSucceedFirst() {
        return succeedFirst;
    }

    public HttpChaosProfileDTO setSucceedFirst(Integer succeedFirst) {
        this.succeedFirst = succeedFirst;
        return this;
    }

    public Integer getFailRequestCount() {
        return failRequestCount;
    }

    public HttpChaosProfileDTO setFailRequestCount(Integer failRequestCount) {
        this.failRequestCount = failRequestCount;
        return this;
    }
}
