package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatChunk(
        String id,
        String object,
        Long created,
        String model,
        List<Delta> choices
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
            Integer index,
            ChatMessage delta,
            @JsonProperty("finish_reason") String finishReason
    ) {}
}
