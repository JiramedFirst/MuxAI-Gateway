package com.muxai.gateway.config;

import java.util.List;

public record RouteProperties(
        Match match,
        Step primary,
        List<Step> fallback,
        String strategy
) {
    public List<Step> fallbackOrEmpty() {
        return fallback != null ? fallback : List.of();
    }

    /** {@code primary-first} (default) or {@code cheapest-first}. */
    public String strategyOrDefault() {
        return strategy != null && !strategy.isBlank() ? strategy : "primary-first";
    }

    public record Match(String appId, String model) {
        public static Match empty() { return new Match(null, null); }
    }

    public record Step(String provider, String model) {}
}
