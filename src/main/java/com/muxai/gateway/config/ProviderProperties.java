package com.muxai.gateway.config;

import java.util.List;

public record ProviderProperties(
        String id,
        String type,
        String baseUrl,
        String apiKey,
        Long timeoutMs,
        List<String> models
) {
    public long timeoutMsOrDefault() {
        return timeoutMs != null ? timeoutMs : 60_000L;
    }

    public List<String> modelsOrEmpty() {
        return models != null ? models : List.of();
    }
}
