package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpResponseModifier.responseModifier;
import static org.mockserver.model.HttpResponseModifierCondition.responseModifierCondition;

public class HttpResponseModifierTest {

    // ---- backward-compatible single-modifier behaviour -------------------------------------

    @Test
    public void unconditionalModifierAddsHeader() {
        HttpResponse response = response().withStatusCode(200);
        responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null)
            .applyTo(response, null);

        assertThat(response.getFirstHeader("X-Added"), is("value"));
    }

    @Test
    public void unconditionalModifierMatchesLegacyUpdateBehaviour() {
        // legacy two-arg update() must behave identically to before
        HttpResponse response = response().withStatusCode(200).withHeader("X-Original", "keep");
        response.update(null, responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null));

        assertThat(response.getFirstHeader("X-Added"), is("value"));
        assertThat(response.getFirstHeader("X-Original"), is("keep"));
    }

    // ---- conditional application -----------------------------------------------------------

    @Test
    public void conditionTrueAppliesModifier() {
        HttpResponse response = response().withStatusCode(503);
        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withHeaders(Collections.singletonList(header("X-Server-Error", "true")), null, null)
            .applyTo(response, null);

        assertThat(response.getFirstHeader("X-Server-Error"), is("true"));
    }

    @Test
    public void conditionFalseSkipsModifier() {
        HttpResponse response = response().withStatusCode(200);
        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withHeaders(Collections.singletonList(header("X-Server-Error", "true")), null, null)
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Server-Error"), is(false));
    }

    @Test
    public void conditionOnExactStatusCode() {
        HttpResponse hit = response().withStatusCode(404);
        HttpResponse miss = response().withStatusCode(200);
        HttpResponseModifier modifier = responseModifier()
            .withCondition(responseModifierCondition().withStatusCode(404))
            .withHeaders(Collections.singletonList(header("X-Not-Found", "yes")), null, null);

        modifier.applyTo(hit, null);
        modifier.applyTo(miss, null);

        assertThat(hit.getFirstHeader("X-Not-Found"), is("yes"));
        assertThat(miss.containsHeader("X-Not-Found"), is(false));
    }

    @Test
    public void conditionOnResponseHeaderPresence() {
        HttpResponse hit = response().withStatusCode(200).withHeader("Content-Type", "application/json");
        HttpResponse miss = response().withStatusCode(200);
        HttpResponseModifier modifier = responseModifier()
            .withCondition(responseModifierCondition().withResponseHasHeader("Content-Type"))
            .withHeaders(Collections.singletonList(header("X-Json", "true")), null, null);

        modifier.applyTo(hit, null);
        modifier.applyTo(miss, null);

        assertThat(hit.getFirstHeader("X-Json"), is("true"));
        assertThat(miss.containsHeader("X-Json"), is(false));
    }

    @Test
    public void conditionOnRequestHeaderPresence() {
        HttpResponse response = response().withStatusCode(200);
        HttpRequest request = HttpRequest.request().withHeader("X-Debug", "1");
        responseModifier()
            .withCondition(responseModifierCondition().withRequestHasHeader("X-Debug"))
            .withHeaders(Collections.singletonList(header("X-Debug-Echo", "1")), null, null)
            .applyTo(response, request);

        assertThat(response.getFirstHeader("X-Debug-Echo"), is("1"));
    }

    @Test
    public void requestConditionSkippedWhenNoRequestAvailable() {
        HttpResponse response = response().withStatusCode(200);
        responseModifier()
            .withCondition(responseModifierCondition().withRequestHasHeader("X-Debug"))
            .withHeaders(Collections.singletonList(header("X-Debug-Echo", "1")), null, null)
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Debug-Echo"), is(false));
    }

    @Test
    public void multipleCriteriaAreLogicalAnd() {
        HttpResponse response = response().withStatusCode(503).withHeader("Content-Type", "text/plain");
        // status matches but header criterion does not -> no apply
        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx").withResponseHasHeader("X-Missing"))
            .withHeaders(Collections.singletonList(header("X-Applied", "yes")), null, null)
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Applied"), is(false));
    }

    // ---- ordered chain ---------------------------------------------------------------------

    @Test
    public void chainAppliesModifiersInOrderAndLaterSeesEarlierOutput() {
        HttpResponse response = response().withStatusCode(200);

        HttpResponseModifier modifierA = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Stage", "a")), null, null);
        // modifier B replaces X-Stage, which only exists because A added it
        HttpResponseModifier modifierB = responseModifier()
            .withHeaders(null, Collections.singletonList(header("X-Stage", "b")), null);

        responseModifier()
            .withModifiers(Arrays.asList(modifierA, modifierB))
            .applyTo(response, null);

        // B saw A's output and replaced it
        assertThat(response.getFirstHeader("X-Stage"), is("b"));
    }

    @Test
    public void chainRespectsPerModifierConditions() {
        HttpResponse response = response().withStatusCode(204);

        HttpResponseModifier always = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Always", "1")), null, null);
        HttpResponseModifier onlyOn5xx = responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withHeaders(Collections.singletonList(header("X-Only5xx", "1")), null, null);

        responseModifier()
            .withModifiers(Arrays.asList(always, onlyOn5xx))
            .applyTo(response, null);

        assertThat(response.getFirstHeader("X-Always"), is("1"));
        assertThat(response.containsHeader("X-Only5xx"), is(false));
    }

    @Test
    public void wrappingConditionGatesWholeChain() {
        HttpResponse response = response().withStatusCode(200);
        HttpResponseModifier child = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Child", "1")), null, null);

        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withModifiers(Collections.singletonList(child))
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Child"), is(false));
    }

    @Test
    public void nullResponseIsNoOp() {
        // must not throw
        responseModifier()
            .withHeaders(Collections.singletonList(header("X", "y")), null, null)
            .applyTo(null, null);
    }
}
