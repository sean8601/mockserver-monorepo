package org.mockserver.serialization.model;

import org.mockserver.load.LoadStep;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * @author jamesdbloom
 */
public class LoadStepDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadStep> {

    private HttpRequestDTO request;
    private DelayDTO thinkTime;

    public LoadStepDTO(LoadStep step) {
        if (step != null) {
            if (step.getRequest() != null) {
                request = new HttpRequestDTO(step.getRequest());
            }
            if (step.getThinkTime() != null) {
                thinkTime = new DelayDTO(step.getThinkTime());
            }
        }
    }

    public LoadStepDTO() {
    }

    public LoadStep buildObject() {
        LoadStep step = new LoadStep();
        if (request != null) {
            HttpRequest builtRequest = request.buildObject();
            step.withRequest(builtRequest);
        }
        if (thinkTime != null) {
            step.withThinkTime(thinkTime.buildObject());
        }
        return step;
    }

    public HttpRequestDTO getRequest() {
        return request;
    }

    public LoadStepDTO setRequest(HttpRequestDTO request) {
        this.request = request;
        return this;
    }

    public DelayDTO getThinkTime() {
        return thinkTime;
    }

    public LoadStepDTO setThinkTime(DelayDTO thinkTime) {
        this.thinkTime = thinkTime;
        return this;
    }
}
