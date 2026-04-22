package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelInfo(
        String id,
        String object,
        Long created,
        @JsonProperty("owned_by") String ownedBy
) {
    public static ModelInfo of(String id, String ownedBy) {
        return new ModelInfo(id, "model", 0L, ownedBy);
    }
}
