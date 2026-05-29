package org.mockserver.llm.drift;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;

public class StructuralShapeDiffTest {

    private static JsonNode json(String s) throws Exception {
        return ObjectMapperFactory.createObjectMapper().readTree(s);
    }

    @Test
    public void identicalShapesHaveNoDrift() throws Exception {
        // values differ, shapes match → no drift
        StructuralShapeDiff.ShapeDiff diff = StructuralShapeDiff.diff(
            json("{\"a\":1,\"b\":\"x\"}"), json("{\"a\":99,\"b\":\"completely different\"}"));
        assertThat(diff.hasDrift(), is(false));
    }

    @Test
    public void detectsAddedField() throws Exception {
        StructuralShapeDiff.ShapeDiff diff = StructuralShapeDiff.diff(
            json("{\"a\":1}"), json("{\"a\":1,\"b\":2}"));
        assertThat(diff.hasDrift(), is(true));
        assertThat(diff.getAddedPaths(), hasItem("$.b"));
        assertThat(diff.getRemovedPaths(), is(empty()));
    }

    @Test
    public void detectsRemovedField() throws Exception {
        StructuralShapeDiff.ShapeDiff diff = StructuralShapeDiff.diff(
            json("{\"a\":1,\"b\":2}"), json("{\"a\":1}"));
        assertThat(diff.getRemovedPaths(), hasItem("$.b"));
    }

    @Test
    public void detectsTypeChange() throws Exception {
        StructuralShapeDiff.ShapeDiff diff = StructuralShapeDiff.diff(
            json("{\"a\":1}"), json("{\"a\":\"now a string\"}"));
        assertThat(diff.getTypeChangedPaths(), contains("$.a"));
    }

    @Test
    public void walksNestedObjectsAndArrays() throws Exception {
        StructuralShapeDiff.ShapeDiff diff = StructuralShapeDiff.diff(
            json("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}"),
            json("{\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"annotations\":[]}]}"));
        assertThat(diff.getAddedPaths(), hasItem("$.content[].annotations"));
    }

    @Test
    public void nullNodesTreatedAsEmptyShape() {
        assertThat(StructuralShapeDiff.diff(null, null).hasDrift(), is(false));
    }

    @Test
    public void handlesTopLevelArrayRoot() throws Exception {
        // same element shape → no drift regardless of length
        assertThat(StructuralShapeDiff.diff(json("[1,2]"), json("[1,2,3]")).hasDrift(), is(false));
    }

    @Test
    public void detectsTypeChangeInArrayFirstElement() throws Exception {
        StructuralShapeDiff.ShapeDiff diff = StructuralShapeDiff.diff(json("[1]"), json("[\"text\"]"));
        assertThat(diff.getTypeChangedPaths(), hasItem("$[]"));
    }

    @Test
    public void primitiveRootsOfSameTypeHaveNoDrift() throws Exception {
        assertThat(StructuralShapeDiff.diff(json("\"hello\""), json("\"world\"")).hasDrift(), is(false));
    }

    @Test
    public void primitiveRootTypeChangeIsDrift() throws Exception {
        assertThat(StructuralShapeDiff.diff(json("\"hello\""), json("42")).getTypeChangedPaths(), hasItem("$"));
    }
}
