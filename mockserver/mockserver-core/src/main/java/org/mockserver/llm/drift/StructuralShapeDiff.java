package org.mockserver.llm.drift;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares two JSON documents by <em>shape</em> — the set of field paths and
 * their value types — ignoring values. Used by drift detection to spot
 * structural changes in a provider's responses (new/removed fields, type
 * changes) without flagging benign value differences.
 * <p>
 * Pure and deterministic. Object fields are walked by name; arrays are
 * represented by a single {@code []} path segment using the first element's
 * shape (a representative-element model — sufficient for detecting field/type
 * drift in homogeneous provider responses).
 */
public final class StructuralShapeDiff {

    /** The structural delta between a recorded ("baseline") and a live document. */
    public static final class ShapeDiff {
        private final List<String> addedPaths;
        private final List<String> removedPaths;
        private final List<String> typeChangedPaths;

        ShapeDiff(List<String> addedPaths, List<String> removedPaths, List<String> typeChangedPaths) {
            this.addedPaths = addedPaths;
            this.removedPaths = removedPaths;
            this.typeChangedPaths = typeChangedPaths;
        }

        /** Paths present in the live document but not the baseline. */
        public List<String> getAddedPaths() {
            return addedPaths;
        }

        /** Paths present in the baseline but missing from the live document. */
        public List<String> getRemovedPaths() {
            return removedPaths;
        }

        /** Paths present in both but with a different value type. */
        public List<String> getTypeChangedPaths() {
            return typeChangedPaths;
        }

        /** True if the two documents differ structurally. */
        public boolean hasDrift() {
            return !addedPaths.isEmpty() || !removedPaths.isEmpty() || !typeChangedPaths.isEmpty();
        }
    }

    private StructuralShapeDiff() {
    }

    /**
     * Diff the shapes of {@code baseline} (recorded) and {@code live}. A null
     * node is treated as an empty shape.
     */
    public static ShapeDiff diff(JsonNode baseline, JsonNode live) {
        Map<String, String> baselineShape = new TreeMap<>();
        Map<String, String> liveShape = new TreeMap<>();
        collect("$", baseline, baselineShape);
        collect("$", live, liveShape);

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> typeChanged = new ArrayList<>();

        for (Map.Entry<String, String> entry : liveShape.entrySet()) {
            if (!baselineShape.containsKey(entry.getKey())) {
                added.add(entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : baselineShape.entrySet()) {
            String livePathType = liveShape.get(entry.getKey());
            if (livePathType == null) {
                removed.add(entry.getKey());
            } else if (!livePathType.equals(entry.getValue())) {
                typeChanged.add(entry.getKey());
            }
        }
        return new ShapeDiff(added, removed, typeChanged);
    }

    private static void collect(String path, JsonNode node, Map<String, String> shape) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        shape.put(path, typeOf(node));
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collect(path + "." + e.getKey(), e.getValue(), shape));
        } else if (node.isArray() && node.size() > 0) {
            // representative-element model: walk the first element under a [] segment
            collect(path + "[]", node.get(0), shape);
        }
    }

    private static String typeOf(JsonNode node) {
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isIntegralNumber()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        return "unknown";
    }
}
