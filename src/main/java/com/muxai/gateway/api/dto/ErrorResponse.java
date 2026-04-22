package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Error error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String message, String type, String code) {}

    public static ErrorResponse of(String message, String type, String code) {
        return new ErrorResponse(new Error(message, type, code));
    }
}
