package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.muxai.gateway.provider.model.EmbeddingRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiEmbeddingRequest(
        @NotBlank String model,
        @NotNull Object input
) {
    @JsonCreator
    public OpenAiEmbeddingRequest {}

    @SuppressWarnings("unchecked")
    public EmbeddingRequest toInternal() {
        List<String> inputs;
        if (input instanceof String s) {
            inputs = List.of(s);
        } else if (input instanceof List<?> list) {
            inputs = list.stream().map(Object::toString).toList();
        } else {
            throw new IllegalArgumentException(
                    "'input' must be a string or array of strings");
        }
        return new EmbeddingRequest(model, inputs);
    }
}
