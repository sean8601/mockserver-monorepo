package org.mockserver.serialization.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for the JavaScript and Python expectation code generators.
 * Each generator embeds the expectation's existing JSON serialization (the same
 * produced by {@code format=JSON}) in a copy-paste-ready client call.
 *
 * @author jamesdbloom
 */
public class ExpectationToCodeSerializerTest {

    private final ExpectationSerializer expectationSerializer =
        new ExpectationSerializer(new MockServerLogger(), true);
    private final ExpectationToJavaScriptSerializer javaScriptSerializer =
        new ExpectationToJavaScriptSerializer(expectationSerializer);
    private final ExpectationToPythonSerializer pythonSerializer =
        new ExpectationToPythonSerializer(expectationSerializer);
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private Expectation sampleExpectation(String path, String body) {
        return new Expectation(request().withMethod("GET").withPath(path))
            .thenRespond(response().withStatusCode(200).withBody(body));
    }

    @Test
    public void shouldGenerateJavaScriptWithRequirePreambleAndMockAnyResponseCall() {
        String code = javaScriptSerializer.serialize(Collections.singletonList(sampleExpectation("/somePath", "someBody")));

        assertThat(code, containsString("const { mockServerClient } = require('mockserver-client');"));
        assertThat(code, containsString("mockServerClient(\"localhost\", 1080).mockAnyResponse("));
        assertThat(code, containsString("\"/somePath\""));
        assertThat(code, containsString("\"someBody\""));
        assertThat(code, containsString(");"));
    }

    @Test
    public void shouldGenerateOneMockAnyResponseCallPerExpectationInJavaScript() {
        String code = javaScriptSerializer.serialize(Arrays.asList(
            sampleExpectation("/first", "one"),
            sampleExpectation("/second", "two")
        ));

        int calls = code.split("mockAnyResponse\\(", -1).length - 1;
        assertThat(calls, is(2));
        // require preamble emitted exactly once
        assertThat(code.split("require\\('mockserver-client'\\)", -1).length - 1, is(1));
        assertThat(code, containsString("\"/first\""));
        assertThat(code, containsString("\"/second\""));
    }

    @Test
    public void shouldGeneratePythonWithImportPreambleAndUpsertCall() {
        String code = pythonSerializer.serialize(Collections.singletonList(sampleExpectation("/somePath", "someBody")));

        assertThat(code, containsString("import json"));
        assertThat(code, containsString("from mockserver import MockServerClient, Expectation"));
        assertThat(code, containsString("client = MockServerClient(\"localhost\", 1080)"));
        assertThat(code, containsString("client.upsert(Expectation.from_dict(json.loads(\"\"\""));
        assertThat(code, containsString("\"/somePath\""));
    }

    @Test
    public void shouldGenerateOneUpsertCallPerExpectationInPython() {
        String code = pythonSerializer.serialize(Arrays.asList(
            sampleExpectation("/first", "one"),
            sampleExpectation("/second", "two")
        ));

        int calls = code.split("client\\.upsert\\(", -1).length - 1;
        assertThat(calls, is(2));
        // import preamble emitted exactly once
        assertThat(code.split("import json", -1).length - 1, is(1));
    }

    @Test
    public void shouldEmbedValidParseableExpectationJsonInJavaScript() throws Exception {
        Expectation expectation = sampleExpectation("/roundtrip", "body");
        String code = javaScriptSerializer.serialize(Collections.singletonList(expectation));

        String embedded = code.substring(
            code.indexOf("mockAnyResponse(") + "mockAnyResponse(".length(),
            code.lastIndexOf(");")
        ).trim();

        // round-trip sanity: the embedded JSON parses and is a valid expectation
        assertThat(objectMapper.readTree(embedded).get("httpRequest").get("path").asText(), is("/roundtrip"));
        Expectation[] parsed = expectationSerializer.deserializeArray("[" + embedded + "]", true);
        assertThat(parsed.length, is(1));
        assertThat(((org.mockserver.model.HttpRequest) parsed[0].getHttpRequest()).getPath().getValue(), is("/roundtrip"));
    }

    @Test
    public void shouldEmbedValidParseableExpectationJsonInPython() throws Exception {
        Expectation expectation = sampleExpectation("/roundtrip", "body");
        String code = pythonSerializer.serialize(Collections.singletonList(expectation));

        String start = "json.loads(\"\"\"";
        String embedded = code.substring(
            code.indexOf(start) + start.length(),
            code.indexOf("\"\"\")))")
        ).trim();

        assertThat(objectMapper.readTree(embedded).get("httpRequest").get("path").asText(), is("/roundtrip"));
    }

    @Test
    public void shouldRoundTripExpectationWithHostileSpecialCharactersInJavaScriptAndPython() throws Exception {
        // an expectation whose path and body carry every embedding hazard: a backslash, an embedded
        // double-quote, a newline, and a non-ASCII/unicode character
        String hostilePath = "/a\\b\"c\ndé中";
        String hostileBody = "body\\with\"quote\nnewlineé中\"\"\"triple";
        Expectation expectation = new Expectation(request().withMethod("GET").withPath(hostilePath))
            .thenRespond(response().withStatusCode(200).withBody(hostileBody));

        // the canonical JSON serialization is what both generators embed verbatim
        String expectedJson = expectationSerializer.serialize(expectation);

        // JavaScript: the embedded text between mockAnyResponse( and the trailing ); must equal the JSON
        String js = javaScriptSerializer.serialize(Collections.singletonList(expectation));
        String jsEmbedded = js.substring(
            js.indexOf("mockAnyResponse(") + "mockAnyResponse(".length(),
            js.lastIndexOf(");")
        );
        assertThat(jsEmbedded, is(expectedJson));
        assertThat(objectMapper.readTree(jsEmbedded), is(objectMapper.readTree(expectedJson)));

        // Python: the embedded text between the """ open and the """))) close must equal the JSON
        String py = pythonSerializer.serialize(Collections.singletonList(expectation));
        String pyStart = "json.loads(\"\"\"";
        String pyEmbedded = py.substring(
            py.indexOf(pyStart) + pyStart.length(),
            py.indexOf("\"\"\")))")
        );
        assertThat(pyEmbedded, is(expectedJson));
        assertThat(objectMapper.readTree(pyEmbedded), is(objectMapper.readTree(expectedJson)));
    }

    @Test
    public void shouldHandleEmptyAndNullExpectationLists() {
        String js = javaScriptSerializer.serialize(Collections.emptyList());
        assertThat(js, containsString("require('mockserver-client')"));
        assertThat(js.contains("mockAnyResponse("), is(false));

        String py = pythonSerializer.serialize(null);
        assertThat(py, containsString("from mockserver import MockServerClient, Expectation"));
        assertThat(py.contains("client.upsert("), is(false));
    }
}
