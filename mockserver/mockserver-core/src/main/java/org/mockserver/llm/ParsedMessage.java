package org.mockserver.llm;

import org.mockserver.model.ToolUse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ParsedMessage {

    public enum Role {
        USER,
        ASSISTANT,
        TOOL,
        SYSTEM
    }

    /**
     * A multimodal image content part recognised in a decoded request message.
     *
     * <p>Request-side only: the codecs parse the presence (and, where available,
     * the media type) of an image so conversation matchers can assert "this
     * message contains an image". MockServer does not store the image bytes.
     */
    public static final class ImagePart {
        private final String mediaType;

        public ImagePart(String mediaType) {
            this.mediaType = mediaType;
        }

        /** The image media type (e.g. {@code image/png}), or {@code null} if not declared by the provider shape. */
        public String getMediaType() {
            return mediaType;
        }
    }

    private final Role role;
    private final String textContent;
    private final List<ToolUse> toolCalls;
    private final Map<String, String> toolResults;
    private final List<ImagePart> images;

    public ParsedMessage(Role role, String textContent, List<ToolUse> toolCalls, Map<String, String> toolResults) {
        this(role, textContent, toolCalls, toolResults, null);
    }

    public ParsedMessage(Role role, String textContent, List<ToolUse> toolCalls, Map<String, String> toolResults, List<ImagePart> images) {
        this.role = role;
        this.textContent = textContent;
        this.toolCalls = toolCalls != null ? Collections.unmodifiableList(toolCalls) : Collections.emptyList();
        this.toolResults = toolResults != null ? Collections.unmodifiableMap(toolResults) : Collections.emptyMap();
        this.images = images != null ? Collections.unmodifiableList(images) : Collections.emptyList();
    }

    public Role getRole() {
        return role;
    }

    public String getTextContent() {
        return textContent;
    }

    public List<ToolUse> getToolCalls() {
        return toolCalls;
    }

    public Map<String, String> getToolResults() {
        return toolResults;
    }

    /** Image content parts recognised in this message; never {@code null} (empty when none). */
    public List<ImagePart> getImages() {
        return images;
    }

    /** True when this message contains at least one image content part. */
    public boolean hasImage() {
        return !images.isEmpty();
    }

    /** The number of image content parts in this message. */
    public int imageCount() {
        return images.size();
    }
}
