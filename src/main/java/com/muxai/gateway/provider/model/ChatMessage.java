package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-shaped chat message.
 *
 * {@code content} is deliberately typed as {@link Object}: the OpenAI wire
 * format accepts either a plain string or a list of {@link ContentPart}s
 * (the vision shape). Jackson round-trips both forms without re-modelling,
 * and adapters that need text (Anthropic system prompt, OCR text extract)
 * go through {@link #contentAsText()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        Object content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        String name
) {
    public ChatMessage(String role, String content) {
        this(role, (Object) content, null, null, null);
    }

    public ChatMessage(String role, Object content) {
        this(role, content, null, null, null);
    }

    public String contentAsText() {
        if (content == null) return null;
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                String text = textOf(item);
                if (text != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    public boolean hasImageParts() {
        if (!(content instanceof List<?> list)) return false;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && "image_url".equals(map.get("type"))) return true;
            if (item instanceof ContentPart cp && "image_url".equals(cp.type())) return true;
        }
        return false;
    }

    private static String textOf(Object item) {
        if (item instanceof ContentPart cp) {
            return "text".equals(cp.type()) ? cp.text() : null;
        }
        if (item instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
            Object t = map.get("text");
            return t instanceof String s ? s : null;
        }
        return null;
    }
}
