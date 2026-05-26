package org.mockserver.model;

import java.util.Objects;

public class ToolUse extends ObjectWithJsonToString {
    private int hashCode;
    private String id;
    private String name;
    private String arguments;

    public static ToolUse toolUse(String name) {
        return new ToolUse().withName(name);
    }

    public ToolUse withId(String id) {
        this.id = id;
        this.hashCode = 0;
        return this;
    }

    public String getId() {
        return id;
    }

    public ToolUse withName(String name) {
        this.name = name;
        this.hashCode = 0;
        return this;
    }

    public String getName() {
        return name;
    }

    public ToolUse withArguments(String arguments) {
        this.arguments = arguments;
        this.hashCode = 0;
        return this;
    }

    public String getArguments() {
        return arguments;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        ToolUse toolUse = (ToolUse) o;
        return Objects.equals(id, toolUse.id) &&
            Objects.equals(name, toolUse.name) &&
            Objects.equals(arguments, toolUse.arguments);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(id, name, arguments);
        }
        return hashCode;
    }
}
