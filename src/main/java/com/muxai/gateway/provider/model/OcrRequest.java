package com.muxai.gateway.provider.model;

public record OcrRequest(
        String model,
        String imageUrl,
        String prompt,
        Double temperature
) {
    public OcrRequest withModel(String newModel) {
        return new OcrRequest(newModel, imageUrl, prompt, temperature);
    }
}
