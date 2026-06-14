package org.mockserver.verify;

import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.model.RequestDefinition;

import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class Verification extends ObjectWithJsonToString {
    private RequestDefinition httpRequest;
    private HttpResponse httpResponse;
    private ExpectationId expectationId;
    private VerificationTimes times = VerificationTimes.atLeast(1);
    private Integer maximumNumberOfRequestToReturnInVerificationFailure;

    public static Verification verification() {
        return new Verification();
    }

    public Verification withRequest(RequestDefinition requestDefinition) {
        this.httpRequest = requestDefinition;
        return this;
    }

    public RequestDefinition getHttpRequest() {
        return httpRequest;
    }

    public Verification withResponse(HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
        return this;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public Verification withExpectationId(ExpectationId expectationId) {
        this.expectationId = expectationId;
        return this;
    }

    public ExpectationId getExpectationId() {
        return expectationId;
    }

    public Verification withTimes(VerificationTimes times) {
        this.times = times;
        return this;
    }

    public VerificationTimes getTimes() {
        return times;
    }

    public Integer getMaximumNumberOfRequestToReturnInVerificationFailure() {
        return maximumNumberOfRequestToReturnInVerificationFailure;
    }

    public Verification withMaximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerificationFailure) {
        this.maximumNumberOfRequestToReturnInVerificationFailure = maximumNumberOfRequestToReturnInVerificationFailure;
        return this;
    }
}
