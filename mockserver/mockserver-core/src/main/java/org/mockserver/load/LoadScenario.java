package org.mockserver.load;

import org.mockserver.model.HttpTemplate;
import org.mockserver.model.ObjectWithJsonToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An API-driven load scenario: an ordered list of templated request {@link LoadStep}s
 * driven at a target concurrency described by a {@link LoadProfile}, with optional
 * per-iteration data variation via the scenario's {@link HttpTemplate.TemplateType}.
 *
 * <p>Modelled on {@link org.mockserver.verify.VerificationSequence} (an ordered list of
 * request steps) but oriented at <em>producing</em> traffic rather than asserting over it.
 * It is a pure SLI producer: it records latency/error samples (into the metrics histograms
 * and the SLO sample store) but contains no verdict logic — the SLO verify feature consumes
 * those samples.
 *
 * <p>Off by default; {@code PUT /mockserver/loadScenario} returns 403 unless
 * {@code loadGenerationEnabled} is set.
 */
public class LoadScenario extends ObjectWithJsonToString {

    private String name;
    private List<LoadStep> steps = new ArrayList<>();
    private LoadProfile profile;
    private HttpTemplate.TemplateType templateType = HttpTemplate.TemplateType.VELOCITY;
    private Integer maxRequests;
    /**
     * Scenario-level custom annotation labels. Attached as OpenTelemetry attributes on every
     * load measurement (arbitrary keys — this is the flexible, k6-beating annotation surface) and
     * surfaced in the status DTO. For the Prometheus side, only the keys named in the
     * {@code mockserver.loadGenerationMetricLabels} allowlist are added as extra fixed labels
     * (Prometheus requires a fixed label-name set). Step labels (see {@link LoadStep#getLabels()})
     * are merged on top of these for a given step, with step keys winning on conflict.
     */
    private Map<String, String> labels;

    public static LoadScenario loadScenario() {
        return new LoadScenario();
    }

    public static LoadScenario loadScenario(String name) {
        return new LoadScenario().withName(name);
    }

    public String getName() {
        return name;
    }

    public LoadScenario withName(String name) {
        this.name = name;
        return this;
    }

    public List<LoadStep> getSteps() {
        return steps;
    }

    public LoadScenario withSteps(LoadStep... steps) {
        Collections.addAll(this.steps, steps);
        return this;
    }

    public LoadScenario withSteps(List<LoadStep> steps) {
        this.steps = steps;
        return this;
    }

    public LoadProfile getProfile() {
        return profile;
    }

    public LoadScenario withProfile(LoadProfile profile) {
        this.profile = profile;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public LoadScenario withTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }

    public Integer getMaxRequests() {
        return maxRequests;
    }

    public LoadScenario withMaxRequests(Integer maxRequests) {
        this.maxRequests = maxRequests;
        return this;
    }

    /**
     * Scenario-level custom labels (may be null/empty). See {@link #labels}.
     */
    public Map<String, String> getLabels() {
        return labels;
    }

    public LoadScenario withLabels(Map<String, String> labels) {
        this.labels = labels != null ? new LinkedHashMap<>(labels) : null;
        return this;
    }

    public LoadScenario withLabel(String name, String value) {
        if (this.labels == null) {
            this.labels = new LinkedHashMap<>();
        }
        this.labels.put(name, value);
        return this;
    }
}
