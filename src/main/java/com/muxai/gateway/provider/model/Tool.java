package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI function-tool definition. {@code type} is always {@code "function"}
 * in the OpenAI spec today; we keep it stringly-typed for future extension.
 * {@code parameters} is a JSON Schema object — kept as {@link Object} so
 * callers can pass arbitrary schemas through without re-modelling them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tool(String type, Function function) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Function(String name, String description, Object parameters) {}
}
