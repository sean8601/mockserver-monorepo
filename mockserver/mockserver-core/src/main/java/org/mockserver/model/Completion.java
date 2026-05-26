package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Completion extends ObjectWithJsonToString {
    private int hashCode;
    private String text;
    private List<ToolUse> toolCalls;
    private String stopReason;
    private Usage usage;
    private Boolean streaming;
    private StreamingPhysics streamingPhysics;

    public static Completion completion() {
        return new Completion();
    }

    public Completion withText(String text) {
        this.text = text;
        this.hashCode = 0;
        return this;
    }

    public String getText() {
        return text;
    }

    public Completion withToolCalls(List<ToolUse> toolCalls) {
        this.toolCalls = toolCalls;
        this.hashCode = 0;
        return this;
    }

    public Completion withToolCalls(ToolUse... toolCalls) {
        this.toolCalls = Arrays.asList(toolCalls);
        this.hashCode = 0;
        return this;
    }

    public Completion withToolCall(ToolUse toolCall) {
        if (this.toolCalls == null) {
            this.toolCalls = new ArrayList<>();
        }
        this.toolCalls.add(toolCall);
        this.hashCode = 0;
        return this;
    }

    public List<ToolUse> getToolCalls() {
        return toolCalls;
    }

    public Completion withStopReason(String stopReason) {
        this.stopReason = stopReason;
        this.hashCode = 0;
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public Completion withUsage(Usage usage) {
        this.usage = usage;
        this.hashCode = 0;
        return this;
    }

    public Usage getUsage() {
        return usage;
    }

    public Completion withStreaming(Boolean streaming) {
        this.streaming = streaming;
        this.hashCode = 0;
        return this;
    }

    public Boolean getStreaming() {
        return streaming;
    }

    public Completion withStreamingPhysics(StreamingPhysics streamingPhysics) {
        this.streamingPhysics = streamingPhysics;
        this.hashCode = 0;
        return this;
    }

    public StreamingPhysics getStreamingPhysics() {
        return streamingPhysics;
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
        Completion that = (Completion) o;
        return Objects.equals(text, that.text) &&
            Objects.equals(toolCalls, that.toolCalls) &&
            Objects.equals(stopReason, that.stopReason) &&
            Objects.equals(usage, that.usage) &&
            Objects.equals(streaming, that.streaming) &&
            Objects.equals(streamingPhysics, that.streamingPhysics);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(text, toolCalls, stopReason, usage, streaming, streamingPhysics);
        }
        return hashCode;
    }
}
