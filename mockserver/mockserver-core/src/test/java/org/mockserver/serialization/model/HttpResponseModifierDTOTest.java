package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.HttpResponseModifier;
import org.mockserver.model.HttpResponseModifierCondition;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponseModifier.responseModifier;
import static org.mockserver.model.HttpResponseModifierCondition.responseModifierCondition;

public class HttpResponseModifierDTOTest {

    @Test
    public void buildObjectRoundTripsLegacyShape() {
        HttpResponseModifier original = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null);

        HttpResponseModifier built = new HttpResponseModifierDTO(original).buildObject();

        assertThat(built, is(original));
        // legacy shape carries no condition / chain
        assertThat(built.getCondition(), nullValue());
        assertThat(built.getModifiers(), nullValue());
    }

    @Test
    public void buildObjectRoundTripsConditionAndChain() {
        HttpResponseModifier child = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Child", "1")), null, null);
        HttpResponseModifier original = responseModifier()
            .withCondition(responseModifierCondition()
                .withStatusCode(503)
                .withStatusCodeRange("5xx")
                .withResponseHasHeader("Content-Type")
                .withRequestHasHeader("X-Debug"))
            .withModifiers(Collections.singletonList(child));

        HttpResponseModifier built = new HttpResponseModifierDTO(original).buildObject();

        assertThat(built, is(original));
        HttpResponseModifierCondition condition = built.getCondition();
        assertThat(condition, notNullValue());
        assertThat(condition.getStatusCode(), is(503));
        assertThat(condition.getStatusCodeRange(), is("5xx"));
        assertThat(condition.getResponseHasHeader(), is("Content-Type"));
        assertThat(condition.getRequestHasHeader(), is("X-Debug"));
        assertThat(built.getModifiers().size(), is(1));
    }

    @Test
    public void jsonRoundTripPreservesConditionAndChain() throws Exception {
        HttpResponseModifier child = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Child", "1")), null, null);
        HttpResponseModifierDTO dto = new HttpResponseModifierDTO(responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx").withResponseHasHeader("Content-Type"))
            .withModifiers(Arrays.asList(child)));

        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        HttpResponseModifierDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, HttpResponseModifierDTO.class);

        assertThat(deserialized.buildObject(), is(dto.buildObject()));
        assertThat(deserialized.getCondition().getStatusCodeRange(), is("5xx"));
        assertThat(deserialized.getModifiers().size(), is(1));
    }

    @Test
    public void jsonRoundTripPreservesLegacyShape() throws Exception {
        HttpResponseModifierDTO dto = new HttpResponseModifierDTO(responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null));

        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(dto);
        HttpResponseModifierDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, HttpResponseModifierDTO.class);

        assertThat(deserialized.buildObject(), is(dto.buildObject()));
        assertThat(deserialized.getCondition(), nullValue());
        assertThat(deserialized.getModifiers(), nullValue());
    }
}
