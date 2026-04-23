package com.muxai.gateway.config;

import java.util.List;
import java.util.Map;

public record ProviderProperties(
        String id,
        String type,
        String baseUrl,
        String apiKey,
        Long timeoutMs,
        List<String> models,
        Map<String, ModelPricing> pricing
) {
    public long timeoutMsOrDefault() {
        return timeoutMs != null ? timeoutMs : 60_000L;
    }

    public List<String> modelsOrEmpty() {
        return models != null ? models : List.of();
    }

    public Map<String, ModelPricing> pricingOrEmpty() {
        return pricing != null ? pricing : Map.of();
    }

    // USD per 1,000,000 tokens. Sprint 2 consumes this via PricingTable to
    // compute muxai_cost_usd_total and enforce per-app budget caps.
    public record ModelPricing(double inputPer1MUsd, double outputPer1MUsd) {}
}
