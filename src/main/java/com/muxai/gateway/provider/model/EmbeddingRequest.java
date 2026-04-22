package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingRequest(String model, List<String> input) {
    public EmbeddingRequest withModel(String newModel) {
        return new EmbeddingRequest(newModel, input);
    }
}
