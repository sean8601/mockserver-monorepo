package org.mockserver.serialization.model;

import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStep;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

/**
 * @author jamesdbloom
 */
public class LoadScenarioDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadScenario> {

    private String name;
    private List<LoadStepDTO> steps = new ArrayList<>();
    private LoadProfileDTO profile;
    private HttpTemplate.TemplateType templateType;
    private Integer maxRequests;

    public LoadScenarioDTO(LoadScenario scenario) {
        if (scenario != null) {
            name = scenario.getName();
            if (scenario.getSteps() != null) {
                for (LoadStep step : scenario.getSteps()) {
                    steps.add(new LoadStepDTO(step));
                }
            }
            if (scenario.getProfile() != null) {
                profile = new LoadProfileDTO(scenario.getProfile());
            }
            templateType = scenario.getTemplateType();
            maxRequests = scenario.getMaxRequests();
        }
    }

    public LoadScenarioDTO() {
    }

    public LoadScenario buildObject() {
        List<LoadStep> builtSteps = new ArrayList<>();
        if (steps != null) {
            for (LoadStepDTO step : steps) {
                builtSteps.add(step.buildObject());
            }
        }
        return new LoadScenario()
            .withName(name)
            .withSteps(builtSteps)
            .withProfile(profile != null ? profile.buildObject() : null)
            .withTemplateType(templateType != null ? templateType : HttpTemplate.TemplateType.VELOCITY)
            .withMaxRequests(maxRequests);
    }

    public String getName() {
        return name;
    }

    public LoadScenarioDTO setName(String name) {
        this.name = name;
        return this;
    }

    public List<LoadStepDTO> getSteps() {
        return steps;
    }

    public LoadScenarioDTO setSteps(List<LoadStepDTO> steps) {
        this.steps = steps;
        return this;
    }

    public LoadProfileDTO getProfile() {
        return profile;
    }

    public LoadScenarioDTO setProfile(LoadProfileDTO profile) {
        this.profile = profile;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public LoadScenarioDTO setTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }

    public Integer getMaxRequests() {
        return maxRequests;
    }

    public LoadScenarioDTO setMaxRequests(Integer maxRequests) {
        this.maxRequests = maxRequests;
        return this;
    }
}
