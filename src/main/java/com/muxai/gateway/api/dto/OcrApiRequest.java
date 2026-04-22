package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muxai.gateway.provider.model.OcrRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrApiRequest(
        String model,
        @NotBlank @Size(max = OcrApiRequest.MAX_IMAGE_LENGTH,
                message = "image exceeds max size of " + OcrApiRequest.MAX_IMAGE_LENGTH + " characters")
        String image,
        String prompt,
        Double temperature
) {
    // Accommodates ~20 MB of raw image bytes base64-encoded (~27 MB string).
    public static final int MAX_IMAGE_LENGTH = 28_000_000;

    public OcrRequest toInternal() {
        String resolvedModel = (model == null || model.isBlank()) ? "typhoon-ocr" : model;
        return new OcrRequest(resolvedModel, normalizeImage(image), prompt, temperature);
    }

    private static String normalizeImage(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("data:") || v.startsWith("https://")) {
            return v;
        }
        if (v.startsWith("http://")) {
            throw new IllegalArgumentException(
                    "image URL scheme 'http://' is not allowed; use 'https://' or a 'data:' URI");
        }
        return "data:image/png;base64," + v;
    }
}
