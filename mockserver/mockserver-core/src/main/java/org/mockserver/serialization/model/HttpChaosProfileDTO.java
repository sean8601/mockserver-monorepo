package org.mockserver.serialization.model;

import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class HttpChaosProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpChaosProfile> {

    private Integer errorStatus;
    private String retryAfter;
    private Double errorProbability;
    private Double dropConnectionProbability;
    private DelayDTO latency;
    private Long seed;
    private Integer succeedFirst;
    private Integer failRequestCount;
    private Long outageAfterMillis;
    private Long outageDurationMillis;

    public HttpChaosProfileDTO(HttpChaosProfile httpChaosProfile) {
        if (httpChaosProfile != null) {
            errorStatus = httpChaosProfile.getErrorStatus();
            retryAfter = httpChaosProfile.getRetryAfter();
            errorProbability = httpChaosProfile.getErrorProbability();
            dropConnectionProbability = httpChaosProfile.getDropConnectionProbability();
            if (httpChaosProfile.getLatency() != null) {
                latency = new DelayDTO(httpChaosProfile.getLatency());
            }
            seed = httpChaosProfile.getSeed();
            succeedFirst = httpChaosProfile.getSucceedFirst();
            failRequestCount = httpChaosProfile.getFailRequestCount();
            outageAfterMillis = httpChaosProfile.getOutageAfterMillis();
            outageDurationMillis = httpChaosProfile.getOutageDurationMillis();
        }
    }

    public HttpChaosProfileDTO() {
    }

    public HttpChaosProfile buildObject() {
        return HttpChaosProfile.httpChaosProfile()
            .withErrorStatus(errorStatus)
            .withRetryAfter(retryAfter)
            .withErrorProbability(errorProbability)
            .withDropConnectionProbability(dropConnectionProbability)
            .withLatency(latency != null ? latency.buildObject() : null)
            .withSeed(seed)
            .withSucceedFirst(succeedFirst)
            .withFailRequestCount(failRequestCount)
            .withOutageAfterMillis(outageAfterMillis)
            .withOutageDurationMillis(outageDurationMillis);
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

    public Double getDropConnectionProbability() {
        return dropConnectionProbability;
    }

    public HttpChaosProfileDTO setDropConnectionProbability(Double dropConnectionProbability) {
        this.dropConnectionProbability = dropConnectionProbability;
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

    public Long getOutageAfterMillis() {
        return outageAfterMillis;
    }

    public HttpChaosProfileDTO setOutageAfterMillis(Long outageAfterMillis) {
        this.outageAfterMillis = outageAfterMillis;
        return this;
    }

    public Long getOutageDurationMillis() {
        return outageDurationMillis;
    }

    public HttpChaosProfileDTO setOutageDurationMillis(Long outageDurationMillis) {
        this.outageDurationMillis = outageDurationMillis;
        return this;
    }
}
