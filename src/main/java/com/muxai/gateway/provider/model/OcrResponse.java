package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrResponse(
        String model,
        String text,
        Usage usage
) {}
