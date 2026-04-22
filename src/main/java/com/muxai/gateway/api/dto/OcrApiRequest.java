package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muxai.gateway.provider.model.OcrRequest;
import jakarta.validation.constraints.NotBlank;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrApiRequest(
        String model,
        @NotBlank String image,
        String prompt,
        Double temperature
) {
    public OcrRequest toInternal() {
        String resolvedModel = (model == null || model.isBlank()) ? "typhoon-ocr" : model;
        return new OcrRequest(resolvedModel, normalizeImage(image), prompt, temperature);
    }

    private static String normalizeImage(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("data:") || v.startsWith("http://") || v.startsWith("https://")) {
            return v;
        }
        return "data:image/png;base64," + v;
    }
}
