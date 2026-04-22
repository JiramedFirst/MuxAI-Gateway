package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool call emitted by the model on {@link ChatMessage#toolCalls()}. The
 * {@code arguments} field is the model's raw JSON string — the gateway does
 * not parse it, so malformed or partial JSON (during streaming) flows through
 * to the client unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(String id, String type, FunctionCall function) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionCall(String name, String arguments) {}
}
