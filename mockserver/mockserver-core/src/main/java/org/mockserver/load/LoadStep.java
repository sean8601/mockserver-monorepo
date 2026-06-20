package org.mockserver.load;

import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithJsonToString;

/**
 * One ordered step of a {@link LoadScenario}: the request to fire and the optional
 * think-time to wait before firing the <em>next</em> step in the same iteration.
 *
 * <p>The request reuses {@link HttpRequest}; per-iteration template placeholders
 * (e.g. {@code $iteration.index}, {@code $uuid}) live in its fields (path, body,
 * headers, query) and are rendered fresh each iteration by the orchestrator using
 * the scenario's {@code templateType}.
 *
 * <p>{@code thinkTime} is inter-step pacing only — the orchestrator schedules the
 * next step after {@code thinkTime.sampleValueMillis()} on its scheduler thread and
 * never calls {@link Delay#applyDelay()} (which would block a worker thread). A null
 * {@code thinkTime} means no pause.
 *
 * <p>Programmatic cross-step capture is deferred; cross-step state in v1 is template-side
 * (e.g. {@code $scenario.set/get} helpers).
 */
public class LoadStep extends ObjectWithJsonToString {

    private HttpRequest request;
    private Delay thinkTime;

    public static LoadStep loadStep() {
        return new LoadStep();
    }

    public static LoadStep loadStep(HttpRequest request) {
        return new LoadStep().withRequest(request);
    }

    public HttpRequest getRequest() {
        return request;
    }

    public LoadStep withRequest(HttpRequest request) {
        this.request = request;
        return this;
    }

    public Delay getThinkTime() {
        return thinkTime;
    }

    public LoadStep withThinkTime(Delay thinkTime) {
        this.thinkTime = thinkTime;
        return this;
    }
}
