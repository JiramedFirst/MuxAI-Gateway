package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingResponse(
        String object,
        List<Embedding> data,
        String model,
        Usage usage
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Embedding(Integer index, String object, List<Double> embedding) {}
}
